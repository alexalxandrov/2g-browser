package org.slowline.browser;

import android.webkit.CookieManager;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;

import okhttp3.Headers;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

import java.io.ByteArrayInputStream;
import java.io.FileInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.SequenceInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * GET resource interception and the one-page cache. The WebView still performs HTML rendering,
 * JavaScript, cookies, navigation history, and non-GET requests.
 */
public final class ResourceInterceptor {
    public interface Listener {
        void onPartialFallback(String url, long received, long expected);
        void onCacheError(String message);
    }

    private static final Pattern CONTENT_RANGE = Pattern.compile("bytes\\s+(\\d+)-(\\d+)/(\\d+|\\*)", Pattern.CASE_INSENSITIVE);
    private final SinglePageCache cache;
    private final TransferStats stats;
    private final TrackerBlocker blocker;
    private final Listener listener;
    private final OkHttpClient client;
    private volatile boolean revalidateMainDocumentOnce;
    private volatile boolean allowThirdPartyCookies;
    private volatile String webViewUserAgent;

    public ResourceInterceptor(SinglePageCache cache, TransferStats stats, TrackerBlocker blocker, Listener listener) {
        this.cache = cache;
        this.stats = stats;
        this.blocker = blocker;
        this.listener = listener;
        this.client = new OkHttpClient.Builder()
                .followRedirects(false)
                .followSslRedirects(false)
                .retryOnConnectionFailure(false)
                .connectTimeout(60, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.MINUTES)
                .writeTimeout(60, TimeUnit.SECONDS)
                .build();
    }

    public void setRevalidateMainDocumentOnce() { revalidateMainDocumentOnce = true; }
    public void setAllowThirdPartyCookies(boolean allowed) { allowThirdPartyCookies = allowed; }
    public void setUserAgent(String value) { webViewUserAgent = value; }

    public WebResourceResponse intercept(WebResourceRequest request) {
        final String url = request.getUrl().toString();
        final boolean mainFrame = request.isForMainFrame();
        final String method = request.getMethod();
        if (!"http".equalsIgnoreCase(request.getUrl().getScheme()) && !"https".equalsIgnoreCase(request.getUrl().getScheme())) return null;
        stats.request(url);

        if (blocker.shouldBlock(url, mainFrame)) {
            stats.blocked(url);
            return emptyResponse(204, "No Content");
        }

        // Keep browser-controlled methods and range seeks native. In particular, media seeking must
        // not be confused with the cache's own resume request.
        if (!"GET".equalsIgnoreCase(method) || hasHeader(request.getRequestHeaders(), "Range")) return null;

        final long token = cache.generation();
        SinglePageCache.Entry entry = cache.getEntry(url);
        // Let Chromium handle dynamic fetch/XHR, document-like and streaming requests natively.
        // The app interceptor is for the main document and stable static assets, not every HTTP GET.
        if (!mainFrame && !isStaticResourceRequest(request) && (entry == null || !entry.complete)) return null;
        boolean forceRevalidate = mainFrame && revalidateMainDocumentOnce;
        if (forceRevalidate) revalidateMainDocumentOnce = false;

        boolean needsValidation = entry != null && entry.complete && (forceRevalidate || requiresValidation(entry));
        if (entry != null && entry.complete && !needsValidation) {
            try { return responseFromEntry(entry, true); }
            catch (IOException e) {
                // A concurrently deleted cache file is not fatal; fall through to network.
            }
        }

        long partialLength = entry == null ? 0 : entry.bodyLength();
        boolean hasResumeValidator = entry != null
                && ((nonEmpty(entry.etag) && !entry.etag.trim().startsWith("W/")) || nonEmpty(entry.lastModified));
        boolean canResume = entry != null && !entry.complete && partialLength > 0
                && entry.supportsRanges && hasResumeValidator && isIdentityEncoding(entry.contentEncoding)
                && (entry.totalLength < 0 || partialLength < entry.totalLength);
        boolean useConditional = needsValidation && entry != null && entry.complete
                && (nonEmpty(entry.etag) || nonEmpty(entry.lastModified));

        Response response = null;
        boolean requestedRange = false;
        try {
            Request.Builder builder = createRequest(request, canResume);
            if (canResume) {
                builder.header("Range", "bytes=" + partialLength + "-");
                String validator = nonEmpty(entry.etag) && !entry.etag.trim().startsWith("W/")
                        ? entry.etag : entry.lastModified;
                if (nonEmpty(validator)) builder.header("If-Range", validator);
                requestedRange = true;
            } else if (useConditional) {
                if (nonEmpty(entry.etag)) builder.header("If-None-Match", entry.etag);
                else if (nonEmpty(entry.lastModified)) builder.header("If-Modified-Since", entry.lastModified);
            }
            response = client.newCall(builder.build()).execute();

            if (isRedirect(response.code())) {
                response.close();
                return null; // Let Chromium follow redirects itself; the redirected top document remains native.
            }

            if (response.code() == 304 && entry != null && entry.complete) {
                if (allowCookiesFor(url, mainFrame)) applyCookies(url, response.headers());
                response.close();
                entry.savedAtMillis = System.currentTimeMillis();
                return responseFromEntry(entry, true);
            }

            if (response.code() == 416 && requestedRange && entry != null) {
                response.close();
                if (entry.totalLength >= 0 && entry.bodyLength() == entry.totalLength) {
                    cache.markComplete(entry);
                    return responseFromEntry(entry, true);
                }
                response = client.newCall(createRequest(request, false).build()).execute();
                requestedRange = false;
            }

            if (response.code() == 206 && requestedRange && entry != null) {
                RangeInfo range = parseContentRange(response.header("Content-Range"));
                String encoding = response.header("Content-Encoding");
                if (range != null && range.start == partialLength && isIdentityEncoding(encoding)
                        && isIdentityEncoding(entry.contentEncoding) && validatorsMatch(entry, response) && response.body() != null) {
                    return resumedResponse(url, token, entry, response, range, allowCookiesFor(url, mainFrame));
                }
                response.close();
                response = client.newCall(createRequest(request, false).build()).execute();
                requestedRange = false;
            }

            return responseFromNetwork(url, token, request, entry, response);
        } catch (IOException | RuntimeException e) {
            if (response != null) response.close();
            SinglePageCache.Entry latest = cache.getEntry(url);
            if (latest != null && latest.complete && latest.bodyFile != null && latest.bodyFile.isFile()) {
                try { return responseFromEntry(latest, true); } catch (IOException ignored) { }
            }
            if (latest != null && latest.bodyLength() > 0 && isIdentityEncoding(latest.contentEncoding)) {
                if (listener != null) listener.onPartialFallback(url, latest.bodyLength(), latest.totalLength);
                try { return partialResponse(latest); } catch (IOException ignored) { }
            }
            if (listener != null) listener.onCacheError("Не удалось получить ресурс: " + safeHost(url) + " · передаём запрос Chromium");
            // A failed auxiliary request should not become a synthetic 504 that breaks the page.
            // Returning null lets WebView retry through its own network stack.
            return null;
        }
    }

    private Request.Builder createRequest(WebResourceRequest request, boolean identityEncoding) {
        Request.Builder builder = new Request.Builder().url(request.getUrl().toString()).get();
        Map<String, String> source = request.getRequestHeaders();
        if (source != null) {
            for (Map.Entry<String, String> header : source.entrySet()) {
                String name = header.getKey();
                String lower = name.toLowerCase(Locale.ROOT);
                if (lower.equals("host") || lower.equals("connection") || lower.equals("content-length")
                        || lower.equals("transfer-encoding") || lower.equals("accept-encoding")
                        || lower.equals("cookie") || lower.equals("range") || lower.equals("if-range")
                        || lower.equals("if-none-match") || lower.equals("if-modified-since")) continue;
                try { builder.header(name, header.getValue()); } catch (IllegalArgumentException ignored) { }
            }
        }
        boolean mainFrame = request.isForMainFrame();
        String requestUrl = request.getUrl().toString();
        String cookie = allowCookiesFor(requestUrl, mainFrame) ? CookieManager.getInstance().getCookie(requestUrl) : null;
        if (nonEmpty(cookie)) builder.header("Cookie", cookie);
        // Use the actual WebView/Chromium user agent. OkHttp's default okhttp/4.x UA can trigger
        // reduced/blocked responses on large sites and their CDNs.
        String ua = webViewUserAgent;
        if (nonEmpty(ua)) builder.header("User-Agent", ua);
        // Only force identity encoding when continuing an incomplete byte range. For ordinary
        // requests OkHttp may transparently decompress gzip without confusing byte offsets.
        if (identityEncoding) builder.header("Accept-Encoding", "identity");
        return builder;
    }

    private WebResourceResponse responseFromNetwork(String url, long token, WebResourceRequest request,
                                                    SinglePageCache.Entry oldEntry, Response response) throws IOException {
        ResponseBody body = response.body();
        InputStream input = body == null ? new ByteArrayInputStream(new byte[0]) : body.byteStream();
        Map<String, String> headers = safeHeaders(response.headers());
        if (allowCookiesFor(url, request.isForMainFrame())) applyCookies(url, response.headers());
        String mime = mimeType(response.header("Content-Type"));
        String charset = charset(response.header("Content-Type"));
        long length = body == null ? 0 : body.contentLength();
        if (length < 0) length = parseLong(response.header("Content-Length"), -1);
        if (length >= 0) stats.knownSize(url, length);

        boolean cacheable = isCacheable(request, response, mime, headers);
        if (cacheable && response.code() >= 200 && response.code() < 300 && response.code() != 204 && response.code() != 206) {
            // Only enable byte-resume when the original representation has a known byte length.
            // OkHttp may transparently decompress gzip responses, making their original offsets invalid.
            boolean ranges = length >= 0 && supportsRanges(headers, response.header("ETag"), response.header("Last-Modified"))
                    && isIdentityEncoding(response.header("Content-Encoding"));
            SinglePageCache.CacheWrite write = cache.startWrite(url, token, null, mime, charset, headers,
                    length, false, ranges, response.header("Content-Encoding"));
            if (write != null) input = new RecordingInputStream(input, write, url, stats, listener);
            else input = new CountingInputStream(input, url, stats);
        } else {
            input = new CountingInputStream(input, url, stats);
        }
        int code = response.code();
        String reason = safeReason(response.message(), code);
        setContentLength(headers, length);
        return new WebResourceResponse(mime, charset, code, reason, headers, input);
    }

    private WebResourceResponse resumedResponse(String url, long token, SinglePageCache.Entry entry,
                                                Response response, RangeInfo range, boolean allowCookies) throws IOException {
        ResponseBody body = response.body();
        if (body == null) {
            response.close();
            return responseFromEntry(entry, false);
        }
        Map<String, String> merged = new LinkedHashMap<>(entry.headers);
        merged.putAll(safeHeaders(response.headers()));
        merged.remove("Content-Range");
        removeIgnoreCase(merged, "Content-Range");
        removeIgnoreCase(merged, "Transfer-Encoding");
        long total = range.total >= 0 ? range.total : entry.totalLength;
        if (total >= 0) setContentLength(merged, total);
        else removeIgnoreCase(merged, "Content-Length");
        SinglePageCache.CacheWrite write = cache.startWrite(url, token, entry, entry.mimeType, entry.charset,
                merged, total, true, true, null);
        if (write == null) {
            response.close();
            return errorResponse(false, url);
        }
        InputStream prefix = new LimitedInputStream(new FileInputStream(entry.bodyFile), entry.bodyLength());
        InputStream tail = new RecordingInputStream(body.byteStream(), write, url, stats, listener);
        InputStream combined = new SequenceInputStream(prefix, tail);
        if (allowCookies) applyCookies(url, response.headers());
        stats.knownSize(url, total);
        return new WebResourceResponse(entry.mimeType, entry.charset, 200, "OK", merged, combined);
    }

    private WebResourceResponse responseFromEntry(SinglePageCache.Entry entry, boolean countAsCache) throws IOException {
        if (entry == null || entry.bodyFile == null || !entry.bodyFile.isFile()) throw new IOException("Missing cache file");
        Map<String, String> headers = new LinkedHashMap<>(entry.headers);
        removeIgnoreCase(headers, "Transfer-Encoding");
        removeIgnoreCase(headers, "Content-Range");
        setContentLength(headers, entry.bodyLength());
        if (entry.totalLength >= 0) stats.knownSize(entry.url, entry.totalLength);
        if (countAsCache) stats.servedFromCache(entry.url, entry.bodyLength());
        // Treat reuse as recent access for the one-page cache's size-based eviction policy.
        //noinspection ResultOfMethodCallIgnored
        entry.bodyFile.setLastModified(System.currentTimeMillis());
        return new WebResourceResponse(entry.mimeType, entry.charset, 200, "OK", headers, new FileInputStream(entry.bodyFile));
    }

    private WebResourceResponse partialResponse(SinglePageCache.Entry entry) throws IOException {
        Map<String, String> headers = new LinkedHashMap<>(entry.headers);
        removeIgnoreCase(headers, "Transfer-Encoding");
        removeIgnoreCase(headers, "Content-Range");
        setContentLength(headers, entry.bodyLength());
        if (listener != null) listener.onPartialFallback(entry.url, entry.bodyLength(), entry.totalLength);
        return new WebResourceResponse(entry.mimeType, entry.charset, 200, "OK", headers, new FileInputStream(entry.bodyFile));
    }

    private static boolean isStaticResourceRequest(WebResourceRequest request) {
        Map<String, String> headers = request.getRequestHeaders();
        String destination = header(headers, "Sec-Fetch-Dest");
        if (destination != null) {
            String dest = destination.trim().toLowerCase(Locale.ROOT);
            if (dest.equals("image") || dest.equals("style") || dest.equals("script")
                    || dest.equals("font") || dest.equals("audio") || dest.equals("video")
                    || dest.equals("track")) return true;
            if (dest.equals("document") || dest.equals("iframe") || dest.equals("empty")
                    || dest.equals("worker") || dest.equals("sharedworker")) return false;
        }
        String accept = header(headers, "Accept");
        if (accept != null) {
            String a = accept.toLowerCase(Locale.ROOT);
            if (a.contains("text/css") || a.contains("image/") || a.contains("font/")
                    || a.contains("javascript") || a.contains("ecmascript")) return true;
        }
        String path = request.getUrl().getPath();
        if (path == null) return false;
        String p = path.toLowerCase(Locale.ROOT);
        return p.matches(".*\\.(?:css|js|mjs|png|jpe?g|gif|webp|avif|svg|ico|woff2?|ttf|otf|eot|mp3|m4a|ogg|opus|wav|mp4|webm|vtt)$");
    }

    private boolean isCacheable(WebResourceRequest request, Response response, String mime, Map<String, String> headers) {
        String cacheControl = header(headers, "Cache-Control");
        String vary = header(headers, "Vary");
        if (containsDirective(cacheControl, "no-store") || (vary != null && vary.trim().equals("*"))) return false;
        if (hasHeader(request.getRequestHeaders(), "Authorization")) return false;
        if (vary != null && (vary.toLowerCase(Locale.ROOT).contains("cookie") || vary.toLowerCase(Locale.ROOT).contains("authorization"))) return false;
        if (request.isForMainFrame()) return true;
        String destination = header(request.getRequestHeaders(), "Sec-Fetch-Dest");
        String lowerDest = destination == null ? "" : destination.toLowerCase(Locale.ROOT);
        if (lowerDest.equals("image") || lowerDest.equals("style") || lowerDest.equals("script")
                || lowerDest.equals("font") || lowerDest.equals("audio") || lowerDest.equals("video")
                || lowerDest.equals("track")) return true;
        String lowerMime = mime.toLowerCase(Locale.ROOT);
        if (lowerMime.startsWith("image/") || lowerMime.startsWith("font/") || lowerMime.startsWith("audio/")
                || lowerMime.startsWith("video/") || lowerMime.equals("text/css")
                || lowerMime.contains("javascript") || lowerMime.contains("ecmascript")) return true;
        return cacheControl.toLowerCase(Locale.ROOT).contains("immutable") || hasPositiveMaxAge(cacheControl);
    }

    private static boolean supportsRanges(Map<String, String> headers, String etag, String lastModified) {
        String acceptRanges = header(headers, "Accept-Ranges");
        return (acceptRanges != null && acceptRanges.toLowerCase(Locale.ROOT).contains("bytes"))
                || (nonEmpty(etag) && !etag.trim().startsWith("W/")) || nonEmpty(lastModified);
    }

    private static boolean requiresValidation(SinglePageCache.Entry entry) {
        String control = header(entry.headers, "Cache-Control");
        if (containsDirective(control, "no-cache") || containsDirective(control, "must-revalidate")) return true;
        if (control != null) {
            Matcher maxAge = Pattern.compile("(?:^|,)\\s*max-age\\s*=\\s*([0-9]+)", Pattern.CASE_INSENSITIVE).matcher(control);
            if (maxAge.find()) {
                long seconds = parseLong(maxAge.group(1), 0);
                long ageMs = Math.max(0, System.currentTimeMillis() - entry.savedAtMillis);
                if (ageMs >= seconds * 1000L) return true;
            }
        }
        String expires = header(entry.headers, "Expires");
        if (nonEmpty(expires)) {
            try {
                java.time.Instant expiry = java.time.ZonedDateTime.parse(expires, java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
                if (java.time.Instant.now().isAfter(expiry)) return true;
            } catch (Exception ignored) { }
        }
        return false;
    }

    private static boolean validatorsMatch(SinglePageCache.Entry entry, Response response) {
        String responseEtag = response.header("ETag");
        String responseModified = response.header("Last-Modified");
        if (nonEmpty(entry.etag) && nonEmpty(responseEtag) && !entry.etag.equals(responseEtag)) return false;
        if (nonEmpty(entry.lastModified) && nonEmpty(responseModified) && !entry.lastModified.equals(responseModified)) return false;
        boolean strongEtagMatches = nonEmpty(entry.etag) && !entry.etag.trim().startsWith("W/")
                && entry.etag.equals(responseEtag);
        boolean modifiedDateMatches = nonEmpty(entry.lastModified) && entry.lastModified.equals(responseModified);
        // Never splice bytes from a response whose identity cannot be checked.
        return strongEtagMatches || modifiedDateMatches;
    }

    private static boolean hasPositiveMaxAge(String control) {
        if (control == null) return false;
        Matcher m = Pattern.compile("(?:^|,)\\s*max-age\\s*=\\s*(\\d+)", Pattern.CASE_INSENSITIVE).matcher(control);
        return m.find() && parseLong(m.group(1), 0) > 0;
    }

    private static boolean containsDirective(String value, String directive) {
        if (value == null) return false;
        for (String token : value.split(",")) if (token.trim().equalsIgnoreCase(directive)) return true;
        return false;
    }

    private boolean allowCookiesFor(String url, boolean mainFrame) {
        if (mainFrame || allowThirdPartyCookies) return true;
        String firstParty = cache.getActivePage();
        try {
            String requestHost = java.net.URI.create(url).getHost();
            String pageHost = firstParty == null ? null : java.net.URI.create(firstParty).getHost();
            if (requestHost == null || pageHost == null) return false;
            requestHost = requestHost.toLowerCase(Locale.ROOT);
            pageHost = pageHost.toLowerCase(Locale.ROOT);
            return requestHost.equals(pageHost) || requestHost.endsWith("." + pageHost) || pageHost.endsWith("." + requestHost);
        } catch (Exception e) {
            return false;
        }
    }

    private static void applyCookies(String url, Headers headers) {
        try {
            CookieManager manager = CookieManager.getInstance();
            for (String value : headers.values("Set-Cookie")) manager.setCookie(url, value);
        } catch (Exception ignored) { }
    }

    private static Map<String, String> safeHeaders(Headers headers) {
        Map<String, String> out = new LinkedHashMap<>();
        for (int i = 0; i < headers.size(); i++) {
            String name = headers.name(i);
            String lower = name.toLowerCase(Locale.ROOT);
            if (lower.equals("set-cookie") || lower.equals("connection") || lower.equals("keep-alive")
                    || lower.equals("proxy-authenticate") || lower.equals("proxy-authorization")
                    || lower.equals("te") || lower.equals("trailer") || lower.equals("transfer-encoding")
                    || lower.equals("upgrade")) continue;
            out.put(name, headers.value(i));
        }
        return out;
    }

    private static void setContentLength(Map<String, String> headers, long length) {
        removeIgnoreCase(headers, "Content-Length");
        if (length >= 0) headers.put("Content-Length", Long.toString(length));
    }

    private static void removeIgnoreCase(Map<String, String> map, String name) {
        String found = null;
        for (String key : map.keySet()) if (key.equalsIgnoreCase(name)) { found = key; break; }
        if (found != null) map.remove(found);
    }

    private static String header(Map<String, String> headers, String name) {
        if (headers == null) return null;
        for (Map.Entry<String, String> entry : headers.entrySet()) if (entry.getKey().equalsIgnoreCase(name)) return entry.getValue();
        return null;
    }

    private static boolean hasHeader(Map<String, String> headers, String name) { return header(headers, name) != null; }

    private static String mimeType(String contentType) {
        try {
            MediaType parsed = contentType == null ? null : MediaType.parse(contentType);
            if (parsed != null) return parsed.type() + "/" + parsed.subtype();
        } catch (Exception ignored) { }
        return "application/octet-stream";
    }

    private static String charset(String contentType) {
        try {
            MediaType parsed = contentType == null ? null : MediaType.parse(contentType);
            if (parsed != null && parsed.charset(StandardCharsets.UTF_8) != null) return parsed.charset(StandardCharsets.UTF_8).name();
        } catch (Exception ignored) { }
        return "UTF-8";
    }

    private static boolean isIdentityEncoding(String encoding) { return encoding == null || encoding.isEmpty() || encoding.equalsIgnoreCase("identity"); }
    private static boolean nonEmpty(String value) { return value != null && !value.trim().isEmpty(); }
    private static boolean isRedirect(int code) { return code >= 300 && code < 400 && code != 304; }
    private static String safeReason(String reason, int code) { return nonEmpty(reason) ? reason : (code == 200 ? "OK" : "Response"); }
    private static long parseLong(String value, long fallback) { try { return Long.parseLong(value); } catch (Exception e) { return fallback; } }
    private static String safeHost(String url) { try { return java.net.URI.create(url).getHost(); } catch (Exception e) { return "сайта"; } }

    private static RangeInfo parseContentRange(String value) {
        if (value == null) return null;
        Matcher m = CONTENT_RANGE.matcher(value.trim());
        if (!m.matches()) return null;
        long start = parseLong(m.group(1), -1);
        long end = parseLong(m.group(2), -1);
        long total = "*".equals(m.group(3)) ? -1 : parseLong(m.group(3), -1);
        if (start < 0 || end < start) return null;
        return new RangeInfo(start, end, total);
    }

    private static WebResourceResponse emptyResponse(int code, String reason) {
        return new WebResourceResponse("text/plain", "UTF-8", code, reason,
                Collections.singletonMap("Cache-Control", "no-store"), new ByteArrayInputStream(new byte[0]));
    }

    private static WebResourceResponse errorResponse(boolean mainFrame, String url) {
        String html = "<!doctype html><meta charset=utf-8><meta name=viewport content='width=device-width,initial-scale=1'>"
                + "<title>Нет соединения</title><body style='font:16px sans-serif;max-width:42em;margin:12vh auto;padding:1em;color:#202124'>"
                + "<h2>Соединение прервано</h2><p>Ресурс не удалось получить. Если для этой страницы уже сохранена часть ответа, нажмите обновление: браузер попробует продолжить загрузку через HTTP Range, если сервер это поддерживает.</p>"
                + "<p style='color:#666;word-break:break-all'>" + escapeHtml(url) + "</p></body>";
        byte[] body = (mainFrame ? html : "").getBytes(StandardCharsets.UTF_8);
        return new WebResourceResponse(mainFrame ? "text/html" : "text/plain", "UTF-8", 504, "Gateway Timeout",
                Collections.singletonMap("Cache-Control", "no-store"), new ByteArrayInputStream(body));
    }

    private static String escapeHtml(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    private static final class RangeInfo {
        final long start, end, total;
        RangeInfo(long start, long end, long total) { this.start = start; this.end = end; this.total = total; }
    }

    private static final class LimitedInputStream extends FilterInputStream {
        private long remaining;
        LimitedInputStream(InputStream input, long limit) { super(input); remaining = Math.max(0, limit); }
        @Override public int read() throws IOException {
            if (remaining <= 0) return -1;
            int value = super.read();
            if (value >= 0) remaining--;
            return value;
        }
        @Override public int read(byte[] buffer, int offset, int length) throws IOException {
            if (remaining <= 0) return -1;
            int count = super.read(buffer, offset, (int) Math.min(length, remaining));
            if (count > 0) remaining -= count;
            return count;
        }
    }

    private static final class RecordingInputStream extends FilterInputStream {
        private final SinglePageCache.CacheWrite writer;
        private final String url;
        private final TransferStats stats;
        private long sincePublish;
        private boolean eof;
        private boolean closed;
        private boolean failed;
        private final Listener listener;
        RecordingInputStream(InputStream input, SinglePageCache.CacheWrite writer, String url, TransferStats stats, Listener listener) {
            super(input); this.writer = writer; this.url = url; this.stats = stats; this.listener = listener;
        }
        @Override public int read() throws IOException {
            try {
                int value = super.read();
                if (value < 0) finishAtEof();
                else {
                    byte[] one = {(byte) value};
                    writer.output().write(one, 0, 1);
                    sincePublish++;
                    if (sincePublish >= 32768) { stats.received(url, sincePublish); sincePublish = 0; }
                }
                return value;
            } catch (IOException e) { preservePartialAndNotify(); throw e; }
        }
        @Override public int read(byte[] buffer, int offset, int length) throws IOException {
            try {
                int count = super.read(buffer, offset, length);
                if (count < 0) finishAtEof();
                else if (count > 0) {
                    writer.output().write(buffer, offset, count);
                    sincePublish += count;
                    if (sincePublish >= 32768) {
                        stats.received(url, sincePublish);
                        sincePublish = 0;
                    }
                }
                return count;
            } catch (IOException e) { preservePartialAndNotify(); throw e; }
        }
        private void preservePartialAndNotify() {
            if (failed || eof) return;
            failed = true;
            if (sincePublish > 0) { stats.received(url, sincePublish); sincePublish = 0; }
            writer.close();
            long len = writer.length();
            long expected = writer.expectedLength();
            if (listener != null && (expected < 0 || len < expected)) listener.onPartialFallback(url, len, expected);
        }
        private void finishAtEof() {
            if (eof) return;
            eof = true;
            if (sincePublish > 0) { stats.received(url, sincePublish); sincePublish = 0; }
            writer.complete();
            long expected = writer.expectedLength();
            stats.knownSize(url, expected >= 0 ? expected : writer.length());
        }
        @Override public void close() throws IOException {
            if (closed) return;
            closed = true;
            try { super.close(); } finally {
                if (!eof && !failed) {
                    if (sincePublish > 0) { stats.received(url, sincePublish); sincePublish = 0; }
                    writer.close();
                }
            }
        }
    }

    private static final class CountingInputStream extends FilterInputStream {
        private final String url;
        private final TransferStats stats;
        private long sincePublish;
        private boolean closed;
        CountingInputStream(InputStream input, String url, TransferStats stats) { super(input); this.url = url; this.stats = stats; }
        @Override public int read() throws IOException {
            int value = super.read();
            if (value >= 0) {
                sincePublish++;
                if (sincePublish >= 32768) { stats.received(url, sincePublish); sincePublish = 0; }
            }
            return value;
        }
        @Override public int read(byte[] buffer, int offset, int length) throws IOException {
            int count = super.read(buffer, offset, length);
            if (count > 0) {
                sincePublish += count;
                if (sincePublish >= 32768) { stats.received(url, sincePublish); sincePublish = 0; }
            }
            return count;
        }
        @Override public void close() throws IOException {
            if (closed) return;
            closed = true;
            try { super.close(); } finally { if (sincePublish > 0) stats.received(url, sincePublish); }
        }
    }
}

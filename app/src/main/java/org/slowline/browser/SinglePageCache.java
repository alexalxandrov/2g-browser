package org.slowline.browser;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;

/** Disk cache scoped to one top-level page. Old-page entries are removed on top-level navigation. */
public final class SinglePageCache {
    private static final long MAX_CACHE_BYTES = 128L * 1024L * 1024L;
    public static final class Entry {
        public String url;
        public File bodyFile;
        public String mimeType = "application/octet-stream";
        public String charset = "UTF-8";
        public String etag;
        public String lastModified;
        public String contentEncoding;
        public long totalLength = -1;
        public long savedAtMillis;
        public boolean complete;
        public boolean supportsRanges;
        public Map<String, String> headers = new LinkedHashMap<>();

        public long bodyLength() { return bodyFile != null && bodyFile.exists() ? bodyFile.length() : 0; }
    }

    public final class CacheWrite implements Closeable {
        private final Entry entry;
        private final long token;
        private final FileOutputStream output;
        private boolean closed;
        private boolean completed;

        private CacheWrite(Entry entry, long token, FileOutputStream output) {
            this.entry = entry;
            this.token = token;
            this.output = output;
        }

        public OutputStream output() { return output; }

        public void complete() {
            if (closed) return;
            try { output.flush(); } catch (IOException ignored) { }
            try { output.close(); } catch (IOException ignored) { }
            closed = true;
            completed = true;
            synchronized (SinglePageCache.this) {
                if (token != generation) return;
                long actual = entry.bodyLength();
                if (entry.totalLength < 0 || entry.totalLength == 0) entry.totalLength = actual;
                entry.complete = entry.totalLength <= 0 || actual >= entry.totalLength;
                if (entry.complete) entry.savedAtMillis = System.currentTimeMillis();
                saveEntry(entry);
            }
        }

        @Override public void close() {
            if (closed) return;
            try { output.flush(); } catch (IOException ignored) { }
            try { output.close(); } catch (IOException ignored) { }
            closed = true;
            synchronized (SinglePageCache.this) {
                if (token != generation) return;
                long actual = entry.bodyLength();
                if (entry.totalLength < 0 && completed) entry.totalLength = actual;
                entry.complete = completed || (entry.totalLength >= 0 && actual >= entry.totalLength);
                if (entry.complete) entry.savedAtMillis = System.currentTimeMillis();
                saveEntry(entry);
            }
        }

        public long length() { return entry.bodyLength(); }
        public long expectedLength() { return entry.totalLength; }
    }

    private final File directory;
    private final File activePageFile;
    private String activePage;
    private long generation = 1;

    public SinglePageCache(File filesDirectory) {
        directory = new File(filesDirectory, "single-page-cache");
        //noinspection ResultOfMethodCallIgnored
        directory.mkdirs();
        activePageFile = new File(directory, "active-page.txt");
        activePage = readActivePage();
    }

    public synchronized String getActivePage() { return activePage; }
    public synchronized long generation() { return generation; }

    /** Returns true only when the page changed and old-page cache files were deleted. */
    public synchronized boolean beginPage(String url) {
        String key = normalizePageUrl(url);
        if (key == null) return false;
        if (activePage == null || !activePage.equals(key)) {
            generation++;
            File[] files = directory.listFiles();
            if (files != null) for (File file : files) {
                if (!file.equals(activePageFile)) deleteRecursively(file);
            }
            activePage = key;
            try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(activePageFile, false), StandardCharsets.UTF_8))) {
                writer.write(key);
            } catch (IOException ignored) { }
            return true;
        }
        return false;
    }

    /**
     * Adopt the final URL after redirects without deleting entries collected during this navigation.
     * The next genuine top-level navigation still uses beginPage() and removes this page's cache.
     */
    public synchronized void adoptActivePage(String url) {
        String key = normalizePageUrl(url);
        if (key == null || key.equals(activePage)) return;
        activePage = key;
        try (BufferedWriter writer = new BufferedWriter(
                new OutputStreamWriter(new FileOutputStream(activePageFile, false), StandardCharsets.UTF_8))) {
            writer.write(key);
        } catch (IOException ignored) { }
    }

    /** Clears entries but retains the current top-level URL; useful for an explicit network refresh. */
    public synchronized void clearCurrentPageResources() {
        generation++;
        File[] files = directory.listFiles();
        if (files == null) return;
        for (File file : files) if (!file.equals(activePageFile)) deleteRecursively(file);
    }

    public synchronized Entry getEntry(String url) {
        String key = stripFragment(url);
        File body = bodyFile(key);
        File meta = metaFile(key);
        if (!body.isFile() || !meta.isFile()) return null;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(meta), StandardCharsets.UTF_8))) {
            StringBuilder all = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) all.append(line);
            JSONObject object = new JSONObject(all.toString());
            Entry entry = new Entry();
            entry.url = key;
            entry.bodyFile = body;
            entry.mimeType = object.optString("mimeType", "application/octet-stream");
            entry.charset = object.optString("charset", "UTF-8");
            entry.etag = nullIfEmpty(object.optString("etag", ""));
            entry.lastModified = nullIfEmpty(object.optString("lastModified", ""));
            entry.contentEncoding = nullIfEmpty(object.optString("contentEncoding", ""));
            entry.totalLength = object.optLong("totalLength", -1);
            entry.savedAtMillis = object.optLong("savedAtMillis", 0);
            entry.complete = object.optBoolean("complete", false);
            entry.supportsRanges = object.optBoolean("supportsRanges", false);
            JSONObject headers = object.optJSONObject("headers");
            if (headers != null) {
                for (java.util.Iterator<String> it = headers.keys(); it.hasNext();) {
                    String name = it.next();
                    entry.headers.put(name, headers.optString(name, ""));
                }
            }
            return entry;
        } catch (Exception e) {
            return null;
        }
    }

    public synchronized CacheWrite startWrite(
            String url,
            long token,
            Entry existing,
            String mimeType,
            String charset,
            Map<String, String> headers,
            long totalLength,
            boolean append,
            boolean supportsRanges,
            String contentEncoding) throws IOException {
        if (token != generation) return null;
        String key = stripFragment(url);
        File body = bodyFile(key);
        Entry entry = existing != null ? existing : new Entry();
        entry.url = key;
        entry.bodyFile = body;
        if (mimeType != null && !mimeType.isEmpty()) entry.mimeType = mimeType;
        if (charset != null && !charset.isEmpty()) entry.charset = charset;
        if (headers != null) entry.headers = new LinkedHashMap<>(headers);
        if (totalLength >= 0) entry.totalLength = totalLength;
        entry.complete = false;
        entry.savedAtMillis = System.currentTimeMillis();
        entry.supportsRanges = supportsRanges || entry.supportsRanges;
        if (contentEncoding != null) entry.contentEncoding = contentEncoding;
        entry.etag = headerValue(entry.headers, "ETag", entry.etag);
        entry.lastModified = headerValue(entry.headers, "Last-Modified", entry.lastModified);
        try {
            FileOutputStream output = new FileOutputStream(body, append);
            saveEntry(entry);
            return new CacheWrite(entry, token, output);
        } catch (IOException e) {
            throw e;
        }
    }

    public synchronized void markComplete(Entry entry) {
        if (entry == null) return;
        entry.complete = true;
        entry.totalLength = entry.bodyLength();
        saveEntry(entry);
    }

    private String readActivePage() {
        if (!activePageFile.isFile()) return null;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(activePageFile), StandardCharsets.UTF_8))) {
            String value = reader.readLine();
            return normalizePageUrl(value);
        } catch (IOException e) {
            return null;
        }
    }

    private void saveEntry(Entry entry) {
        if (entry == null || entry.bodyFile == null) return;
        JSONObject object = new JSONObject();
        try {
            object.put("url", entry.url == null ? "" : entry.url);
            object.put("mimeType", entry.mimeType);
            object.put("charset", entry.charset);
            object.put("etag", entry.etag == null ? "" : entry.etag);
            object.put("lastModified", entry.lastModified == null ? "" : entry.lastModified);
            object.put("contentEncoding", entry.contentEncoding == null ? "" : entry.contentEncoding);
            object.put("totalLength", entry.totalLength);
            object.put("savedAtMillis", entry.savedAtMillis);
            object.put("complete", entry.complete);
            object.put("supportsRanges", entry.supportsRanges);
            JSONObject headers = new JSONObject();
            for (Map.Entry<String, String> header : entry.headers.entrySet()) headers.put(header.getKey(), header.getValue());
            object.put("headers", headers);
            try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(metaFile(entry.url), false), StandardCharsets.UTF_8))) {
                writer.write(object.toString());
            }
            pruneIfNeeded(entry.url);
        } catch (Exception ignored) { }
    }

    private void pruneIfNeeded(String justWrittenUrl) {
        File[] bodies = directory.listFiles((dir, name) -> name.endsWith(".body"));
        if (bodies == null) return;
        long total = 0;
        for (File body : bodies) total += body.length();
        if (total <= MAX_CACHE_BYTES) return;
        java.util.Arrays.sort(bodies, java.util.Comparator.comparingLong(File::lastModified));
        for (File body : bodies) {
            if (total <= MAX_CACHE_BYTES) break;
            String base = body.getName().substring(0, body.getName().length() - 5);
            File meta = new File(directory, base + ".json");
            String url = "";
            if (meta.isFile()) {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(meta), StandardCharsets.UTF_8))) {
                    StringBuilder content = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) content.append(line);
                    url = new JSONObject(content.toString()).optString("url", "");
                } catch (Exception ignored) { }
            }
            if (activePage != null && activePage.equals(normalizePageUrl(url))) continue;
            if (justWrittenUrl != null && body.getName().equals(sha256(stripFragment(justWrittenUrl)) + ".body")) continue;
            long length = body.length();
            //noinspection ResultOfMethodCallIgnored
            body.delete();
            //noinspection ResultOfMethodCallIgnored
            meta.delete();
            total -= length;
        }
    }

    private File bodyFile(String url) { return new File(directory, sha256(stripFragment(url)) + ".body"); }
    private File metaFile(String url) { return new File(directory, sha256(stripFragment(url)) + ".json"); }

    private static String normalizePageUrl(String value) {
        if (value == null || value.trim().isEmpty()) return null;
        String stripped = stripFragment(value.trim());
        try {
            java.net.URI uri = new java.net.URI(stripped).normalize();
            String scheme = uri.getScheme();
            String host = uri.getHost();
            if (scheme == null || host == null) return stripped;
            scheme = scheme.toLowerCase(java.util.Locale.ROOT);
            host = host.toLowerCase(java.util.Locale.ROOT);
            int port = uri.getPort();
            if ((scheme.equals("http") && port == 80) || (scheme.equals("https") && port == 443)) port = -1;
            StringBuilder out = new StringBuilder(scheme).append("://");
            if (uri.getRawUserInfo() != null) out.append(uri.getRawUserInfo()).append('@');
            if (host.indexOf(':') >= 0 && !host.startsWith("[")) out.append('[').append(host).append(']');
            else out.append(host);
            if (port >= 0) out.append(':').append(port);
            String path = uri.getRawPath();
            out.append(path == null || path.isEmpty() ? "/" : path);
            if (uri.getRawQuery() != null) out.append('?').append(uri.getRawQuery());
            return out.toString();
        } catch (Exception e) {
            return stripped;
        }
    }

    public static String stripFragment(String value) {
        if (value == null) return "";
        int index = value.indexOf('#');
        return index >= 0 ? value.substring(0, index) : value;
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder();
            for (byte b : digest) out.append(String.format(java.util.Locale.ROOT, "%02x", b & 0xff));
            return out.toString();
        } catch (Exception e) {
            return Integer.toHexString(value.hashCode());
        }
    }

    private static String headerValue(Map<String, String> headers, String name, String fallback) {
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(name)) return entry.getValue();
        }
        return fallback;
    }

    private static String nullIfEmpty(String value) { return value == null || value.isEmpty() ? null : value; }

    private static void deleteRecursively(File file) {
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) for (File child : children) deleteRecursively(child);
        }
        //noinspection ResultOfMethodCallIgnored
        file.delete();
    }
}

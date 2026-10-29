package org.slowline.browser;

import android.net.Uri;
import android.webkit.SafeBrowsingResponse;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.SslErrorHandler;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.PermissionRequest;
import android.net.http.SslError;
import android.webkit.GeolocationPermissions;

/** WebView bridge. Sensitive capabilities are denied unless a future UI explicitly grants them. */
public final class BrowserClient extends WebViewClient {
    public interface Listener {
        void onTopLevelNavigation(String url);
        void onPageStarted(String url);
        void onPageFinished(String url);
        void onProgressChanged(int progress);
        void onMainFrameError(String description);
        void onExternalScheme(String url);
        void onSafeBrowsingWarning(String url);
    }

    private final ResourceInterceptor interceptor;
    private final Listener listener;

    public BrowserClient(ResourceInterceptor interceptor, Listener listener) {
        this.interceptor = interceptor;
        this.listener = listener;
    }

    @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
        Uri uri = request.getUrl();
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(java.util.Locale.ROOT);
        if (request.isForMainFrame() && (scheme.equals("http") || scheme.equals("https"))) {
            listener.onTopLevelNavigation(uri.toString());
            return false;
        }
        if (scheme.equals("mailto") || scheme.equals("tel")) {
            listener.onExternalScheme(uri.toString());
            return true;
        }
        // Do not allow file/content/javascript/intent schemes to escape into the WebView.
        if (!scheme.equals("http") && !scheme.equals("https") && !scheme.equals("about") && !scheme.equals("blob") && !scheme.equals("data")) {
            return true;
        }
        return false;
    }

    @Override public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
        return interceptor.intercept(request);
    }

    @Override public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
        super.onPageStarted(view, url, favicon);
        listener.onPageStarted(url);
    }

    @Override public void onPageFinished(WebView view, String url) {
        super.onPageFinished(view, url);
        listener.onPageFinished(url);
    }

    @Override public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
        super.onReceivedError(view, request, error);
        if (request.isForMainFrame()) {
            CharSequence description = error.getDescription();
            listener.onMainFrameError(description == null ? "Ошибка загрузки" : description.toString());
        }
    }

    @Override public void onReceivedHttpError(WebView view, WebResourceRequest request, WebResourceResponse errorResponse) {
        super.onReceivedHttpError(view, request, errorResponse);
        if (request.isForMainFrame() && errorResponse != null && errorResponse.getStatusCode() >= 500) {
            listener.onMainFrameError("HTTP " + errorResponse.getStatusCode());
        }
    }

    @Override public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) {
        // Never offer a silent "proceed" path for invalid certificates.
        handler.cancel();
        listener.onMainFrameError("Ошибка TLS-сертификата: соединение отменено");
    }

    @Override public void onSafeBrowsingHit(WebView view, WebResourceRequest request, int threatType, SafeBrowsingResponse response) {
        listener.onSafeBrowsingWarning(request.getUrl().toString());
        response.backToSafety(true);
    }

    @Override public void onReceivedClientCertRequest(WebView view, android.webkit.ClientCertRequest request) {
        request.cancel();
    }

    public static final class BrowserChromeClient extends WebChromeClient {
        public interface Listener {
            void onProgressChanged(int progress);
            boolean onFileChooser(android.webkit.ValueCallback<android.net.Uri[]> callback,
                                  WebChromeClient.FileChooserParams params);
        }
        private final Listener listener;
        public BrowserChromeClient(Listener listener) { this.listener = listener; }
        @Override public void onProgressChanged(WebView view, int newProgress) {
            super.onProgressChanged(view, newProgress);
            listener.onProgressChanged(newProgress);
        }
        @Override public boolean onShowFileChooser(WebView webView,
                                                   android.webkit.ValueCallback<android.net.Uri[]> filePathCallback,
                                                   FileChooserParams fileChooserParams) {
            return listener.onFileChooser(filePathCallback, fileChooserParams);
        }
        @Override public void onPermissionRequest(PermissionRequest request) {
            request.deny();
        }
        @Override public void onGeolocationPermissionsShowPrompt(String origin, GeolocationPermissions.Callback callback) {
            callback.invoke(origin, false, false);
        }
        @Override public boolean onCreateWindow(WebView view, boolean isDialog, boolean isUserGesture, android.os.Message resultMsg) {
            // Multiple windows are disabled deliberately; this browser has a single active-page cache.
            return false;
        }
    }
}

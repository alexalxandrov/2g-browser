package org.slowline.browser;

import android.app.Activity;
import android.app.DownloadManager;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.net.Uri;
import android.net.TrafficStats;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;
import android.text.InputType;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.animation.LinearInterpolator;
import android.os.Build;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.webkit.CookieManager;
import android.webkit.DownloadListener;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebStorage;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.WebSettings;
import android.webkit.URLUtil;
import android.widget.ArrayAdapter;
import android.widget.AutoCompleteTextView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.PopupMenu;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import android.app.AlertDialog;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;

import java.io.UnsupportedEncodingException;
import java.net.IDN;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class MainActivity extends Activity implements TransferStats.Listener,
        ResourceInterceptor.Listener, BrowserClient.Listener, BrowserClient.BrowserChromeClient.Listener {
    private static final String PREFS = "slowline_settings";
    private static final String PREF_BLOCK_TRACKERS = "block_trackers";
    private static final String PREF_IMAGES = "images_enabled";
    private static final String PREF_THIRD_PARTY = "third_party_cookies";
    private static final String PREF_GPRS_MODE = "gprs_text_first";
    private static final int FILE_CHOOSER_REQUEST = 1701;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private SinglePageCache pageCache;
    private TransferStats transferStats;
    private TrackerBlocker trackerBlocker;
    private ResourceInterceptor resourceInterceptor;

    private LinearLayout root;
    private AutoCompleteTextView addressBar;
    private ArrayAdapter<String> addressAdapter;
    private final ArrayList<String> addressSuggestions = new ArrayList<>();
    private ProgressBar progressBar;
    private TextView statsText;
    private TextView hourglass;
    private ObjectAnimator hourglassAnimator;
    private WebView webView;
    private TextView backButton;
    private TextView forwardButton;
    private TextView reloadButton;
    private boolean loading;
    private boolean navigationPrepared;
    private int progressValue;
    private long pageReceivedBytes;
    private long receiveBytesPerSecond;
    private long lastUidRxBytes = -1L;
    private long lastTrafficSampleAt;
    private boolean uidTrafficStatsAvailable;

    private final Runnable trafficSampler = new Runnable() {
        @Override public void run() {
            sampleAppTraffic();
            if (webView != null) mainHandler.postDelayed(this, 1000L);
        }
    };
    private String notice = "";
    private boolean imagesEnabled = true;
    private boolean thirdPartyCookies = false;
    private boolean gprsMode = true;
    private ValueCallback<Uri[]> fileChooserCallback;
    private SharedPreferences preferences;
    private BrowserStore browserStore;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(Color.rgb(17, 19, 21));
        getWindow().setNavigationBarColor(Color.rgb(17, 19, 21));
        getWindow().getDecorView().setSystemUiVisibility(0);

        preferences = getSharedPreferences(PREFS, MODE_PRIVATE);
        trackerBlocker = new TrackerBlocker();
        trackerBlocker.setEnabled(preferences.getBoolean(PREF_BLOCK_TRACKERS, true));
        imagesEnabled = preferences.getBoolean(PREF_IMAGES, true);
        thirdPartyCookies = preferences.getBoolean(PREF_THIRD_PARTY, false);
        gprsMode = preferences.getBoolean(PREF_GPRS_MODE, true);
        browserStore = new BrowserStore(preferences);
        pageCache = new SinglePageCache(getFilesDir());
        transferStats = new TransferStats(this);
        resourceInterceptor = new ResourceInterceptor(pageCache, transferStats, trackerBlocker, this);
        resourceInterceptor.setAllowThirdPartyCookies(thirdPartyCookies);

        buildUi();
        configureWebView();
        resetPageTrafficCounters();
        mainHandler.postDelayed(trafficSampler, 1000L);

        String launchUrl = null;
        Intent launchIntent = getIntent();
        if (launchIntent != null && Intent.ACTION_VIEW.equals(launchIntent.getAction()) && launchIntent.getData() != null) {
            String candidate = launchIntent.getData().toString();
            if (isHttpUrl(candidate)) launchUrl = candidate;
        }
        String lastPage = pageCache.getActivePage();
        String initialPage = launchUrl != null ? launchUrl : (isHttpUrl(lastPage) ? lastPage : null);
        if (initialPage != null) {
            addressBar.setText(initialPage);
            prepareNavigation(initialPage);
            webView.loadUrl(initialPage);
        } else {
            showStartPage();
        }
        renderStats(transferStats.snapshot());
    }

    private void buildUi() {
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(19, 22, 25));
        if (Build.VERSION.SDK_INT >= 35) {
            root.setOnApplyWindowInsetsListener((view, insets) -> {
                android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
                view.setPadding(0, bars.top, 0, bars.bottom);
                return WindowInsets.CONSUMED;
            });
        }

        LinearLayout toolbar = new LinearLayout(this);
        toolbar.setOrientation(LinearLayout.HORIZONTAL);
        toolbar.setGravity(Gravity.CENTER_VERTICAL);
        toolbar.setPadding(dp(3), dp(3), dp(3), dp(3));
        toolbar.setBackgroundColor(Color.rgb(37, 42, 47));

        backButton = toolbarButton("◀", "Назад — предыдущая страница");
        forwardButton = toolbarButton("▶", "Вперёд");
        reloadButton = toolbarButton("↻", "Обновить / остановить");
        toolbar.addView(backButton, new LinearLayout.LayoutParams(dp(34), dp(38)));
        toolbar.addView(forwardButton, new LinearLayout.LayoutParams(dp(34), dp(38)));
        toolbar.addView(reloadButton, new LinearLayout.LayoutParams(dp(34), dp(38)));

        hourglass = new TextView(this);
        hourglass.setText("⌛");
        hourglass.setTextSize(16);
        hourglass.setGravity(Gravity.CENTER);
        hourglass.setTextColor(Color.rgb(231, 192, 112));
        hourglass.setContentDescription("Загрузка");
        hourglass.setVisibility(View.GONE);
        toolbar.addView(hourglass, new LinearLayout.LayoutParams(dp(22), dp(36)));

        addressBar = new AutoCompleteTextView(this);
        addressBar.setSingleLine(true);
        addressBar.setTextSize(13);
        addressBar.setTextColor(Color.rgb(236, 239, 242));
        addressBar.setHintTextColor(Color.rgb(153, 162, 172));
        addressBar.setHint("Адрес / поиск");
        addressBar.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        addressBar.setImeOptions(EditorInfo.IME_ACTION_GO);
        addressBar.setThreshold(0);
        addressBar.setDropDownHeight(dp(240));
        addressBar.setDropDownVerticalOffset(dp(2));
        addressBar.setPadding(dp(8), 0, dp(8), 0);
        android.graphics.drawable.GradientDrawable addressBg = new android.graphics.drawable.GradientDrawable();
        addressBg.setColor(Color.rgb(20, 23, 26));
        addressBg.setCornerRadius(dp(2));
        addressBg.setStroke(dp(1), Color.rgb(105, 116, 126));
        addressBar.setBackground(addressBg);
        android.graphics.drawable.GradientDrawable dropBg = new android.graphics.drawable.GradientDrawable();
        dropBg.setColor(Color.rgb(30, 35, 40));
        dropBg.setCornerRadius(dp(2));
        dropBg.setStroke(dp(1), Color.rgb(105, 116, 126));
        addressBar.setDropDownBackgroundDrawable(dropBg);
        addressAdapter = new ArrayAdapter<>(this, android.R.layout.simple_dropdown_item_1line, addressSuggestions);
        addressBar.setAdapter(addressAdapter);
        LinearLayout.LayoutParams addressParams = new LinearLayout.LayoutParams(0, dp(36), 1f);
        addressParams.setMargins(dp(3), 0, dp(3), 0);
        toolbar.addView(addressBar, addressParams);

        TextView go = toolbarButton("GO", "Перейти");
        go.setTextSize(11);
        go.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        go.setOnClickListener(v -> navigateFromAddressBar());
        toolbar.addView(go, new LinearLayout.LayoutParams(dp(36), dp(38)));
        TextView menu = toolbarButton("⋮", "Настройки и приватность");
        menu.setOnClickListener(this::showBrowserMenu);
        toolbar.addView(menu, new LinearLayout.LayoutParams(dp(30), dp(38)));

        backButton.setOnClickListener(v -> {
            if (webView != null && webView.canGoBack()) webView.goBack();
        });
        forwardButton.setOnClickListener(v -> {
            if (webView != null && webView.canGoForward()) webView.goForward();
        });
        reloadButton.setOnClickListener(v -> {
            if (webView == null) return;
            if (loading) {
                webView.stopLoading();
                loading = false;
                reloadButton.setText("↻");
                renderStats(transferStats.snapshot());
            } else {
                notice = "";
                resourceInterceptor.setRevalidateMainDocumentOnce();
                webView.reload();
            }
        });
        addressBar.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus) {
                refreshAddressSuggestions();
                if (addressAdapter.getCount() > 0) addressBar.postDelayed(() -> addressBar.showDropDown(), 120L);
            } else {
                addressBar.dismissDropDown();
            }
        });
        addressBar.setOnItemClickListener((parent, view, position, id) -> {
            Object selected = parent.getItemAtPosition(position);
            if (selected != null) {
                addressBar.setText(selected.toString());
                addressBar.setSelection(addressBar.length());
                navigateFromAddressBar();
            }
        });
        addressBar.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_GO || (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER)) {
                navigateFromAddressBar();
                return true;
            }
            return false;
        });
        root.addView(toolbar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)));

        progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setMax(100);
        progressBar.setProgress(0);
        progressBar.setProgressTintList(ColorStateList.valueOf(Color.rgb(216, 176, 99)));
        progressBar.setProgressBackgroundTintList(ColorStateList.valueOf(Color.rgb(57, 64, 71)));
        root.addView(progressBar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(3)));

        statsText = new TextView(this);
        statsText.setTextColor(Color.rgb(189, 198, 207));
        statsText.setTextSize(10);
        statsText.setPadding(dp(7), dp(3), dp(7), dp(4));
        statsText.setMaxLines(2);
        statsText.setBackgroundColor(Color.rgb(27, 31, 35));
        root.addView(statsText, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        webView = new WebView(this);
        root.addView(webView, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout footer = new LinearLayout(this);
        footer.setOrientation(LinearLayout.HORIZONTAL);
        footer.setGravity(Gravity.CENTER_VERTICAL);
        footer.setPadding(dp(3), dp(2), dp(3), dp(2));
        footer.setBackgroundColor(Color.rgb(37, 42, 47));
        TextView history = footerButton("История");
        history.setOnClickListener(v -> showHistoryDialog());
        TextView bookmarks = footerButton("Закладки");
        bookmarks.setOnClickListener(v -> showBookmarksDialog());
        TextView addBookmark = footerButton("★");
        addBookmark.setContentDescription("Добавить текущую страницу в закладки");
        addBookmark.setOnClickListener(v -> addCurrentBookmark());
        footer.addView(history, new LinearLayout.LayoutParams(0, dp(32), 1f));
        footer.addView(bookmarks, new LinearLayout.LayoutParams(0, dp(32), 1f));
        footer.addView(addBookmark, new LinearLayout.LayoutParams(dp(46), dp(32)));
        root.addView(footer, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(36)));

        setContentView(root);
        refreshAddressSuggestions();
        updateNavigationButtons();
    }

    private TextView toolbarButton(String label, String description) {
        TextView button = new TextView(this);
        button.setText(label);
        button.setTextSize(23);
        button.setGravity(Gravity.CENTER);
        button.setTextColor(Color.rgb(222, 227, 234));
        button.setContentDescription(description);
        button.setBackground(classicButtonBackground());
        button.setFocusable(true);
        return button;
    }

    private TextView footerButton(String label) {
        TextView button = new TextView(this);
        button.setText(label);
        button.setTextSize(11);
        button.setGravity(Gravity.CENTER);
        button.setTextColor(Color.rgb(222, 227, 234));
        button.setBackground(classicButtonBackground());
        button.setFocusable(true);
        return button;
    }

    private android.graphics.drawable.Drawable classicButtonBackground() {
        android.graphics.drawable.GradientDrawable normal = new android.graphics.drawable.GradientDrawable();
        normal.setColor(Color.rgb(54, 62, 70));
        normal.setCornerRadius(dp(2));
        normal.setStroke(dp(1), Color.rgb(113, 125, 136));
        android.graphics.drawable.GradientDrawable pressed = new android.graphics.drawable.GradientDrawable();
        pressed.setColor(Color.rgb(77, 87, 96));
        pressed.setCornerRadius(dp(2));
        pressed.setStroke(dp(1), Color.rgb(190, 164, 111));
        android.graphics.drawable.StateListDrawable states = new android.graphics.drawable.StateListDrawable();
        states.addState(new int[]{android.R.attr.state_pressed}, pressed);
        states.addState(new int[]{}, normal);
        return states;
    }

    private void configureWebView() {
        WebSettings settings = webView.getSettings();
        resourceInterceptor.setUserAgent(settings.getUserAgentString());
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(false);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setJavaScriptCanOpenWindowsAutomatically(false);
        settings.setSupportMultipleWindows(false);
        settings.setLoadsImagesAutomatically(imagesEnabled);
        // GPRS text-first: let the document and its interface arrive before images start.
        settings.setBlockNetworkImage(!imagesEnabled || gprsMode);
        settings.setDefaultTextEncodingName("UTF-8");
        settings.setBuiltInZoomControls(true);
        settings.setDisplayZoomControls(false);
        settings.setSupportZoom(true);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setSafeBrowsingEnabled(true);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        settings.setMediaPlaybackRequiresUserGesture(true);
        CookieManager cookies = CookieManager.getInstance();
        cookies.setAcceptCookie(true);
        cookies.setAcceptThirdPartyCookies(webView, thirdPartyCookies);
        webView.setWebViewClient(new BrowserClient(resourceInterceptor, this));
        webView.setWebChromeClient(new BrowserClient.BrowserChromeClient(this));
        webView.setDownloadListener(downloadListener());
        webView.setBackgroundColor(Color.rgb(17, 19, 21));
        webView.setOverScrollMode(View.OVER_SCROLL_IF_CONTENT_SCROLLS);
    }

    private DownloadListener downloadListener() {
        return (url, userAgent, contentDisposition, mimeType, contentLength) -> {
            try {
                if (!isHttpUrl(url)) {
                    Toast.makeText(this, "Эта ссылка не поддерживается загрузчиком Android", Toast.LENGTH_LONG).show();
                    return;
                }
                DownloadManager.Request request = new DownloadManager.Request(Uri.parse(url));
                request.setTitle(URLUtil.guessFileName(url, contentDisposition, mimeType));
                request.setDescription("Slowline Browser");
                request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
                request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, URLUtil.guessFileName(url, contentDisposition, mimeType));
                if (userAgent != null) request.addRequestHeader("User-Agent", userAgent);
                String cookie = CookieManager.getInstance().getCookie(url);
                if (cookie != null && !cookie.isEmpty()) request.addRequestHeader("Cookie", cookie);
                if (mimeType != null) request.setMimeType(mimeType);
                DownloadManager manager = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
                manager.enqueue(request);
                Toast.makeText(this, "Загрузка добавлена в очередь Android", Toast.LENGTH_SHORT).show();
            } catch (Exception e) {
                Toast.makeText(this, "Не удалось начать загрузку файла", Toast.LENGTH_LONG).show();
            }
        };
    }

    private void navigateFromAddressBar() {
        String target = normalizeAddress(addressBar.getText().toString());
        if (target == null) return;
        hideKeyboard();
        notice = "";
        addressBar.setText(target);
        prepareNavigation(target);
        webView.loadUrl(target);
    }

    private String normalizeAddress(String input) {
        String value = input == null ? "" : input.trim();
        if (value.isEmpty()) return null;

        // Explicit HTTP(S) addresses always win; they must never be sent to search.
        if (value.matches("(?i)^https?://.*")) return value;

        boolean looksLikeHost = looksLikeWebAddress(value);
        if (value.matches("(?i)^[a-z][a-z0-9+.-]*:.*") && !looksLikeHost) {
            Toast.makeText(this, "Разрешены только HTTP и HTTPS-адреса", Toast.LENGTH_LONG).show();
            return null;
        }
        if (looksLikeHost) return "https://" + value;

        try {
            return "https://html.duckduckgo.com/html/?q=" + URLEncoder.encode(value, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            return "https://html.duckduckgo.com/html/";
        }
    }

    private boolean looksLikeWebAddress(String value) {
        if (value == null || value.isEmpty() || value.matches(".*\\s+.*")) return false;
        String authority = value.split("[/?#]", 2)[0];
        if (authority.isEmpty() || authority.contains("@")) return false;

        // Bracketed IPv6 literal, optionally with a port.
        if (authority.startsWith("[")) {
            int close = authority.indexOf(']');
            if (close <= 1) return false;
            String suffix = authority.substring(close + 1);
            return suffix.isEmpty() || suffix.matches(":\\d{1,5}");
        }

        String host = authority;
        int colon = host.lastIndexOf(':');
        if (colon >= 0) {
            String port = host.substring(colon + 1);
            if (!port.matches("\\d{1,5}")) return false;
            host = host.substring(0, colon);
        }
        if (host.equalsIgnoreCase("localhost")) return true;
        if (host.matches("\\d{1,3}(?:\\.\\d{1,3}){3}")) return true;

        try {
            if (host.endsWith(".")) host = host.substring(0, host.length() - 1);
            host = IDN.toASCII(host, IDN.USE_STD3_ASCII_RULES);
        } catch (IllegalArgumentException e) {
            return false;
        }
        if (!host.contains(".")) return false;
        String[] labels = host.split("\\.", -1);
        if (labels.length < 2) return false;
        for (String label : labels) {
            if (label.isEmpty() || label.length() > 63 || label.startsWith("-") || label.endsWith("-")) return false;
            if (!label.matches("[A-Za-z0-9-]+")) return false;
        }
        return labels[labels.length - 1].length() >= 2;
    }

    private void prepareNavigation(String url) {
        if (!isHttpUrl(url) || isInternalStartUrl(url)) return;
        navigationPrepared = true;
        boolean changed = pageCache.beginPage(url);
        if (changed) {
            if (webView != null) webView.clearCache(true); // Discard Chromium's previous-page cache.
            transferStats.reset();
            resetPageTrafficCounters();
            progressValue = 0;
            progressBar.setProgress(0);
        }
        if (addressBar != null) addressBar.setText(url);
        updateNavigationButtons();
    }

    private void showStartPage() {
        String html = "<!doctype html><html><head><meta charset='utf-8'><meta name='viewport' content='width=device-width,initial-scale=1'>"
                + "<title>Slowline Browser</title><style>html{color-scheme:dark}body{font:16px system-ui,sans-serif;color:#e6eaf0;background:#111315;margin:12vh auto;padding:0 22px;max-width:620px}h1{font-size:28px;font-weight:500;color:#f4f6f8}p{line-height:1.6;color:#b6bec8}.hint{font-size:13px;border-top:1px solid #343a40;margin-top:24px;padding-top:14px}</style>"
                + "</head><body><h1>Slowline Browser</h1><p>Браузер для медленных соединений. Введите адрес или поисковый запрос в поле сверху.</p>"
                + "<p>Изображения включены. Обнаруженные ресурсы, известный размер, фактически переданные байты и объём из кэша показываются в верхней строке.</p>"
                + "<p class='hint'>Частичное продолжение работает только для ответов, которые сервер разрешает продолжать через HTTP Range. Если сервер этого не поддерживает, браузер попробует обычную загрузку.</p></body></html>";
        webView.loadDataWithBaseURL("https://slowline.local/", html, "text/html", "UTF-8", null);
    }

    private void showBrowserMenu(View anchor) {
        PopupMenu popup = new PopupMenu(this, anchor);
        popup.getMenu().add(0, 1, 1, (trackerBlocker.isEnabled() ? "✓ " : "○ ") + "Блокировать известные трекеры");
        popup.getMenu().add(0, 2, 2, (imagesEnabled ? "✓ " : "○ ") + "Загружать изображения");
        popup.getMenu().add(0, 3, 3, (thirdPartyCookies ? "✓ " : "○ ") + "Разрешить сторонние cookie");
        popup.getMenu().add(0, 4, 4, "Обновить из сети (удалить копию страницы)");
        popup.getMenu().add(0, 5, 5, "Скопировать ссылку без UTM/рекламных меток");
        popup.getMenu().add(0, 6, 6, "Очистить cookie, историю и кэш");
        popup.getMenu().add(0, 7, 7, "О текущем кэше и ограничениях");
        popup.getMenu().add(0, 8, 8, "Настройки Private DNS Android");
        popup.getMenu().add(0, 9, 9, "Добавить текущую страницу в закладки");
        popup.getMenu().add(0, 10, 10, "История");
        popup.getMenu().add(0, 11, 11, "Закладки");
        popup.getMenu().add(0, 12, 12, (gprsMode ? "✓ " : "○ ") + "GPRS: сначала текст, затем изображения");
        popup.setOnMenuItemClickListener(this::onMenuItemClicked);
        popup.show();
    }

    private boolean onMenuItemClicked(MenuItem item) {
        switch (item.getItemId()) {
            case 1:
                trackerBlocker.setEnabled(!trackerBlocker.isEnabled());
                preferences.edit().putBoolean(PREF_BLOCK_TRACKERS, trackerBlocker.isEnabled()).apply();
                Toast.makeText(this, trackerBlocker.isEnabled() ? "Блокировка трекеров включена" : "Блокировка трекеров отключена", Toast.LENGTH_SHORT).show();
                return true;
            case 2:
                imagesEnabled = !imagesEnabled;
                preferences.edit().putBoolean(PREF_IMAGES, imagesEnabled).apply();
                webView.getSettings().setLoadsImagesAutomatically(imagesEnabled);
                webView.getSettings().setBlockNetworkImage(!imagesEnabled || (gprsMode && loading));
                Toast.makeText(this, imagesEnabled ? "Изображения включены" : "Изображения выключены", Toast.LENGTH_SHORT).show();
                if (imagesEnabled) webView.reload();
                return true;
            case 3:
                thirdPartyCookies = !thirdPartyCookies;
                preferences.edit().putBoolean(PREF_THIRD_PARTY, thirdPartyCookies).apply();
                CookieManager.getInstance().setAcceptThirdPartyCookies(webView, thirdPartyCookies);
                resourceInterceptor.setAllowThirdPartyCookies(thirdPartyCookies);
                Toast.makeText(this, thirdPartyCookies ? "Сторонние cookie разрешены" : "Сторонние cookie блокируются", Toast.LENGTH_SHORT).show();
                return true;
            case 4:
                if (isHttpUrl(webView.getUrl())) {
                    pageCache.clearCurrentPageResources();
                    webView.clearCache(true);
                    transferStats.reset();
                    notice = "Локальная копия удалена; запрашивается новая версия";
                    resourceInterceptor.setRevalidateMainDocumentOnce();
                    webView.reload();
                }
                return true;
            case 5:
                copyCleanUrl();
                return true;
            case 6:
                confirmClearBrowsingData();
                return true;
            case 7:
                showCacheInfo();
                return true;
            case 8:
                try {
                    startActivity(new Intent("android.settings.PRIVATE_DNS_SETTINGS"));
                } catch (Exception e) {
                    Toast.makeText(this, "Откройте Настройки Android → Сеть → Private DNS", Toast.LENGTH_LONG).show();
                }
                return true;
            case 9:
                addCurrentBookmark();
                return true;
            case 10:
                showHistoryDialog();
                return true;
            case 11:
                showBookmarksDialog();
                return true;
            case 12:
                gprsMode = !gprsMode;
                preferences.edit().putBoolean(PREF_GPRS_MODE, gprsMode).apply();
                if (webView != null) {
                    webView.getSettings().setBlockNetworkImage(!imagesEnabled || gprsMode);
                    if (isHttpUrl(webView.getUrl())) webView.reload();
                }
                Toast.makeText(this, gprsMode ? "GPRS: сначала текст, затем изображения" : "Обычная загрузка ресурсов", Toast.LENGTH_SHORT).show();
                return true;
            default:
                return false;
        }
    }

    private void copyCleanUrl() {
        String current = webView.getUrl();
        if (!isHttpUrl(current)) return;
        Uri uri = Uri.parse(current);
        Set<String> tracking = new HashSet<>(Arrays.asList(
                "utm_source", "utm_medium", "utm_campaign", "utm_term", "utm_content", "utm_id",
                "gclid", "dclid", "fbclid", "msclkid", "yclid", "gbraid", "wbraid", "igshid",
                "mc_cid", "mc_eid", "vero_id", "_hsenc", "_hsmi", "ref_src"
        ));
        Uri.Builder builder = uri.buildUpon().clearQuery();
        for (String name : uri.getQueryParameterNames()) {
            if (tracking.contains(name.toLowerCase(Locale.ROOT))) continue;
            for (String value : uri.getQueryParameters(name)) builder.appendQueryParameter(name, value);
        }
        String clean = builder.build().toString();
        android.content.ClipboardManager clipboard = (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        clipboard.setPrimaryClip(ClipData.newPlainText("URL", clean));
        Toast.makeText(this, "Очищенная ссылка скопирована", Toast.LENGTH_SHORT).show();
    }

    private void confirmClearBrowsingData() {
        new AlertDialog.Builder(this)
                .setTitle("Очистить данные браузера?")
                .setMessage("Будут удалены cookie, localStorage, история WebView и одностраничный кэш. Это завершит сохранённые сеансы на сайтах.")
                .setNegativeButton("Отмена", null)
                .setPositiveButton("Очистить", (dialog, which) -> clearBrowsingData())
                .show();
    }

    private void clearBrowsingData() {
        webView.stopLoading();
        pageCache.clearCurrentPageResources();
        webView.clearCache(true);
        webView.clearHistory();
        WebStorage.getInstance().deleteAllData();
        CookieManager.getInstance().removeAllCookies(success -> CookieManager.getInstance().flush());
        browserStore.clearHistory();
        refreshAddressSuggestions();
        transferStats.reset();
        notice = "Данные браузера очищены";
        renderStats(transferStats.snapshot());
        Toast.makeText(this, "Cookie, история и кэш очищены", Toast.LENGTH_SHORT).show();
    }

    private void showCacheInfo() {
        String url = pageCache.getActivePage();
        String message = "Кэш хранит ресурсы только текущего верхнеуровневого URL. При переходе на другую страницу старые файлы удаляются. При перезапуске приложения текущий кэш сохраняется для попытки продолжить загрузку.\n\n"
                + "HTTP Range: продолжение возможно только если сервер поддерживает диапазоны и не меняет представление ресурса. Иначе загрузка начинается заново.\n\n"
                + "Строка прогресса: процент WebView не равен проценту полученных байтов. Размер суммируется только для обнаруженных ресурсов с известным Content-Length; часть запросов WebView не раскрывает полностью.\n\n"
                + "Текущая страница: " + (url == null ? "нет" : url) + "\n\n"
                + "Антислежка — локальный список доменов, не полная анонимизация. Браузер не использует VPN, Tor или прокси и не гарантирует обход блокировок. Private DNS — системная настройка Android: она может защитить DNS-запросы, но не обходит блокировки по IP/SNI/DPI.";
        ScrollView scroll = new ScrollView(this);
        TextView text = new TextView(this);
        text.setText(message);
        text.setTextSize(14);
        text.setPadding(dp(18), dp(14), dp(18), dp(14));
        scroll.addView(text);
        new AlertDialog.Builder(this).setTitle("Кэш и сетевые ограничения").setView(scroll).setPositiveButton("Закрыть", null).show();
    }

    private void hideKeyboard() {
        try {
            InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
            imm.hideSoftInputFromWindow(addressBar.getWindowToken(), 0);
            addressBar.dismissDropDown();
            addressBar.clearFocus();
        } catch (Exception ignored) { }
    }

    private void updateNavigationButtons() {
        if (webView == null) return;
        boolean canBack = webView.canGoBack();
        boolean canForward = webView.canGoForward();
        backButton.setAlpha(canBack ? 1f : 0.35f);
        forwardButton.setAlpha(canForward ? 1f : 0.35f);
        backButton.setContentDescription(canBack ? "Назад — предыдущая страница" : "Назад недоступно");
        forwardButton.setContentDescription(canForward ? "Вперёд" : "Вперёд недоступно");
    }

    private void refreshAddressSuggestions() {
        if (addressAdapter == null || browserStore == null) return;
        LinkedHashSet<String> values = new LinkedHashSet<>();
        for (BrowserStore.Entry entry : browserStore.history()) {
            values.add(entry.url);
            if (values.size() >= 30) break;
        }
        for (BrowserStore.Entry entry : browserStore.bookmarks()) {
            values.add(entry.url);
            if (values.size() >= 40) break;
        }
        addressAdapter.clear();
        addressAdapter.addAll(values);
        addressAdapter.notifyDataSetChanged();
    }

    private void showHistoryDialog() {
        showSavedEntries("История посещений и поиска", browserStore.history(), false);
    }

    private void showBookmarksDialog() {
        showSavedEntries("Закладки", browserStore.bookmarks(), true);
    }

    private void showSavedEntries(String title, List<BrowserStore.Entry> entries, boolean allowDelete) {
        if (entries == null || entries.isEmpty()) {
            Toast.makeText(this, allowDelete ? "Закладок пока нет" : "История пока пуста", Toast.LENGTH_SHORT).show();
            return;
        }
        ArrayList<String> labels = new ArrayList<>();
        for (BrowserStore.Entry entry : entries) labels.add(entry.label());
        ListView list = new ListView(this);
        list.setDividerHeight(dp(1));
        list.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, labels));
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(title)
                .setView(list)
                .setNegativeButton("Закрыть", null)
                .setNeutralButton(allowDelete ? "Готово" : "Очистить историю", (d, which) -> {
                    if (!allowDelete) {
                        browserStore.clearHistory();
                        refreshAddressSuggestions();
                        Toast.makeText(this, "История очищена", Toast.LENGTH_SHORT).show();
                    }
                })
                .create();
        list.setOnItemClickListener((parent, view, position, id) -> {
            BrowserStore.Entry entry = entries.get(position);
            dialog.dismiss();
            loadBrowserEntry(entry.url);
        });
        if (allowDelete) {
            list.setOnItemLongClickListener((parent, view, position, id) -> {
                BrowserStore.Entry entry = entries.get(position);
                new AlertDialog.Builder(this)
                        .setTitle("Удалить закладку?")
                        .setMessage(entry.label())
                        .setNegativeButton("Отмена", null)
                        .setPositiveButton("Удалить", (d, which) -> {
                            browserStore.removeBookmark(entry.url);
                            refreshAddressSuggestions();
                            dialog.dismiss();
                            showBookmarksDialog();
                        }).show();
                return true;
            });
        }
        dialog.show();
    }

    private void loadBrowserEntry(String url) {
        if (!isHttpUrl(url) || webView == null) return;
        addressBar.setText(url);
        prepareNavigation(url);
        webView.loadUrl(url);
    }

    private void addCurrentBookmark() {
        String url = webView == null ? null : webView.getUrl();
        if (!isHttpUrl(url) || isInternalStartUrl(url)) {
            Toast.makeText(this, "Откройте страницу перед добавлением закладки", Toast.LENGTH_SHORT).show();
            return;
        }
        boolean added = browserStore.addBookmark(url, webView.getTitle());
        refreshAddressSuggestions();
        Toast.makeText(this, added ? "Добавлено в закладки" : "Эта страница уже в закладках", Toast.LENGTH_SHORT).show();
    }

    private void updateHourglass(int pendingRequests) {
        if (hourglass == null) return;
        boolean active = loading || pendingRequests > 0;
        if (active) {
            hourglass.setVisibility(View.VISIBLE);
            if (hourglassAnimator == null) {
                hourglassAnimator = ObjectAnimator.ofFloat(hourglass, "rotation", 0f, 180f);
                hourglassAnimator.setDuration(850L);
                hourglassAnimator.setRepeatCount(ValueAnimator.INFINITE);
                hourglassAnimator.setInterpolator(new LinearInterpolator());
            }
            if (!hourglassAnimator.isStarted()) hourglassAnimator.start();
        } else {
            if (hourglassAnimator != null) hourglassAnimator.cancel();
            hourglass.setRotation(0f);
            hourglass.setVisibility(View.GONE);
        }
    }

    @Override public void onTopLevelNavigation(String url) {
        prepareNavigation(url);
        notice = "";
    }

    @Override public void onPageStarted(String url) {
        // Do not treat an HTTP redirect as a new page and erase in-flight cache files.
        // Back/forward navigation still prepares a fresh active page here.
        if (!navigationPrepared && isHttpUrl(url) && !isInternalStartUrl(url)) prepareNavigation(url);
        navigationPrepared = true;
        loading = true;
        if (webView != null) webView.getSettings().setBlockNetworkImage(!imagesEnabled || gprsMode);
        progressValue = 0;
        progressBar.setVisibility(View.VISIBLE);
        progressBar.setProgress(0);
        reloadButton.setText("×");
        notice = "";
        if (isHttpUrl(url) && !isInternalStartUrl(url)) addressBar.setText(url);
        renderStats(transferStats.snapshot());
        updateNavigationButtons();
    }

    @Override public void onPageFinished(String url) {
        if (isHttpUrl(url) && !isInternalStartUrl(url)) {
            pageCache.adoptActivePage(url);
            browserStore.addHistory(url, webView == null ? null : webView.getTitle());
            refreshAddressSuggestions();
        }
        loading = false;
        navigationPrepared = false;
        if (webView != null) webView.getSettings().setBlockNetworkImage(!imagesEnabled);
        progressValue = 100;
        progressBar.setProgress(100);
        reloadButton.setText("↻");
        if (isHttpUrl(url) && !isInternalStartUrl(url)) addressBar.setText(url);
        updateNavigationButtons();
        renderStats(transferStats.snapshot());
        mainHandler.postDelayed(() -> {
            if (!loading && transferStats.snapshot().pendingRequests == 0 && progressBar != null) progressBar.setVisibility(View.INVISIBLE);
        }, 900);
    }

    @Override public void onProgressChanged(int progress) {
        if (progress < 100) {
            loading = true;
            progressValue = progress;
            progressBar.setVisibility(View.VISIBLE);
            reloadButton.setText("×");
        } else {
            // WebView progress is for the main frame, not every outstanding subresource.
            // Hold below 100 until onPageFinished is called.
            progressValue = loading ? 97 : 100;
        }
        progressBar.setProgress(progressValue);
        renderStats(transferStats.snapshot());
    }

    @Override public void onMainFrameError(String description) {
        loading = false;
        navigationPrepared = false;
        if (isHttpUrl(webView == null ? null : webView.getUrl())) {
            browserStore.addHistory(webView.getUrl(), webView.getTitle());
            refreshAddressSuggestions();
        }
        if (webView != null) webView.getSettings().setBlockNetworkImage(!imagesEnabled);
        reloadButton.setText("↻");
        String detail = description == null ? "Ошибка загрузки" : description;
        String current = webView == null ? null : webView.getUrl();
        if (isHttpUrl(current)) {
            SinglePageCache.Entry entry = pageCache.getEntry(current);
            if (entry != null && !entry.complete && entry.bodyLength() > 0) {
                detail += " · частичная копия сохранена; обновите страницу для попытки продолжения";
            }
        }
        notice = detail;
        renderStats(transferStats.snapshot());
    }

    @Override public void onExternalScheme(String url) {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            if (intent.resolveActivity(getPackageManager()) != null) startActivity(intent);
            else Toast.makeText(this, "Нет приложения для этой ссылки", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this, "Не удалось открыть внешнюю ссылку", Toast.LENGTH_SHORT).show();
        }
    }

    @Override public void onSafeBrowsingWarning(String url) {
        notice = "Защита Safe Browsing заблокировала опасный адрес";
        renderStats(transferStats.snapshot());
    }

    @Override public void onPartialFallback(String url, long received, long expected) {
        mainHandler.post(() -> {
            notice = "Сохранена частичная копия (" + formatBytes(received) + (expected > 0 ? " из " + formatBytes(expected) : "") + "); обновите страницу для попытки продолжения";
            renderStats(transferStats.snapshot());
        });
    }

    @Override public void onCacheError(String message) {
        mainHandler.post(() -> {
            notice = message;
            renderStats(transferStats.snapshot());
        });
    }

    @Override public void onStatsChanged(TransferStats.Snapshot snapshot) {
        mainHandler.post(() -> renderStats(snapshot));
    }

    private void resetPageTrafficCounters() {
        pageReceivedBytes = 0;
        receiveBytesPerSecond = 0;
        lastTrafficSampleAt = SystemClock.elapsedRealtime();
        long current = TrafficStats.getUidRxBytes(Process.myUid());
        uidTrafficStatsAvailable = current >= 0;
        lastUidRxBytes = uidTrafficStatsAvailable ? current : -1L;
    }

    private void sampleAppTraffic() {
        long now = SystemClock.elapsedRealtime();
        long current = TrafficStats.getUidRxBytes(Process.myUid());
        if (current >= 0) {
            uidTrafficStatsAvailable = true;
            if (lastUidRxBytes >= 0 && current >= lastUidRxBytes) {
                long delta = current - lastUidRxBytes;
                pageReceivedBytes += delta;
                long elapsed = Math.max(1L, now - lastTrafficSampleAt);
                receiveBytesPerSecond = delta * 1000L / elapsed;
            } else {
                receiveBytesPerSecond = 0;
            }
            lastUidRxBytes = current;
        } else {
            uidTrafficStatsAvailable = false;
            lastUidRxBytes = -1L;
            receiveBytesPerSecond = 0;
        }
        lastTrafficSampleAt = now;
        if (statsText != null) renderStats(transferStats.snapshot());
    }

    private void renderStats(TransferStats.Snapshot snapshot) {
        if (statsText == null || snapshot == null) return;
        String phase;
        if (loading) phase = "Документ " + progressValue + "%";
        else if (snapshot.pendingRequests > 0) phase = "Ресурсы: " + snapshot.pendingRequests + " в загрузке";
        else phase = "Готово";
        String known = snapshot.knownTotalBytes > 0 ? formatBytes(snapshot.knownTotalBytes) : "неизвестен";
        String received = uidTrafficStatsAvailable ? formatBytes(pageReceivedBytes) : "недоступно";
        String rate = uidTrafficStatsAvailable ? formatBytes(receiveBytesPerSecond) + "/с" : "недоступна";
        String mode = gprsMode ? "GPRS: текст сначала" : "обычный режим";
        String value = phase + " · " + mode
                + " · ресурсов: " + snapshot.uniqueUrls
                + " · известный размер: " + known
                + " · получено: " + received
                + " · скорость: " + rate
                + " · кэш: " + formatBytes(snapshot.cacheBytesServed);
        if (snapshot.blockedUrls > 0) value += " · блокировано: " + snapshot.blockedUrls;
        if (notice != null && !notice.isEmpty()) value += "\n" + notice;
        statsText.setText(value);
        updateHourglass(snapshot.pendingRequests);
        if (loading || snapshot.pendingRequests > 0) progressBar.setVisibility(View.VISIBLE);
        else progressBar.setVisibility(View.INVISIBLE);
    }

    @Override public boolean onFileChooser(ValueCallback<Uri[]> callback, WebChromeClient.FileChooserParams params) {
        if (fileChooserCallback != null) fileChooserCallback.onReceiveValue(null);
        fileChooserCallback = callback;
        try {
            Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("*/*");
            intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, params != null && params.getMode() == WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE);
            startActivityForResult(Intent.createChooser(intent, "Выбрать файл"), FILE_CHOOSER_REQUEST);
            return true;
        } catch (Exception e) {
            fileChooserCallback = null;
            callback.onReceiveValue(null);
            return false;
        }
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == FILE_CHOOSER_REQUEST && fileChooserCallback != null) {
            Uri[] result = null;
            if (resultCode == RESULT_OK && data != null) {
                if (data.getClipData() != null) {
                    ClipData clip = data.getClipData();
                    result = new Uri[clip.getItemCount()];
                    for (int i = 0; i < clip.getItemCount(); i++) result[i] = clip.getItemAt(i).getUri();
                } else if (data.getData() != null) result = new Uri[]{data.getData()};
            }
            fileChooserCallback.onReceiveValue(result);
            fileChooserCallback = null;
        }
    }

    private static boolean isHttpUrl(String value) {
        return value != null && value.matches("(?i)^https?://.+");
    }

    private static boolean isInternalStartUrl(String value) {
        return value != null && value.toLowerCase(Locale.ROOT).startsWith("https://slowline.local/");
    }

    private static String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        double kb = bytes / 1024.0;
        if (kb < 1024) return String.format(Locale.ROOT, "%.1f KB", kb);
        double mb = kb / 1024.0;
        if (mb < 1024) return String.format(Locale.ROOT, "%.2f MB", mb);
        return String.format(Locale.ROOT, "%.2f GB", mb / 1024.0);
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (intent != null && Intent.ACTION_VIEW.equals(intent.getAction()) && intent.getData() != null) {
            String target = intent.getData().toString();
            if (isHttpUrl(target)) {
                addressBar.setText(target);
                prepareNavigation(target);
                webView.loadUrl(target);
            }
        }
    }

    @Override public void onBackPressed() {
        if (webView != null && webView.canGoBack()) webView.goBack();
        else super.onBackPressed();
    }

    @Override protected void onDestroy() {
        mainHandler.removeCallbacks(trafficSampler);
        if (hourglassAnimator != null) hourglassAnimator.cancel();
        if (fileChooserCallback != null) {
            fileChooserCallback.onReceiveValue(null);
            fileChooserCallback = null;
        }
        if (webView != null) {
            webView.stopLoading();
            webView.setWebChromeClient(null);
            webView.setWebViewClient(new WebViewClient());
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }
}

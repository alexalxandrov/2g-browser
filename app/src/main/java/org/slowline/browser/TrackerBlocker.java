package org.slowline.browser;

import java.net.URI;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/** A small, offline-first tracker list. False positives are possible; users can disable it. */
public final class TrackerBlocker {
    private volatile boolean enabled = true;
    private final Set<String> domains = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "doubleclick.net", "googlesyndication.com", "googleadservices.com",
            "google-analytics.com", "googletagmanager.com", "connect.facebook.net",
            "facebook.com/tr", "pixel.facebook.com", "analytics.tiktok.com",
            "ads.tiktok.com", "hotjar.com", "clarity.ms", "segment.io",
            "api.segment.io", "mixpanel.com", "amplitude.com", "scorecardresearch.com",
            "taboola.com", "outbrain.com", "criteo.com", "adnxs.com", "rubiconproject.com",
            "pubmatic.com", "openx.net", "adsrvr.org", "app-measurement.com",
            "branch.io", "adjust.com", "smartadserver.com", "quantserve.com",
            "newrelic.com", "nr-data.net", "optimizely.com", "mouseflow.com",
            "fullstory.com", "demdex.net", "everesttech.net", "adform.net",
            "yieldmo.com", "casalemedia.com", "lijit.com", "bidswitch.net"
    )));

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean value) { enabled = value; }

    public boolean shouldBlock(String url, boolean mainFrame) {
        if (!enabled || mainFrame) return false;
        try {
            URI uri = URI.create(url);
            String host = uri.getHost();
            if (host == null) return false;
            host = host.toLowerCase(Locale.ROOT);
            for (String rule : domains) {
                // The path-specific Facebook pixel rule avoids blocking the whole facebook.com domain.
                if (rule.contains("/")) {
                    String ruleHost = rule.substring(0, rule.indexOf('/'));
                    String path = rule.substring(rule.indexOf('/'));
                    if (host.equals(ruleHost) && uri.getPath() != null && uri.getPath().startsWith(path)) return true;
                } else if (host.equals(rule) || host.endsWith("." + rule)) {
                    return true;
                }
            }
        } catch (Exception ignored) {
            return false;
        }
        return false;
    }
}

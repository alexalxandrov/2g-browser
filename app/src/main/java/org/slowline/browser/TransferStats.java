package org.slowline.browser;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Counters for requests observed by the app's interception layer. They are not whole-device totals. */
public final class TransferStats {
    public interface Listener { void onStatsChanged(Snapshot snapshot); }

    public static final class Snapshot {
        public final int uniqueUrls;
        public final int blockedUrls;
        public final long knownTotalBytes;
        public final long networkBytes;
        public final long cacheBytesServed;
        public final int pendingRequests;

        Snapshot(int uniqueUrls, int blockedUrls, long knownTotalBytes, long networkBytes, long cacheBytesServed, int pendingRequests) {
            this.uniqueUrls = uniqueUrls;
            this.blockedUrls = blockedUrls;
            this.knownTotalBytes = knownTotalBytes;
            this.networkBytes = networkBytes;
            this.cacheBytesServed = cacheBytesServed;
            this.pendingRequests = pendingRequests;
        }
    }

    private final Listener listener;
    private final Set<String> urls = new HashSet<>();
    private final Set<String> blocked = new HashSet<>();
    private final Map<String, Long> expectedSizes = new HashMap<>();
    private long networkBytes;
    private long cacheBytesServed;
    private int pendingRequests;

    public TransferStats(Listener listener) { this.listener = listener; }

    public synchronized void reset() {
        urls.clear();
        blocked.clear();
        expectedSizes.clear();
        networkBytes = 0;
        cacheBytesServed = 0;
        pendingRequests = 0;
        publish();
    }

    public synchronized void request(String url) {
        urls.add(url);
        publish();
    }

    public synchronized void blocked(String url) {
        blocked.add(url);
        urls.add(url);
        publish();
    }

    public synchronized void knownSize(String url, long size) {
        if (size >= 0) expectedSizes.put(url, size);
        publish();
    }

    public synchronized void received(String url, long count) {
        if (count <= 0) return;
        networkBytes += count;
        publish();
    }

    public synchronized void servedFromCache(String url, long count) {
        if (count > 0) cacheBytesServed += count;
        publish();
    }

    public synchronized void requestStarted() {
        pendingRequests++;
        publish();
    }

    public synchronized void requestFinished() {
        if (pendingRequests > 0) pendingRequests--;
        publish();
    }

    public synchronized Snapshot snapshot() {
        long known = 0;
        for (Long size : expectedSizes.values()) known += Math.max(0, size);
        return new Snapshot(urls.size(), blocked.size(), known, networkBytes, cacheBytesServed, pendingRequests);
    }

    private void publish() {
        if (listener != null) listener.onStatsChanged(snapshot());
    }
}

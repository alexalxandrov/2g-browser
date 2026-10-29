package org.slowline.browser;

import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;

/** Lightweight persistent browser history and bookmarks. No database or network access. */
public final class BrowserStore {
    private static final String HISTORY_KEY = "browser_history_v1";
    private static final String BOOKMARKS_KEY = "browser_bookmarks_v1";
    private static final int MAX_HISTORY = 100;

    public static final class Entry {
        public final String url;
        public final String title;
        public final long time;
        Entry(String url, String title, long time) {
            this.url = url;
            this.title = title == null || title.trim().isEmpty() ? url : title;
            this.time = time;
        }
        public String label() { return title.equals(url) ? url : title + " — " + url; }
    }

    private final SharedPreferences preferences;
    public BrowserStore(SharedPreferences preferences) { this.preferences = preferences; }

    public synchronized List<Entry> history() { return read(HISTORY_KEY); }
    public synchronized List<Entry> bookmarks() { return read(BOOKMARKS_KEY); }

    public synchronized void addHistory(String url, String title) {
        if (!isHttpUrl(url)) return;
        List<Entry> entries = new ArrayList<>(read(HISTORY_KEY));
        for (int i = entries.size() - 1; i >= 0; i--) {
            if (entries.get(i).url.equals(url)) entries.remove(i);
        }
        entries.add(0, new Entry(url, title, System.currentTimeMillis()));
        while (entries.size() > MAX_HISTORY) entries.remove(entries.size() - 1);
        write(HISTORY_KEY, entries);
    }

    public synchronized boolean addBookmark(String url, String title) {
        if (!isHttpUrl(url)) return false;
        List<Entry> entries = new ArrayList<>(read(BOOKMARKS_KEY));
        for (Entry entry : entries) if (entry.url.equals(url)) return false;
        entries.add(0, new Entry(url, title, System.currentTimeMillis()));
        write(BOOKMARKS_KEY, entries);
        return true;
    }

    public synchronized void removeBookmark(String url) {
        List<Entry> entries = new ArrayList<>(read(BOOKMARKS_KEY));
        for (int i = entries.size() - 1; i >= 0; i--) {
            if (entries.get(i).url.equals(url)) entries.remove(i);
        }
        write(BOOKMARKS_KEY, entries);
    }

    public synchronized void clearHistory() { preferences.edit().remove(HISTORY_KEY).apply(); }

    private List<Entry> read(String key) {
        String encoded = preferences.getString(key, "[]");
        List<Entry> entries = new ArrayList<>();
        try {
            JSONArray array = new JSONArray(encoded);
            for (int i = 0; i < array.length(); i++) {
                JSONObject object = array.optJSONObject(i);
                if (object == null) continue;
                String url = object.optString("url", "");
                if (!isHttpUrl(url)) continue;
                entries.add(new Entry(url, object.optString("title", url), object.optLong("time", 0L)));
            }
        } catch (Exception ignored) { }
        return entries;
    }

    private void write(String key, List<Entry> entries) {
        JSONArray array = new JSONArray();
        for (Entry entry : entries) {
            JSONObject object = new JSONObject();
            try {
                object.put("url", entry.url);
                object.put("title", entry.title);
                object.put("time", entry.time);
                array.put(object);
            } catch (Exception ignored) { }
        }
        preferences.edit().putString(key, array.toString()).apply();
    }

    private static boolean isHttpUrl(String value) {
        return value != null && value.matches("(?i)^https?://.+");
    }
}

package com.animke.app;

import android.app.DownloadManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Environment;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * İndirilen bölümler. Dosyalar uygulamanın kendi klasörüne (izin gerekmez, uygulama silinince
 * gider) Android'in indirme yöneticisiyle iner: uygulama kapalıyken de sürer, kopan bağlantıda
 * kaldığı yerden devam eder, ilerleme bildirimde görünür.
 */
public final class DownloadStore {

    private static final String PREFS = "animke_downloads";
    private static final String KEY_ITEMS = "items";
    private static final String KEY_WIFI_ONLY = "wifi_only";

    /** Bir indirme kaydı. */
    public static final class Item {
        public String id;        // bölüm kaydı (sitede tekil)
        public String anime;     // anime adı
        public String label;     // "1. sezon 5. bölüm" gibi
        public String quality;   // "720p"
        public String watchUrl;  // sitedeki izleme adresi (çevrimiçiyken)
        public long videoDm;     // indirme yöneticisi kimliği
        public long subDm;       // altyazı (yoksa -1)
        public long size;        // bayt (tahmini)
        public long added;
        public long position;    // kaldığı yer (ms)

        JSONObject toJson() throws Exception {
            JSONObject o = new JSONObject();
            o.put("id", id).put("anime", anime).put("label", label).put("quality", quality)
                .put("watchUrl", watchUrl).put("videoDm", videoDm).put("subDm", subDm)
                .put("size", size).put("added", added).put("position", position);
            return o;
        }

        static Item fromJson(JSONObject o) {
            Item i = new Item();
            i.id = o.optString("id");
            i.anime = o.optString("anime");
            i.label = o.optString("label");
            i.quality = o.optString("quality");
            i.watchUrl = o.optString("watchUrl");
            i.videoDm = o.optLong("videoDm", -1);
            i.subDm = o.optLong("subDm", -1);
            i.size = o.optLong("size");
            i.added = o.optLong("added");
            i.position = o.optLong("position");
            return i;
        }
    }

    /** İndirme yöneticisindeki durum. */
    public static final class Status {
        public int state = DownloadManager.STATUS_FAILED;
        public long done;
        public long total;
        public int reason;
    }

    private DownloadStore() {}

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static boolean wifiOnly(Context c) {
        return prefs(c).getBoolean(KEY_WIFI_ONLY, true);
    }

    public static void setWifiOnly(Context c, boolean v) {
        prefs(c).edit().putBoolean(KEY_WIFI_ONLY, v).apply();
    }

    public static synchronized List<Item> all(Context c) {
        List<Item> out = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(prefs(c).getString(KEY_ITEMS, "[]"));
            for (int i = 0; i < arr.length(); i++) out.add(Item.fromJson(arr.getJSONObject(i)));
        } catch (Exception ignored) {
            // bozuk kayıt
        }
        return out;
    }

    private static synchronized void save(Context c, List<Item> items) {
        JSONArray arr = new JSONArray();
        for (Item i : items) {
            try {
                arr.put(i.toJson());
            } catch (Exception ignored) {
                // atla
            }
        }
        prefs(c).edit().putString(KEY_ITEMS, arr.toString()).apply();
    }

    public static Item find(Context c, String id) {
        for (Item i : all(c)) if (i.id.equals(id)) return i;
        return null;
    }

    private static File dir(Context c) {
        File d = new File(c.getExternalFilesDir(Environment.DIRECTORY_MOVIES), "animke");
        //noinspection ResultOfMethodCallIgnored
        d.mkdirs();
        return d;
    }

    public static File videoFile(Context c, String id) {
        return new File(dir(c), safe(id) + ".mp4");
    }

    public static File subtitleFile(Context c, String id) {
        return new File(dir(c), safe(id) + ".ass");
    }

    private static String safe(String id) {
        return id.replaceAll("[^A-Za-z0-9_-]", "_");
    }

    /** Sırada / inmiş değilse indirmeyi başlatır. Dönüş: yeni eklendiyse true. */
    public static boolean enqueue(Context c, Item item, String videoUrl, String subtitleUrl) {
        if (find(c, item.id) != null) return false;
        DownloadManager dm = (DownloadManager) c.getSystemService(Context.DOWNLOAD_SERVICE);
        if (dm == null) return false;
        boolean wifi = wifiOnly(c);

        File video = videoFile(c, item.id);
        //noinspection ResultOfMethodCallIgnored
        video.delete();
        DownloadManager.Request req = new DownloadManager.Request(Uri.parse(videoUrl))
            .setTitle("Animke · " + item.anime)
            .setDescription(item.label)
            .setDestinationInExternalFilesDir(c, Environment.DIRECTORY_MOVIES, "animke/" + video.getName())
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setAllowedOverMetered(!wifi)
            .setAllowedOverRoaming(false);
        item.videoDm = dm.enqueue(req);

        item.subDm = -1;
        if (subtitleUrl != null && !subtitleUrl.isEmpty()) {
            File sub = subtitleFile(c, item.id);
            //noinspection ResultOfMethodCallIgnored
            sub.delete();
            DownloadManager.Request s = new DownloadManager.Request(Uri.parse(subtitleUrl))
                .setDestinationInExternalFilesDir(c, Environment.DIRECTORY_MOVIES, "animke/" + sub.getName())
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_HIDDEN)
                .setAllowedOverMetered(!wifi);
            item.subDm = dm.enqueue(s);
        }
        item.added = System.currentTimeMillis();
        List<Item> items = all(c);
        items.add(0, item);
        save(c, items);
        return true;
    }

    public static Status status(Context c, long dmId) {
        Status s = new Status();
        DownloadManager dm = (DownloadManager) c.getSystemService(Context.DOWNLOAD_SERVICE);
        if (dm == null || dmId < 0) return s;
        try (Cursor cur = dm.query(new DownloadManager.Query().setFilterById(dmId))) {
            if (cur != null && cur.moveToFirst()) {
                s.state = cur.getInt(cur.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
                s.done = cur.getLong(cur.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR));
                s.total = cur.getLong(cur.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES));
                s.reason = cur.getInt(cur.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON));
            }
        } catch (Exception ignored) {
            // sorgu başarısız
        }
        return s;
    }

    /** Video inmiş mi (dosya var ve indirme tamam)? */
    public static boolean ready(Context c, Item i) {
        return status(c, i.videoDm).state == DownloadManager.STATUS_SUCCESSFUL && videoFile(c, i.id).exists();
    }

    public static void remove(Context c, String id) {
        DownloadManager dm = (DownloadManager) c.getSystemService(Context.DOWNLOAD_SERVICE);
        List<Item> items = all(c);
        List<Item> keep = new ArrayList<>();
        for (Item i : items) {
            if (!i.id.equals(id)) {
                keep.add(i);
                continue;
            }
            if (dm != null) {
                if (i.videoDm >= 0) dm.remove(i.videoDm);
                if (i.subDm >= 0) dm.remove(i.subDm);
            }
            //noinspection ResultOfMethodCallIgnored
            videoFile(c, i.id).delete();
            //noinspection ResultOfMethodCallIgnored
            subtitleFile(c, i.id).delete();
        }
        save(c, keep);
    }

    public static void savePosition(Context c, String id, long positionMs) {
        List<Item> items = all(c);
        for (Item i : items) if (i.id.equals(id)) i.position = positionMs;
        save(c, items);
    }

    /** Sitenin "indirildi" işaretleri için: indirilen / inen bölüm kimlikleri. */
    public static JSONArray ids(Context c) {
        JSONArray arr = new JSONArray();
        for (Item i : all(c)) arr.put(i.id);
        return arr;
    }
}

package com.animke.app;

import android.app.DownloadManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Environment;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * İndirilen bölümler.
 *
 * Video, Android'in indirme yöneticisiyle uygulamanın kendi klasörüne iner (uygulama kapalıyken
 * de sürer, kopan bağlantıda kaldığı yerden devam eder). İnince hemen şifrelenir (.vid) ve düz
 * hâli silinir. Altyazı ise uygulamanın kendisi tarafından indirilip doğrudan şifreli yazılır
 * (.sub); diske hiç düz hâliyle düşmez. Bkz. DownloadCrypto.
 */
public final class DownloadStore {

    private static final String PREFS = "animke_downloads";
    private static final String KEY_ITEMS = "items";
    private static final String KEY_WIFI_ONLY = "wifi_only";
    private static final Set<String> ENCRYPTING = Collections.synchronizedSet(new HashSet<>());

    /** Bir indirme kaydı. */
    public static final class Item {
        public String id;        // bölüm kaydı (sitede tekil)
        public String anime;     // anime adı
        public String label;     // "1. sezon 5. bölüm" gibi
        public String quality;   // "720p"
        public String watchUrl;  // sitedeki izleme adresi (çevrimiçiyken)
        public long videoDm;     // indirme yöneticisi kimliği
        public long size;        // bayt (tahmini)
        public long added;
        public long position;    // kaldığı yer (ms)
        public String videoUrl;  // tekrar denemek için
        public String subUrl;

        JSONObject toJson() throws Exception {
            JSONObject o = new JSONObject();
            o.put("id", id).put("anime", anime).put("label", label).put("quality", quality)
                .put("watchUrl", watchUrl).put("videoDm", videoDm)
                .put("size", size).put("added", added).put("position", position)
                .put("videoUrl", videoUrl).put("subUrl", subUrl);
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
            i.size = o.optLong("size");
            i.added = o.optLong("added");
            i.position = o.optLong("position");
            i.videoUrl = o.optString("videoUrl");
            i.subUrl = o.optString("subUrl");
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
        prefs(c).edit().putString(KEY_ITEMS, arr.toString()).commit();
    }

    public static Item find(Context c, String id) {
        for (Item i : all(c)) if (i.id.equals(id)) return i;
        return null;
    }

    public static Item findByDownload(Context c, long dmId) {
        for (Item i : all(c)) if (i.videoDm == dmId) return i;
        return null;
    }

    private static File dir(Context c) {
        File d = new File(c.getExternalFilesDir(Environment.DIRECTORY_MOVIES), "animke");
        //noinspection ResultOfMethodCallIgnored
        d.mkdirs();
        return d;
    }

    private static String safe(String id) {
        return id.replaceAll("[^A-Za-z0-9_-]", "_");
    }

    /** İndirme yöneticisinin indirdiği düz video (şifrelenince silinir). */
    static File plainVideoFile(Context c, String id) {
        return new File(dir(c), safe(id) + ".mp4");
    }

    /** Şifreli video. */
    public static File videoFile(Context c, String id) {
        return new File(dir(c), safe(id) + ".vid");
    }

    /** Şifreli altyazı. */
    public static File subtitleFile(Context c, String id) {
        return new File(dir(c), safe(id) + ".sub");
    }

    /**
     * Video sunucusu yalnızca animke.com'dan gelen isteklere izin verir (Cloudflare): indirme
     * yöneticisi de tarayıcı gibi sitenin adresini ve uygulamanın kimliğini gönderir.
     */
    private static DownloadManager.Request withSiteHeaders(Context c, DownloadManager.Request r) {
        r.addRequestHeader("Referer", "https://animke.com/");
        r.addRequestHeader("Origin", "https://animke.com");
        String ua = userAgent(c);
        if (ua != null) r.addRequestHeader("User-Agent", ua);
        return r;
    }

    private static String userAgent(Context c) {
        try {
            return android.webkit.WebSettings.getDefaultUserAgent(c) + " AnimkeAndroid";
        } catch (Exception e) {
            return null;
        }
    }

    /** Sırada / inmiş değilse indirmeyi başlatır. Dönüş: yeni eklendiyse true. */
    public static boolean enqueue(Context c, Item item, String videoUrl, String subtitleUrl) {
        if (find(c, item.id) != null) return false;
        DownloadManager dm = (DownloadManager) c.getSystemService(Context.DOWNLOAD_SERVICE);
        if (dm == null) return false;
        boolean wifi = wifiOnly(c);

        File plain = plainVideoFile(c, item.id);
        //noinspection ResultOfMethodCallIgnored
        plain.delete();
        DownloadManager.Request req = withSiteHeaders(c, new DownloadManager.Request(Uri.parse(videoUrl)))
            .setTitle("Animke · " + item.anime)
            .setDescription(item.label)
            .setDestinationInExternalFilesDir(c, Environment.DIRECTORY_MOVIES, "animke/" + plain.getName())
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setAllowedOverMetered(!wifi)
            .setAllowedOverRoaming(false);
        item.videoDm = dm.enqueue(req);

        // Kayıt hemen yazılır: sonradan bir şey ters gitse de bölüm İndirilenler'de görünür.
        item.videoUrl = videoUrl;
        item.subUrl = subtitleUrl == null ? "" : subtitleUrl;
        item.added = System.currentTimeMillis();
        List<Item> items = all(c);
        items.add(0, item);
        save(c, items);

        fetchSubtitle(c, item.id, item.subUrl);
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

    /** İzlemeye hazır mı (şifreli video var)? */
    public static boolean ready(Context c, Item i) {
        return videoFile(c, i.id).exists();
    }

    /** Video indi ama henüz şifrelenmedi mi (ya da şifreleniyor mu)? */
    public static boolean preparing(Context c, Item i) {
        return !ready(c, i) && (ENCRYPTING.contains(i.id) || plainVideoFile(c, i.id).exists()
            && status(c, i.videoDm).state == DownloadManager.STATUS_SUCCESSFUL);
    }

    /** İnen düz videoyu arka planda şifreler ve düz hâlini siler. */
    public static void encryptIfNeeded(Context c, Item i, Runnable done) {
        if (ready(c, i) || !plainVideoFile(c, i.id).exists()) return;
        if (status(c, i.videoDm).state != DownloadManager.STATUS_SUCCESSFUL) return;
        if (!ENCRYPTING.add(i.id)) return;
        final Context app = c.getApplicationContext();
        new Thread(() -> {
            try {
                DownloadCrypto.encryptFile(app, plainVideoFile(app, i.id), videoFile(app, i.id));
                //noinspection ResultOfMethodCallIgnored
                plainVideoFile(app, i.id).delete();
            } catch (Exception ignored) {
                // bir sonraki açılışta yeniden denenir
            } finally {
                ENCRYPTING.remove(i.id);
                if (done != null) done.run();
            }
        }).start();
    }

    /** Altyazıyı arka planda indirip şifreli yazar. */
    public static void fetchSubtitle(Context c, String id, String url) {
        if (url == null || !url.startsWith("https://")) return;
        final Context app = c.getApplicationContext();
        new Thread(() -> fetchSubtitleSync(app, id, url)).start();
    }

    /**
     * Altyazıyı bu iş parçacığında indirir ve doğrudan şifreli yazar; zaten varsa ya da
     * indiyse true. Sunucu altyazıyı şifreli gönderir ({"k","iv","d"}), burada açılıp
     * telefonun anahtarıyla yeniden şifrelenir.
     */
    public static boolean fetchSubtitleSync(Context c, String id, String url) {
        File target = subtitleFile(c, id);
        if (target.exists() && target.length() > 0) return true;
        if (url == null || !url.startsWith("https://")) return false;
        HttpURLConnection con = null;
        File part = new File(target.getPath() + ".part");
        try {
            con = (HttpURLConnection) new URL(url).openConnection();
            con.setConnectTimeout(15000);
            con.setReadTimeout(30000);
            con.setRequestProperty("Referer", "https://animke.com/");
            con.setRequestProperty("Origin", "https://animke.com");
            String ua = userAgent(c);
            if (ua != null) con.setRequestProperty("User-Agent", ua);
            if (con.getResponseCode() != 200) return false;
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            try (InputStream in = con.getInputStream()) {
                byte[] buf = new byte[16384];
                int n;
                while ((n = in.read(buf)) > 0) {
                    if (body.size() + n > 20 * 1024 * 1024) return false; // altyazı bu kadar büyük olamaz
                    body.write(buf, 0, n);
                }
            }
            String type = con.getContentType();
            byte[] text = type != null && type.contains("json")
                ? DownloadCrypto.openServerPayload(body.toByteArray())
                : body.toByteArray();
            try (OutputStream out = DownloadCrypto.encryptingStream(c, target, part)) {
                out.write(text);
            }
            return part.renameTo(target);
        } catch (Exception ignored) {
            // bağlantı yok: oynatıcı açılırken yeniden denenir
            return false;
        } finally {
            if (con != null) con.disconnect();
            //noinspection ResultOfMethodCallIgnored
            part.delete();
        }
    }

    public static void remove(Context c, String id) {
        DownloadManager dm = (DownloadManager) c.getSystemService(Context.DOWNLOAD_SERVICE);
        List<Item> keep = new ArrayList<>();
        for (Item i : all(c)) {
            if (!i.id.equals(id)) {
                keep.add(i);
                continue;
            }
            if (dm != null && i.videoDm >= 0) dm.remove(i.videoDm);
            //noinspection ResultOfMethodCallIgnored
            plainVideoFile(c, i.id).delete();
            //noinspection ResultOfMethodCallIgnored
            videoFile(c, i.id).delete();
            //noinspection ResultOfMethodCallIgnored
            subtitleFile(c, i.id).delete();
        }
        save(c, keep);
    }

    /** Başarısız indirmeyi aynı adreslerle yeniden başlatır. */
    public static boolean retry(Context c, String id) {
        Item old = find(c, id);
        if (old == null || old.videoUrl == null || old.videoUrl.isEmpty()) return false;
        remove(c, id);
        Item fresh = new Item();
        fresh.id = old.id;
        fresh.anime = old.anime;
        fresh.label = old.label;
        fresh.quality = old.quality;
        fresh.watchUrl = old.watchUrl;
        fresh.size = old.size;
        return enqueue(c, fresh, old.videoUrl, old.subUrl);
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

    /** İnen ve inmiş dosyaların diskte kapladığı toplam alan. */
    public static long usedBytes(Context c) {
        long total = 0;
        File[] files = dir(c).listFiles();
        if (files != null) for (File f : files) total += f.length();
        return total;
    }

    public static long freeBytes(Context c) {
        return dir(c).getUsableSpace();
    }

    /**
     * Kaydı olmayan dosyaları ve hâlâ inen kayıtsız indirmeleri temizler (ör. eski bir hatadan
     * kalanlar). İndirilenler ekranı açılınca çağrılır.
     */
    public static void cleanupOrphans(Context c) {
        List<Item> items = all(c);
        Set<String> keep = new HashSet<>();
        Set<Long> known = new HashSet<>();
        for (Item i : items) {
            keep.add(plainVideoFile(c, i.id).getName());
            keep.add(videoFile(c, i.id).getName());
            keep.add(subtitleFile(c, i.id).getName());
            known.add(i.videoDm);
        }
        DownloadManager dm = (DownloadManager) c.getSystemService(Context.DOWNLOAD_SERVICE);
        String dirPath = dir(c).getAbsolutePath();
        if (dm != null) {
            try (Cursor cur = dm.query(new DownloadManager.Query())) {
                while (cur != null && cur.moveToNext()) {
                    long id = cur.getLong(cur.getColumnIndexOrThrow(DownloadManager.COLUMN_ID));
                    String local = cur.getString(cur.getColumnIndexOrThrow(DownloadManager.COLUMN_LOCAL_URI));
                    String path = local == null ? null : Uri.parse(local).getPath();
                    if (path != null && path.startsWith(dirPath) && !known.contains(id)) dm.remove(id);
                }
            } catch (Exception ignored) {
                // sorgu başarısız
            }
        }
        File[] files = dir(c).listFiles();
        if (files == null) return;
        for (File f : files) {
            boolean partial = f.getName().endsWith(".part");
            if ((!keep.contains(f.getName()) || partial) && System.currentTimeMillis() - f.lastModified() > 60_000) {
                //noinspection ResultOfMethodCallIgnored
                f.delete();
            }
        }
    }
}

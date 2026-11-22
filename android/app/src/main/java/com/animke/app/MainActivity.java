package com.animke.app;

import android.Manifest;
import android.app.DownloadManager;
import android.app.PictureInPictureParams;
import android.content.BroadcastReceiver;
import android.content.IntentFilter;
import android.os.Environment;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.graphics.Color;
import android.media.AudioManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.util.Rational;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.WebChromeClient;
import android.webkit.WebView;
import android.widget.FrameLayout;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import androidx.webkit.JavaScriptReplyProxy;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;

import com.getcapacitor.BridgeActivity;
import com.getcapacitor.BridgeWebChromeClient;
import com.google.firebase.FirebaseApp;
import com.google.firebase.messaging.FirebaseMessaging;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.HashSet;
import java.util.Set;

/**
 * Animke: animke.com'u açan uygulama.
 *
 * - Video tam ekranı gerçekten tam ekran (yatay döner, çubuklar gizlenir, ekran kararmaz).
 * - Geri tuşu: tam ekrandan çık → sayfada geri git → uygulamayı kapat.
 * - Resim içinde resim: video oynarken uygulamadan çıkınca küçük pencerede devam eder.
 * - Yeni bölüm bildirimleri (Firebase kuruluysa); bildirime dokununca ilgili sayfa açılır.
 * - Site ile köprü ("AnimkeNative"): parlaklık, ses, mobil veri bilgisi, oynatma durumu, bildirim izni.
 *   Yalnızca animke.com'un ana çerçevesi kullanabilir (sayfadaki başka sitelerin çerçeveleri değil).
 */
public class MainActivity extends BridgeActivity {

    private static final String[] SITE_ORIGINS = { "https://animke.com", "https://www.animke.com" };

    private View fullscreenView;
    private WebChromeClient.CustomViewCallback fullscreenCallback;
    private WebView webView;

    /** Sitedeki oynatıcı oynuyor mu, görüntü oranı ne? (köprüden gelir) */
    private boolean videoPlaying = false;
    private int videoWidth = 16;
    private int videoHeight = 9;

    private JavaScriptReplyProxy siteReply;
    private Boolean lastMetered = null;
    private ConnectivityManager.NetworkCallback networkCallback;
    private ActivityResultLauncher<String> notificationPermission;

    /** Uygulama içi güncelleme: inen APK ve izin bekleyen kurulum. */
    private long updateDownloadId = -1;
    private File updateFile;
    private File pendingInstall;
    private BroadcastReceiver downloadReceiver;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        notificationPermission = registerForActivityResult(
            new ActivityResultContracts.RequestPermission(), granted -> sendPushState());
        if (bridge == null) return;

        webView = bridge.getWebView();
        webView.setWebChromeClient(new BridgeWebChromeClient(bridge) {
            @Override
            public void onShowCustomView(View view, CustomViewCallback callback) {
                enterFullscreen(view, callback);
            }

            @Override
            public void onHideCustomView() {
                exitFullscreen();
            }
        });

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (fullscreenView != null) {
                    exitFullscreen();
                } else if (webView.canGoBack()) {
                    webView.goBack();
                } else {
                    finish();
                }
            }
        });

        setupSiteBridge();
        setupPartyAgent();
        watchNetwork();
        MessagingService.ensureChannel(this);
        // Uygulama bildirime dokunularak açıldıysa o sayfaya git.
        webView.post(() -> openFromIntent(getIntent()));
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        openFromIntent(intent);
    }

    /** Bildirimdeki adres (yalnızca animke.com) uygulamada açılır. */
    private void openFromIntent(Intent intent) {
        if (intent == null || webView == null) return;
        // Uygulama açıkken gösterilen bildirim EXTRA_URL, kapalıyken Android'in gösterdiği "url" taşır.
        String url = intent.getStringExtra(MessagingService.EXTRA_URL);
        if (url == null) url = intent.getStringExtra("url");
        if (url == null) return;
        intent.removeExtra(MessagingService.EXTRA_URL);
        intent.removeExtra("url");
        Uri uri = Uri.parse(url);
        String host = uri.getHost();
        if ("https".equals(uri.getScheme()) && ("animke.com".equals(host) || "www.animke.com".equals(host))) {
            webView.loadUrl(url);
        }
    }

    // --- İndirme --------------------------------------------------------------------------------

    private static boolean httpsUrl(String u) {
        return u != null && u.startsWith("https://") && u.length() < 2000;
    }

    /** Siteden gelen bölümleri indirme yöneticisine verir (yalnızca https adresler). */
    private void handleDownload(JSONArray items) {
        if (items == null) return;
        int added = 0, skipped = 0;
        for (int i = 0; i < Math.min(items.length(), 60); i++) {
            JSONObject o = items.optJSONObject(i);
            if (o == null) continue;
            String id = o.optString("id");
            String video = o.optString("video");
            String sub = o.optString("subtitle");
            if (!id.matches("[A-Za-z0-9_-]{1,64}") || !httpsUrl(video)) continue;
            DownloadStore.Item item = new DownloadStore.Item();
            item.id = id;
            item.anime = o.optString("anime", "Animke");
            item.label = o.optString("label");
            item.quality = o.optString("quality");
            String watch = o.optString("watchUrl");
            item.watchUrl = watch.startsWith("https://animke.com/") ? watch : "";
            item.size = o.optLong("size");
            if (DownloadStore.enqueue(this, item, video, httpsUrl(sub) ? sub : null)) added++;
            else skipped++;
        }
        try {
            JSONObject r = new JSONObject();
            r.put("type", "download-result");
            r.put("added", added);
            r.put("skipped", skipped);
            r.put("wifiWaiting", DownloadStore.wifiOnly(this) && isMetered());
            if (siteReply != null) siteReply.postMessage(r.toString());
        } catch (Exception ignored) {
            // sayfa kapanmış olabilir
        }
        postDownloads();
    }

    /** Sitenin "indirildi" işaretleri için indirilen bölüm kimlikleri. */
    private void postDownloads() {
        runOnUiThread(() -> {
            if (siteReply == null) return;
            try {
                JSONObject r = new JSONObject();
                r.put("type", "downloads");
                r.put("ids", DownloadStore.ids(this));
                r.put("wifiOnly", DownloadStore.wifiOnly(this));
                siteReply.postMessage(r.toString());
            } catch (Exception ignored) {
                // sayfa kapanmış olabilir
            }
        });
    }

    // --- Uygulama içi güncelleme -------------------------------------------------------------

    private void postUpdateState(String state) {
        if (siteReply == null) return;
        try {
            JSONObject r = new JSONObject();
            r.put("type", "update-state");
            r.put("state", state);
            siteReply.postMessage(r.toString());
        } catch (Exception ignored) {
            // sayfa kapanmış olabilir
        }
    }

    /** Yeni sürümün APK'sını indirir; bitince Android'in kurulum ekranını açar (kullanıcı onaylar). */
    private void startUpdate(String url, String version) {
        // Yalnızca kendi deposundaki yayınlar.
        if (url == null || !url.startsWith("https://github.com/iEmre1/animke-android/releases/")) return;
        if (updateDownloadId >= 0) {
            postUpdateState("downloading");
            return;
        }
        DownloadManager dm = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
        if (dm == null) {
            postUpdateState("failed");
            return;
        }
        String name = "Animke-" + version.replaceAll("[^0-9.]", "") + ".apk";
        updateFile = new File(getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), name);
        //noinspection ResultOfMethodCallIgnored
        updateFile.delete();
        DownloadManager.Request req = new DownloadManager.Request(Uri.parse(url))
            .setTitle("Animke güncellemesi")
            .setDescription("Sürüm " + version)
            .setMimeType("application/vnd.android.package-archive")
            .setDestinationInExternalFilesDir(this, Environment.DIRECTORY_DOWNLOADS, name)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE);
        updateDownloadId = dm.enqueue(req);
        ensureDownloadReceiver();
        postUpdateState("downloading");
    }

    private void ensureDownloadReceiver() {
        if (downloadReceiver != null) return;
        downloadReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context ctx, Intent intent) {
                long id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1);
                if (id < 0 || id != updateDownloadId) return;
                updateDownloadId = -1;
                DownloadStore.Status st = DownloadStore.status(MainActivity.this, id);
                if (st.state != DownloadManager.STATUS_SUCCESSFUL || updateFile == null || !updateFile.exists()) {
                    postUpdateState("failed");
                    return;
                }
                postUpdateState("installing");
                installApk(updateFile);
            }
        };
        ContextCompat.registerReceiver(this, downloadReceiver,
            new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE), ContextCompat.RECEIVER_EXPORTED);
    }

    private void installApk(File apk) {
        // Android 8+: "bu kaynaktan uygulama yükle" izni yoksa ayar sayfası açılır, dönünce kurulur.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !getPackageManager().canRequestPackageInstalls()) {
            pendingInstall = apk;
            startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + getPackageName())));
            return;
        }
        pendingInstall = null;
        Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", apk);
        Intent install = new Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(install);
        } catch (Exception e) {
            postUpdateState("failed");
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        // İzin ayarından dönüldüyse bekleyen kurulumu sürdür.
        if (pendingInstall != null && pendingInstall.exists()
            && (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || getPackageManager().canRequestPackageInstalls())) {
            installApk(pendingInstall);
        }
    }

    // --- Bildirimler -------------------------------------------------------------------------

    private boolean firebaseReady() {
        try {
            return !FirebaseApp.getApps(this).isEmpty();
        } catch (Exception e) {
            return false;
        }
    }

    private boolean notificationsGranted() {
        return Build.VERSION.SDK_INT < 33
            || ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;
    }

    /** Siteye: bildirimler kullanılabilir mi, izin var mı, cihazın bildirim adresi (token). */
    private void sendPushState() {
        if (siteReply == null) return;
        final boolean available = firebaseReady();
        final boolean granted = notificationsGranted();
        if (!available || !granted) {
            postPush(available, granted, null);
            return;
        }
        FirebaseMessaging.getInstance().getToken().addOnCompleteListener(task ->
            postPush(true, true, task.isSuccessful() ? task.getResult() : null));
    }

    private void postPush(boolean available, boolean granted, String token) {
        runOnUiThread(() -> {
            if (siteReply == null) return;
            try {
                JSONObject s = new JSONObject();
                s.put("type", "push");
                s.put("available", available);
                s.put("granted", granted);
                if (token != null) s.put("token", token);
                siteReply.postMessage(s.toString());
            } catch (Exception ignored) {
                // sayfa kapanmış olabilir
            }
        });
    }

    private void enablePush() {
        if (!firebaseReady()) {
            postPush(false, notificationsGranted(), null);
        } else if (!notificationsGranted() && Build.VERSION.SDK_INT >= 33) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS);
        } else {
            sendPushState();
        }
    }

    // --- Birlikte İzle aracısı ------------------------------------------------------------

    /**
     * Fansub oynatıcıları başka sitelerden (iframe) geldiği için animke.com onların videosunu
     * okuyamaz. assets/party-agent.js her alt çerçeveye sayfa yüklenirken eklenir; videonun
     * olaylarını animke.com'a bildirir ve animke.com'un komutlarını uygular (bkz. dosyadaki açıklama).
     * Senkron komutuyla oynatma dokunma beklemesin diye otomatik oynatmaya da izin verilir.
     */
    private void setupPartyAgent() {
        webView.getSettings().setMediaPlaybackRequiresUserGesture(false);
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) return;
        try (java.io.InputStream in = getAssets().open("party-agent.js")) {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            Set<String> all = new HashSet<>();
            all.add("*");
            WebViewCompat.addDocumentStartJavaScript(webView, out.toString("UTF-8"), all);
        } catch (Exception ignored) {
            // aracı yüklenemezse Birlikte İzle eskisi gibi (geri sayımla) çalışır
        }
    }

    // --- Site köprüsü ---------------------------------------------------------------------

    private void setupSiteBridge() {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) return;
        Set<String> origins = new HashSet<>();
        for (String o : SITE_ORIGINS) origins.add(o);
        WebViewCompat.addWebMessageListener(webView, "AnimkeNative", origins, (view, message, sourceOrigin, isMainFrame, replyProxy) -> {
            if (!isMainFrame || message.getData() == null) return;
            siteReply = replyProxy;
            try {
                handleSiteMessage(new JSONObject(message.getData()));
            } catch (Exception ignored) {
                // geçersiz ileti
            }
        });
    }

    private void handleSiteMessage(JSONObject msg) {
        switch (msg.optString("type")) {
            case "hello":
            case "state":
                sendState();
                break;
            case "playing":
                videoPlaying = msg.optBoolean("playing");
                int w = msg.optInt("width"), h = msg.optInt("height");
                if (w > 0 && h > 0) {
                    videoWidth = w;
                    videoHeight = h;
                }
                updatePipParams();
                break;
            case "download":
                handleDownload(msg.optJSONArray("items"));
                break;
            case "downloads":
                postDownloads();
                break;
            case "open-downloads":
                runOnUiThread(() -> startActivity(new Intent(this, DownloadsActivity.class)));
                break;
            case "update":
                runOnUiThread(() -> startUpdate(msg.optString("url"), msg.optString("version")));
                break;
            case "push-status":
                sendPushState();
                break;
            case "push-enable":
                runOnUiThread(this::enablePush);
                break;
            case "brightness":
                setBrightness((float) msg.optDouble("value", -1));
                break;
            case "volume":
                setVolume((float) msg.optDouble("value", 0.5));
                break;
            default:
                break;
        }
    }

    /** Sitenin bilmesi gerekenler: sürüm, mobil veri, parlaklık, ses. */
    private void sendState() {
        if (siteReply == null) return;
        try {
            JSONObject s = new JSONObject();
            s.put("type", "state");
            s.put("version", BuildConfig.VERSION_NAME);
            s.put("metered", isMetered());
            s.put("brightness", getBrightness());
            s.put("volume", getVolume());
            s.put("pip", Build.VERSION.SDK_INT >= Build.VERSION_CODES.O);
            // Bu sürüm uygulama içi güncellemeyi (APK indir + kur) destekliyor.
            s.put("canUpdate", true);
            siteReply.postMessage(s.toString());
        } catch (Exception ignored) {
            // sayfa kapanmış olabilir
        }
    }

    private boolean isMetered() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        return cm != null && cm.isActiveNetworkMetered();
    }

    /** Ağ değişince (Wi-Fi ↔ mobil veri) siteye bildir: oynatıcı kaliteyi düşürebilsin. */
    private void watchNetwork() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return;
        networkCallback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onCapabilitiesChanged(@NonNull Network network, @NonNull NetworkCapabilities caps) {
                // Sinyal gücü değişince de çağrılır; yalnızca mobil veri durumu değişince bildir.
                boolean metered = !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED);
                if (lastMetered != null && lastMetered == metered) return;
                lastMetered = metered;
                runOnUiThread(MainActivity.this::sendState);
            }
        };
        try {
            cm.registerDefaultNetworkCallback(networkCallback);
        } catch (Exception ignored) {
            networkCallback = null;
        }
    }

    /** 0-1; uygulama kendi parlaklığını ayarlamadıysa sistem parlaklığı. */
    private float getBrightness() {
        float b = getWindow().getAttributes().screenBrightness;
        if (b >= 0) return b;
        try {
            return Settings.System.getInt(getContentResolver(), Settings.System.SCREEN_BRIGHTNESS) / 255f;
        } catch (Exception e) {
            return 0.5f;
        }
    }

    /** Yalnızca bu pencerenin parlaklığı (sistem ayarı değişmez, izin gerekmez). -1: sisteme bırak. */
    private void setBrightness(float value) {
        runOnUiThread(() -> {
            WindowManager.LayoutParams lp = getWindow().getAttributes();
            lp.screenBrightness = value < 0 ? WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE : Math.max(0.01f, Math.min(1f, value));
            getWindow().setAttributes(lp);
        });
    }

    private float getVolume() {
        AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        if (am == null) return 0.5f;
        int max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        return max > 0 ? am.getStreamVolume(AudioManager.STREAM_MUSIC) / (float) max : 0.5f;
    }

    private void setVolume(float value) {
        AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        if (am == null) return;
        int max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        int v = Math.round(Math.max(0f, Math.min(1f, value)) * max);
        am.setStreamVolume(AudioManager.STREAM_MUSIC, v, 0);
    }

    // --- Resim içinde resim ----------------------------------------------------------------

    @RequiresApi(api = Build.VERSION_CODES.O)
    private PictureInPictureParams pipParams() {
        PictureInPictureParams.Builder b = new PictureInPictureParams.Builder();
        // Android'in kabul ettiği en uç oranlar 2.39:1 ile 1:2.39 arası.
        float ratio = Math.max(1 / 2.39f, Math.min(2.39f, videoWidth / (float) videoHeight));
        b.setAspectRatio(ratio >= 1 ? new Rational(Math.round(ratio * 1000), 1000) : new Rational(1000, Math.round(1000 / ratio)));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Android 12+: ana ekrana geçerken kendiliğinden küçük pencereye geçer.
            b.setAutoEnterEnabled(videoPlaying);
            b.setSeamlessResizeEnabled(true);
        }
        return b.build();
    }

    private void updatePipParams() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        runOnUiThread(() -> {
            try {
                setPictureInPictureParams(pipParams());
            } catch (Exception ignored) {
                // cihaz desteklemiyor
            }
        });
    }

    @Override
    protected void onUserLeaveHint() {
        super.onUserLeaveHint();
        // Android 8-11: ana ekran tuşunda elle geçilir (12+ kendiliğinden geçer).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && Build.VERSION.SDK_INT < Build.VERSION_CODES.S && videoPlaying) {
            try {
                enterPictureInPictureMode(pipParams());
            } catch (Exception ignored) {
                // desteklenmiyor
            }
        }
    }

    @Override
    @RequiresApi(api = Build.VERSION_CODES.O)
    public void onPictureInPictureModeChanged(boolean isInPip, Configuration newConfig) {
        super.onPictureInPictureModeChanged(isInPip, newConfig);
        // Küçük pencerede sayfanın yalnızca oynatıcıyı göstermesi için siteye haber ver.
        dispatchToSite("animke-pip", isInPip ? "true" : "false");
    }

    @Override
    public void onStop() {
        super.onStop();
        // Küçük pencere kapatıldıysa ya da resim içinde resim yoksa video arkada çalmaya devam etmesin.
        if (webView != null && !(Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && isInPictureInPictureMode())) {
            webView.evaluateJavascript("document.querySelectorAll('video').forEach(function(v){v.pause()})", null);
        }
    }

    @Override
    public void onDestroy() {
        if (downloadReceiver != null) {
            try {
                unregisterReceiver(downloadReceiver);
            } catch (Exception ignored) {
                // zaten kaldırılmış
            }
        }
        if (networkCallback != null) {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            try {
                if (cm != null) cm.unregisterNetworkCallback(networkCallback);
            } catch (Exception ignored) {
                // zaten kaldırılmış
            }
        }
        super.onDestroy();
    }

    private void dispatchToSite(String event, String detailJs) {
        if (webView == null) return;
        webView.evaluateJavascript(
            "window.dispatchEvent(new CustomEvent('" + event + "',{detail:" + detailJs + "}))", null);
    }

    // --- Video tam ekranı ------------------------------------------------------------------

    private void enterFullscreen(View view, WebChromeClient.CustomViewCallback callback) {
        if (fullscreenView != null) {
            callback.onCustomViewHidden();
            return;
        }
        fullscreenView = view;
        fullscreenCallback = callback;

        ViewGroup decor = (ViewGroup) getWindow().getDecorView();
        view.setBackgroundColor(Color.BLACK);
        decor.addView(view, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        WindowInsetsControllerCompat bars = WindowCompat.getInsetsController(getWindow(), decor);
        bars.hide(WindowInsetsCompat.Type.systemBars());
        bars.setSystemBarsBehavior(WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        // Telefonun iki yatay yönünden hangisi tutuluyorsa ona döner.
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
    }

    private void exitFullscreen() {
        if (fullscreenView == null) return;
        ViewGroup decor = (ViewGroup) getWindow().getDecorView();
        decor.removeView(fullscreenView);
        fullscreenView = null;

        WindowInsetsControllerCompat bars = WindowCompat.getInsetsController(getWindow(), decor);
        bars.show(WindowInsetsCompat.Type.systemBars());

        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
        // Oynatıcıda değiştirilen parlaklık tam ekrandan çıkınca sisteme döner.
        setBrightness(-1);

        if (fullscreenCallback != null) {
            fullscreenCallback.onCustomViewHidden();
            fullscreenCallback = null;
        }
    }
}

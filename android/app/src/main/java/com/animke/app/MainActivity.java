package com.animke.app;

import android.app.PictureInPictureParams;
import android.content.Context;
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
import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import androidx.webkit.JavaScriptReplyProxy;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;

import com.getcapacitor.BridgeActivity;
import com.getcapacitor.BridgeWebChromeClient;

import org.json.JSONObject;

import java.util.HashSet;
import java.util.Set;

/**
 * Animke: animke.com'u açan uygulama.
 *
 * - Video tam ekranı gerçekten tam ekran (yatay döner, çubuklar gizlenir, ekran kararmaz).
 * - Geri tuşu: tam ekrandan çık → sayfada geri git → uygulamayı kapat.
 * - Resim içinde resim: video oynarken uygulamadan çıkınca küçük pencerede devam eder.
 * - Site ile köprü ("AnimkeNative"): parlaklık, ses, mobil veri bilgisi, oynatma durumu.
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

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
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
        watchNetwork();
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

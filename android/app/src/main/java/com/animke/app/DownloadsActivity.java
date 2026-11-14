package com.animke.app;

import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import java.util.List;
import java.util.Locale;

/**
 * İndirilenler: inen / inmiş bölümler, "yalnızca Wi-Fi" ayarı, izle ve sil.
 * İnternet olmadan da açılır (site değil, uygulamanın kendi ekranı).
 */
public class DownloadsActivity extends AppCompatActivity {

    private static final int BG = Color.parseColor("#121212");
    private static final int CARD = Color.parseColor("#1E293B");
    private static final int BRAND = Color.parseColor("#1E90E0");
    private static final int MUTED = Color.parseColor("#9CA3AF");

    private LinearLayout list;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            render();
            handler.postDelayed(this, 1000);
        }
    };

    private int dp(float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(BG);
        scroll.setFillViewport(true);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(16), dp(16), dp(24));
        scroll.addView(root);
        ViewCompat.setOnApplyWindowInsetsListener(scroll, (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(0, bars.top, 0, bars.bottom);
            return insets;
        });

        // Başlık + siteye dönüş
        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = text("İndirilenler", 24, Color.WHITE, true);
        header.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        Button site = button("Animke'ye dön", false);
        site.setOnClickListener(v -> {
            startActivity(new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP));
            finish();
        });
        header.addView(site);
        root.addView(header);

        TextView hint = text("İndirdiğin bölümleri internet olmadan izleyebilirsin.", 14, MUTED, false);
        hint.setPadding(0, dp(4), 0, dp(12));
        root.addView(hint);

        // Yalnızca Wi-Fi
        LinearLayout wifiRow = card();
        wifiRow.setOrientation(LinearLayout.HORIZONTAL);
        wifiRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout wifiText = new LinearLayout(this);
        wifiText.setOrientation(LinearLayout.VERTICAL);
        wifiText.addView(text("Yalnızca Wi-Fi'de indir", 15, Color.WHITE, true));
        wifiText.addView(text("Mobil verideyken indirme Wi-Fi gelince başlar; kotan harcanmaz.", 12, MUTED, false));
        wifiRow.addView(wifiText, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        Switch wifi = new Switch(this);
        wifi.setChecked(DownloadStore.wifiOnly(this));
        wifi.setOnCheckedChangeListener((CompoundButton b, boolean on) -> DownloadStore.setWifiOnly(this, on));
        wifiRow.addView(wifi);
        root.addView(wifiRow);

        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(0, dp(12), 0, 0);
        root.addView(list);

        setContentView(scroll);
    }

    @Override
    protected void onResume() {
        super.onResume();
        handler.post(tick);
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(tick);
    }

    private void render() {
        list.removeAllViews();
        List<DownloadStore.Item> items = DownloadStore.all(this);
        if (items.isEmpty()) {
            TextView empty = text(
                "Henüz indirilen bölüm yok.\n\nUygulamada bir bölümü açıp oynatıcının altındaki \"İndir\" düğmesine dokun. "
                    + "Yalnızca \"Animke\" kaynağındaki bölümler indirilebilir.",
                14, MUTED, false);
            empty.setPadding(0, dp(24), 0, 0);
            empty.setGravity(Gravity.CENTER_HORIZONTAL);
            list.addView(empty);
            return;
        }
        for (DownloadStore.Item item : items) list.addView(row(item));
    }

    private View row(DownloadStore.Item item) {
        LinearLayout c = card();
        c.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(10);
        c.setLayoutParams(lp);

        c.addView(text(item.anime, 16, Color.WHITE, true));
        DownloadStore.Status st = DownloadStore.status(this, item.videoDm);
        boolean ready = st.state == DownloadManager.STATUS_SUCCESSFUL && DownloadStore.videoFile(this, item.id).exists();
        long total = st.total > 0 ? st.total : item.size;

        String state;
        switch (st.state) {
            case DownloadManager.STATUS_SUCCESSFUL:
                state = ready ? "İndirildi · " + mb(total) : "Dosya bulunamadı";
                break;
            case DownloadManager.STATUS_RUNNING:
                state = "İniyor · " + mb(st.done) + " / " + mb(total);
                break;
            case DownloadManager.STATUS_PENDING:
                state = "Sırada";
                break;
            case DownloadManager.STATUS_PAUSED:
                state = st.reason == DownloadManager.PAUSED_QUEUED_FOR_WIFI || st.reason == DownloadManager.PAUSED_WAITING_FOR_NETWORK
                    ? "Wi-Fi bekleniyor · " + mb(st.done) + " / " + mb(total)
                    : "Duraklatıldı · " + mb(st.done) + " / " + mb(total);
                break;
            default:
                state = "İndirilemedi";
        }
        c.addView(text(item.label + (item.quality.isEmpty() ? "" : " · " + item.quality) + "\n" + state, 13, MUTED, false));

        if (!ready && total > 0 && (st.state == DownloadManager.STATUS_RUNNING || st.state == DownloadManager.STATUS_PAUSED)) {
            ProgressBar bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
            bar.setMax(1000);
            bar.setProgress((int) (st.done * 1000 / Math.max(1, total)));
            c.addView(bar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(10)));
        }

        LinearLayout actions = new LinearLayout(this);
        actions.setPadding(0, dp(10), 0, 0);
        if (ready) {
            Button play = button(item.position > 5000 ? "Devam et" : "İzle", true);
            play.setOnClickListener(v -> startActivity(new Intent(this, OfflinePlayerActivity.class).putExtra("id", item.id)));
            actions.addView(play);
        }
        Button del = button(ready ? "Sil" : "İptal", false);
        del.setOnClickListener(v -> new AlertDialog.Builder(this)
            .setTitle(ready ? "Bölüm silinsin mi?" : "İndirme iptal edilsin mi?")
            .setMessage(item.anime + " · " + item.label)
            .setPositiveButton(ready ? "Sil" : "İptal et", (d, w) -> {
                DownloadStore.remove(this, item.id);
                render();
            })
            .setNegativeButton("Vazgeç", null)
            .show());
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        dlp.leftMargin = ready ? dp(8) : 0;
        actions.addView(del, dlp);
        c.addView(actions);
        return c;
    }

    private static String mb(long bytes) {
        if (bytes <= 0) return "?";
        return bytes >= 1024L * 1024 * 1024
            ? String.format(Locale.ROOT, "%.2f GB", bytes / (1024.0 * 1024 * 1024))
            : String.format(Locale.ROOT, "%d MB", Math.round(bytes / (1024.0 * 1024)));
    }

    private LinearLayout card() {
        LinearLayout c = new LinearLayout(this);
        c.setPadding(dp(14), dp(12), dp(14), dp(12));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(CARD);
        bg.setCornerRadius(dp(14));
        c.setBackground(bg);
        return c;
    }

    private TextView text(String s, float sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private Button button(String s, boolean primary) {
        Button b = new Button(this);
        b.setText(s);
        b.setAllCaps(false);
        b.setTextColor(Color.WHITE);
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setPadding(dp(16), dp(8), dp(16), dp(8));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(primary ? BRAND : Color.parseColor("#334155"));
        bg.setCornerRadius(dp(10));
        b.setBackground(bg);
        return b;
    }
}

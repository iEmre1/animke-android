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
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * İndirilenler: animeye göre gruplanmış bölümler, ilerleme, "yalnızca Wi-Fi" ayarı,
 * izle / tekrar dene / sil. İnternet olmadan da açılır (uygulamanın kendi ekranı).
 */
public class DownloadsActivity extends AppCompatActivity {

    private static final int BG = Color.parseColor("#121212");
    private static final int CARD = Color.parseColor("#1A2233");
    private static final int LINE = Color.parseColor("#263247");
    private static final int BRAND = Color.parseColor("#1E90E0");
    private static final int MUTED = Color.parseColor("#9CA3AF");
    private static final int GREEN = Color.parseColor("#34D399");
    private static final int AMBER = Color.parseColor("#FBBF24");
    private static final int RED = Color.parseColor("#F87171");

    private LinearLayout list;
    private TextView summary;
    private String lastSignature = "";
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            render(false);
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
        root.setPadding(dp(16), dp(12), dp(16), dp(28));
        scroll.addView(root);
        ViewCompat.setOnApplyWindowInsetsListener(scroll, (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(0, bars.top, 0, bars.bottom);
            return insets;
        });

        // Üst çubuk: geri + başlık
        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView back = text("‹", 30, Color.WHITE, false);
        back.setPadding(0, 0, dp(14), dp(4));
        back.setOnClickListener(v -> goSite());
        header.addView(back);
        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.addView(text("İndirilenler", 24, Color.WHITE, true));
        summary = text("", 13, MUTED, false);
        titles.addView(summary);
        header.addView(titles, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        root.addView(header);

        // Yalnızca Wi-Fi
        LinearLayout wifiRow = card();
        wifiRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams wlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        wlp.topMargin = dp(16);
        wifiRow.setLayoutParams(wlp);
        LinearLayout wifiText = new LinearLayout(this);
        wifiText.setOrientation(LinearLayout.VERTICAL);
        wifiText.addView(text("Yalnızca Wi-Fi'de indir", 15, Color.WHITE, true));
        wifiText.addView(text("Mobil verideyken indirme Wi-Fi gelince başlar; kotan harcanmaz.", 12, MUTED, false));
        wifiRow.addView(wifiText, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        Switch wifi = new Switch(this);
        wifi.setChecked(DownloadStore.wifiOnly(this));
        wifi.setOnCheckedChangeListener((b, on) -> {
            DownloadStore.setWifiOnly(this, on);
            Toast.makeText(this, on ? "Yeni indirmeler yalnızca Wi-Fi'de inecek." : "Yeni indirmeler mobil veride de inecek.", Toast.LENGTH_SHORT).show();
        });
        wifiRow.addView(wifi);
        root.addView(wifiRow);

        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        root.addView(list);

        setContentView(scroll);
    }

    private void goSite() {
        startActivity(new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP));
        finish();
    }

    @Override
    protected void onResume() {
        super.onResume();
        lastSignature = "";
        handler.post(tick);
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(tick);
    }

    /** Yalnızca bir şey değiştiyse yeniden çizer (her saniye titremesin). */
    private void render(boolean force) {
        List<DownloadStore.Item> items = DownloadStore.all(this);
        StringBuilder sig = new StringBuilder();
        List<DownloadStore.Status> statuses = new ArrayList<>();
        for (DownloadStore.Item i : items) {
            DownloadStore.Status st = DownloadStore.status(this, i.videoDm);
            statuses.add(st);
            sig.append(i.id).append(':').append(st.state).append(':').append(st.done / (512 * 1024)).append(';');
        }
        if (!force && sig.toString().equals(lastSignature)) return;
        lastSignature = sig.toString();

        summary.setText(items.isEmpty()
            ? "İnternetsiz izlemek için bölüm indir"
            : items.size() + " bölüm · " + mb(DownloadStore.usedBytes(this)) + " · boş alan " + mb(DownloadStore.freeBytes(this)));

        list.removeAllViews();
        if (items.isEmpty()) {
            LinearLayout empty = new LinearLayout(this);
            empty.setOrientation(LinearLayout.VERTICAL);
            empty.setGravity(Gravity.CENTER_HORIZONTAL);
            empty.setPadding(dp(8), dp(48), dp(8), 0);
            TextView icon = text("⬇", 44, LINE, false);
            icon.setGravity(Gravity.CENTER);
            empty.addView(icon);
            TextView t = text("Henüz indirilen bölüm yok", 17, Color.WHITE, true);
            t.setGravity(Gravity.CENTER);
            t.setPadding(0, dp(8), 0, dp(6));
            empty.addView(t);
            TextView d = text("Bir bölümü açıp oynatıcının altındaki \"Bölümü indir\" ya da \"Sezonu indir\" düğmesine dokun. "
                + "Yalnızca \"Animke\" kaynağındaki bölümler indirilebilir.", 14, MUTED, false);
            d.setGravity(Gravity.CENTER);
            empty.addView(d);
            Button go = button("Animke'ye dön", true);
            LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            glp.topMargin = dp(18);
            go.setLayoutParams(glp);
            go.setOnClickListener(v -> goSite());
            empty.addView(go);
            list.addView(empty);
            return;
        }

        // Animeye göre grupla (ilk eklenen sırasıyla).
        Map<String, List<Integer>> groups = new LinkedHashMap<>();
        for (int i = 0; i < items.size(); i++) {
            String key = items.get(i).anime;
            if (!groups.containsKey(key)) groups.put(key, new ArrayList<>());
            groups.get(key).add(i);
        }
        for (Map.Entry<String, List<Integer>> g : groups.entrySet()) {
            TextView h = text(g.getKey(), 16, Color.WHITE, true);
            h.setPadding(dp(2), dp(22), 0, dp(8));
            list.addView(h);
            LinearLayout box = card();
            box.setOrientation(LinearLayout.VERTICAL);
            box.setPadding(0, 0, 0, 0);
            List<Integer> idx = g.getValue();
            for (int k = 0; k < idx.size(); k++) {
                if (k > 0) {
                    View sep = new View(this);
                    sep.setBackgroundColor(LINE);
                    box.addView(sep, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1));
                }
                box.addView(row(items.get(idx.get(k)), statuses.get(idx.get(k))));
            }
            list.addView(box);
        }
    }

    private View row(DownloadStore.Item item, DownloadStore.Status st) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.VERTICAL);
        r.setPadding(dp(14), dp(12), dp(14), dp(12));

        boolean ready = st.state == DownloadManager.STATUS_SUCCESSFUL && DownloadStore.videoFile(this, item.id).exists();
        boolean failed = st.state == DownloadManager.STATUS_FAILED || (st.state == DownloadManager.STATUS_SUCCESSFUL && !ready);
        long total = st.total > 0 ? st.total : item.size;
        int pct = total > 0 ? (int) Math.min(100, st.done * 100 / total) : 0;

        String label = item.label + (item.quality == null || item.quality.isEmpty() ? "" : " · " + item.quality);
        r.addView(text(label, 15, Color.WHITE, true));

        String state;
        int color;
        if (ready) {
            state = "İndirildi · " + mb(total) + (item.position > 5000 ? " · " + time(item.position) + "'de kaldın" : "");
            color = GREEN;
        } else if (failed) {
            state = "İndirilemedi" + reason(st.reason);
            color = RED;
        } else if (st.state == DownloadManager.STATUS_RUNNING) {
            state = "İniyor · %" + pct + " · " + mb(st.done) + " / " + mb(total);
            color = BRAND;
        } else if (st.state == DownloadManager.STATUS_PAUSED) {
            boolean wifi = st.reason == DownloadManager.PAUSED_QUEUED_FOR_WIFI || st.reason == DownloadManager.PAUSED_WAITING_FOR_NETWORK;
            state = (wifi ? "Wi-Fi bekleniyor" : "Duraklatıldı") + " · %" + pct;
            color = AMBER;
        } else {
            state = "Sırada";
            color = MUTED;
        }
        TextView s = text(state, 13, color, false);
        s.setPadding(0, dp(3), 0, 0);
        r.addView(s);

        if (!ready && !failed && total > 0) {
            ProgressBar bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
            bar.setMax(100);
            bar.setProgress(pct);
            LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(8));
            blp.topMargin = dp(8);
            r.addView(bar, blp);
        }

        LinearLayout actions = new LinearLayout(this);
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        alp.topMargin = dp(10);
        actions.setLayoutParams(alp);
        if (ready) {
            Button play = button(item.position > 5000 ? "▶  Devam et" : "▶  İzle", true);
            play.setOnClickListener(v -> startActivity(new Intent(this, OfflinePlayerActivity.class).putExtra("id", item.id)));
            actions.addView(play);
        } else if (failed) {
            Button again = button("Tekrar dene", true);
            again.setOnClickListener(v -> {
                if (!DownloadStore.retry(this, item.id)) {
                    Toast.makeText(this, "Bu indirme yeniden başlatılamadı; bölümü siteden tekrar indir.", Toast.LENGTH_LONG).show();
                }
                render(true);
            });
            actions.addView(again);
        }
        Button del = button(ready || failed ? "Sil" : "İptal", false);
        del.setOnClickListener(v -> new AlertDialog.Builder(this)
            .setTitle(ready ? "Bölüm silinsin mi?" : failed ? "Kayıt silinsin mi?" : "İndirme iptal edilsin mi?")
            .setMessage(item.anime + " · " + item.label)
            .setPositiveButton(ready || failed ? "Sil" : "İptal et", (d, w) -> {
                DownloadStore.remove(this, item.id);
                render(true);
            })
            .setNegativeButton("Vazgeç", null)
            .show());
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        dlp.leftMargin = actions.getChildCount() > 0 ? dp(8) : 0;
        actions.addView(del, dlp);
        r.addView(actions);
        return r;
    }

    private static String reason(int code) {
        switch (code) {
            case DownloadManager.ERROR_INSUFFICIENT_SPACE:
                return " · telefonda yer yok";
            case DownloadManager.ERROR_HTTP_DATA_ERROR:
            case DownloadManager.ERROR_CANNOT_RESUME:
                return " · bağlantı koptu";
            case DownloadManager.ERROR_FILE_ERROR:
            case DownloadManager.ERROR_DEVICE_NOT_FOUND:
                return " · dosya yazılamadı";
            default:
                return code >= 400 && code < 600 ? " · sunucu reddetti (" + code + ")" : "";
        }
    }

    private static String time(long ms) {
        long s = ms / 1000;
        return String.format(Locale.ROOT, "%d:%02d", s / 60, s % 60);
    }

    private static String mb(long bytes) {
        if (bytes <= 0) return "?";
        return bytes >= 1024L * 1024 * 1024
            ? String.format(Locale.ROOT, "%.1f GB", bytes / (1024.0 * 1024 * 1024))
            : String.format(Locale.ROOT, "%d MB", Math.max(1, Math.round(bytes / (1024.0 * 1024))));
    }

    private LinearLayout card() {
        LinearLayout c = new LinearLayout(this);
        c.setPadding(dp(14), dp(12), dp(14), dp(12));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(CARD);
        bg.setCornerRadius(dp(16));
        c.setBackground(bg);
        c.setClipToOutline(true);
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
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setPadding(dp(16), dp(9), dp(16), dp(9));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(primary ? BRAND : Color.parseColor("#334155"));
        bg.setCornerRadius(dp(10));
        b.setBackground(bg);
        b.setStateListAnimator(null);
        return b;
    }
}

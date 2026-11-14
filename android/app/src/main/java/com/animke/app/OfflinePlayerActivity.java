package com.animke.app;

import android.content.pm.ActivityInfo;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.view.WindowManager;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.PlayerView;

import java.io.File;
import java.util.Collections;

/**
 * İndirilen bölümü internetsiz oynatır (Media3). Altyazı (ASS) varsa gösterilir;
 * kaldığın yer kaydedilir ve tekrar açınca oradan devam eder.
 */
public class OfflinePlayerActivity extends AppCompatActivity {

    private ExoPlayer player;
    private String id;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        id = getIntent().getStringExtra("id");
        DownloadStore.Item item = id == null ? null : DownloadStore.find(this, id);
        File video = id == null ? null : DownloadStore.videoFile(this, id);
        if (item == null || video == null || !video.exists()) {
            Toast.makeText(this, "Bölüm bulunamadı.", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        WindowInsetsControllerCompat bars = WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
        bars.hide(WindowInsetsCompat.Type.systemBars());
        bars.setSystemBarsBehavior(WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);

        PlayerView view = new PlayerView(this);
        view.setBackgroundColor(Color.BLACK);
        view.setKeepScreenOn(true);
        setContentView(view);

        player = new ExoPlayer.Builder(this).build();
        view.setPlayer(player);

        MediaItem.Builder media = new MediaItem.Builder().setUri(Uri.fromFile(video)).setMediaId(id);
        File sub = DownloadStore.subtitleFile(this, id);
        if (sub.exists() && sub.length() > 0) {
            media.setSubtitleConfigurations(Collections.singletonList(
                new MediaItem.SubtitleConfiguration.Builder(Uri.fromFile(sub))
                    .setMimeType(MimeTypes.TEXT_SSA)
                    .setLanguage("tr")
                    .setLabel("Türkçe")
                    .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                    .build()));
        }
        player.setMediaItem(media.build());
        if (item.position > 5000) player.seekTo(item.position);
        player.prepare();
        player.setPlayWhenReady(true);
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (player != null) {
            long pos = player.getCurrentPosition();
            long dur = player.getDuration();
            // Sona yakın bittiyse baştan başlasın.
            DownloadStore.savePosition(this, id, dur > 0 && dur - pos < 30_000 ? 0 : pos);
            player.pause();
        }
    }

    @Override
    protected void onDestroy() {
        if (player != null) {
            player.release();
            player = null;
        }
        super.onDestroy();
    }
}

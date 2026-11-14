package com.animke.app;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

import com.google.firebase.messaging.FirebaseMessagingService;
import com.google.firebase.messaging.RemoteMessage;

import java.util.Map;

/**
 * Yeni bölüm bildirimleri (Firebase). Uygulama arka plandayken "notification" iletilerini
 * Android kendisi gösterir; burada uygulama açıkken gelenler ve yalnızca veri taşıyanlar gösterilir.
 * Bildirime dokununca "url" (yalnızca animke.com) uygulamada açılır.
 */
public class MessagingService extends FirebaseMessagingService {

    public static final String CHANNEL_ID = "episodes";
    public static final String EXTRA_URL = "animke_url";

    @Override
    public void onNewToken(@NonNull String token) {
        // Site, uygulama her açıldığında güncel adresi kendisi sorup kaydeder.
        getSharedPreferences("animke", MODE_PRIVATE).edit().putString("push_token", token).apply();
    }

    @Override
    public void onMessageReceived(@NonNull RemoteMessage message) {
        Map<String, String> data = message.getData();
        String title = message.getNotification() != null ? message.getNotification().getTitle() : data.get("title");
        String body = message.getNotification() != null ? message.getNotification().getBody() : data.get("body");
        if (title == null && body == null) return;
        show(this, title, body, data.get("url"), data.get("tag"));
    }

    static void ensureChannel(Context ctx) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager nm = ctx.getSystemService(NotificationManager.class);
        if (nm == null || nm.getNotificationChannel(CHANNEL_ID) != null) return;
        NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "Yeni bölümler", NotificationManager.IMPORTANCE_DEFAULT);
        ch.setDescription("Listendeki animelerin yeni bölümleri");
        nm.createNotificationChannel(ch);
    }

    static void show(Context ctx, String title, String body, String url, String tag) {
        ensureChannel(ctx);
        Intent open = new Intent(ctx, MainActivity.class)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        if (url != null) open.putExtra(EXTRA_URL, url);
        int id = (tag != null ? tag : String.valueOf(System.currentTimeMillis())).hashCode();
        PendingIntent pi = PendingIntent.getActivity(ctx, id, open,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        NotificationCompat.Builder n = new NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_notify)
            .setColor(0xFF1E90E0)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(new NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setContentIntent(pi);
        try {
            NotificationManagerCompat.from(ctx).notify(id, n.build());
        } catch (SecurityException ignored) {
            // bildirim izni yok
        }
    }
}

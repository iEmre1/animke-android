package com.animke.app;

import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Bölüm videosu inince (uygulama kapalı olsa da) hemen şifrelenir ve düz hâli silinir.
 * Bir sebeple yarıda kalırsa İndirilenler ya da oynatıcı açılırken yeniden denenir.
 */
public class DownloadDoneReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (!DownloadManager.ACTION_DOWNLOAD_COMPLETE.equals(intent.getAction())) return;
        long id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1);
        DownloadStore.Item item = DownloadStore.findByDownload(context, id);
        if (item == null) return;
        final PendingResult pending = goAsync();
        // "Bitti" yalnızca bir kez bildirilmeli (iki kez çağrılırsa uygulama çöker).
        final java.util.concurrent.atomic.AtomicBoolean finished = new java.util.concurrent.atomic.AtomicBoolean(false);
        final Runnable finish = () -> {
            if (finished.compareAndSet(false, true)) pending.finish();
        };
        DownloadStore.encryptIfNeeded(context, item, finish);
        // Şifreleme başlamadıysa (ör. indirme başarısız) beklemeden bitir.
        if (!DownloadStore.preparing(context, item)) finish.run();
    }
}

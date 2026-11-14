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
        DownloadStore.encryptIfNeeded(context, item, pending::finish);
        // Şifreleme başlamadıysa (ör. indirme başarısız) beklemeden bitir.
        if (!DownloadStore.preparing(context, item)) {
            try {
                pending.finish();
            } catch (IllegalStateException ignored) {
                // zaten bitti
            }
        }
    }
}

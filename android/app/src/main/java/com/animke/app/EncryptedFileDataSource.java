package com.animke.app;

import android.content.Context;
import android.net.Uri;

import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.BaseDataSource;
import androidx.media3.datasource.DataSpec;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;

import javax.crypto.Cipher;
import javax.crypto.ShortBufferException;

/**
 * İndirilen (şifreli) dosyayı oynatıcıya okudukça çözerek verir. Video ve altyazı diske hiç
 * düz yazılmaz; çözülen veri yalnızca bellekte, oynatıcının o an okuduğu kadardır.
 */
@UnstableApi
public final class EncryptedFileDataSource extends BaseDataSource {

    private final Context context;
    @Nullable private RandomAccessFile file;
    @Nullable private Cipher cipher;
    @Nullable private Uri uri;
    private long bytesRemaining;
    private boolean opened;
    private byte[] scratch = new byte[64 * 1024];

    public EncryptedFileDataSource(Context context) {
        super(/* isNetwork= */ false);
        this.context = context.getApplicationContext();
    }

    @Override
    public long open(DataSpec dataSpec) throws IOException {
        uri = dataSpec.uri;
        transferInitializing(dataSpec);
        String path = uri.getPath();
        if (path == null) throw new IOException("Geçersiz dosya");
        File f = new File(path);
        file = new RandomAccessFile(f, "r");
        long length = f.length();
        if (dataSpec.position > length) throw new IOException("Konum dosyanın dışında");
        file.seek(dataSpec.position);
        cipher = DownloadCrypto.decryptorAt(context, f.getName(), dataSpec.position);
        bytesRemaining = dataSpec.length == C.LENGTH_UNSET
            ? length - dataSpec.position
            : Math.min(dataSpec.length, length - dataSpec.position);
        opened = true;
        transferStarted(dataSpec);
        return bytesRemaining;
    }

    @Override
    public int read(byte[] buffer, int offset, int length) throws IOException {
        if (length == 0) return 0;
        if (bytesRemaining == 0 || file == null || cipher == null) return C.RESULT_END_OF_INPUT;
        int toRead = (int) Math.min(length, bytesRemaining);
        if (scratch.length < toRead) scratch = new byte[toRead];
        int n = file.read(scratch, 0, toRead);
        if (n <= 0) return C.RESULT_END_OF_INPUT;
        try {
            cipher.update(scratch, 0, n, buffer, offset);
        } catch (ShortBufferException e) {
            throw new IOException(e);
        }
        bytesRemaining -= n;
        bytesTransferred(n);
        return n;
    }

    @Nullable
    @Override
    public Uri getUri() {
        return uri;
    }

    @Override
    public void close() throws IOException {
        uri = null;
        cipher = null;
        try {
            if (file != null) file.close();
        } finally {
            file = null;
            if (opened) {
                opened = false;
                transferEnded();
            }
        }
    }
}

package com.animke.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.security.KeyStore;
import java.security.SecureRandom;

import javax.crypto.Cipher;
import javax.crypto.CipherOutputStream;
import javax.crypto.KeyGenerator;
import javax.crypto.Mac;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * İndirilen bölümlerin telefonda şifreli saklanması.
 *
 * - Telefona özel 256 bit ana anahtar, Android Keystore'daki (dışarı çıkarılamayan) bir anahtarla
 *   kilitlenip saklanır; yalnızca bu uygulama, bu telefonda açabilir.
 * - Her dosyanın anahtarı ana anahtardan dosya adıyla türetilir (HMAC-SHA256): iki dosya aynı
 *   anahtarı paylaşmaz.
 * - Şifreleme AES-256-CTR: oynatıcı istediği yerden (ileri/geri sarma) okuyup çözebilir.
 */
final class DownloadCrypto {

    private static final String KEYSTORE = "AndroidKeyStore";
    private static final String WRAP_ALIAS = "animke_downloads_wrap";
    private static final String PREFS = "animke_crypto";
    private static final String KEY_WRAPPED = "master";

    private static byte[] master;

    private DownloadCrypto() {}

    /** Ana anahtar (ilk kullanımda üretilir). */
    private static synchronized byte[] master(Context c) throws IOException {
        if (master != null) return master;
        SharedPreferences prefs = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        try {
            SecretKey wrap = wrappingKey();
            String stored = prefs.getString(KEY_WRAPPED, null);
            if (stored != null) {
                String[] parts = stored.split(":");
                Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
                cipher.init(Cipher.DECRYPT_MODE, wrap, new GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)));
                master = cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP));
                return master;
            }
            byte[] fresh = new byte[32];
            new SecureRandom().nextBytes(fresh);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, wrap);
            String value = Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP) + ":"
                + Base64.encodeToString(cipher.doFinal(fresh), Base64.NO_WRAP);
            prefs.edit().putString(KEY_WRAPPED, value).commit();
            master = fresh;
            return master;
        } catch (Exception e) {
            throw new IOException("Şifreleme anahtarı açılamadı", e);
        }
    }

    private static SecretKey wrappingKey() throws Exception {
        KeyStore ks = KeyStore.getInstance(KEYSTORE);
        ks.load(null);
        if (ks.containsAlias(WRAP_ALIAS)) {
            return ((KeyStore.SecretKeyEntry) ks.getEntry(WRAP_ALIAS, null)).getSecretKey();
        }
        KeyGenerator gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE);
        gen.init(new KeyGenParameterSpec.Builder(WRAP_ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build());
        return gen.generateKey();
    }

    /** Dosyaya özel anahtar: HMAC-SHA256(ana anahtar, dosya adı). */
    private static SecretKeySpec fileKey(Context c, String fileName) throws IOException {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(master(c), "HmacSHA256"));
            return new SecretKeySpec(mac.doFinal(fileName.getBytes("UTF-8")), "AES");
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException(e);
        }
    }

    /** CTR sayacı: konumun bulunduğu 16 baytlık blok numarası (büyük uçlu, 16 bayt). */
    private static IvParameterSpec counterAt(long block) {
        byte[] iv = new byte[16];
        byte[] n = BigInteger.valueOf(block).toByteArray();
        int len = Math.min(n.length, 16);
        System.arraycopy(n, n.length - len, iv, 16 - len, len);
        return new IvParameterSpec(iv);
    }

    /** Dosyanın `position` baytından itibaren çözen şifre (oynatıcı ileri/geri sarabilir). */
    static Cipher decryptorAt(Context c, String fileName, long position) throws IOException {
        try {
            Cipher cipher = Cipher.getInstance("AES/CTR/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, fileKey(c, fileName), counterAt(position / 16));
            int skip = (int) (position % 16);
            if (skip > 0) cipher.update(new byte[skip]);
            return cipher;
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException(e);
        }
    }

    /** `target` dosyası için şifreleyerek yazan akış (adı şifre anahtarını belirler). */
    static OutputStream encryptingStream(Context c, File target, File writeTo) throws IOException {
        try {
            Cipher cipher = Cipher.getInstance("AES/CTR/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, fileKey(c, target.getName()), counterAt(0));
            return new CipherOutputStream(new FileOutputStream(writeTo), cipher);
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException(e);
        }
    }

    /** Düz dosyayı şifreleyip `target`'a yazar (önce .part, bitince yeniden adlandırılır). */
    static void encryptFile(Context c, File plain, File target) throws IOException {
        File part = new File(target.getPath() + ".part");
        try (InputStream in = new FileInputStream(plain); OutputStream out = encryptingStream(c, target, part)) {
            byte[] buf = new byte[256 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        } catch (IOException e) {
            //noinspection ResultOfMethodCallIgnored
            part.delete();
            throw e;
        }
        if (!part.renameTo(target)) {
            //noinspection ResultOfMethodCallIgnored
            part.delete();
            throw new IOException("Şifreli dosya yazılamadı");
        }
    }

    /** Sunucunun gönderdiği şifreli altyazıyı açar: {"k","iv","d"} (AES-256-GCM, base64). */
    static byte[] openServerPayload(byte[] json) throws Exception {
        org.json.JSONObject o = new org.json.JSONObject(new String(json, "UTF-8"));
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE,
            new SecretKeySpec(Base64.decode(o.getString("k"), Base64.NO_WRAP), "AES"),
            new GCMParameterSpec(128, Base64.decode(o.getString("iv"), Base64.NO_WRAP)));
        return cipher.doFinal(Base64.decode(o.getString("d"), Base64.NO_WRAP));
    }
}

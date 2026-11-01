package com.suvo.agent.data;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.SecureRandom;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * AES-GCM encryption for API keys, custom-tool secrets, connector tokens and SSH credentials.
 *
 * Normally the key is a non-exportable key in the Android Keystore. Some phones have a broken
 * Keystore (custom ROMs, some older or emulated devices, a corrupted key after a restore): key
 * generation throws {@code ProviderException} and nothing could be saved ("Secure storage is
 * unavailable"). Then, in this order:
 * <ol>
 *   <li>the Keystore key is deleted and created again (fixes a corrupted key);</li>
 *   <li>a 128-bit Keystore key is tried (some Keystores reject 256-bit AES);</li>
 *   <li>last resort: a random AES key kept in the app's private no-backup folder. Secrets stay
 *       encrypted and readable only by this app, but without the Keystore's hardware protection.</li>
 * </ol>
 * Ciphertext: base64(iv[12] || ciphertext+tag) for the Keystore key, "s1:" + the same for the
 * fallback key, so both kinds can be read later. Never falls back to plaintext.
 */
public final class SecretStore {

    private static final String TAG = "YF.Secrets";
    private static final String KEYSTORE = "AndroidKeyStore";
    private static final String ALIAS = "yourfriend_secrets_v1";
    private static final String ALIAS_128 = "yourfriend_secrets_v1_128";
    private static final String SOFT_PREFIX = "s1:";
    private static final String SOFT_FILE = "yf_secret.key";
    private static final int IV_LEN = 12;

    private static volatile SecretStore instance;
    private static volatile Context appCtx;

    /** Gives the store a place for the fallback key; call once at app start. */
    public static void attach(Context ctx) {
        appCtx = ctx.getApplicationContext();
    }

    public static SecretStore get() {
        if (instance == null) {
            synchronized (SecretStore.class) {
                if (instance == null) instance = new SecretStore();
            }
        }
        return instance;
    }

    private SecretKey keystoreKey;
    private boolean keystoreBroken;
    private SecretKey softKey;

    private SecretStore() {
    }

    /** True when secrets are protected by the Android Keystore (false = app-private fallback key). */
    public synchronized boolean hardwareBacked() {
        return keystoreKey() != null;
    }

    // ------------------------------------------------------------------ keys

    /** The Keystore key, or null when this phone's Keystore does not work. */
    private synchronized SecretKey keystoreKey() {
        if (keystoreKey != null) return keystoreKey;
        if (keystoreBroken) return null;
        try {
            keystoreKey = loadOrCreate(ALIAS, 256);
            return keystoreKey;
        } catch (Exception first) {
            Log.w(TAG, "Keystore key failed (" + first + "); recreating");
        }
        try {
            deleteAlias(ALIAS);
            keystoreKey = loadOrCreate(ALIAS, 256);
            return keystoreKey;
        } catch (Exception second) {
            Log.w(TAG, "Keystore 256-bit key failed again (" + second + "); trying 128-bit");
        }
        try {
            keystoreKey = loadOrCreate(ALIAS_128, 128);
            return keystoreKey;
        } catch (Exception third) {
            Log.w(TAG, "Keystore unusable on this phone (" + third + "); using the app-private key");
            keystoreBroken = true;
            return null;
        }
    }

    private static SecretKey loadOrCreate(String alias, int bits) throws Exception {
        KeyStore ks = KeyStore.getInstance(KEYSTORE);
        ks.load(null);
        KeyStore.Entry e = ks.getEntry(alias, null);
        if (e instanceof KeyStore.SecretKeyEntry) return ((KeyStore.SecretKeyEntry) e).getSecretKey();
        KeyGenerator kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE);
        kg.init(new KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(bits)
                .build());
        SecretKey k = kg.generateKey();
        // Prove the key really works before trusting it (broken Keystores sometimes fail only here).
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, k);
        c.doFinal(new byte[]{1});
        return k;
    }

    private static void deleteAlias(String alias) {
        try {
            KeyStore ks = KeyStore.getInstance(KEYSTORE);
            ks.load(null);
            if (ks.containsAlias(alias)) ks.deleteEntry(alias);
        } catch (Exception ignored) {
        }
    }

    /** The app-private fallback key (created on first use). */
    private synchronized SecretKey softKey() throws Exception {
        if (softKey != null) return softKey;
        Context ctx = appCtx;
        if (ctx == null) throw new IllegalStateException("SecretStore not attached");
        File f = new File(ctx.getNoBackupFilesDir(), SOFT_FILE);
        byte[] raw = new byte[32];
        if (f.length() == 32) {
            try (FileInputStream in = new FileInputStream(f)) {
                int n = 0;
                while (n < 32) {
                    int r = in.read(raw, n, 32 - n);
                    if (r < 0) break;
                    n += r;
                }
                if (n != 32) throw new IllegalStateException("Damaged key file");
            }
        } else {
            new SecureRandom().nextBytes(raw);
            File tmp = new File(f.getPath() + ".tmp");
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                out.write(raw);
                out.getFD().sync();
            }
            if (!tmp.renameTo(f)) throw new IllegalStateException("Could not save the key file");
        }
        softKey = new SecretKeySpec(raw, "AES");
        return softKey;
    }

    // ------------------------------------------------------------------ encrypt / decrypt

    /** Returns "" for empty input. Throws only if no key at all can be used (never falls back to plaintext). */
    public String encrypt(String plain) {
        if (plain == null || plain.isEmpty()) return "";
        SecretKey k = keystoreKey();
        try {
            if (k != null) return seal(k, plain);
        } catch (Exception e) {
            Log.w(TAG, "Keystore encrypt failed (" + e + "); using the app-private key");
            synchronized (this) {
                keystoreKey = null;
                keystoreBroken = true;
            }
        }
        try {
            return SOFT_PREFIX + seal(softKey(), plain);
        } catch (Exception e) {
            throw new IllegalStateException("Secure storage is unavailable: " + e.getClass().getSimpleName(), e);
        }
    }

    /** Returns "" when empty or when the key was invalidated (e.g. after a device restore). */
    public String decrypt(String encoded) {
        if (encoded == null || encoded.isEmpty()) return "";
        try {
            if (encoded.startsWith(SOFT_PREFIX)) return open(softKey(), encoded.substring(SOFT_PREFIX.length()));
            SecretKey k = keystoreKey();
            if (k != null) {
                try {
                    return open(k, encoded);
                } catch (Exception ignored) {
                    // Maybe written with the other Keystore key (256 vs 128-bit): try both below.
                }
            }
            for (String alias : new String[]{ALIAS, ALIAS_128}) {
                SecretKey other = loadExisting(alias);
                if (other == null || other.equals(k)) continue;
                try {
                    return open(other, encoded);
                } catch (Exception ignored) {
                }
            }
            throw new IllegalStateException("No key opens this secret");
        } catch (Exception e) {
            Log.w(TAG, "Could not decrypt a stored secret (" + e.getClass().getSimpleName() + "); re-enter it");
            return "";
        }
    }

    private static SecretKey loadExisting(String alias) {
        try {
            KeyStore ks = KeyStore.getInstance(KEYSTORE);
            ks.load(null);
            KeyStore.Entry e = ks.getEntry(alias, null);
            return e instanceof KeyStore.SecretKeyEntry ? ((KeyStore.SecretKeyEntry) e).getSecretKey() : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static String seal(SecretKey k, String plain) throws Exception {
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        if (k instanceof SecretKeySpec) {
            byte[] iv = new byte[IV_LEN];
            new SecureRandom().nextBytes(iv);
            c.init(Cipher.ENCRYPT_MODE, k, new GCMParameterSpec(128, iv));
        } else {
            c.init(Cipher.ENCRYPT_MODE, k); // the Keystore picks the IV
        }
        byte[] iv = c.getIV();
        byte[] ct = c.doFinal(plain.getBytes(StandardCharsets.UTF_8));
        ByteBuffer bb = ByteBuffer.allocate(iv.length + ct.length);
        bb.put(iv).put(ct);
        return Base64.encodeToString(bb.array(), Base64.NO_WRAP);
    }

    private static String open(SecretKey k, String b64) throws Exception {
        byte[] all = Base64.decode(b64, Base64.NO_WRAP);
        if (all.length <= IV_LEN) return "";
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.DECRYPT_MODE, k, new GCMParameterSpec(128, all, 0, IV_LEN));
        byte[] pt = c.doFinal(all, IV_LEN, all.length - IV_LEN);
        return new String(pt, StandardCharsets.UTF_8);
    }
}

package com.suvo.agent.data;

<<<<<<< HEAD
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import android.util.Log;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * AES-256-GCM encryption with a non-exportable key held in the Android Keystore. Used for API keys,
 * custom-tool secrets and SSH credentials. Ciphertext format: base64(iv[12] || ciphertext+tag).
 */
public final class SecretStore {

    private static final String TAG = "YF.Secrets";
    private static final String KEYSTORE = "AndroidKeyStore";
    private static final String ALIAS = "yourfriend_secrets_v1";
    private static final int IV_LEN = 12;

    private static volatile SecretStore instance;

    public static SecretStore get() {
        if (instance == null) {
            synchronized (SecretStore.class) {
                if (instance == null) instance = new SecretStore();
            }
        }
        return instance;
    }

    private SecretStore() {
    }

    private synchronized SecretKey key() throws Exception {
        KeyStore ks = KeyStore.getInstance(KEYSTORE);
        ks.load(null);
        KeyStore.Entry e = ks.getEntry(ALIAS, null);
        if (e instanceof KeyStore.SecretKeyEntry) return ((KeyStore.SecretKeyEntry) e).getSecretKey();
        KeyGenerator kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE);
        kg.init(new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build());
        return kg.generateKey();
    }

    /** Returns "" for empty input. Throws if the Keystore is unusable (never falls back to plaintext). */
    public String encrypt(String plain) {
        if (plain == null || plain.isEmpty()) return "";
        try {
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, key());
            byte[] iv = c.getIV();
            byte[] ct = c.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            ByteBuffer bb = ByteBuffer.allocate(iv.length + ct.length);
            bb.put(iv).put(ct);
            return Base64.encodeToString(bb.array(), Base64.NO_WRAP);
        } catch (Exception e) {
            throw new IllegalStateException("Secure storage is unavailable: " + e.getClass().getSimpleName(), e);
        }
    }

    /** Returns "" when empty or when the key was invalidated (e.g. after a device restore). */
    public String decrypt(String encoded) {
        if (encoded == null || encoded.isEmpty()) return "";
        try {
            byte[] all = Base64.decode(encoded, Base64.NO_WRAP);
            if (all.length <= IV_LEN) return "";
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, all, 0, IV_LEN));
            byte[] pt = c.doFinal(all, IV_LEN, all.length - IV_LEN);
            return new String(pt, StandardCharsets.UTF_8);
        } catch (Exception e) {
            Log.w(TAG, "Could not decrypt a stored secret (" + e.getClass().getSimpleName() + "); re-enter it");
            return "";
        }
    }
=======
import android.content.Context;
import android.content.SharedPreferences;

import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKey;

/**
 * Encrypted key/value store for API keys and skill secrets.
 * These values are NEVER put into prompts sent to the model.
 */
public class SecretStore {

    private SharedPreferences prefs;

    public SecretStore(Context ctx) {
        try {
            MasterKey key = new MasterKey.Builder(ctx)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build();
            prefs = EncryptedSharedPreferences.create(
                    ctx, "aura_secrets", key,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM);
        } catch (Exception e) {
            // fallback (should not normally happen)
            prefs = ctx.getSharedPreferences("aura_secrets_fallback", Context.MODE_PRIVATE);
        }
    }

    public String get(String key, String def) { return prefs.getString(key, def); }
    public void put(String key, String value) { prefs.edit().putString(key, value).apply(); }
    public void remove(String key) { prefs.edit().remove(key).apply(); }
>>>>>>> 2fdbf158535950372900ccd3a8ad3b5b0f730b15
}

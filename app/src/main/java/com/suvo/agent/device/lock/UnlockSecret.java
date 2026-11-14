package com.suvo.agent.device.lock;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import com.suvo.agent.data.Settings;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Your Friend — Original project by Suvo.
 *
 * The saved screen-lock secret for "unlock the phone" (off by default): a PIN / password, or a
 * pattern as the order of its dots ("1-5-9"). It is encrypted with a key that lives in the Android
 * Keystore (the key never leaves it), so the app's files alone don't reveal it. The agent never
 * sees it: only {@link ScreenUnlocker} reads it, to type or draw it on the lock screen.
 */
public final class UnlockSecret {

    /** "none" (not saved: only bring up the unlock screen), "pin" (PIN or password), "pattern". */
    public static final String METHOD = "unlock_method";
    private static final String DATA = "unlock_secret";
    /** Optional guard: unlock only while charging. */
    public static final String ONLY_CHARGING = "unlock_only_charging";
    /** Wrong tries in a row (2 → saved unlock stops itself, so the phone is never locked out). */
    public static final String FAILS = "unlock_fails";
    private static final String ALIAS = "yf_unlock_secret";

    private UnlockSecret() {
    }

    public static String method(Context ctx) {
        String m = Settings.get(ctx).str(METHOD, "none");
        return has(ctx) ? m : "none";
    }

    public static boolean has(Context ctx) {
        return !Settings.get(ctx).str(DATA, "").isEmpty();
    }

    public static void save(Context ctx, String method, String secret) throws Exception {
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, key());
        byte[] ct = c.doFinal(secret.getBytes(StandardCharsets.UTF_8));
        String blob = Base64.encodeToString(c.getIV(), Base64.NO_WRAP) + ":" + Base64.encodeToString(ct, Base64.NO_WRAP);
        Settings st = Settings.get(ctx);
        st.put(DATA, blob);
        st.put(METHOD, method);
        st.put(FAILS, 0);
    }

    public static void clear(Context ctx) {
        Settings st = Settings.get(ctx);
        st.put(DATA, "");
        st.put(METHOD, "none");
        st.put(FAILS, 0);
    }

    /** The secret, or null when none is saved (or it cannot be read any more). */
    static String read(Context ctx) {
        String blob = Settings.get(ctx).str(DATA, "");
        int colon = blob.indexOf(':');
        if (colon <= 0) return null;
        try {
            byte[] iv = Base64.decode(blob.substring(0, colon), Base64.NO_WRAP);
            byte[] ct = Base64.decode(blob.substring(colon + 1), Base64.NO_WRAP);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, iv));
            return new String(c.doFinal(ct), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }

    private static SecretKey key() throws Exception {
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);
        if (ks.containsAlias(ALIAS)) return ((KeyStore.SecretKeyEntry) ks.getEntry(ALIAS, null)).getSecretKey();
        KeyGenerator kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        kg.init(new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build());
        return kg.generateKey();
    }
}

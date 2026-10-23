package com.suvo.agent.data;

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
}

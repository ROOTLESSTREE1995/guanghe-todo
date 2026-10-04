package cn.banxu.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import org.json.JSONArray;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

public final class SecureSettings {
    private static final String ALIAS = "banxu_api_key_v1";
    private final SharedPreferences prefs;
    public SecureSettings(Context c) { prefs = c.getSharedPreferences("settings", Context.MODE_PRIVATE); }
    public synchronized JSONObject publicJson() throws Exception {
        JSONObject out = new JSONObject(prefs.getString("settings", "{}"));
        if (!out.has("teacherName")) out.put("teacherName", "老师");
        if (!out.has("className")) out.put("className", "我的班级");
        if (!out.has("model")) out.put("model", "deepseek-flash");
        if (!out.has("cloudEnabled")) out.put("cloudEnabled", false);
        if (!out.has("captureEnabled")) out.put("captureEnabled", false);
        if (!out.has("allowedPackages")) out.put("allowedPackages", new JSONArray().put("com.tencent.mm"));
        if (!out.has("conversationFilters")) out.put("conversationFilters", "");
        if (!out.has("leadMinutes")) out.put("leadMinutes", 15);
        out.put("hasApiKey", prefs.contains("key_cipher"));
        return out;
    }
    public synchronized void save(JSONObject patch) throws Exception {
        JSONObject out = publicJson(); out.remove("hasApiKey");
        for (String name : new String[]{"teacherName", "className", "model", "conversationFilters"}) {
            if (patch.has(name)) {
                if (!(patch.get(name) instanceof String)) throw new IllegalArgumentException("设置格式不正确");
                String value = patch.getString(name).trim();
                if (value.length() > (name.equals("conversationFilters") ? 2000 : 120)) throw new IllegalArgumentException("设置内容过长");
                if (name.equals("model") && !value.matches("[A-Za-z0-9._:/-]{1,120}")) throw new IllegalArgumentException("模型名称格式不正确");
                out.put(name, value);
            }
        }
        for (String name : new String[]{"cloudEnabled", "captureEnabled"}) if (patch.has(name)) {
            if (!(patch.get(name) instanceof Boolean)) throw new IllegalArgumentException("开关设置无效");
            out.put(name, patch.getBoolean(name));
        }
        if (patch.has("leadMinutes")) {
            long lead = DomainRules.validatedTimestamp(patch.get("leadMinutes"));
            if (lead > 10080) throw new IllegalArgumentException("提前提醒最多7天");
            out.put("leadMinutes", lead);
        }
        if (patch.has("allowedPackages")) {
            JSONArray selected = patch.getJSONArray("allowedPackages");
            if (selected.length() > 30) throw new IllegalArgumentException("来源应用过多");
            JSONArray clean = new JSONArray();
            for (int i = 0; i < selected.length(); i++) {
                String name = selected.getString(i);
                if (!name.matches("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+") || name.equals("cn.banxu.app")) throw new IllegalArgumentException("来源应用包名无效");
                clean.put(name);
            }
            out.put("allowedPackages", clean);
        }
        SharedPreferences.Editor edit = prefs.edit();
        if (patch.has("apiKey")) {
            if (!(patch.get("apiKey") instanceof String)) throw new IllegalArgumentException("API Key 格式无效");
            String key = patch.getString("apiKey").trim();
            if (key.length() > 1024 || key.contains("\n") || key.contains("\r")) throw new IllegalArgumentException("API Key 格式无效");
            if (key.isEmpty()) { edit.remove("key_cipher"); edit.remove("key_iv"); }
            else {
                Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
                cipher.init(Cipher.ENCRYPT_MODE, encryptionKey());
                edit.putString("key_cipher", Base64.encodeToString(cipher.doFinal(key.getBytes(StandardCharsets.UTF_8)), Base64.NO_WRAP));
                edit.putString("key_iv", Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP));
            }
        }
        if (!edit.putString("settings", out.toString()).commit()) throw new IllegalStateException("设置保存失败，请重试");
    }
    public synchronized String apiKey() throws Exception {
        String encrypted = prefs.getString("key_cipher", null);
        if (encrypted == null) return "";
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, encryptionKey(), new GCMParameterSpec(128, Base64.decode(prefs.getString("key_iv", ""), Base64.NO_WRAP)));
            return new String(cipher.doFinal(Base64.decode(encrypted, Base64.NO_WRAP)), StandardCharsets.UTF_8);
        } catch (Exception error) { throw new IllegalStateException("API Key 无法解密，请重新填写"); }
    }
    private static synchronized SecretKey encryptionKey() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore"); store.load(null);
        if (!store.containsAlias(ALIAS)) {
            KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
            generator.init(new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setRandomizedEncryptionRequired(true).build());
            generator.generateKey();
        }
        return (SecretKey) store.getKey(ALIAS, null);
    }
}

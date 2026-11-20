package com.osmo.viewer;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * 密码的本地对称加密。
 *
 * 密钥放在 Android Keystore（硬件保护的，取不出来），密文是 AES/GCM 的
 * base64（iv + 密文 + tag），存 SharedPreferences。这样即使备份/root 拿到
 * prefs 文件，没有这台设备的 Keystore 也解不开。
 *
 * 老版本存的明文密码会被自动识别并升级（decrypt 发现没有前缀原样返回，
 * 调用方存回去时就会变成密文）。
 */
public final class SecretStore {

    private static final String PREFIX = "enc:v1:";
    private static final String KEY_ALIAS = "osv_remote_pw";
    private static final String ANDROID_KEYSTORE = "AndroidKeyStore";

    private SecretStore() {}

    private static SecretKey key() {
        try {
            KeyStore ks = KeyStore.getInstance(ANDROID_KEYSTORE);
            ks.load(null);
            if (ks.containsAlias(KEY_ALIAS)) {
                return (SecretKey) ks.getKey(KEY_ALIAS, null);
            }
            KeyGenerator generator = KeyGenerator.getInstance(
                    KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE);
            generator.init(new KeyGenParameterSpec.Builder(KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build());
            return generator.generateKey();
        } catch (Throwable t) {
            Diag.w("SecretStore", "无法获取 Keystore 密钥", t);
            return null;
        }
    }

    /** 加密；Keystore 不可用时返回原文（保证功能不回退成不可用）。 */
    public static String encrypt(String plain) {
        if (plain == null || plain.isEmpty()) return "";
        if (plain.startsWith(PREFIX)) return plain;   // 已加密
        SecretKey k = key();
        if (k == null) return plain;
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, k);
            byte[] iv = cipher.getIV();
            byte[] body = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            byte[] packed = new byte[1 + iv.length + body.length];
            packed[0] = (byte) iv.length;
            System.arraycopy(iv, 0, packed, 1, iv.length);
            System.arraycopy(body, 0, packed, 1 + iv.length, body.length);
            return PREFIX + Base64.encodeToString(packed, Base64.NO_WRAP);
        } catch (Throwable t) {
            Diag.w("SecretStore", "加密失败", t);
            return plain;
        }
    }

    /** 解密；不是密文（老数据或明文）就原样返回。 */
    public static String decrypt(String stored) {
        if (stored == null || stored.isEmpty()) return "";
        if (!stored.startsWith(PREFIX)) return stored;
        SecretKey k = key();
        if (k == null) return "";
        try {
            byte[] packed = Base64.decode(stored.substring(PREFIX.length()), Base64.NO_WRAP);
            int ivLen = packed[0] & 0xff;
            byte[] iv = new byte[ivLen];
            System.arraycopy(packed, 1, iv, 0, ivLen);
            byte[] body = new byte[packed.length - 1 - ivLen];
            System.arraycopy(packed, 1 + ivLen, body, 0, body.length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, k, new GCMParameterSpec(128, iv));
            return new String(cipher.doFinal(body), StandardCharsets.UTF_8);
        } catch (Throwable t) {
            Diag.w("SecretStore", "解密失败（可能是换机或清除过密钥）", t);
            return "";
        }
    }

    public static boolean isEncrypted(String stored) {
        return stored != null && stored.startsWith(PREFIX);
    }
}

package com.tendoarisu.mmdskin.sync.util;

public class MMDSyncNativeBridge {
    static {
        MMDSyncNativeLoader.load();
    }
    public static native String getHardwareId();
    public static native String deriveHandshakePem(String serverSecret, byte[] targetHash, String clientHwid);
    public static native byte[] unwrapHandshakeKey(String encryptedAesKeyBase64, String serverSecret);
    public static native String getLibraryHash();
    public static native String getClassHash(byte[] classBytes);
    public static native String rsaEncrypt(byte[] aesKey, String publicKeyPem);
    public static native byte[] aesEncrypt(byte[] data, byte[] aesKey);
    public static native byte[] aesDecrypt(byte[] encryptedData, byte[] aesKey);
}

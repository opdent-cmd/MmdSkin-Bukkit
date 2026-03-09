package com.tendoarisu.mmdskin.sync.util;

/**
 * 辅助 Native 库的桥接类 (Bukkit 版本)
 */
public class MMDSyncNativeBridge {
    static {
        // 使用多平台加载器加载库
        MMDSyncNativeLoader.load();
    }

    /**
     * 获取当前设备的硬件指纹 (HWID)
     * 用于进阶加密，将加密文件绑定到特定设备
     * @return 硬件指纹字符串
     */
    public static native String getHardwareId();

    /**
     * 派生握手阶段使用的 PEM 文本材料
     * @param serverSecret 服务器私密盐 (config.yml 中的密钥)
     * @param targetHash 官方 Native 库的哈希 (用于跨平台同步)
     * @param clientHwid 客户端上报的硬件 ID
     * @return PEM 格式的握手材料
     */
    public static native String deriveHandshakePem(String serverSecret, byte[] targetHash, String clientHwid);

    /**
     * 使用握手上下文派生的本地材料解封装会话密钥
     * @param encryptedAesKeyBase64 Base64 格式的封装会话密钥
     * @param serverSecret 服务器私密盐 (config.yml 中的密钥)
     * @return 原始会话密钥 (32字节)
     */
    public static native byte[] unwrapHandshakeKey(String encryptedAesKeyBase64, String serverSecret);

    /**
     * 获取 Native 核心库自身的 SHA256 哈希值
     * 用于服务器校验客户端 Native 库是否被篡改
     * @return 16进制哈希字符串
     */
    public static native String getLibraryHash();

    /**
     * 计算 Java 类文件的 SHA256 哈希值
     * @param classBytes 类文件的字节流
     * @return 16进制哈希字符串
     */
    public static native String getClassHash(byte[] classBytes);

    /**
     * 使用 RSA 公钥加密 AES 密钥
     * @param aesKey 原始 AES 密钥 (32字节)
     * @param publicKeyPem RSA 公钥 (PEM格式)
     * @return 加密后的 AES 密钥 (Base64)
     */
    public static native String rsaEncrypt(byte[] aesKey, String publicKeyPem);

    /**
     * 使用 AES-GCM 加密模型数据
     * @param data 原始模型数据
     * @param aesKey AES 密钥
     * @return 加密后的模型数据 (包含 Nonce)
     */
    public static native byte[] aesEncrypt(byte[] data, byte[] aesKey);

    /**
     * 使用 AES-GCM 解密模型数据
     * @param encryptedData 加密后的模型数据
     * @param aesKey 已经解密出的原始 AES 密钥
     * @return 原始模型数据
     */
    public static native byte[] aesDecrypt(byte[] encryptedData, byte[] aesKey);
}

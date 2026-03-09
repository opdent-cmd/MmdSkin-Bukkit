package com.tendoarisu.mmdskin.sync.util;

import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.logging.Logger;

/**
 * MMDSync 辅助原生库加载器 (Bukkit 版本)
 */
public final class MMDSyncNativeLoader {
    private static final Logger logger = Logger.getLogger("MmdSkin-Native");
    private static volatile boolean loaded;

    private static final boolean isWindows = System.getProperty("os.name").toLowerCase().contains("windows");
    private static final boolean isMacOS = System.getProperty("os.name").toLowerCase().contains("mac");
    private static final boolean isLinux = System.getProperty("os.name").toLowerCase().contains("linux");
    private static final boolean isArm64;

    static {
        String arch = System.getProperty("os.arch").toLowerCase();
        isArm64 = arch.contains("aarch64") || arch.contains("arm64");
    }

    public static synchronized void load() {
        if (loaded) {
            return;
        }
        loadDesktop();
    }

    private static void loadDesktop() {
        String resourcePath;
        String fileName;

        if (isWindows) {
            String archDir = isArm64 ? "windows-arm64" : "windows-x64";
            resourcePath = "/natives/" + archDir + "/mmdsync_bridge.dll";
            fileName = "mmdsync_bridge.dll";
        } else if (isMacOS) {
            String archDir = isArm64 ? "macos-arm64" : "macos-x64";
            resourcePath = "/natives/" + archDir + "/libmmdsync_bridge.dylib";
            fileName = "libmmdsync_bridge.dylib";
        } else if (isLinux) {
            String archDir = isArm64 ? "linux-arm64" : "linux-x64";
            resourcePath = "/natives/" + archDir + "/libmmdsync_bridge.so";
            fileName = "libmmdsync_bridge.so";
        } else {
            return;
        }

        try {
            File extracted = extractLibrary(resourcePath, fileName);
            if (extracted != null) {
                System.load(extracted.getAbsolutePath());
                loaded = true;
            }
        } catch (Throwable e) {
            logger.severe("MMDSync 辅助库加载失败: " + e.getMessage());
        }
    }

    private static File extractLibrary(String resourcePath, String fileName) {
        try {
            InputStream is = MMDSyncNativeLoader.class.getResourceAsStream(resourcePath);
            if (is == null) {
                logger.severe("找不到原生库资源: " + resourcePath);
                return null;
            }

            File tempDir = new File(System.getProperty("java.io.tmpdir"), "mmdskin_natives_" + System.currentTimeMillis());
            if (!tempDir.exists()) tempDir.mkdirs();
            
            File extractedFile = new File(tempDir, fileName);
            Files.copy(is, extractedFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
            is.close();
            
            extractedFile.deleteOnExit();
            return extractedFile;
        } catch (Exception e) {
            logger.severe("提取原生库失败: " + e.getMessage());
            return null;
        }
    }
}

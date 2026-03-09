package com.opdent.mmdskin.bukkit;

import com.tendoarisu.mmdskin.sync.util.MMDSyncNativeBridge;
import com.tendoarisu.mmdskin.sync.util.MMDSyncNativeLoader;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.plugin.messaging.PluginMessageListener;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import javax.crypto.Mac;
import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.spec.X509EncodedKeySpec;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Level;
import java.util.stream.Stream;
import java.util.zip.GZIPOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Bukkit/Spigot/Paper 端的自定义 Payload 转发器。
 */
public class MmdSkinBukkit extends JavaPlugin implements PluginMessageListener, Listener, CommandExecutor {

    private static final String CHANNEL_SYNC_URL = "mmdsync:sync_url";
    private HttpServer httpServer;
    private ExecutorService httpExecutor;
    private String syncUrl;
    private File modelDir;
    private int syncPort;
    private double maxBandwidthMbps;
    private boolean enableGzip;
    private final Map<Path, CacheEntry> md5Cache = new ConcurrentHashMap<>();
    
    /**
     * 服务器全局同步 AES 密钥 (用于加密下发给客户端的 VMD/PMX/纹理)
     * 持久化在 config.yml 中，确保重启后客户端已下载的文件依然可用。
     */
    private byte[] serverSyncKey;
    private final SecureRandom secureRandom = new SecureRandom();
    private final Map<UUID, PendingHandshake> pendingHandshakes = new ConcurrentHashMap<>();
    private final Map<String, DownloadSession> downloadSessions = new ConcurrentHashMap<>();
    private static final long HANDSHAKE_TTL_MS = 60_000L;
    private static final long DOWNLOAD_TOKEN_TTL_MS = 90_000L;
    private static final long REQUEST_TS_WINDOW_SEC = 5L;
    private static final long NONCE_RETENTION_MS = 15_000L;
    private static final int NONCE_HEX_LENGTH = 32;
    private static final String NEXT_TOKEN_ATTR = "mmdsync.nextToken";

    private static class PendingHandshake {
        final String challenge;
        final long expireAt;

        PendingHandshake(String challenge, long expireAt) {
            this.challenge = challenge;
            this.expireAt = expireAt;
        }
    }

    private static class DownloadSession {
        final UUID playerUuid;
        final String boundIp;
        final long expireAt;
        final Map<String, Long> usedNonces = new ConcurrentHashMap<>();
        final java.util.concurrent.atomic.AtomicBoolean downloadConsumed = new java.util.concurrent.atomic.AtomicBoolean(false);

        DownloadSession(UUID playerUuid, String boundIp, long expireAt) {
            this.playerUuid = playerUuid;
            this.boundIp = boundIp;
            this.expireAt = expireAt;
        }

        boolean tryUseNonce(String nonce) {
            long now = System.currentTimeMillis();
            usedNonces.entrySet().removeIf(entry -> now - entry.getValue() > NONCE_RETENTION_MS);
            return usedNonces.putIfAbsent(nonce, now) == null;
        }

        boolean tryConsumeDownload() {
            return downloadConsumed.compareAndSet(false, true);
        }

        DownloadSession renew() {
            return new DownloadSession(playerUuid, boundIp, expireAt);
        }
    }

    private static class CacheEntry {
        final String md5;
        final long lastModified;

        CacheEntry(String md5, long lastModified) {
            this.md5 = md5;
            this.lastModified = lastModified;
        }
    }

    // 对齐仓库内已提供的客户端版本（1.20.1/1.21.1）：Forge/NeoForge/Fabric

    // 1.20.1: 使用 3d-skin 命名空间
    // Forge/NeoForge: ResourceLocation("3d-skin", "network_pack")
    private static final String CHANNEL_3DSKIN_PACK = "3d-skin:network_pack";

    // Fabric: SKIN_C2S/S2C
    private static final String CHANNEL_3DSKIN_C2S = "3d-skin:network_c2s";
    private static final String CHANNEL_3DSKIN_S2C = "3d-skin:network_s2c";

    // 1.21.1: MOD_ID 为 mmdskin
    // Fabric: CustomPacketPayload TYPE = "mmdskin:network"
    private static final String CHANNEL_MMDSKIN_NETWORK = "mmdskin:network";

    // MMDSync 握手包 (1.21.1+)
    private static final String CHANNEL_MMDSYNC_HANDSHAKE = "mmdsync:handshake";

    // NeoForge: Payload TYPE = "mmdskin:network_pack"
    private static final String CHANNEL_MMDSKIN_PACK = "mmdskin:network_pack";

    /** 监听的所有通道 */
    private final Set<String> incomingChannels = new LinkedHashSet<>();

    /** 服务器主动下发（S2C）优先使用的通道（按顺序尝试） */
    private final Set<String> preferredOutgoingChannels = new LinkedHashSet<>();

    /**
     * 运行时缓存：玩家 UUID -> 当前模型名
     *
     * 用于：
     * - 玩家加入时补发（避免“别人看不到衣服/模型变化”）
     * - 处理客户端 opCode 10 的“请求模型信息”
     */
    private final Map<UUID, String> playerModels = new ConcurrentHashMap<>();

    /**
     * 服务器私密盐 (从 config.yml 加载)
     * 用于基于 HWID 动态派生 RSA 密钥对，确保跨服安全。
     */
    private String serverSecret;

    @Override
    public void onEnable() {
        // 加载 Native 辅助库
        try {
            MMDSyncNativeLoader.load();
        } catch (Throwable t) {
            getLogger().warning("Failed to load native library: " + t.getMessage());
            getLogger().warning("Server-side real-time encryption will be disabled.");
        }

        // config.yml
        saveDefaultConfig();
        reloadConfig();
        
        // 加载服务器私密盐
        serverSecret = getConfig().getString("security.serverSecret", "");
        if (serverSecret != null) {
            serverSecret = serverSecret.trim();
        }
        if (serverSecret.isEmpty()) {
            serverSecret = UUID.randomUUID().toString().replace("-", "");
            getConfig().set("security.serverSecret", serverSecret);
            saveConfig();
        }

        // 只覆盖仓库内的两代协议（1.20.1 / 1.21.1），避免过多“历史兼容”冗余

        // 监听多个通道，兼容 Fabric/Forge/NeoForge 的不同 channel
        incomingChannels.add(CHANNEL_MMDSKIN_NETWORK);
        incomingChannels.add(CHANNEL_MMDSYNC_HANDSHAKE);
        incomingChannels.add(CHANNEL_MMDSKIN_PACK);
        incomingChannels.add(CHANNEL_3DSKIN_PACK);
        incomingChannels.add(CHANNEL_3DSKIN_C2S);

        // 服务器下发时，优先尝试这些通道
        preferredOutgoingChannels.add(CHANNEL_MMDSKIN_NETWORK);
        preferredOutgoingChannels.add(CHANNEL_MMDSKIN_PACK);
        preferredOutgoingChannels.add(CHANNEL_3DSKIN_PACK);
        preferredOutgoingChannels.add(CHANNEL_3DSKIN_S2C);

        // Register plugin channels
        for (String ch : incomingChannels) {
            this.getServer().getMessenger().registerIncomingPluginChannel(this, ch, this);
        }
        for (String ch : preferredOutgoingChannels) {
            this.getServer().getMessenger().registerOutgoingPluginChannel(this, ch);
        }
        // Register sync channel
        this.getServer().getMessenger().registerOutgoingPluginChannel(this, CHANNEL_SYNC_URL);

        // 加载或生成服务器同步密钥
        loadSyncKey();

        // Model synchronization logic
        if (getConfig().getBoolean("sync.enabled", true)) {
            loadCache();
            syncPort = getConfig().getInt("sync.port", 5000);
            syncUrl = getConfig().getString("sync.serverUrl", "");
            maxBandwidthMbps = getConfig().getDouble("sync.maxBandwidthMbps", 0.0);
            enableGzip = getConfig().getBoolean("sync.enableGzip", true);
            
            // 固定为服务器根目录下的 3d-skin
            modelDir = new File(getServer().getWorldContainer(), "3d-skin");
            if (!modelDir.exists()) modelDir.mkdirs();

            startHttpServer(syncPort);
        }

        // Register events
        this.getServer().getPluginManager().registerEvents(this, this);
        // Register command
        this.getCommand("mmdsync").setExecutor(this);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("mmdsync.admin")) {
            sender.sendMessage("§cYou don't have permission to use this command.");
            return true;
        }

        if (args.length == 0) {
            sender.sendMessage("§6/mmdsync reload §7- Reload config and restart HTTP server");
            sender.sendMessage("§6/mmdsync sync   §7- Force sync models for all players");
            return true;
        }

        if (args[0].equalsIgnoreCase("reload")) {
            sender.sendMessage("§aReloading MmdSkin-Bukkit...");
            reloadConfig();

            // Reload sync key
            loadSyncKey();
            
            // Restart HTTP server if needed
            if (httpServer != null) {
                httpServer.stop(0);
                httpServer = null;
            }
            if (httpExecutor != null) {
                httpExecutor.shutdown();
                httpExecutor = null;
            }
            saveCache();
            
            if (getConfig().getBoolean("sync.enabled", true)) {
                loadCache();
                syncPort = getConfig().getInt("sync.port", 5000);
                syncUrl = getConfig().getString("sync.serverUrl", "");
                maxBandwidthMbps = getConfig().getDouble("sync.maxBandwidthMbps", 0.0);
                enableGzip = getConfig().getBoolean("sync.enableGzip", true);
                
                // 固定为服务器根目录下的 3d-skin
                modelDir = new File(getServer().getWorldContainer(), "3d-skin");
                if (!modelDir.exists()) modelDir.mkdirs();
                startHttpServer(syncPort);
            }

            // Resend URL to all players
            for (Player player : Bukkit.getOnlinePlayers()) {
                if (httpServer != null) {
                    sendSyncUrl(player);
                }
            }
            sender.sendMessage("§aConfig reloaded and Sync URL resent to all players.");
            return true;
        }

        if (args[0].equalsIgnoreCase("sync")) {
            sender.sendMessage("§aSyncing models for all players...");
            
            // Reload cache before sync to ensure MD5s are up to date
            md5Cache.clear();
            loadCache();

            for (Player player : Bukkit.getOnlinePlayers()) {
                if (httpServer != null) {
                    sendSyncUrl(player);
                }
                
                // For each player, broadcast their current model to everyone else
                UUID uuid = player.getUniqueId();
                String modelName = playerModels.get(uuid);
                if (modelName != null && !modelName.isEmpty()) {
                    for (String ch : preferredOutgoingChannels) {
                        try {
                            byte[] data = createModelSyncPacket(ch, uuid, modelName);
                            broadcastPacket(Set.of(ch), data, null, null);
                        } catch (IOException e) {
                            getLogger().log(Level.SEVERE, "Error broadcasting model for " + uuid, e);
                        }
                    }
                }
            }
            sender.sendMessage("§aModel sync triggered for all online players.");
            return true;
        }

        return false;
    }

    private void startHttpServer(int port) {
        try {
            // 绑定到 0.0.0.0 以允许外部访问
            httpServer = HttpServer.create(new InetSocketAddress("0.0.0.0", port), 0);
            httpServer.createContext("/", new IndexHandler());
            httpServer.createContext("/api/sync", new SyncHandler());
            httpServer.createContext("/download/", new DownloadHandler());
            httpServer.createContext("/upload", new UploadHandler());
            httpServer.createContext("/mmd/", new ModelFileHandler());
            
            httpExecutor = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());
            httpServer.setExecutor(httpExecutor);
            httpServer.start();
            getLogger().info("内置 HTTP 资源服务器已启动，端口: " + port);
        } catch (IOException e) {
            getLogger().log(Level.SEVERE, "HTTP 服务器启动失败", e);
        }
    }

    private class ModelFileHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!isAuthorized(exchange)) {
                sendResponse(exchange, 403, "403 Forbidden".getBytes(StandardCharsets.UTF_8), "text/plain");
                return;
            }
            String path = URLDecoder.decode(exchange.getRequestURI().getPath(), StandardCharsets.UTF_8);
            if (path.startsWith("/mmd/")) {
                String fileName = path.substring(5);
                File file = new File(modelDir, fileName);
                if (file.exists() && file.isFile()) {
                    byte[] bytes = Files.readAllBytes(file.toPath());
                    sendResponse(exchange, 200, bytes, getContentType(fileName));
                    return;
                }
            }
            String response = "404 Not Found";
            sendResponse(exchange, 404, response.getBytes(), "text/plain");
        }

        private String getContentType(String fileName) {
            String lower = fileName.toLowerCase();
            if (lower.endsWith(".html")) return "text/html; charset=UTF-8";
            if (lower.endsWith(".css")) return "text/css";
            if (lower.endsWith(".js")) return "application/javascript";
            if (lower.endsWith(".png")) return "image/png";
            if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
            if (lower.endsWith(".json")) return "application/json";
            return "application/octet-stream";
        }
    }

    private class IndexHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String path = exchange.getRequestURI().getPath();
            String resourcePath = "/".equals(path) ? "/assets/mmdsync/web/index.html" : "/assets/mmdsync/web" + path;
            InputStream is = MmdSkinBukkit.class.getResourceAsStream(resourcePath);
            if (is == null) {
                if (!"/".equals(path)) {
                    sendResponse(exchange, 404, "404 Not Found".getBytes(StandardCharsets.UTF_8), "text/plain; charset=UTF-8");
                    return;
                }
                String error = "404 Not Found (index.html missing)";
                sendResponse(exchange, 404, error.getBytes(StandardCharsets.UTF_8), "text/plain; charset=UTF-8");
                return;
            }
            byte[] response = is.readAllBytes();
            sendResponse(exchange, 200, response, getContentType(path));
        }

        private String getContentType(String path) {
            String lower = path.toLowerCase(Locale.ROOT);
            if (lower.endsWith(".html") || "/".equals(path)) return "text/html; charset=UTF-8";
            if (lower.endsWith(".woff2")) return "font/woff2";
            if (lower.endsWith(".txt")) return "text/plain; charset=UTF-8";
            if (lower.endsWith(".webp")) return "image/webp";
            if (lower.endsWith(".png")) return "image/png";
            if (lower.endsWith(".ico")) return "image/x-icon";
            return "application/octet-stream";
        }
    }

    private class SyncHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!isAuthorized(exchange)) {
                sendResponse(exchange, 403, "403 Forbidden".getBytes(StandardCharsets.UTF_8), "text/plain");
                return;
            }
            Map<String, List<Map<String, String>>> responseMap = new HashMap<>();
            responseMap.put("pmx", scanFolders(new File(modelDir, "EntityPlayer")));
            responseMap.put("vmd", scanFolders(new File(modelDir, "StageAnim")));

            String json = buildSimpleJson(responseMap);
            byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
            sendResponse(exchange, 200, bytes, "application/json");
        }

        private List<Map<String, String>> scanFolders(File dir) {
            List<Map<String, String>> list = new ArrayList<>();
            if (!dir.exists() || !dir.isDirectory()) return list;
            
            File[] folders = dir.listFiles(File::isDirectory);
            if (folders != null) {
                for (File folder : folders) {
                    Map<String, String> obj = new HashMap<>();
                    obj.put("name", folder.getName());
                    obj.put("md5", getFolderMD5(folder.toPath()));
                    list.add(obj);
                }
            }
            return list;
        }

        private String buildSimpleJson(Map<String, List<Map<String, String>>> map) {
            StringBuilder sb = new StringBuilder("{");
            boolean firstZone = true;
            for (Map.Entry<String, List<Map<String, String>>> entry : map.entrySet()) {
                if (!firstZone) sb.append(",");
                sb.append("\"").append(entry.getKey()).append("\":[");
                boolean firstItem = true;
                for (Map<String, String> item : entry.getValue()) {
                    if (!firstItem) sb.append(",");
                    sb.append("{\"name\":\"").append(item.get("name")).append("\",\"md5\":\"").append(item.get("md5")).append("\"}");
                    firstItem = false;
                }
                sb.append("]");
                firstZone = false;
            }
            sb.append("}");
            return sb.toString();
        }
    }

    private class DownloadHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!isAuthorized(exchange)) {
                sendResponse(exchange, 403, "403 Forbidden".getBytes(StandardCharsets.UTF_8), "text/plain");
                return;
            }
            String nextToken = consumeIssuedNextToken(exchange);
            if (!nextToken.isEmpty()) {
                exchange.getResponseHeaders().set("X-MMDSync-Next-Token", nextToken);
            }
            String path = URLDecoder.decode(exchange.getRequestURI().getPath(), StandardCharsets.UTF_8);
            String[] parts = path.split("/", 4);
            if (parts.length < 4) {
                exchange.sendResponseHeaders(404, 0);
                exchange.close();
                return;
            }

            String zone = parts[2];
            String folderName = parts[3];
            File baseDir = new File(modelDir, zone.equals("pmx") ? "EntityPlayer" : "StageAnim");
            File targetFolder = new File(baseDir, folderName);

            if (targetFolder.exists() && targetFolder.isDirectory()) {
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                try (java.util.zip.ZipOutputStream zos = new java.util.zip.ZipOutputStream(bos)) {
                    zos.setLevel(java.util.zip.Deflater.DEFAULT_COMPRESSION);
                    compressFolder(targetFolder, targetFolder, zos);
                }
                
                byte[] zipBytes = bos.toByteArray();
                sendResponse(exchange, 200, zipBytes, "application/zip");
            } else {
                sendResponse(exchange, 404, "404 Not Found".getBytes(), "text/plain");
            }
        }

        private void compressFolder(File root, File source, ZipOutputStream zos) throws IOException {
            File[] files = source.listFiles();
            if (files == null) return;
            for (File file : files) {
                if (file.isDirectory()) {
                    compressFolder(root, file, zos);
                } else {
                    String rel = root.toPath().relativize(file.toPath()).toString().replace(File.separatorChar, '/');
                    zos.putNextEntry(new ZipEntry(rel));
                    
                    byte[] data = Files.readAllBytes(file.toPath());
                    // 如果开启了同步加密，且文件是需要加密的类型（pmx/vmd/纹理）
                    String lower = rel.toLowerCase();
                    if (serverSyncKey != null && (lower.endsWith(".pmx") || lower.endsWith(".vmd") ||
                        lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".tga"))) {
                        boolean alreadyEncrypted = data.length >= 7
                                && data[0] == 'M' && data[1] == 'M' && data[2] == 'D'
                                && data[3] == 'A' && data[4] == 'R' && data[5] == 'C';
                        if (!alreadyEncrypted) {
                            // 发送前实时加密
                            byte[] encrypted = MMDSyncNativeBridge.aesEncrypt(data, serverSyncKey);
                            if (encrypted != null) {
                                // 加上容器头部
                                byte[] header = "MMDARC".getBytes(StandardCharsets.UTF_8);
                                byte version = 0x01;
                                byte[] result = new byte[header.length + 1 + encrypted.length];
                                System.arraycopy(header, 0, result, 0, header.length);
                                result[header.length] = version;
                                System.arraycopy(encrypted, 0, result, header.length + 1, encrypted.length);
                                data = result;
                            }
                        }
                    }
                    
                    zos.write(data);
                    zos.closeEntry();
                }
            }
        }
    }

    private class UploadHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(405, 0);
                exchange.close();
                return;
            }

            Map<String, String> params = parseQuery(exchange.getRequestURI().getQuery());
            String zone = params.getOrDefault("zone", "pmx");
            String originalName = params.getOrDefault("name", "upload.zip");

            String datePrefix = new SimpleDateFormat("yyyy-MM-dd").format(new Date());
            File baseDir = new File(modelDir, zone.equals("pmx") ? "EntityPlayer" : "StageAnim");
            if (!baseDir.exists()) baseDir.mkdirs();

            try (InputStream is = exchange.getRequestBody()) {
                if (originalName.toLowerCase().endsWith(".zip")) {
                    processZipUpload(is, baseDir.toPath(), datePrefix, originalName);
                } else {
                    Path targetFile;
                    String normalizedName = originalName.replace('\\', '/');
                    if (normalizedName.contains("/")) {
                        int firstSlash = normalizedName.indexOf('/');
                        String topDir = normalizedName.substring(0, firstSlash);
                        String remaining = normalizedName.substring(firstSlash + 1);
                        String folderName = datePrefix + "_" + topDir;
                        targetFile = baseDir.toPath().resolve(folderName).resolve(remaining.replace('/', File.separatorChar));
                    } else {
                        String nameWithoutExt = originalName;
                        int lastDot = originalName.lastIndexOf('.');
                        if (lastDot != -1) nameWithoutExt = originalName.substring(0, lastDot);
                        String folderName = datePrefix + "_" + nameWithoutExt;
                        File targetFolder = new File(baseDir, folderName);
                        if (targetFolder.exists()) {
                            String suffix = Integer.toHexString(new Random().nextInt(0x10000));
                            targetFolder = new File(baseDir, folderName + "_" + suffix);
                        }
                        targetFile = targetFolder.toPath().resolve(originalName);
                    }
                    Files.createDirectories(targetFile.getParent());
                    Files.copy(is, targetFile, StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (Exception e) {
                getLogger().log(Level.SEVERE, "Upload failed", e);
                String error = "Upload failed: " + e.getMessage();
                exchange.sendResponseHeaders(500, error.length());
                exchange.getResponseBody().write(error.getBytes());
                exchange.close();
                return;
            }

            String response = "Upload successful";
            sendResponse(exchange, 200, response.getBytes(), "text/plain");
        }

        private void processZipUpload(InputStream is, Path targetBaseDir, String datePrefix, String originalName) throws IOException {
            Path tempZip = Files.createTempFile("mmdsync_", ".zip");
            Files.copy(is, tempZip, StandardCopyOption.REPLACE_EXISTING);
            try {
                List<String> entryNames = new ArrayList<>();
                try (ZipInputStream zis = new ZipInputStream(Files.newInputStream(tempZip))) {
                    ZipEntry entry;
                    while ((entry = zis.getNextEntry()) != null) entryNames.add(entry.getName());
                }

                String commonPrefix = "";
                while (true) {
                    String currentPrefix = commonPrefix;
                    String firstTopDir = null;
                    boolean allMatch = true;
                    boolean hasFileInCurrentLevel = false;
                    for (String name : entryNames) {
                        if (!name.startsWith(currentPrefix)) continue;
                        String relative = name.substring(currentPrefix.length());
                        if (relative.isEmpty()) continue;
                        int slashIndex = relative.indexOf('/');
                        if (slashIndex == -1) {
                            hasFileInCurrentLevel = true;
                        } else {
                            String topDir = relative.substring(0, slashIndex + 1);
                            if (firstTopDir == null) firstTopDir = topDir;
                            else if (!firstTopDir.equals(topDir)) allMatch = false;
                        }
                    }
                    if (!hasFileInCurrentLevel && firstTopDir != null && allMatch) commonPrefix += firstTopDir;
                    else break;
                }

                boolean needsExtraWrap = false;
                String firstItem = null;
                int itemCount = 0;
                for (String name : entryNames) {
                    if (!name.startsWith(commonPrefix)) continue;
                    String relative = name.substring(commonPrefix.length());
                    if (relative.isEmpty()) continue;
                    int slashIndex = relative.indexOf('/');
                    String item = (slashIndex == -1) ? relative : relative.substring(0, slashIndex);
                    if (firstItem == null) { firstItem = item; itemCount = 1; }
                    else if (!firstItem.equals(item)) { itemCount++; break; }
                }
                
                final String finalCommonPrefix = commonPrefix;
                final String finalFirstItem = firstItem;
                if (itemCount > 1 || (finalFirstItem != null && !entryNames.stream().anyMatch(n -> n.equals(finalCommonPrefix + finalFirstItem + "/")))) {
                    needsExtraWrap = true;
                }

                final String wrapNameBase = originalName.toLowerCase().endsWith(".zip") 
                        ? originalName.substring(0, originalName.length() - 4) : originalName;

                try (ZipInputStream zis = new ZipInputStream(Files.newInputStream(tempZip))) {
                    ZipEntry entry;
                    Map<String, String> dirRenames = new HashMap<>();
                    while ((entry = zis.getNextEntry()) != null) {
                        String name = entry.getName();
                        if (!name.startsWith(finalCommonPrefix)) continue;
                        String relativePath = name.substring(finalCommonPrefix.length());
                        if (relativePath.isEmpty()) continue;
                        String finalPath;
                        if (needsExtraWrap) {
                            String topName = dirRenames.computeIfAbsent("_wrap_", k -> {
                                String target = datePrefix + "_" + wrapNameBase;
                                while (Files.exists(targetBaseDir.resolve(target))) {
                                    String suffix = Integer.toHexString(new Random().nextInt(0x10000));
                                    target = datePrefix + "_" + wrapNameBase + "_" + suffix;
                                }
                                return target;
                            });
                            finalPath = topName + "/" + relativePath;
                        } else {
                            int firstSlash = relativePath.indexOf('/');
                            String topName = (firstSlash == -1) ? relativePath : relativePath.substring(0, firstSlash);
                            String remaining = (firstSlash == -1) ? "" : relativePath.substring(firstSlash);
                            String newTopName = dirRenames.computeIfAbsent(topName, k -> {
                                String target = datePrefix + "_" + k;
                                while (Files.exists(targetBaseDir.resolve(target))) {
                                    String suffix = Integer.toHexString(new Random().nextInt(0x10000));
                                    target = datePrefix + "_" + k + "_" + suffix;
                                }
                                return target;
                            });
                            finalPath = newTopName + remaining;
                        }
                        Path targetFile = targetBaseDir.resolve(finalPath.replace('/', File.separatorChar));
                        if (entry.isDirectory()) Files.createDirectories(targetFile);
                        else {
                            Files.createDirectories(targetFile.getParent());
                            Files.copy(zis, targetFile, StandardCopyOption.REPLACE_EXISTING);
                        }
                        zis.closeEntry();
                    }
                }
            } finally {
                Files.deleteIfExists(tempZip);
            }
        }

        private Map<String, String> parseQuery(String query) {
            Map<String, String> result = new HashMap<>();
            if (query != null) {
                for (String param : query.split("&")) {
                    String[] pair = param.split("=");
                    if (pair.length > 1) result.put(pair[0], URLDecoder.decode(pair[1], StandardCharsets.UTF_8));
                }
            }
            return result;
        }
    }

    private String getFolderMD5(Path folder) {
        try (Stream<Path> stream = Files.walk(folder)) {
            StringBuilder combined = new StringBuilder();
            stream.filter(Files::isRegularFile)
                  .sorted()
                  .forEach(p -> combined.append(getCachedMD5(p)));
            
            MessageDigest digest = MessageDigest.getInstance("MD5");
            byte[] hash = digest.digest(combined.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private String getCachedMD5(Path path) {
        try {
            if (!Files.exists(path)) return "";
            long lastModified = Files.getLastModifiedTime(path).toMillis();
            CacheEntry entry = md5Cache.get(path);
            if (entry != null && entry.lastModified == lastModified) return entry.md5;

            MessageDigest digest = MessageDigest.getInstance("MD5");
            try (InputStream is = Files.newInputStream(path)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = is.read(buffer)) != -1) digest.update(buffer, 0, read);
            }
            byte[] hash = digest.digest();
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) sb.append(String.format("%02x", b));
            String md5 = sb.toString();
            md5Cache.put(path, new CacheEntry(md5, lastModified));
            return md5;
        } catch (Exception e) {
            return "";
        }
    }

    private void loadCache() {
        File cacheFile = new File(getDataFolder(), "md5_cache.dat");
        if (!cacheFile.exists()) return;
        try (DataInputStream dis = new DataInputStream(new FileInputStream(cacheFile))) {
            int size = dis.readInt();
            for (int i = 0; i < size; i++) {
                String pathStr = dis.readUTF();
                String md5 = dis.readUTF();
                long lastModified = dis.readLong();
                md5Cache.put(new File(pathStr).toPath(), new CacheEntry(md5, lastModified));
            }
        } catch (IOException e) {
            getLogger().warning("Failed to load MD5 cache: " + e.getMessage());
        }
    }

    private void saveCache() {
        File cacheFile = new File(getDataFolder(), "md5_cache.dat");
        try (DataOutputStream dos = new DataOutputStream(new FileOutputStream(cacheFile))) {
            dos.writeInt(md5Cache.size());
            for (Map.Entry<Path, CacheEntry> entry : md5Cache.entrySet()) {
                dos.writeUTF(entry.getKey().toString());
                dos.writeUTF(entry.getValue().md5);
                dos.writeLong(entry.getValue().lastModified);
            }
        } catch (IOException e) {
            getLogger().warning("Failed to save MD5 cache: " + e.getMessage());
        }
    }

    @Override
    public void onDisable() {
        if (httpServer != null) {
            httpServer.stop(0);
        }
        this.getServer().getMessenger().unregisterIncomingPluginChannel(this);
        this.getServer().getMessenger().unregisterOutgoingPluginChannel(this);
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();

        // 延迟一点：很多客户端会在进入世界后才完成通道注册/Mod 初始化。
        // 若不延迟，join 时的补发包可能被客户端丢弃，表现为“别人看不到变化”。
        Bukkit.getScheduler().runTaskLater(this, () -> {
            // 下发同步服务器地址
            if (httpServer != null) {
                sendSyncUrl(player);
            }

            // 对每个 outgoing 通道，若玩家声明监听，则补发一遍（只会命中其实际监听的通道）
            for (String ch : preferredOutgoingChannels) {
                if (!isPlayerListening(player, ch)) continue;

                playerModels.forEach((uuid, modelName) -> {
                    try {
                        byte[] data = createModelSyncPacket(ch, uuid, modelName);
                        player.sendPluginMessage(this, ch, data);
                    } catch (IOException e) {
                        getLogger().log(Level.SEVERE, "Error creating sync packet for " + uuid, e);
                    }
                });
            }
        }, 20L);
    }

    private void loadSyncKey() {
        String keyBase64 = getConfig().getString("sync.key", "");
        if (keyBase64.isEmpty() || keyBase64.length() < 32) {
            serverSyncKey = new byte[32];
            secureRandom.nextBytes(serverSyncKey);
            getConfig().set("sync.key", Base64.getEncoder().encodeToString(serverSyncKey));
            saveConfig();
        } else {
            try {
                serverSyncKey = Base64.getDecoder().decode(keyBase64);
                if (serverSyncKey.length != 32) {
                    throw new IllegalArgumentException("Key must be 32 bytes (256-bit)");
                }
            } catch (Exception e) {
                getLogger().severe("[MmdSkin] Failed to load sync key: " + e.getMessage());
                serverSyncKey = new byte[32];
                secureRandom.nextBytes(serverSyncKey);
                getConfig().set("sync.key", Base64.getEncoder().encodeToString(serverSyncKey));
                saveConfig();
            }
        }
    }

    private String normalizePem(String pem) {
        if (pem == null) return "";
        return pem
                .replace("-----BEGIN PUBLIC KEY-----", "")
                .replace("-----END PUBLIC KEY-----", "")
                .replaceAll("\\s+", "");
    }


    private String randomUrlSafeToken(int byteLen) {
        byte[] bytes = new byte[byteLen];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String issueHandshakeChallenge(UUID playerUuid) {
        String challenge = randomUrlSafeToken(24);
        pendingHandshakes.put(playerUuid, new PendingHandshake(challenge, System.currentTimeMillis() + HANDSHAKE_TTL_MS));
        return challenge;
    }

    private String issueDownloadToken(Player player) {
        String token = randomUrlSafeToken(32);
        String boundIp = "";
        if (player.getAddress() != null && player.getAddress().getAddress() != null) {
            boundIp = player.getAddress().getAddress().getHostAddress();
        }
        downloadSessions.put(token, new DownloadSession(player.getUniqueId(), boundIp, System.currentTimeMillis() + DOWNLOAD_TOKEN_TTL_MS));
        return token;
    }

    private String buildStableServerId() {
        if (serverSyncKey == null || serverSyncKey.length == 0) {
            return "";
        }
        StringBuilder sb = new StringBuilder("srv-");
        for (byte b : serverSyncKey) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    private String calcRequestSignature(String token, String ts, String nonce, String method, String rawPath) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(serverSyncKey, "HmacSHA256"));
            String payload = token + "|" + ts + "|" + nonce + "|" + method + "|" + rawPath;
            byte[] digest = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private boolean isAuthorized(HttpExchange exchange) {
        String query = exchange.getRequestURI().getQuery();
        if (query == null || query.isEmpty()) return false;
        String token = null;
        for (String param : query.split("&")) {
            int idx = param.indexOf('=');
            if (idx <= 0) continue;
            String key = param.substring(0, idx);
            if (!"token".equals(key)) continue;
            token = URLDecoder.decode(param.substring(idx + 1), StandardCharsets.UTF_8);
            break;
        }
        if (token == null || token.isEmpty()) return false;
        DownloadSession session = downloadSessions.get(token);
        if (session == null) return false;
        if (System.currentTimeMillis() > session.expireAt) {
            downloadSessions.remove(token);
            return false;
        }
        String remoteIp = "";
        if (exchange.getRemoteAddress() != null && exchange.getRemoteAddress().getAddress() != null) {
            remoteIp = exchange.getRemoteAddress().getAddress().getHostAddress();
        }
        if (!session.boundIp.isEmpty() && !session.boundIp.equals(remoteIp)) {
            return false;
        }
        String ts = exchange.getRequestHeaders().getFirst("X-MMDSync-Ts");
        String nonce = exchange.getRequestHeaders().getFirst("X-MMDSync-Nonce");
        String sign = exchange.getRequestHeaders().getFirst("X-MMDSync-Sign");
        if (ts == null || nonce == null || sign == null) return false;
        if (!isValidNonce(nonce)) return false;
        long tsSec;
        try {
            tsSec = Long.parseLong(ts);
        } catch (NumberFormatException e) {
            return false;
        }
        long nowSec = System.currentTimeMillis() / 1000L;
        if (Math.abs(nowSec - tsSec) > REQUEST_TS_WINDOW_SEC) return false;
        String rawPath = exchange.getRequestURI().getRawPath();
        if (!session.tryUseNonce(nonce)) return false;
        String expected = calcRequestSignature(token, ts, nonce, exchange.getRequestMethod(), rawPath);
        if (expected.isEmpty() || !expected.equalsIgnoreCase(sign)) {
            session.usedNonces.remove(nonce);
            return false;
        }
        if (rawPath != null && rawPath.startsWith("/download/")) {
            if (!session.tryConsumeDownload()) {
                return false;
            }
            String nextToken = randomUrlSafeToken(32);
            downloadSessions.put(nextToken, session.renew());
            downloadSessions.remove(token, session);
            exchange.setAttribute(NEXT_TOKEN_ATTR, nextToken);
        }
        return true;
    }

    private String consumeIssuedNextToken(HttpExchange exchange) {
        Object value = exchange.getAttribute(NEXT_TOKEN_ATTR);
        if (!(value instanceof String nextToken) || nextToken.isEmpty()) {
            return "";
        }
        exchange.setAttribute(NEXT_TOKEN_ATTR, null);
        return nextToken;
    }

    private boolean isValidNonce(String nonce) {
        if (nonce == null || nonce.length() != NONCE_HEX_LENGTH) {
            return false;
        }
        for (int i = 0; i < nonce.length(); i++) {
            char c = nonce.charAt(i);
            boolean isHex = (c >= '0' && c <= '9')
                    || (c >= 'a' && c <= 'f')
                    || (c >= 'A' && c <= 'F');
            if (!isHex) {
                return false;
            }
        }
        return true;
    }

    private byte[] getNativeResourceHash(String platform) {
        if (platform == null || platform.isEmpty()) return null;
        String fileName;
        if (platform.startsWith("windows")) {
            fileName = "mmdsync_bridge.dll";
        } else if (platform.startsWith("macos")) {
            fileName = "libmmdsync_bridge.dylib";
        } else if (platform.startsWith("linux")) {
            fileName = "libmmdsync_bridge.so";
        } else {
            return null;
        }
        String resourcePath = "/natives/" + platform + "/" + fileName;
        try (InputStream is = MmdSkinBukkit.class.getResourceAsStream(resourcePath)) {
            if (is == null) return null;
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            int n;
            while ((n = is.read(buffer)) != -1) {
                digest.update(buffer, 0, n);
            }
            return digest.digest();
        } catch (Exception e) {
            return null;
        }
    }

    private void handleHandshake(Player player, String clientPublicKey, String platform, String hwid) throws Exception {
        if (player == null) {
            return;
        }

        if (serverSyncKey == null || serverSyncKey.length != 32) {
            loadSyncKey();
        }
        if (serverSyncKey == null || serverSyncKey.length != 32) {
            getLogger().warning("握手失败：服务器同步密钥不可用。player=" + player.getUniqueId());
            return;
        }

        PendingHandshake pending = pendingHandshakes.get(player.getUniqueId());
        if (pending == null) {
            return;
        }
        if (System.currentTimeMillis() > pending.expireAt) {
            pendingHandshakes.remove(player.getUniqueId());
            return;
        }

        byte[] targetHash = getNativeResourceHash(platform);
        if (targetHash == null) {
            getLogger().warning("握手失败：无法获取平台原生库指纹。player=" + player.getUniqueId() + ", platform=" + platform);
            return;
        }

        String expectedHandshakePem = MMDSyncNativeBridge.deriveHandshakePem(pending.challenge, targetHash, hwid);
        String expectedNormalized = normalizePem(expectedHandshakePem);
        String clientNormalized = normalizePem(clientPublicKey);
        if (expectedNormalized.isEmpty()) {
            getLogger().warning("握手失败：服务器未生成有效公钥材料。player=" + player.getUniqueId());
            return;
        }

        if (!expectedNormalized.equals(clientNormalized)) {
            getLogger().warning("握手失败：客户端公钥校验不一致。player=" + player.getUniqueId());
            return;
        }

        pendingHandshakes.remove(player.getUniqueId());
        String downloadToken = issueDownloadToken(player);
        String encryptedAesKeyBase64 = rsaEncryptJava(serverSyncKey, expectedHandshakePem);
        sendSyncUrl(player, encryptedAesKeyBase64, pending.challenge + "|" + downloadToken);
    }

    /**
     * Java 实现的 RSA 加密，用于给客户端下发 AES 密钥。
     */
    private String rsaEncryptJava(byte[] data, String publicKeyPem) throws Exception {
        String cleanKey = publicKeyPem
                .replace("-----BEGIN PUBLIC KEY-----", "")
                .replace("-----END PUBLIC KEY-----", "")
                .replaceAll("\\s+", "");
        
        byte[] encoded = Base64.getDecoder().decode(cleanKey);
        X509EncodedKeySpec keySpec = new X509EncodedKeySpec(encoded);
        KeyFactory keyFactory = KeyFactory.getInstance("RSA");
        PublicKey publicKey = keyFactory.generatePublic(keySpec);
        
        Cipher cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding");
        cipher.init(Cipher.ENCRYPT_MODE, publicKey);
        byte[] encrypted = cipher.doFinal(data);
        return Base64.getEncoder().encodeToString(encrypted);
    }

    private void sendSyncUrl(Player player) {
        String challenge = issueHandshakeChallenge(player.getUniqueId());
        sendSyncUrl(player, "", challenge + "|");
    }

    private void sendSyncUrl(Player player, String encryptedKey) {
        sendSyncUrl(player, encryptedKey, "");
    }

    private void sendSyncUrl(Player player, String encryptedKey, String handshakeContext) {
        String urlToDown = syncUrl;
        if (urlToDown == null || urlToDown.isEmpty()) {
            urlToDown = ":" + syncPort;
        }

        if (!urlToDown.startsWith(":") && !urlToDown.startsWith("http://") && !urlToDown.startsWith("https://")) {
            urlToDown = "http://" + urlToDown;
        }

        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             DataOutputStream dos = new DataOutputStream(baos)) {
            writeString(dos, urlToDown);
            writeString(dos, encryptedKey == null ? "" : encryptedKey);
            writeString(dos, handshakeContext == null ? "" : handshakeContext);
            writeString(dos, buildStableServerId());
            player.sendPluginMessage(this, CHANNEL_SYNC_URL, baos.toByteArray());
        } catch (IOException e) {
            getLogger().log(Level.SEVERE, "Error sending sync URL", e);
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        playerModels.remove(uuid);
        pendingHandshakes.remove(uuid);
        downloadSessions.entrySet().removeIf(e -> e.getValue().playerUuid.equals(uuid));

        // 给其他客户端补发一次“清空模型”（可选，但能避免部分客户端缓存残留）
        for (String ch : preferredOutgoingChannels) {
            try {
                byte[] data = createModelSyncPacket(ch, uuid, "");
                broadcastPacket(Set.of(ch), data, null, uuid);
            } catch (IOException e) {
                getLogger().log(Level.SEVERE, "Error sending clear packet", e);
            }
        }
    }

    @Override
    public void onPluginMessageReceived(String channel, Player sender, byte[] message) {
        if (!incomingChannels.contains(channel)) return;

        // 关键原则：
        // - 转发器模式下，客户端 payload 可能因为“我们解析不完整/某些 opCode 变体长度不同”而解析失败。
        // - 但 raw bytes 对客户端来说仍然是合法的。
        // 因此：默认必须转发；解析仅用于实现 opCode 3/10 的缓存/补发逻辑。

        String outgoing = mapOutgoingChannel(channel);

        // 先做最小头解析（只读 opCode + UUID），尽量避免因为后续字段差异导致整包被丢弃。
        ParsedHeader header = null;
        try {
            header = parseHeader(message);
        } catch (IOException e) {
            // 头都解析不了，通常是长度不足；这种情况下也仍然转发（让客户端自行决定是否丢弃）。
            getLogger().log(Level.WARNING, "Forward payload (header parse failed) from " + sender.getName() + " on " + channel + ", len=" + (message == null ? 0 : message.length) + ": " + e.getMessage());
            broadcastPacket(Set.of(outgoing), message, sender.getUniqueId(), null);
            return;
        }

        // opCode 20: 握手包，客户端发送 RSA 公钥
        if (header.opCode == 20) {
            try {
                ParsedPayload parsed = parsePayload(channel, message);
                handleHandshake(sender, parsed.stringArg, parsed.platform, parsed.hwid);
            } catch (Exception e) {
                getLogger().log(Level.SEVERE, "Error handling handshake from " + sender.getName(), e);
            }
            return;
        }

        // opCode 10: 客户端请求所有玩家的模型信息。只需服务器响应，不应广播给其他客户端。
        if (header.opCode == 10) {
            sendAllModelsToPlayer(sender);
            return;
        }

        // opCode 3: 模型选择同步（保存缓存，供 join/opCode10 补发）
        if (header.opCode == 3) {
            try {
                ParsedPayload parsed = parsePayload(channel, message);
                String modelName = parsed.stringArg;
                if (modelName == null || modelName.isEmpty()) {
                    playerModels.remove(parsed.playerUUID);
                } else {
                    playerModels.put(parsed.playerUUID, modelName);
                }
            } catch (IOException e) {
                // 不影响转发；仅意味着我们无法为该玩家缓存模型名。
                getLogger().log(Level.FINE, "Model sync payload parsed failed from " + sender.getName() + " on " + channel + ": " + e.getMessage());
            }
        }

        broadcastPacket(Set.of(outgoing), message, sender.getUniqueId(), null);
    }

    private void sendAllModelsToPlayer(Player player) {
        for (String ch : preferredOutgoingChannels) {
            if (!isPlayerListening(player, ch)) continue;

            playerModels.forEach((uuid, modelName) -> {
                try {
                    byte[] data = createModelSyncPacket(ch, uuid, modelName);
                    player.sendPluginMessage(this, ch, data);
                } catch (IOException e) {
                    getLogger().log(Level.SEVERE, "Error creating sync packet for " + uuid, e);
                }
            });
        }
    }

    /**
     * 广播插件消息给在线玩家。
     *
     * @param channelsToUse        使用哪些 channel 发送
     * @param message              原始 payload bytes
     * @param excludeSenderUuid    不发送给该 UUID（通常是发送者自己），可为 null
     * @param excludeTargetUuid    不发送给该 UUID（例如已退出的玩家），可为 null
     * @return 实际发送到的玩家数量（按玩家计数，不按 channel 计数）
     */
    private int broadcastPacket(Set<String> channelsToUse, byte[] message, UUID excludeSenderUuid, UUID excludeTargetUuid) {
        int sentCount = 0;
        for (Player p : Bukkit.getOnlinePlayers()) {
            UUID uuid = p.getUniqueId();
            if (excludeSenderUuid != null && uuid.equals(excludeSenderUuid)) continue;
            if (excludeTargetUuid != null && uuid.equals(excludeTargetUuid)) continue;

            boolean sentToThisPlayer = false;
            for (String ch : channelsToUse) {
                if (!isPlayerListening(p, ch)) continue;
                p.sendPluginMessage(this, ch, message);
                sentToThisPlayer = true;
            }

            if (sentToThisPlayer) {
                sentCount++;
            }
        }
        return sentCount;
    }

    private boolean isPlayerListening(Player player, String channel) {
        try {
            return player != null && player.isOnline() && player.getListeningPluginChannels().contains(channel);
        } catch (Throwable t) {
            // 某些服务端分支可能实现不同，兜底为 true
            return player != null && player.isOnline();
        }
    }

    private String mapOutgoingChannel(String incomingChannel) {
        // Fabric 1.20.1: C2S/S2C 通道不同
        if (CHANNEL_3DSKIN_C2S.equals(incomingChannel)) return CHANNEL_3DSKIN_S2C;
        return incomingChannel;
    }

    /**
     * 构造 opCode 3 模型同步包。
     *
     * 1.20.1 (3d-skin:* / mmdskin:network_pack)：
     * - int opCode
     * - UUID(msb/lsb)
     * - utf string (VarInt 长度 + bytes)
     *
     * 1.21.1 Fabric (mmdskin:network)：固定字段
     * - int opCode
     * - UUID(msb/lsb)
     * - int intArg
     * - int entityId
     * - utf string
     */
    private byte[] createModelSyncPacket(String channel, UUID uuid, String modelName) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(baos);

        out.writeInt(3); // opCode 3
        out.writeLong(uuid.getMostSignificantBits());
        out.writeLong(uuid.getLeastSignificantBits());

        if (CHANNEL_MMDSKIN_NETWORK.equals(channel)) {
            // 1.21.1 Fabric 固定字段
            out.writeInt(0); // intArg
            out.writeInt(0); // entityId
        }

        writeString(out, modelName == null ? "" : modelName);
        return baos.toByteArray();
    }

    // ==================== Payload 解析与 VarInt/UTF ====================

    private record ParsedHeader(int opCode, UUID playerUUID) {
    }

    private record ParsedPayload(int opCode, UUID playerUUID, String stringArg, int intArg, String platform, String hwid) {
        // 快捷构造函数，兼容旧代码
        ParsedPayload(int opCode, UUID playerUUID, String stringArg, int intArg) {
            this(opCode, playerUUID, stringArg, intArg, null, null);
        }
    }

    /**
     * 仅解析最小头部：opCode + UUID。
     *
     * 用途：
     * - 转发器必须“尽量不丢包”，但又要识别 opCode 10（请求模型信息）避免广播。
     * - 某些 opCode 的后续字段长度/类型在不同端实现存在差异，完整 parse 失败不应阻止转发。
     */
    private ParsedHeader parseHeader(byte[] message) throws IOException {
        if (message == null) throw new IOException("message is null");
        if (message.length < 4 + 16) throw new IOException("message too short: " + message.length);
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(message));
        int opCode = in.readInt();
        UUID playerUUID = new UUID(in.readLong(), in.readLong());
        return new ParsedHeader(opCode, playerUUID);
    }

    /**
     * 解析客户端发送的 payload。
     *
     * 对齐 MC-MMD-rust 的网络包逻辑：
     * - opCode
     * - UUID
     * - 字段根据 opCode 决定读取 string 或 int 或 (int+string)
     */
    private ParsedPayload parsePayload(String channel, byte[] message) throws IOException {
        ByteArrayInputStream bais = new ByteArrayInputStream(message);
        bais.mark(message.length);
        DataInputStream in = new DataInputStream(bais);

        int opCode = in.readInt();
        UUID playerUUID = new UUID(in.readLong(), in.readLong());

        // MMDSync 握手包 (1.21.1+)
        if (CHANNEL_MMDSYNC_HANDSHAKE.equals(channel)) {
            // HandshakePacket: opCode(int) + UUID + publicKey(string) + platform(string) + hwid(string)
            String publicKey = readString(in);
            String platform = readString(in);
            String hwid = readString(in);
            return new ParsedPayload(opCode, playerUUID, publicKey, 0, platform, hwid);
        }

        // 1.21.1 Fabric: 固定格式 opCode+UUID+intArg+entityId+stringArg
        if (CHANNEL_MMDSKIN_NETWORK.equals(channel)) {
            int intArg = in.readInt();
            int entityId = in.readInt();
            String s = readString(in);
            return new ParsedPayload(opCode, playerUUID, s, intArg);
        }

        // 1.20.1 / 1.21.1 NeoForge(network_pack): 变长格式

        // 字符串参数 opCodes
        if (opCode == 1 || opCode == 3 || opCode == 6 || opCode == 7 || opCode == 8 || opCode == 9) {
            String s = readString(in);
            return new ParsedPayload(opCode, playerUUID, s, 0);
        }

        // opCode 10: 有的版本当作 string，有的版本当作 int；这里只要识别到 10 即可
        if (opCode == 10) {
            if (bais.available() <= 0) {
                return new ParsedPayload(opCode, playerUUID, "", 0);
            }

            bais.mark(bais.available());
            try {
                String s = readString(in);
                return new ParsedPayload(opCode, playerUUID, s, 0);
            } catch (IOException ignored) {
                // 回退为 int
                bais.reset();
                int arg0 = in.readInt();
                return new ParsedPayload(opCode, playerUUID, null, arg0);
            }
        }

        // 女仆：entityId(int) + string
        if (opCode == 4 || opCode == 5) {
            int arg0 = in.readInt();
            String s = readString(in);
            return new ParsedPayload(opCode, playerUUID, s, arg0);
        }

        // 其他：int 参数
        int arg0 = in.readInt();
        return new ParsedPayload(opCode, playerUUID, null, arg0);
    }

    /** Custom String reading/writing for Minecraft protocol (VarInt length + UTF-8 bytes) */

    private String readString(DataInputStream in) throws IOException {
        int len = readVarInt(in);
        if (len < 0) throw new IOException("String length < 0");
        if (len > 32767) throw new IOException("String length too large: " + len);
        byte[] bytes = new byte[len];
        in.readFully(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private void writeString(DataOutputStream out, String s) throws IOException {
        byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        writeVarInt(out, bytes.length);
        out.write(bytes);
    }

    private int readVarInt(DataInputStream in) throws IOException {
        int numRead = 0;
        int result = 0;
        byte read;
        do {
            read = in.readByte();
            int value = (read & 0b01111111);
            result |= (value << (7 * numRead));

            numRead++;
            if (numRead > 5) {
                throw new IOException("VarInt is too big");
            }
        } while ((read & 0b10000000) != 0);

        return result;
    }

    private void writeVarInt(DataOutputStream out, int value) throws IOException {
        while (true) {
            if ((value & ~0x7F) == 0) {
                out.writeByte(value);
                return;
            } else {
                out.writeByte((value & 0x7F) | 0x80);
                value >>>= 7;
            }
        }
    }

    private void sendResponse(HttpExchange exchange, int code, byte[] data, String contentType) throws IOException {
        boolean useGzip = enableGzip && data.length > 1024;
        if (useGzip) {
            exchange.getResponseHeaders().set("Content-Encoding", "gzip");
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            try (GZIPOutputStream gzos = new GZIPOutputStream(bos)) {
                gzos.write(data);
            }
            data = bos.toByteArray();
        }

        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(code, data.length);

        OutputStream os = exchange.getResponseBody();
        if (maxBandwidthMbps > 0) {
            os = new ThrottledOutputStream(os, maxBandwidthMbps);
        }
        os.write(data);
        os.close();
    }

    private static class ThrottledOutputStream extends FilterOutputStream {
        private final double bytesPerSecond;
        private long bytesWritten = 0;
        private final long startTime;

        public ThrottledOutputStream(OutputStream out, double mbps) {
            super(out);
            this.bytesPerSecond = mbps * 1024 * 1024 / 8;
            this.startTime = System.currentTimeMillis();
        }

        @Override
        public void write(int b) throws IOException {
            throttle(1);
            out.write(b);
            bytesWritten++;
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            throttle(len);
            out.write(b, off, len);
            bytesWritten += len;
        }

        private void throttle(int len) throws IOException {
            if (bytesPerSecond <= 0) return;
            long now = System.currentTimeMillis();
            long elapsed = now - startTime;
            if (elapsed <= 0) return;
            double currentBps = (double) bytesWritten / (elapsed / 1000.0);
            if (currentBps > bytesPerSecond) {
                long expectedTime = (long) ((bytesWritten + len) / bytesPerSecond * 1000);
                long sleepTime = expectedTime - elapsed;
                if (sleepTime > 0) {
                    try { Thread.sleep(Math.min(sleepTime, 100)); } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException(e); }
                }
            }
        }
    }
}

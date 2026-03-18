package com.opdent.mmdskin.bukkit;

import com.opdent.mmdskin.bukkit.resource.BukkitResourceTransferCodec;
import com.tendoarisu.mmdskin.sync.util.MMDSyncNativeBridge;
import com.tendoarisu.mmdskin.sync.util.MMDSyncNativeLoader;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.plugin.messaging.PluginMessageListener;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.spec.X509EncodedKeySpec;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.stream.Stream;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Bukkit/Spigot/Paper 端的自定义 Payload 转发器。
 */
public class MmdSkinBukkit extends JavaPlugin implements PluginMessageListener, Listener, CommandExecutor, TabCompleter {

    private static final String CHANNEL_SYNC_URL = "mmdsync:sync_url";
    private File modelDir;
    private final Map<Path, CacheEntry> md5Cache = new ConcurrentHashMap<>();
    
    /**
     * 服务器全局同步 AES 密钥 (用于加密下发给客户端的 VMD/PMX/纹理)
     * 持久化在 config.yml 中，确保重启后客户端已下载的文件依然可用。
     */
    private byte[] serverSyncKey;
    private final SecureRandom secureRandom = new SecureRandom();
    private final Map<UUID, PendingHandshake> pendingHandshakes = new ConcurrentHashMap<>();
    private static final long HANDSHAKE_TTL_MS = 60_000L;
    private static final String CHANNEL_MMDSYNC_RESOURCE = "mmdsync:resource_transfer";
    private static final int RESOURCE_CHUNK_SIZE = 24 * 1024;
    private final Map<String, ResourceUploadSession> resourceUploadSessions = new ConcurrentHashMap<>();

    private static final byte[] MMDARC_HEADER = "MMDARC".getBytes(StandardCharsets.UTF_8);
    private static final byte MMDARC_VERSION = 0x01;

    private static class PendingHandshake {
        final String challenge;
        final long expireAt;

        PendingHandshake(String challenge, long expireAt) {
            this.challenge = challenge;
            this.expireAt = expireAt;
        }
    }

    private static class ResourceUploadSession {
        final UUID playerUuid;
        final String zone;
        final String folderName;
        final String relativePath;
        final Path tempFile;

        ResourceUploadSession(UUID playerUuid, String zone, String folderName, String relativePath, Path tempFile) {
            this.playerUuid = playerUuid;
            this.zone = zone;
            this.folderName = folderName;
            this.relativePath = relativePath;
            this.tempFile = tempFile;
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
        incomingChannels.add(CHANNEL_MMDSYNC_RESOURCE);

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
        this.getServer().getMessenger().registerOutgoingPluginChannel(this, CHANNEL_MMDSYNC_RESOURCE);

        // 加载或生成服务器同步密钥
        loadSyncKey();

        // Model synchronization logic
        if (getConfig().getBoolean("sync.enabled", true)) {
            loadCache();
            md5Cache.clear();

            // 固定为服务器根目录下的 3d-skin
            modelDir = new File(getServer().getWorldContainer(), "3d-skin");
            if (!modelDir.exists()) modelDir.mkdirs();
        }

        // Register events
        this.getServer().getPluginManager().registerEvents(this, this);
        // Register command
        this.getCommand("mmdsync").setExecutor(this);
        this.getCommand("mmdsync").setTabCompleter(this);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("mmdsync.admin")) {
            sender.sendMessage("§c你没有权限使用这个命令。");
            return true;
        }

        if (args.length == 0) {
            return executeSyncCommand(sender, false);
        }

        if (args[0].equalsIgnoreCase("reload")) {
            sender.sendMessage("§a正在重载 MmdSkin-Bukkit 配置...");
            reloadConfig();

            // Reload sync key
            loadSyncKey();
            
            saveCache();

            boolean syncEnabled = getConfig().getBoolean("sync.enabled", true);
            if (syncEnabled) {
                loadCache();
                md5Cache.clear();

                // 固定为服务器根目录下的 3d-skin
                modelDir = new File(getServer().getWorldContainer(), "3d-skin");
                if (!modelDir.exists()) modelDir.mkdirs();
            }

            // Resend URL to all players
            if (syncEnabled) {
                executeSyncCommand(sender, true);
            } else {
                sender.sendMessage("§a配置已重载，同步功能当前处于关闭状态。");
            }
            return true;
        }

        sender.sendMessage("§c未知子命令，仅支持 §e/mmdsync §c或 §e/mmdsync reload§c。");
        return true;
    }

    private boolean executeSyncCommand(CommandSender sender, boolean reloaded) {
        sender.sendMessage(reloaded ? "§a配置重载完成，正在向全服重新同步资源..." : "§a正在向全服同步 MMD 资源...");

        md5Cache.clear();
        loadCache();

        for (Player player : Bukkit.getOnlinePlayers()) {
            sendSyncUrl(player);

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

        sender.sendMessage("§a已向所有在线玩家下发新的同步指令。输入 §e/mmdsync reload §a可重载配置后再同步。");
        return true;
    }

    @Override
    public java.util.List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission("mmdsync.admin")) {
            return java.util.List.of();
        }
        if (args.length == 1) {
            String input = args[0].toLowerCase(Locale.ROOT);
            if ("reload".startsWith(input)) {
                return java.util.List.of("reload");
            }
        }
        return java.util.List.of();
    }

    // HTTP 资源服务已废弃，保留空白区域以避免大范围行号漂移

    private String getFolderMD5(Path folder, boolean forceFresh) {
        try (Stream<Path> stream = Files.walk(folder)) {
            if (forceFresh) {
                invalidateCacheUnder(folder);
            }
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

    private void invalidateCacheUnder(Path root) {
        try {
            Path normalizedRoot = root.normalize();
            md5Cache.entrySet().removeIf(entry -> entry.getKey().normalize().startsWith(normalizedRoot));
        } catch (Exception ignored) {
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
            sendSyncUrl(player);

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

    private byte[] getNativeResourceHash(String platform) {
        if (platform == null || platform.isEmpty()) return null;
        String normalized = platform.trim().toLowerCase(Locale.ROOT);
        String archSuffix = normalized.contains("arm") ? "arm64" : "x64";
        String folder;
        String fileName;
        if (normalized.startsWith("windows")) {
            folder = "windows-" + archSuffix;
            fileName = "mmdsync_bridge.dll";
        } else if (normalized.startsWith("macos") || normalized.startsWith("osx")) {
            folder = "macos-" + archSuffix;
            fileName = "libmmdsync_bridge.dylib";
        } else if (normalized.startsWith("linux")) {
            folder = "linux-" + archSuffix;
            fileName = "libmmdsync_bridge.so";
        } else {
            return null;
        }
        String resourcePath = "/natives/" + folder + "/" + fileName;
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
        String encryptedAesKeyBase64 = rsaEncryptJava(serverSyncKey, expectedHandshakePem);
        sendSyncUrl(player, encryptedAesKeyBase64, pending.challenge);
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
        if (player == null || !player.isOnline()) {
            return;
        }
        String challenge = issueHandshakeChallenge(player.getUniqueId());
        sendSyncUrl(player, "", challenge);
    }

    private void sendSyncUrl(Player player, String encryptedKey, String handshakeContext) {
        if (player == null || !player.isOnline()) {
            return;
        }
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             DataOutputStream dos = new DataOutputStream(baos)) {
            // 对齐 common 模块中的 SyncUrlPacket (encryptedKey, serverSecret, serverId)
            // serverSecret 即为 handshakeContext (包含 challenge)
            writeString(dos, encryptedKey == null ? "" : encryptedKey);
            writeString(dos, handshakeContext == null ? "" : handshakeContext);
            writeString(dos, buildStableServerId());
            byte[] payload = baos.toByteArray();
            player.sendPluginMessage(this, CHANNEL_SYNC_URL, payload);
        } catch (IOException e) {
            getLogger().log(Level.SEVERE, "Error sending sync URL", e);
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        playerModels.remove(uuid);
        pendingHandshakes.remove(uuid);
        resourceUploadSessions.entrySet().removeIf(entry -> {
            if (!entry.getValue().playerUuid.equals(uuid)) {
                return false;
            }
            try {
                Files.deleteIfExists(entry.getValue().tempFile);
            } catch (IOException ignored) {
            }
            return true;
        });

        // 给其他客户端补发一次“清空模型”
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
        if (CHANNEL_MMDSYNC_RESOURCE.equals(channel)) {
            handleResourceTransferPacket(sender, message);
            return;
        }
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

    private void handleResourceTransferPacket(Player sender, byte[] message) {
        BukkitResourceTransferCodec.ResourcePacket packet;
        try {
            packet = BukkitResourceTransferCodec.decode(message);
        } catch (IOException e) {
            getLogger().log(Level.WARNING, "解析资源传输包失败: " + sender.getName(), e);
            return;
        }

        try {
            switch (packet.opCode()) {
                case BukkitResourceTransferCodec.MANIFEST -> sendResourceManifest(sender, packet.transferId());
                case BukkitResourceTransferCodec.REQUEST_CHUNK -> sendRequestedResourceChunks(sender, packet);
                case BukkitResourceTransferCodec.UPLOAD_BEGIN -> beginResourceUpload(sender, packet);
                case BukkitResourceTransferCodec.UPLOAD_CHUNK -> appendResourceUploadChunk(sender, packet);
                case BukkitResourceTransferCodec.UPLOAD_FINISH -> finishResourceUpload(sender, packet.transferId());
                case BukkitResourceTransferCodec.ABORT -> abortResourceUpload(packet.transferId());
                default -> sendResourceAck(sender, packet.transferId(), "ignored");
            }
        } catch (Exception e) {
            getLogger().log(Level.WARNING, "处理资源传输包失败: " + sender.getName() + ", transferId=" + packet.transferId(), e);
            sendResourceAbort(sender, packet.transferId(), "server_error:" + e.getClass().getSimpleName());
        }
    }

    private void sendResourceManifest(Player player, String transferId) throws IOException {
        List<BukkitResourceTransferCodec.ManifestEntry> entries = buildResourceManifestEntries();
        sendResourcePacket(player, new BukkitResourceTransferCodec.ResourcePacket(
                BukkitResourceTransferCodec.MANIFEST,
                transferId,
                buildStableServerId(),
                "",
                "",
                "",
                0,
                0,
                0L,
                "",
                new byte[0],
                entries,
                "manifest"
        ));
    }

    private void sendRequestedResourceChunks(Player player, BukkitResourceTransferCodec.ResourcePacket packet) throws IOException {
        Path file = resolveResourceFile(packet.zone(), packet.folderName(), packet.relativePath());
        if (file == null || !Files.isRegularFile(file)) {
            sendResourceAbort(player, packet.transferId(), "not_found");
            return;
        }

        byte[] rawData = Files.readAllBytes(file);
        byte[] data = prepareTransferPayload(rawData, packet.relativePath());
        String digest = md5Hex(rawData);
        int chunkCount = Math.max(1, (data.length + RESOURCE_CHUNK_SIZE - 1) / RESOURCE_CHUNK_SIZE);
        for (int i = 0; i < chunkCount; i++) {
            int start = i * RESOURCE_CHUNK_SIZE;
            int end = Math.min(data.length, start + RESOURCE_CHUNK_SIZE);
            byte[] chunk = Arrays.copyOfRange(data, start, end);
            sendResourcePacket(player, new BukkitResourceTransferCodec.ResourcePacket(
                    BukkitResourceTransferCodec.CHUNK,
                    packet.transferId(),
                    buildStableServerId(),
                    packet.zone(),
                    packet.folderName(),
                    packet.relativePath(),
                    i,
                    chunkCount,
                    rawData.length,
                    digest,
                    chunk,
                    List.of(),
                    ""
            ));
        }
    }

    private void beginResourceUpload(Player sender, BukkitResourceTransferCodec.ResourcePacket packet) throws IOException {
        Path target = resolveResourceFile(packet.zone(), packet.folderName(), packet.relativePath());
        if (target == null) {
            sendResourceAbort(sender, packet.transferId(), "invalid_target");
            return;
        }

        String transferId = normalizeTransferId(packet.transferId());
        if (transferId.isEmpty()) {
            sendResourceAbort(sender, packet.transferId(), "invalid_transfer_id");
            return;
        }

        ResourceUploadSession old = resourceUploadSessions.remove(transferId);
        if (old != null) {
            Files.deleteIfExists(old.tempFile);
        }

        Path stagingDir = getDataFolder().toPath().resolve("resource-upload-staging");
        Files.createDirectories(stagingDir);
        Path tempFile = stagingDir.resolve(transferId + ".part");
        Files.deleteIfExists(tempFile);
        resourceUploadSessions.put(transferId, new ResourceUploadSession(
                sender.getUniqueId(),
                packet.zone(),
                packet.folderName(),
                packet.relativePath(),
                tempFile
        ));
        sendResourceAck(sender, transferId, "upload_begin_ok");
    }

    private void appendResourceUploadChunk(Player sender, BukkitResourceTransferCodec.ResourcePacket packet) throws IOException {
        ResourceUploadSession session = resourceUploadSessions.get(packet.transferId());
        if (session == null || !session.playerUuid.equals(sender.getUniqueId())) {
            sendResourceAbort(sender, packet.transferId(), "upload_session_missing");
            return;
        }

        Files.createDirectories(session.tempFile.getParent());
        Files.write(session.tempFile, packet.payload(), StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        if (packet.chunkIndex() + 1 >= packet.chunkCount()) {
            sendResourceAck(sender, packet.transferId(), "upload_chunks_received");
        }
    }

    private void finishResourceUpload(Player sender, String transferId) throws IOException {
        ResourceUploadSession session = resourceUploadSessions.remove(transferId);
        if (session == null || !session.playerUuid.equals(sender.getUniqueId())) {
            sendResourceAbort(sender, transferId, "upload_session_missing");
            return;
        }

        Path target = resolveResourceFile(session.zone, session.folderName, session.relativePath);
        if (target == null) {
            Files.deleteIfExists(session.tempFile);
            sendResourceAbort(sender, transferId, "invalid_target");
            return;
        }

        Files.createDirectories(target.getParent());
        Files.move(session.tempFile, target, StandardCopyOption.REPLACE_EXISTING);
        Path zoneRoot = resolveZoneRoot(session.zone);
        if (zoneRoot != null) {
            invalidateCacheUnder(zoneRoot);
        }
        sendResourceAck(sender, transferId, "upload_finish_ok");
    }

    private void abortResourceUpload(String transferId) {
        ResourceUploadSession session = resourceUploadSessions.remove(transferId);
        if (session == null) {
            return;
        }
        try {
            Files.deleteIfExists(session.tempFile);
        } catch (IOException ignored) {
        }
    }

    private List<BukkitResourceTransferCodec.ManifestEntry> buildResourceManifestEntries() {
        List<BukkitResourceTransferCodec.ManifestEntry> entries = new ArrayList<>();
        appendResourceManifestEntries(entries, "pmx", resolveZoneRoot("pmx"));
        appendResourceManifestEntries(entries, "vmd", resolveZoneRoot("vmd"));
        return entries;
    }

    private void appendResourceManifestEntries(List<BukkitResourceTransferCodec.ManifestEntry> entries, String zone, Path zoneRoot) {
        if (zoneRoot == null || !Files.isDirectory(zoneRoot)) {
            return;
        }

        try (Stream<Path> stream = Files.walk(zoneRoot)) {
            stream.filter(Files::isRegularFile)
                    .sorted()
                    .forEach(path -> {
                        try {
                            String relative = zoneRoot.relativize(path).toString().replace('\\', '/');
                            String[] parts = relative.split("/", 2);
                            if (parts.length < 2) {
                                return;
                            }
                            byte[] rawData = Files.readAllBytes(path);
                            byte[] payload = prepareTransferPayload(rawData, parts[1]);
                            entries.add(new BukkitResourceTransferCodec.ManifestEntry(
                                    zone,
                                    parts[0],
                                    parts[1],
                                    rawData.length,
                                    md5Hex(rawData)
                            ));
                        } catch (Exception e) {
                            getLogger().log(Level.WARNING, "构建资源清单时跳过文件: " + path, e);
                        }
                    });
        } catch (IOException e) {
            getLogger().log(Level.WARNING, "扫描资源清单失败: " + zoneRoot, e);
        }
    }

    private Path resolveZoneRoot(String zone) {
        if (modelDir == null || zone == null) {
            return null;
        }
        return switch (zone) {
            case "pmx" -> new File(modelDir, "EntityPlayer").toPath();
            case "vmd" -> new File(modelDir, "StageAnim").toPath();
            default -> null;
        };
    }

    private Path resolveResourceFile(String zone, String folderName, String relativePath) {
        Path zoneRoot = resolveZoneRoot(zone);
        if (zoneRoot == null) {
            return null;
        }

        String safeFolderName = folderName == null ? "" : folderName.strip();
        String safeRelativePath = sanitizeTransferPath(relativePath);
        if (safeFolderName.isEmpty() || safeRelativePath.isEmpty()) {
            return null;
        }

        Path resolved = zoneRoot.resolve(safeFolderName).resolve(safeRelativePath).normalize();
        if (!resolved.startsWith(zoneRoot.normalize())) {
            return null;
        }
        return resolved;
    }

    private String sanitizeTransferPath(String relativePath) {
        if (relativePath == null) {
            return "";
        }
        return relativePath.replace('\\', '/').replace("..", "").replaceFirst("^/+", "");
    }

    private String normalizeTransferId(String transferId) {
        if (transferId == null) {
            return "";
        }
        return transferId.trim().replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private void sendResourcePacket(Player player, BukkitResourceTransferCodec.ResourcePacket packet) throws IOException {
        if (player == null || !player.isOnline()) {
            return;
        }
        byte[] encoded = BukkitResourceTransferCodec.encode(packet);
        player.sendPluginMessage(this, CHANNEL_MMDSYNC_RESOURCE, encoded);
    }

    private void sendResourceAck(Player player, String transferId, String message) throws IOException {
        sendResourcePacket(player, new BukkitResourceTransferCodec.ResourcePacket(
                BukkitResourceTransferCodec.ACK,
                transferId,
                buildStableServerId(),
                "",
                "",
                "",
                0,
                0,
                0L,
                "",
                new byte[0],
                List.of(),
                message
        ));
    }

    private void sendResourceAbort(Player player, String transferId, String message) {
        try {
            sendResourcePacket(player, new BukkitResourceTransferCodec.ResourcePacket(
                    BukkitResourceTransferCodec.ABORT,
                    transferId,
                    buildStableServerId(),
                    "",
                    "",
                    "",
                    0,
                    0,
                    0L,
                    "",
                    new byte[0],
                    List.of(),
                    message
            ));
        } catch (IOException e) {
            getLogger().log(Level.WARNING, "发送资源中止包失败", e);
        }
    }

    private byte[] prepareTransferPayload(byte[] data, String relativePath) {
        String lower = relativePath == null ? "" : relativePath.toLowerCase(Locale.ROOT);
        if (serverSyncKey == null || !shouldEncryptTransferredFile(lower) || isEncryptedArchive(data)) {
            return data;
        }

        byte[] encrypted = MMDSyncNativeBridge.aesEncrypt(data, serverSyncKey);
        if (encrypted == null) {
            return data;
        }

        byte[] result = new byte[MMDARC_HEADER.length + 1 + encrypted.length];
        System.arraycopy(MMDARC_HEADER, 0, result, 0, MMDARC_HEADER.length);
        result[MMDARC_HEADER.length] = MMDARC_VERSION;
        System.arraycopy(encrypted, 0, result, MMDARC_HEADER.length + 1, encrypted.length);
        return result;
    }

    private boolean shouldEncryptTransferredFile(String lowerName) {
        return lowerName.endsWith(".pmx") || lowerName.endsWith(".pmd") || lowerName.endsWith(".vrm")
                || lowerName.endsWith(".vmd") || lowerName.endsWith(".fbx")
                || lowerName.endsWith(".png") || lowerName.endsWith(".jpg")
                || lowerName.endsWith(".jpeg") || lowerName.endsWith(".tga");
    }

    private boolean isEncryptedArchive(byte[] data) {
        return data.length >= 7
                && data[0] == 'M' && data[1] == 'M' && data[2] == 'D'
                && data[3] == 'A' && data[4] == 'R' && data[5] == 'C';
    }

    private String md5Hex(byte[] data) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("MD5");
            digest.update(data);
            StringBuilder sb = new StringBuilder();
            for (byte b : digest.digest()) {
                sb.append(String.format(Locale.ROOT, "%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IOException("计算 MD5 失败", e);
        }
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
            // 对齐 MC-MMD-rust/fabric: MmdSkinPayload.write
            // - int opCode
            // - UUID
            // - int intArg
            // - int entityId
            // - String stringArg (FriendlyByteBuf#writeUtf: VarInt len + bytes)
            // - byte[] binaryData (FriendlyByteBuf#writeByteArray: VarInt len + bytes)
            out.writeInt(0); // intArg
            out.writeInt(0); // entityId
        }

        writeString(out, modelName == null ? "" : modelName);

        if (CHANNEL_MMDSKIN_NETWORK.equals(channel)) {
            // 追加空 binaryData，否则客户端 MmdSkinPayload.read() 在 readByteArray() 会越界崩溃
            writeVarInt(out, 0);
        }

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
}

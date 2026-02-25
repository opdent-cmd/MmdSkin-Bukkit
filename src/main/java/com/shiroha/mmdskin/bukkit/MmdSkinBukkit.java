package com.opdent.mmdskin.bukkit;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.plugin.messaging.PluginMessageListener;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * Bukkit/Spigot/Paper 端的自定义 Payload 转发器。
 *
 * 该插件承担“无 Mod 服务端”时的中继职责：
 * - 客户端(Mod) -> 服务端(Bukkit) 发送自定义 Payload
 * - 插件转发给其他客户端(Mod)
 *
 * 重要：Payload 的二进制结构必须与客户端一致，否则会导致“丢包”（解析异常直接被丢弃）。
 */
public class MmdSkinBukkit extends JavaPlugin implements PluginMessageListener, Listener {

    /**
     * 调试开关：在 config.yml 里配置。
     * 说明：用户反馈“Bukkit 中继没声音，但服务端装 Mod 正常”，最常见原因就是中继端丢包/发错通道。
     * 打开 debug 后会把每次收到/转发的关键信息输出到服务端控制台。
     */
    private boolean debugEnabled = false;

    /** 每条 payload 最多打印多少字节的 hex（0=不打印 hex） */
    private int debugPayloadHexMaxBytes = 0;

    /** 是否打印每条消息的转发接收者名单（可能非常刷屏） */
    private boolean debugLogRecipients = false;

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

    @Override
    public void onEnable() {
        // config.yml
        saveDefaultConfig();
        reloadConfig();
        debugEnabled = getConfig().getBoolean("debug.enabled", false);
        debugPayloadHexMaxBytes = Math.max(0, getConfig().getInt("debug.payloadHexMaxBytes", 0));
        debugLogRecipients = getConfig().getBoolean("debug.logRecipients", false);

        // 只覆盖仓库内的两代协议（1.20.1 / 1.21.1），避免过多“历史兼容”冗余

        // 监听多个通道，兼容 Fabric/Forge/NeoForge 的不同 channel
        incomingChannels.add(CHANNEL_MMDSKIN_NETWORK);
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

        // Register events
        this.getServer().getPluginManager().registerEvents(this, this);

        getLogger().info("MmdSkin Bukkit enabled. Incoming=" + incomingChannels + ", Outgoing=" + preferredOutgoingChannels
                + ", debug=" + debugEnabled + ", hexMaxBytes=" + debugPayloadHexMaxBytes + ", logRecipients=" + debugLogRecipients);
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
            // 对每个 outgoing 通道，若玩家声明监听，则补发一遍（只会命中其实际监听的通道）
            for (String ch : preferredOutgoingChannels) {
                if (!isPlayerListening(player, ch)) continue;

                playerModels.forEach((uuid, modelName) -> {
                    try {
                        byte[] data = createModelSyncPacket(ch, uuid, modelName);
                        player.sendPluginMessage(this, ch, data);
                        if (debugEnabled) {
                            getLogger().info("[MmdSkin][DBG] join-sync to=" + player.getName() + " via=" + ch + " uuid=" + uuid + " model='" + (modelName == null ? "" : modelName) + "'");
                        }
                    } catch (IOException e) {
                        getLogger().log(Level.SEVERE, "Error creating sync packet for " + uuid, e);
                    }
                });
            }
        }, 20L);
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        playerModels.remove(uuid);

        // 给其他客户端补发一次“清空模型”（可选，但能避免部分客户端缓存残留）
        for (String ch : preferredOutgoingChannels) {
            try {
                byte[] data = createModelSyncPacket(ch, uuid, "");
                int forwarded = broadcastPacket(Set.of(ch), data, null, uuid);
                if (debugEnabled) {
                    getLogger().info("[MmdSkin][DBG] quit-clear uuid=" + uuid + " via=" + ch + " forwarded=" + forwarded);
                }
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
            debugLogMessage("RECV", channel, outgoing, sender, null, message, false, 0);
            int forwarded = broadcastPacket(Set.of(outgoing), message, sender.getUniqueId(), null);
            if (debugEnabled) {
                debugLogMessage("FWD", channel, outgoing, sender, null, message, false, forwarded);
            }
            return;
        }

        if (debugEnabled) {
            debugLogMessage("RECV", channel, outgoing, sender, header, message, true, 0);

            // 额外解析：把关键字符串内容打出来（舞台开始/音频/结束），便于确认客户端到底发送了什么。
            // 解析失败也不影响转发。
            if (header.opCode == 7 || header.opCode == 8 || header.opCode == 9) {
                try {
                    ParsedPayload parsed = parsePayload(channel, message);
                    String s = parsed.stringArg;
                    if (s == null) s = "";
                    // 避免过长刷屏
                    String preview = s.length() > 200 ? (s.substring(0, 200) + "...") : s;
                    getLogger().info("[MmdSkin][DBG] op" + header.opCode + " string='" + preview + "'");
                } catch (Exception e) {
                    getLogger().info("[MmdSkin][DBG] op" + header.opCode + " string parse failed: " + e.getMessage());
                }
            }
        }

        // opCode 10: 客户端请求所有玩家的模型信息。只需服务器响应，不应广播给其他客户端。
        if (header.opCode == 10) {
            if (debugEnabled) {
                getLogger().info("[MmdSkin][DBG] op10 model request from " + sender.getName() + "(" + sender.getUniqueId() + ") on " + channel);
            }
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
                if (debugEnabled) {
                    getLogger().info("[MmdSkin][DBG] cached model op3 uuid=" + parsed.playerUUID + " model='" + (modelName == null ? "" : modelName) + "'");
                }
            } catch (IOException e) {
                // 不影响转发；仅意味着我们无法为该玩家缓存模型名。
                getLogger().log(Level.FINE, "Model sync payload parsed failed from " + sender.getName() + " on " + channel + ": " + e.getMessage());
                if (debugEnabled) {
                    getLogger().info("[MmdSkin][DBG] op3 parse failed: " + e.getMessage());
                }
            }
        }

        int forwarded = broadcastPacket(Set.of(outgoing), message, sender.getUniqueId(), null);
        if (debugEnabled) {
            debugLogMessage("FWD", channel, outgoing, sender, header, message, true, forwarded);
        }
    }

    private void sendAllModelsToPlayer(Player player) {
        for (String ch : preferredOutgoingChannels) {
            if (!isPlayerListening(player, ch)) continue;

            playerModels.forEach((uuid, modelName) -> {
                try {
                    byte[] data = createModelSyncPacket(ch, uuid, modelName);
                    player.sendPluginMessage(this, ch, data);
                    if (debugEnabled) {
                        getLogger().info("[MmdSkin][DBG] op10-sync to=" + player.getName() + " via=" + ch + " uuid=" + uuid + " model='" + (modelName == null ? "" : modelName) + "'");
                    }
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
                if (debugEnabled && debugLogRecipients) {
                    getLogger().info("[MmdSkin][DBG]   -> " + p.getName() + "(" + p.getUniqueId() + ")");
                }
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

    private record ParsedPayload(int opCode, UUID playerUUID, String stringArg, int intArg) {
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

    // ==================== Debug helpers ====================

    private void debugLogMessage(String tag,
                                 String incomingChannel,
                                 String outgoingChannel,
                                 Player sender,
                                 ParsedHeader header,
                                 byte[] message,
                                 boolean headerOk,
                                 int forwardedPlayers) {
        if (!debugEnabled) return;

        int len = (message == null) ? 0 : message.length;
        String op = headerOk && header != null ? String.valueOf(header.opCode) : "?";
        String uuid = headerOk && header != null ? String.valueOf(header.playerUUID) : "?";

        StringBuilder sb = new StringBuilder();
        sb.append("[MmdSkin][DBG]")
                .append(' ').append(tag)
                .append(" ch=").append(incomingChannel)
                .append(" -> ").append(outgoingChannel)
                .append(" from=").append(sender == null ? "?" : sender.getName())
                .append("(").append(sender == null ? "?" : sender.getUniqueId()).append(')')
                .append(" op=").append(op)
                .append(" uuid=").append(uuid)
                .append(" len=").append(len);

        if ("FWD".equals(tag)) {
            sb.append(" forwardedPlayers=").append(forwardedPlayers);
        }

        if (!headerOk) {
            sb.append(" headerOk=false");
        }

        if (debugPayloadHexMaxBytes > 0 && message != null && message.length > 0) {
            int n = Math.min(debugPayloadHexMaxBytes, message.length);
            sb.append(" hex[").append(n).append("]=").append(toHex(message, n));
        }

        getLogger().info(sb.toString());
    }

    private String toHex(byte[] data, int max) {
        int n = Math.min(max, data.length);
        StringBuilder sb = new StringBuilder(n * 3);
        for (int i = 0; i < n; i++) {
            int v = data[i] & 0xFF;
            if (i > 0) sb.append(' ');
            if (v < 16) sb.append('0');
            sb.append(Integer.toHexString(v));
        }
        return sb.toString();
    }
}

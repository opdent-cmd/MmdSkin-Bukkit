package com.shiroha.mmdskin.bukkit;

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
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

public class MmdSkinBukkit extends JavaPlugin implements PluginMessageListener, Listener {

    private static final String CHANNEL = "mmdskin:network";
    // Store player models: UUID -> ModelName
    private final Map<UUID, String> playerModels = new ConcurrentHashMap<>();

    @Override
    public void onEnable() {
        // Register plugin channels
        this.getServer().getMessenger().registerIncomingPluginChannel(this, CHANNEL, this);
        this.getServer().getMessenger().registerOutgoingPluginChannel(this, CHANNEL);
        
        // Register events
        this.getServer().getPluginManager().registerEvents(this, this);
        
        getLogger().info("MmdSkin Bukkit enabled. Channel: " + CHANNEL);
    }

    @Override
    public void onDisable() {
        this.getServer().getMessenger().unregisterIncomingPluginChannel(this);
        this.getServer().getMessenger().unregisterOutgoingPluginChannel(this);
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        // Send all existing models to the new player
        Player player = event.getPlayer();
        // Delay slightly to ensure client is ready? Usually not needed for plugin messages but safe.
        // But for join, the client might not have initialized the mod yet?
        // The mod listens to packets.
        
        // Send all known models to this player
        playerModels.forEach((uuid, modelName) -> {
            try {
                byte[] data = createModelSyncPacket(uuid, modelName);
                player.sendPluginMessage(this, CHANNEL, data);
            } catch (IOException e) {
                getLogger().log(Level.SEVERE, "Error creating sync packet for " + uuid, e);
            }
        });
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        // Remove player from model map
        UUID uuid = event.getPlayer().getUniqueId();
        playerModels.remove(uuid);
        
        // Optionally broadcast that the player left? 
        // The client handles player removal logic via standard tab list usually, 
        // but for MMD models, if the player entity is removed, the renderer stops.
        // However, we should probably clear the memory on other clients?
        // The mod has `onPlayerLeave` in `PlayerModelSyncManager`.
        // But that's triggered by standard player removal or disconnect?
        // The `PlayerModelSyncManager` has `onPlayerLeave(UUID)`.
        // We don't necessarily need to send a packet for this if the client hooks into standard disconnect events.
        // But if we want to be explicit, we could send a packet with empty model name.
        
        // Let's send a clear packet just in case
        try {
            byte[] data = createModelSyncPacket(uuid, "");
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (!p.getUniqueId().equals(uuid)) {
                    p.sendPluginMessage(this, CHANNEL, data);
                }
            }
        } catch (IOException e) {
            getLogger().log(Level.SEVERE, "Error sending clear packet", e);
        }
    }

    @Override
    public void onPluginMessageReceived(String channel, Player player, byte[] message) {
        if (!channel.equals(CHANNEL)) return;

        try {
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(message));
            
            // Read fields manually matching FriendlyByteBuf structure
            int opCode = in.readInt();
            UUID packetPlayerUUID = new UUID(in.readLong(), in.readLong());
            int intArg = in.readInt();
            int entityId = in.readInt();
            String stringArg = readString(in);

            // Handle OpCode 3: Model Sync
            if (opCode == 3) {
                // If the packet is about the sender (player), store it
                // We assume the packetPlayerUUID matches the sender or is a valid update
                
                if (stringArg == null || stringArg.isEmpty()) {
                    playerModels.remove(packetPlayerUUID);
                    getLogger().info("Player " + packetPlayerUUID + " cleared model.");
                } else {
                    playerModels.put(packetPlayerUUID, stringArg);
                    getLogger().info("Player " + packetPlayerUUID + " selected model: " + stringArg);
                }
                
                // Broadcast to other players
                broadcastPacket(message, player);
            } else {
                // For other OpCodes (Animation, Physics, etc.), just relay to others
                // This allows players to see each other's animations
                broadcastPacket(message, player);
            }

        } catch (IOException e) {
            getLogger().log(Level.SEVERE, "Error reading plugin message from " + player.getName(), e);
        }
    }

    private void broadcastPacket(byte[] message, Player sender) {
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.getUniqueId().equals(sender.getUniqueId())) continue;
            p.sendPluginMessage(this, CHANNEL, message);
        }
    }

    private byte[] createModelSyncPacket(UUID uuid, String modelName) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(baos);
        
        out.writeInt(3); // OpCode 3
        out.writeLong(uuid.getMostSignificantBits());
        out.writeLong(uuid.getLeastSignificantBits());
        out.writeInt(0); // intArg
        out.writeInt(0); // entityId
        writeString(out, modelName);
        
        return baos.toByteArray();
    }

    // Custom String reading/writing for Minecraft protocol (VarInt length + UTF-8 bytes)

    private String readString(DataInputStream in) throws IOException {
        int len = readVarInt(in);
        if (len < 0) throw new IOException("The received encoded string buffer length is less than zero! Weird string!");
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

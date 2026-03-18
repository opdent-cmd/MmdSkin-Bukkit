package com.opdent.mmdskin.bukkit.resource;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Bukkit 端的资源传输协议二进制编解码器。
 *
 * 注意：必须与 common 模块中的 ResourceTransferPacket 逻辑一致，以确保跨平台互通。
 */
public final class BukkitResourceTransferCodec {
    public static final int MANIFEST = 1;
    public static final int REQUEST_CHUNK = 2;
    public static final int CHUNK = 3;
    public static final int UPLOAD_BEGIN = 4;
    public static final int UPLOAD_CHUNK = 5;
    public static final int UPLOAD_FINISH = 6;
    public static final int ABORT = 7;
    public static final int ACK = 8;

    private BukkitResourceTransferCodec() {
    }

    public record ManifestEntry(String zone, String folderName, String relativePath, long size, String sha256) {
    }

    public record ResourcePacket(
            int opCode,
            String transferId,
            String serverId,
            String zone,
            String folderName,
            String relativePath,
            int chunkIndex,
            int chunkCount,
            long totalSize,
            String digest,
            byte[] payload,
            List<ManifestEntry> manifestEntries,
            String message
    ) {
    }

    public static byte[] encode(ResourcePacket packet) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(baos);
        out.writeInt(packet.opCode());
        writeString(out, packet.transferId());
        writeString(out, packet.serverId());
        writeString(out, packet.zone());
        writeString(out, packet.folderName());
        writeString(out, packet.relativePath());
        out.writeInt(packet.chunkIndex());
        out.writeInt(packet.chunkCount());
        writeVarLong(out, packet.totalSize());
        writeString(out, packet.digest());
        writeBytes(out, packet.payload());

        String manifestJson = encodeManifestEntries(packet.manifestEntries());
        writeString(out, manifestJson);
        writeString(out, packet.message());

        out.flush();
        return baos.toByteArray();
    }

    public static ResourcePacket decode(byte[] data) throws IOException {
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(data));
        int opCode = in.readInt();
        String transferId = readString(in);
        String serverId = readString(in);
        String zone = readString(in);
        String folderName = readString(in);
        String relativePath = readString(in);
        int chunkIndex = in.readInt();
        int chunkCount = in.readInt();
        long totalSize = readVarLong(in);
        String digest = readString(in);
        byte[] payload = readBytes(in);

        String manifestJson = readString(in);
        List<ManifestEntry> entries = decodeManifestEntries(manifestJson);
        String message = readString(in);

        return new ResourcePacket(
                opCode,
                transferId,
                serverId,
                zone,
                folderName,
                relativePath,
                chunkIndex,
                chunkCount,
                totalSize,
                digest,
                payload,
                entries,
                message
        );
    }

    private static void writeString(DataOutputStream out, String value) throws IOException {
        byte[] bytes = value == null ? new byte[0] : value.getBytes(StandardCharsets.UTF_8);
        writeVarInt(out, bytes.length);
        out.write(bytes);
    }

    private static String readString(DataInputStream in) throws IOException {
        int length = readVarInt(in);
        if (length <= 0) {
            return "";
        }
        byte[] bytes = in.readNBytes(length);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static void writeBytes(DataOutputStream out, byte[] payload) throws IOException {
        if (payload == null || payload.length == 0) {
            writeVarInt(out, 0);
            return;
        }
        writeVarInt(out, payload.length);
        out.write(payload);
    }

    private static byte[] readBytes(DataInputStream in) throws IOException {
        int length = readVarInt(in);
        if (length <= 0) {
            return new byte[0];
        }
        return in.readNBytes(length);
    }

    private static void writeVarInt(DataOutputStream out, int value) throws IOException {
        while (true) {
            if ((value & ~0x7F) == 0) {
                out.writeByte(value);
                return;
            }
            out.writeByte((value & 0x7F) | 0x80);
            value >>>= 7;
        }
    }

    private static int readVarInt(DataInputStream in) throws IOException {
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

    private static void writeVarLong(DataOutputStream out, long value) throws IOException {
        while (true) {
            if ((value & ~0x7FL) == 0) {
                out.writeByte((int) value);
                return;
            }
            out.writeByte(((int) value & 0x7F) | 0x80);
            value >>>= 7;
        }
    }

    private static long readVarLong(DataInputStream in) throws IOException {
        int numRead = 0;
        long result = 0;
        byte read;
        do {
            read = in.readByte();
            long value = (read & 0b01111111);
            result |= (value << (7 * numRead));
            numRead++;
            if (numRead > 10) {
                throw new IOException("VarLong is too big");
            }
        } while ((read & 0b10000000) != 0);
        return result;
    }

    private static String encodeManifestEntries(List<ManifestEntry> entries) {
        if (entries == null || entries.isEmpty()) {
            return "[]";
        }
        StringBuilder sb = new StringBuilder();
        sb.append('[');
        boolean first = true;
        for (ManifestEntry entry : entries) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append('{');
            sb.append("\"zone\":\"").append(escapeJson(entry.zone())).append("\",");
            sb.append("\"folderName\":\"").append(escapeJson(entry.folderName())).append("\",");
            sb.append("\"relativePath\":\"").append(escapeJson(entry.relativePath())).append("\",");
            sb.append("\"size\":").append(entry.size()).append(',');
            sb.append("\"sha256\":\"").append(escapeJson(entry.sha256())).append("\"");
            sb.append('}');
        }
        sb.append(']');
        return sb.toString();
    }

    private static List<ManifestEntry> decodeManifestEntries(String json) {
        List<ManifestEntry> entries = new ArrayList<>();
        if (json == null || json.isBlank()) {
            return entries;
        }
        int idx = 0;
        while (idx < json.length()) {
            int start = json.indexOf('{', idx);
            if (start < 0) {
                break;
            }
            int end = findMatchingBrace(json, start);
            if (end < 0) {
                break;
            }
            String obj = json.substring(start + 1, end);
            entries.add(new ManifestEntry(
                    readJsonString(obj, "zone"),
                    readJsonString(obj, "folderName"),
                    readJsonString(obj, "relativePath"),
                    readJsonLong(obj, "size"),
                    readJsonString(obj, "sha256")
            ));
            idx = end + 1;
        }
        return entries;
    }

    private static int findMatchingBrace(String json, int start) {
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int i = start; i < json.length(); i++) {
            char c = json.charAt(i);
            if (escaped) {
                escaped = false;
                continue;
            }
            if (c == '\\') {
                if (inString) {
                    escaped = true;
                }
                continue;
            }
            if (c == '"') {
                inString = !inString;
                continue;
            }
            if (inString) {
                continue;
            }
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return -1;
    }

    private static String readJsonString(String obj, String key) {
        int keyIndex = obj.indexOf("\"" + key + "\"");
        if (keyIndex < 0) {
            return "";
        }
        int colon = obj.indexOf(':', keyIndex);
        if (colon < 0) {
            return "";
        }
        int i = colon + 1;
        while (i < obj.length() && Character.isWhitespace(obj.charAt(i))) {
            i++;
        }
        if (i >= obj.length() || obj.charAt(i) != '"') {
            return "";
        }
        i++;
        StringBuilder sb = new StringBuilder();
        boolean escaped = false;
        while (i < obj.length()) {
            char c = obj.charAt(i++);
            if (escaped) {
                switch (c) {
                    case '"' -> sb.append('"');
                    case '\\' -> sb.append('\\');
                    case 'n' -> sb.append('\n');
                    case 'r' -> sb.append('\r');
                    case 't' -> sb.append('\t');
                    case 'b' -> sb.append('\b');
                    case 'f' -> sb.append('\f');
                    case 'u' -> {
                        if (i + 3 < obj.length()) {
                            String hex = obj.substring(i, i + 4);
                            try {
                                sb.append((char) Integer.parseInt(hex, 16));
                            } catch (NumberFormatException ignored) {
                            }
                            i += 4;
                        }
                    }
                    default -> sb.append(c);
                }
                escaped = false;
                continue;
            }
            if (c == '\\') {
                escaped = true;
                continue;
            }
            if (c == '"') {
                break;
            }
            sb.append(c);
        }
        return sb.toString();
    }

    private static long readJsonLong(String obj, String key) {
        int keyIndex = obj.indexOf("\"" + key + "\"");
        if (keyIndex < 0) {
            return 0L;
        }
        int colon = obj.indexOf(':', keyIndex);
        if (colon < 0) {
            return 0L;
        }
        int i = colon + 1;
        while (i < obj.length() && Character.isWhitespace(obj.charAt(i))) {
            i++;
        }
        int start = i;
        while (i < obj.length()) {
            char c = obj.charAt(i);
            if (c == '-' || (c >= '0' && c <= '9')) {
                i++;
                continue;
            }
            break;
        }
        if (start == i) {
            return 0L;
        }
        try {
            return Long.parseLong(obj.substring(start, i));
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    private static String escapeJson(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(value.length() + 8);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
    }
}

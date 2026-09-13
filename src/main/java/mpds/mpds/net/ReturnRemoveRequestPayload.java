package mpds.mpds.net;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

public record ReturnRemoveRequestPayload(UUID requestId, List<Entry> entries) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<ReturnRemoveRequestPayload> PACKET_ID =
            new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath("mpds", "return_remove"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ReturnRemoveRequestPayload> codec =
            StreamCodec.ofMember(ReturnRemoveRequestPayload::write, ReturnRemoveRequestPayload::read);

    public static ReturnRemoveRequestPayload read(RegistryFriendlyByteBuf buf) {
        long msb = buf.readLong();
        long lsb = buf.readLong();
        UUID id = new UUID(msb, lsb);

        int count = buf.readInt();
        if (count < 0 || count > 32) {
            throw new IllegalArgumentException("pairCount out of range: " + count);
        }

        List<Entry> entries = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            String key = readStringBytes(buf, 128);
            String value = readStringBytes(buf, 256);
            entries.add(new Entry(key, value));
        }

        return new ReturnRemoveRequestPayload(id, entries);
    }

    public void write(RegistryFriendlyByteBuf buf) {
        buf.writeLong(requestId.getMostSignificantBits());
        buf.writeLong(requestId.getLeastSignificantBits());
        buf.writeInt(Math.min(entries.size(), 32));
        for (int i = 0; i < entries.size() && i < 32; i++) {
            Entry e = entries.get(i);
            writeStringBytes(buf, e.key(), 128);
            writeStringBytes(buf, e.value(), 256);
        }
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return PACKET_ID;
    }

    public record Entry(String key, String value) {
    }

    private static String readStringBytes(RegistryFriendlyByteBuf buf, int maxLen) {
        int len = buf.readInt();
        if (len < 0 || len > maxLen) {
            throw new IllegalArgumentException("String length out of range: " + len);
        }
        byte[] bytes = new byte[len];
        buf.readBytes(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static void writeStringBytes(RegistryFriendlyByteBuf buf, String value, int maxLen) {
        byte[] bytes = value == null ? new byte[0] : value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > maxLen) {
            throw new IllegalArgumentException("String too long: " + bytes.length);
        }
        buf.writeInt(bytes.length);
        buf.writeBytes(bytes);
    }
}

package mpds.mpds.net;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

public record ReturnRemoveResponsePayload(
        UUID requestId,
        boolean success,
        int removedInventory,
        int removedDb,
        int remainingInventory,
        int remainingDb,
        String error
) implements CustomPayload {
    public static final CustomPayload.Id<ReturnRemoveResponsePayload> PACKET_ID =
            new CustomPayload.Id<>(Identifier.of("mpds", "return_remove"));

    public static final PacketCodec<RegistryByteBuf, ReturnRemoveResponsePayload> codec =
            PacketCodec.of(ReturnRemoveResponsePayload::write, ReturnRemoveResponsePayload::read);

    public static ReturnRemoveResponsePayload read(RegistryByteBuf buf) {
        UUID id = new UUID(buf.readLong(), buf.readLong());
        boolean success = buf.readBoolean();
        int removedInv = buf.readInt();
        int removedDb = buf.readInt();
        int remainingInv = buf.readInt();
        int remainingDb = buf.readInt();
        String error = readStringBytes(buf, 8192);
        return new ReturnRemoveResponsePayload(id, success, removedInv, removedDb, remainingInv, remainingDb, error);
    }

    public void write(RegistryByteBuf buf) {
        buf.writeLong(requestId.getMostSignificantBits());
        buf.writeLong(requestId.getLeastSignificantBits());
        buf.writeBoolean(success);
        buf.writeInt(removedInventory);
        buf.writeInt(removedDb);
        buf.writeInt(remainingInventory);
        buf.writeInt(remainingDb);
        writeStringBytes(buf, error, 8192);
    }

    @Override
    public Id<? extends CustomPayload> getId() {
        return PACKET_ID;
    }

    private static String readStringBytes(RegistryByteBuf buf, int maxLen) {
        int len = buf.readInt();
        if (len < 0 || len > maxLen) {
            throw new IllegalArgumentException("String length out of range: " + len);
        }
        byte[] bytes = new byte[len];
        buf.readBytes(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static void writeStringBytes(RegistryByteBuf buf, String value, int maxLen) {
        byte[] bytes = value == null ? new byte[0] : value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > maxLen) {
            throw new IllegalArgumentException("String too long: " + bytes.length);
        }
        buf.writeInt(bytes.length);
        buf.writeBytes(bytes);
    }
}

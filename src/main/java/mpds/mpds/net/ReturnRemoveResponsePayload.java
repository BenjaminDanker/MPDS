package mpds.mpds.net;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

public record ReturnRemoveResponsePayload(
        UUID requestId,
        boolean success,
        int removedInventory,
        int removedDb,
        int remainingInventory,
        int remainingDb,
        String error
) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<ReturnRemoveResponsePayload> PACKET_ID =
            new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath("mpds", "return_remove"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ReturnRemoveResponsePayload> codec =
            StreamCodec.ofMember(ReturnRemoveResponsePayload::write, ReturnRemoveResponsePayload::read);

    public static ReturnRemoveResponsePayload read(RegistryFriendlyByteBuf buf) {
        UUID id = new UUID(buf.readLong(), buf.readLong());
        boolean success = buf.readBoolean();
        int removedInv = buf.readInt();
        int removedDb = buf.readInt();
        int remainingInv = buf.readInt();
        int remainingDb = buf.readInt();
        String error = readStringBytes(buf, 8192);
        return new ReturnRemoveResponsePayload(id, success, removedInv, removedDb, remainingInv, remainingDb, error);
    }

    public void write(RegistryFriendlyByteBuf buf) {
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
    public Type<? extends CustomPacketPayload> type() {
        return PACKET_ID;
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

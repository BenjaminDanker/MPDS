package mpds.mpds.events;

import mpds.mpds.MPDS;
import mpds.mpds.sql;
import mpds.mpds.sqlPlayer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;

import static mpds.mpds.MPDS.broken;

public final class Disconnect {
    private Disconnect() { }

    public static void ondisconnect(ServerGamePacketListenerImpl handler, MinecraftServer server) {
        var player = handler.getPlayer();
        var playerId = player.getUUID();
        String playerName = player.getName().getString();

        if (broken.remove(playerId)) {
            MPDS.LOGGER.warn("Not saving MPDS state for {} because authoritative data was never loaded; vanilla player state is untouched",
                    playerName);
            return;
        }

        try {
            // Capture on the server thread before disconnect cleanup; the durable queue retries the
            // idempotent UUID-keyed upsert after MariaDB returns, including across backend restarts.
            sql.queueDisconnectSave(new sqlPlayer(player));
            MPDS.LOGGER.info("Queued MPDS state save for {}", playerName);
        } catch (RuntimeException failure) {
            MPDS.LOGGER.error("Could not capture MPDS state for {}; vanilla state was not cleared", playerName, failure);
        }
    }
}

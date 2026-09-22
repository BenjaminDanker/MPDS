package mpds.mpds.events;

import mpds.mpds.MPDS;
import mpds.mpds.sql;
import mpds.mpds.sqlPlayer;
import net.fabricmc.fabric.api.networking.v1.PacketSender;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;

import java.sql.SQLException;
import java.util.Optional;

import static mpds.mpds.MPDS.ServerName;
import static mpds.mpds.MPDS.broken;

public final class Join {
    private Join() { }

    public static void onjoin(ServerGamePacketListenerImpl handler, PacketSender packetSender, MinecraftServer server) {
        ServerPlayer player = handler.getPlayer();
        broken.add(player.getUUID());
        Thread worker = new Thread(() -> loadPlayer(server, player), "MPDS-Load-" + player.getUUID());
        worker.setDaemon(true);
        worker.start();
    }

    private static void loadPlayer(MinecraftServer server, ServerPlayer player) {
        String name = player.getName().getString();
        String uuid = player.getStringUUID();
        long delayMillis = 1_000;
        int rowWaits = 0;

        while (server.isRunning()) {
            if (server.getPlayerList().getPlayer(player.getUUID()) != player) {
                broken.remove(player.getUUID());
                return;
            }
            try {
                sql.awaitReady();
                sql.flushPendingFor(player.getUUID());
                if (sql.isSkipped(name)) {
                    broken.remove(player.getUUID());
                    MPDS.LOGGER.info("Skipped MPDS load for {} by configured skip list", name);
                    return;
                }

                Optional<sql.StoredPlayer> found = sql.findPlayerData(uuid);
                if (found.isEmpty()) {
                    if (++rowWaits < 3) {
                        Thread.sleep(1_000);
                        continue;
                    }
                    sql.ensureRow(name, uuid);
                    sql.setServer(uuid);
                    broken.remove(player.getUUID());
                    MPDS.LOGGER.info("No MPDS record existed for {}; initialized one without replacing vanilla state", name);
                    return;
                }

                sql.StoredPlayer stored = found.orElseThrow();
                if ("false".equalsIgnoreCase(stored.sync())) {
                    if (++rowWaits < 3) {
                        Thread.sleep(1_000);
                        continue;
                    }
                    if (ServerName.equals(stored.server()) || "*".equals(stored.server())) {
                        broken.remove(player.getUUID());
                        MPDS.LOGGER.info("MPDS state for {} is already owned by this server", name);
                    } else {
                        MPDS.LOGGER.error("MPDS state for {} is still being synchronized by {}; refusing to apply or save stale data",
                                name, stored.server());
                    }
                    return;
                }

                sql.beFalse(uuid);
                sql.setServer(uuid);
                sqlPlayer.SqlRow row = stored.data();
                server.execute(() -> {
                    if (server.getPlayerList().getPlayer(player.getUUID()) != player) {
                        broken.remove(player.getUUID());
                        return;
                    }
                    try {
                        sqlPlayer.sqlToPlayer(player, row);
                        broken.remove(player.getUUID());
                        MPDS.LOGGER.info("Loaded authoritative MPDS state for {}", name);
                    } catch (Exception failure) {
                        MPDS.LOGGER.error("Failed to apply MPDS state for {}; retaining the no-save guard", name, failure);
                    }
                });
                return;
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            } catch (SQLException | RuntimeException failure) {
                MPDS.LOGGER.warn("MPDS load for {} is waiting for a usable database/state", name, failure);
                try {
                    Thread.sleep(delayMillis);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                }
                delayMillis = Math.min(30_000, delayMillis * 2);
            }
        }
        broken.remove(player.getUUID());
    }
}

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

    /**
     * Fabric fires JOIN before vanilla adds the player to the player list, so a list lookup would race and
     * wrongly report a connected player as gone, skipping the load and then saving an empty state over the
     * stored one. Only a disconnected player is gone.
     */
    private static boolean isGone(MinecraftServer server, ServerPlayer player) {
        return player.hasDisconnected() && server.getPlayerList().getPlayer(player.getUUID()) != player;
    }

    private static void loadPlayer(MinecraftServer server, ServerPlayer player) {
        String name = player.getName().getString();
        String uuid = player.getStringUUID();
        long delayMillis = 1_000;
        int rowWaits = 0;
        int lockWaits = 0;

        while (server.isRunning()) {
            if (isGone(server, player)) {
                // Leave the no-save guard to the disconnect handler, which clears it without saving.
                MPDS.LOGGER.warn("{} disconnected before MPDS state was loaded; nothing applied", name);
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
                    // Own the new row like any other, so a server switch before the first save waits for it
                    // instead of applying the row's empty stat columns.
                    if (!sql.tryLock(uuid)) {
                        continue;
                    }
                    broken.remove(player.getUUID());
                    MPDS.LOGGER.info("No MPDS record existed for {}; initialized one without replacing vanilla state", name);
                    return;
                }

                sql.StoredPlayer stored = found.orElseThrow();
                if ("false".equalsIgnoreCase(stored.sync())) {
                    if (ServerName.equals(stored.server()) || "*".equals(stored.server())) {
                        // This server held the lock and never released it (it crashed with the player online):
                        // its own player file is newer than the stored row, so keep it and own the row again.
                        if (ServerName.equals(stored.server())) sql.reclaim(uuid);
                        broken.remove(player.getUUID());
                        MPDS.LOGGER.info("MPDS state for {} is already owned by this server", name);
                        return;
                    }
                    // Another server still owns it: normally it saves within moments of the player leaving it.
                    if (++lockWaits < 15) {
                        Thread.sleep(1_000);
                        continue;
                    }
                    if (sql.takeOverStale(uuid, stored.server())) {
                        MPDS.LOGGER.warn("MPDS lock for {} held by {} stopped being renewed (that server died); "
                                + "taking over with the last saved state", name, stored.server());
                        Optional<sql.StoredPlayer> fresh = sql.findPlayerData(uuid);
                        if (fresh.isPresent()) apply(server, player, name, fresh.get().data());
                        return;
                    }
                    MPDS.LOGGER.error("MPDS state for {} is still being synchronized by {}; refusing to apply or save stale data",
                            name, stored.server());
                    return;
                }

                if (!sql.tryLock(uuid)) {
                    // Another server took it between the read and now; read again.
                    continue;
                }
                apply(server, player, name, stored.data());
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

    private static void apply(MinecraftServer server, ServerPlayer player, String name, sqlPlayer.SqlRow row) {
        server.execute(() -> {
            if (isGone(server, player)) {
                MPDS.LOGGER.warn("{} disconnected before MPDS state could be applied", name);
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
    }
}

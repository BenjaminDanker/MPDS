package mpds.mpds.events;

import com.mysql.cj.jdbc.exceptions.CommunicationsException;
import mpds.mpds.mixin.PlayerManagerInvoker;
import mpds.mpds.sql;
import mpds.mpds.sqlPlayer;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import java.sql.ResultSet;
import java.util.concurrent.CompletableFuture;

import static mpds.mpds.MPDS.*;
import static net.minecraft.sounds.SoundEvents.GLASS_BREAK;

public class Disconnect {

    public static void ondisconnect(ServerGamePacketListenerImpl serverPlayNetworkHandler, MinecraftServer minecraftServer) {
        ServerPlayer player = serverPlayNetworkHandler.getPlayer();
        String playerN = player.getName().getString();
        String playerUuid = player.getStringUUID();

        new Thread(() -> {
            LOGGER.info("saving {}'s data...", playerN);

            while (true) {
                try {
                    ResultSet checkskiprs = sql.checkSkip(playerN);

                    if (checkskiprs.next() && "true".equals(checkskiprs.getString("skip"))) {
                        if (ASM)
                            minecraftServer.execute(() -> minecraftServer.getPlayerList().broadcastSystemMessage(Component.translatable("skip saving because " + playerN + "'s data includes skip list").withStyle(ChatFormatting.YELLOW), false));
                        LOGGER.warn("skip saving because {}'s data includes skip list", playerN);

                        // player.playSound(BLOCK_GLASS_BREAK, 1f, 1f);
                        sql.beA(playerUuid);

                        return;
                    }

                    if (broken.contains(player.getUUID())) {
                        LOGGER.warn("skip saving because {}'s data was broken", playerN);
                        broken.remove(player.getUUID());

                        minecraftServer.execute(() -> {
                            try {
                                player.getInventory().clearContent();
                                player.getEnderChestInventory().clearContent();
                                player.removeAllEffects();
                                // Don't force-save here; vanilla already saves on disconnect and double-saving can race.
                            } catch (Exception e) {
                                LOGGER.error("Failed to clear broken data for {}:", playerN, e);
                            }
                        });

                        return;
                    }

                    CompletableFuture<sqlPlayer> snapshotFuture = new CompletableFuture<>();
                    minecraftServer.execute(() -> {
                        try {
                            snapshotFuture.complete(new sqlPlayer(player));
                        } catch (Exception e) {
                            snapshotFuture.completeExceptionally(e);
                        }
                    });

                    sqlPlayer snapshot = snapshotFuture.join();
                    sql.disconnect(snapshot);
                    LOGGER.info("success to save {}'s data", playerN);

                    return;
                } catch (CommunicationsException ignored) {
                } catch (Exception e) {
                    LOGGER.error("FAIL TO SAVE {}'s DATA:", playerN);
                    e.printStackTrace();

                    return;
                }
            }
        }).start();
    }
}

package mpds.mpds.events;

import com.mysql.cj.jdbc.exceptions.CommunicationsException;
import mpds.mpds.sql;
import mpds.mpds.sqlPlayer;
import net.fabricmc.fabric.api.networking.v1.PacketSender;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayNetworkHandler;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.sound.SoundCategory;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.sql.ResultSet;

import static mpds.mpds.MPDS.*;
import static net.minecraft.sound.SoundEvents.*;

public class Join {

    public static void onjoin(ServerPlayNetworkHandler serverPlayNetworkHandler, PacketSender packetSender, MinecraftServer minecraftServer) {
        ServerPlayerEntity player = serverPlayNetworkHandler.getPlayer();
        String playerN = player.getName().getString();
        broken.add(player.getUuid());

        new Thread(() -> {
            String playerUuid = player.getUuidAsString();

            // if (AJM)
            //     player.sendMessage(Text.translatable("loading " + playerN + "'s data...").formatted(Formatting.YELLOW));
            LOGGER.info("loading {}'s data...", playerN);

            while (true) {
                try {
                    ResultSet checkskiprs = sql.checkSkip(playerN);

                    if (checkskiprs.next() && "true".equals(checkskiprs.getString("skip"))) {
                        // if (ASM) {
                        //     minecraftServer.getPlayerManager().broadcast(Text.translatable("skip loading because " + playerN + "'s data includes skip list").formatted(Formatting.YELLOW), false);
                        //     player.sendMessage(Text.translatable("skip loading because " + playerN + "'s data includes skip list").formatted(Formatting.YELLOW));
                        // }
                        LOGGER.warn("skip loading because {}'s data includes skip list", playerN);

                        broken.remove(player.getUuid());

                        // playSound(player, BLOCK_GLASS_BREAK);
                        // player.playSound(BLOCK_GLASS_BREAK, 1f, 1f);

                        return;
                    }

                    ResultSet resultSet;
                    if ((resultSet = sql.join(playerUuid)).next()) {
                        for (int i = 0; "false".equals(resultSet.getString("sync")); i++) {
                            if (i == 3) {
                                if (ServerName.equals(resultSet.getString("server")) || "*".equals(resultSet.getString("server"))) {
                                    // if (AJM)
                                    //     player.sendMessage(Text.translatable("saved " + playerN + "'s correct data").formatted(Formatting.AQUA));
                                    LOGGER.info("saved {}'s correct data", playerN);

                                    broken.remove(player.getUuid());
                                    // playSound(player, ENTITY_PLAYER_LEVELUP);

                                    return;
                                }
                                // if (AEM)
                                //     player.sendMessage(Text.translatable("IT LOOKS " + playerN + "'s DATA WAS BROKEN!\nPLEASE CONNECT TO " + resultSet.getString("server") + "!").formatted(Formatting.RED));
                                LOGGER.error("IT LOOKS {}'s DATA WAS BROKEN!\nPLEASE CONNECT TO {}!", playerN, resultSet.getString("server"));
                                // playSound(player, BLOCK_ANVIL_DESTROY);

                                return;
                            }
                            Thread.sleep(1000);
                            (resultSet = sql.join(playerUuid)).next();
                        }

                        sql.beFalse(playerUuid);
                        sqlPlayer.SqlRow row = sqlPlayer.readRow(resultSet);

                        minecraftServer.execute(() -> {
                            try {
                                sqlPlayer.sqlToPlayer(player, row);
                            } catch (Exception e) {
                                LOGGER.error("Failed to apply MPDS data for {}:", playerN, e);
                            }
                        });

                        // if (AJM)
                        //     player.sendMessage(Text.translatable("success to load " + playerN + "'s data!").formatted(Formatting.AQUA));
                        LOGGER.info("success to load {}'s data!", playerN);

                        // playSound(player, ENTITY_PLAYER_LEVELUP);
                        sql.setServer(playerUuid);
                        broken.remove(player.getUuid());

                    } else {
                        Thread.sleep(1000);

                        for (int i = 1; !sql.join(playerUuid).next(); i++) {
                            if (i == 3) {
                                // if (AEM)
                                //     player.sendMessage(Text.translatable("COULD NOT FIND " + playerN + "'s DATA!\nMADE NEW ONE!").formatted(Formatting.RED));
                                LOGGER.warn("COULD NOT FIND {}'s DATA!\nMADE NEW ONE!", playerN);

                                // playSound(player, BLOCK_GLASS_BREAK);
                                broken.remove(player.getUuid());
                                sql.setServer(playerUuid);

                                return;
                            }
                            Thread.sleep(1000);
                        }
                    }

                    return;
                } catch (CommunicationsException ignored) {
                } catch (Exception e) {
                    // if (AEM)
                    //     player.sendMessage(Text.translatable("THERE WERE SOME ERRORS WHEN LOAD " + playerN + "'s DATA! : \n" + e.getMessage()).formatted(Formatting.RED));
                    LOGGER.error("THERE WERE SOME ERRORS WHEN LOAD {}'s DATA!:", playerN);

                    // playSound(player, BLOCK_ANVIL_DESTROY);
                    e.printStackTrace();

                    return;
                }
            }
        }).start();
    }
}

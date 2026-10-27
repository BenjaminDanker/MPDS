package mpds.mpds.api;

import mpds.mpds.sql;

import java.sql.SQLException;
import java.util.Locale;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * Grants a boss reward once per player: sets the boss's defeated flag and raises SoulboundMax by {@code delta}.
 * For other mods (they call it by name, so they don't need MPDS at compile time) and the /mpdsgrantbossreward
 * command. Talks to the database: call it off the server thread.
 */
public final class BossRewards {
    private BossRewards() {
    }

    /** True when the reward was granted now, false when the player already had it. Unknown flag: IllegalArgumentException. */
    public static boolean grant(String name, String uuid, String flag, int delta) throws SQLException {
        boolean defeated;
        switch (flag.toLowerCase(Locale.ROOT)) {
            case "skyisland", "skyislanddefeated" -> {
                defeated = sql.isSkyIslandDefeated(name, uuid);
                if (!defeated) sql.setSkyIslandDefeated(name, uuid, true);
            }
            case "desert", "desertdefeated" -> {
                defeated = sql.isDesertDefeated(name, uuid);
                if (!defeated) sql.setDesertDefeated(name, uuid, true);
            }
            case "ocean", "oceandefeated" -> {
                defeated = sql.isOceanDefeated(name, uuid);
                if (!defeated) sql.setOceanDefeated(name, uuid, true);
            }
            case "cave", "cavedefeated" -> {
                defeated = sql.isCaveDefeated(name, uuid);
                if (!defeated) sql.setCaveDefeated(name, uuid, true);
            }
            default -> throw new IllegalArgumentException("Unknown boss reward flag: " + flag);
        }
        if (defeated) {
            return false;
        }
        sql.adjustSoulboundMax(name, uuid, delta);
        return true;
    }

    /**
     * Tells the player (if still online) what the reward means, in the domain's voice. Any thread: the message is sent
     * on the server thread. Call it once, when {@link #grant} returned true.
     */
    public static void announce(MinecraftServer server, UUID player, String flag) {
        String domain = switch (flag.toLowerCase(Locale.ROOT)) {
            case "skyisland", "skyislanddefeated" -> "sky";
            case "ocean", "oceandefeated" -> "sea";
            case "desert", "desertdefeated" -> "sand";
            case "cave", "cavedefeated" -> "deep";
            default -> null;
        };
        if (server == null || domain == null) {
            return;
        }
        server.execute(() -> {
            ServerPlayer online = server.getPlayerList().getPlayer(player);
            if (online != null) {
                online.sendSystemMessage(Component.literal("The " + domain + " remembers you.").withStyle(ChatFormatting.AQUA, ChatFormatting.ITALIC));
                online.sendSystemMessage(Component.literal("You can bind one more item to your soul.").withStyle(ChatFormatting.GRAY));
            }
        });
    }
}

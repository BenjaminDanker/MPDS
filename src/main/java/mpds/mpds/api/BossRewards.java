package mpds.mpds.api;

import mpds.mpds.sql;

import java.sql.SQLException;
import java.util.Locale;

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
}

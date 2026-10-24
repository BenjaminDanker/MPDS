package mpds.mpds.api;

import mpds.mpds.MPDS;
import mpds.mpds.sql;

import java.sql.SQLException;

/**
 * Story progression for other mods (called by name, so they don't need MPDS at compile time) instead of running
 * the mpdsbranchswitch / mpdsstage1 / mpdsremovecustomid commands as the console. All of these talk to the
 * database: call them off the server thread.
 */
public final class Progression {
    private Progression() {
    }

    /** Moves the player from one exclusive branch (magic, tech) to the other; the old branch's server wipes itself. */
    public static void switchBranch(String name, String uuid, String from, String to) throws SQLException {
        sql.switchBranch(name, uuid, from, to);
    }

    public static void setStage1Cleared(String name, String uuid, boolean cleared) throws SQLException {
        sql.setStage1Cleared(name, uuid, cleared);
    }

    /** Removes items with custom data key=value from the player's stored inventories; returns how many. */
    public static int removeCustomIdFromStoredInventories(String uuid, String key, String value) throws SQLException {
        if (MPDS.wrappedOps == null) {
            throw new IllegalStateException("MPDS registry ops are not ready yet");
        }
        return MPDS.removeCustomDataFromDbInventories(uuid, key, value);
    }
}

package mpds.mpds;

import java.sql.*;

import static mpds.mpds.MPDS.ServerName;
import static mpds.mpds.MPDS.config;

public class sql {

    static Connection connection = null;

    public static String TABLE_NAME;

    public static PreparedStatement disconnect;

    public static PreparedStatement bea;

    public static PreparedStatement befalse;

    public static PreparedStatement checkskip;

    public static PreparedStatement join;

    public static PreparedStatement setserver;

    public static PreparedStatement showskip;

    public static PreparedStatement updateskip;

    public static PreparedStatement ensureRow;

    public static PreparedStatement adjustSoulboundMax;

    public static PreparedStatement getCraftedSoulboundCapacity;

    public static PreparedStatement incrementCurrentCraftedSoulbound;

    public static PreparedStatement tryReserveCraftedSoulbound;

    public static PreparedStatement setSkyIslandDefeated;

    public static PreparedStatement setDesertDefeated;

    public static PreparedStatement setOceanDefeated;

    public static PreparedStatement setCaveDefeated;

    public static PreparedStatement setStage1Cleared;

    public static void init() throws SQLException {
        try {
            Class.forName("com.mysql.cj.jdbc.Driver");
        } catch (ClassNotFoundException e) {
            throw new RuntimeException(e);
        }

        connection = DriverManager.getConnection("jdbc:mysql://" + config.get("HOST") + "/" + config.get("DB_NAME") + "?autoReconnect=true", config.get("USER"), config.get("PASSWD"));

        showskip = connection.prepareStatement("SELECT * FROM skipplayer");

        updateskip = connection.prepareStatement("INSERT INTO skipplayer (Name, skip) VALUES (?, ?) ON DUPLICATE KEY UPDATE Name=VALUES(Name), skip=VALUES(skip)");

        checkskip = connection.prepareStatement("SELECT skip FROM skipplayer WHERE Name = ?");

        bea = connection.prepareStatement("UPDATE " + TABLE_NAME + " SET server=\"*\" WHERE uuid = ?");

        befalse = connection.prepareStatement("UPDATE " + TABLE_NAME + " SET sync=\"false\" WHERE uuid = ?");

        join = connection.prepareStatement("SELECT * FROM " + TABLE_NAME + " WHERE uuid = ?");

        checkskip = connection.prepareStatement("SELECT skip FROM skipplayer WHERE Name = ?");

        befalse = connection.prepareStatement("UPDATE " + TABLE_NAME + " SET sync=\"false\" WHERE uuid = ?");

        setserver = connection.prepareStatement("UPDATE " + TABLE_NAME + " SET server=\"" + ServerName + "\" WHERE uuid = ?");

        disconnect = connection.prepareStatement("INSERT INTO " + TABLE_NAME +
            " (Name, uuid, Air, Health, enderChestInventory, exhaustion, foodLevel, saturationLevel, foodTickTimer, main, off, armor, selectedSlot, experienceLevel, experienceProgress, effects, sync) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, \"true\") " +
            "ON DUPLICATE KEY UPDATE " +
            "Air=VALUES(Air)," +
            "Health=VALUES(Health)," +
            "enderChestInventory=VALUES(enderChestInventory)," +
            "exhaustion=VALUES(exhaustion)," +
            "foodLevel=VALUES(foodLevel)," +
            "saturationLevel=VALUES(saturationLevel)," +
            "foodTickTimer=VALUES(foodTickTimer)," +
            "main=VALUES(main)," +
            "off=VALUES(off)," +
            "armor=VALUES(armor)," +
            "selectedSlot=VALUES(selectedSlot)," +
            "experienceLevel=VALUES(experienceLevel)," +
            "experienceProgress=VALUES(experienceProgress)," +
            "effects=VALUES(effects)," +
            "sync=VALUES(sync)");

        Statement statement = connection.createStatement();

        statement.execute
                ("CREATE TABLE IF NOT EXISTS " + TABLE_NAME + "(" +
                        "id int AUTO_INCREMENT PRIMARY KEY," +
                        "Name char(16)," +
                        "uuid char(36) UNIQUE," +
                        "Air int," +
                        "Health float," +
                        "enderChestInventory longtext," +
                        "exhaustion float," +
                        "foodLevel int," +
                        "saturationLevel float," +
                        "foodTickTimer int," +
                        "main longtext," +
                        "off longtext," +
                        "armor longtext," +
                        "selectedSlot int," +
                        "experienceLevel int," +
                        "experienceProgress float," +
                        "effects longtext," +
                        "CraftedSoulboundMax int NOT NULL DEFAULT 0," +
                        "CurrentCraftedSoulbound int NOT NULL DEFAULT 0," +
                        "SkyIslandDefeated boolean NOT NULL DEFAULT false," +
                        "DesertDefeated boolean NOT NULL DEFAULT false," +
                        "OceanDefeated boolean NOT NULL DEFAULT false," +
                        "CaveDefeated boolean NOT NULL DEFAULT false," +
                        "Stage1Cleared boolean NOT NULL DEFAULT false," +
                        "sync char(5)," +
                        "server text" +
                        ")");

        // Safe migrations for existing tables. Ignore "duplicate column" errors.
        try {
            statement.execute("ALTER TABLE " + TABLE_NAME + " ADD COLUMN CraftedSoulboundMax int NOT NULL DEFAULT 0");
        } catch (SQLException ignored) {
        }
        try {
            statement.execute("ALTER TABLE " + TABLE_NAME + " ADD COLUMN CurrentCraftedSoulbound int NOT NULL DEFAULT 0");
        } catch (SQLException ignored) {
        }
        try {
            statement.execute("ALTER TABLE " + TABLE_NAME + " ADD COLUMN SkyIslandDefeated boolean NOT NULL DEFAULT false");
        } catch (SQLException ignored) {
        }
        try {
            statement.execute("ALTER TABLE " + TABLE_NAME + " ADD COLUMN DesertDefeated boolean NOT NULL DEFAULT false");
        } catch (SQLException ignored) {
        }
        try {
            statement.execute("ALTER TABLE " + TABLE_NAME + " ADD COLUMN OceanDefeated boolean NOT NULL DEFAULT false");
        } catch (SQLException ignored) {
        }
        try {
            statement.execute("ALTER TABLE " + TABLE_NAME + " ADD COLUMN CaveDefeated boolean NOT NULL DEFAULT false");
        } catch (SQLException ignored) {
        }
        try {
            statement.execute("ALTER TABLE " + TABLE_NAME + " ADD COLUMN Stage1Cleared boolean NOT NULL DEFAULT false");
        } catch (SQLException ignored) {
        }

        ensureRow = connection.prepareStatement(
            "INSERT INTO " + TABLE_NAME + " (Name, uuid, sync, server) VALUES (?, ?, \"true\", \"*\") " +
                "ON DUPLICATE KEY UPDATE Name=VALUES(Name)");

        adjustSoulboundMax = connection.prepareStatement(
            "UPDATE " + TABLE_NAME + " SET CraftedSoulboundMax = GREATEST(0, IFNULL(CraftedSoulboundMax, 0) + ?) WHERE uuid = ?");

        getCraftedSoulboundCapacity = connection.prepareStatement(
            "SELECT IFNULL(CurrentCraftedSoulbound, 0) AS CurrentCraftedSoulbound, IFNULL(CraftedSoulboundMax, 0) AS CraftedSoulboundMax " +
                "FROM " + TABLE_NAME + " WHERE uuid = ?");

        incrementCurrentCraftedSoulbound = connection.prepareStatement(
            "UPDATE " + TABLE_NAME + " SET CurrentCraftedSoulbound = " +
                "LEAST(IFNULL(CraftedSoulboundMax, 0), GREATEST(0, IFNULL(CurrentCraftedSoulbound, 0) + ?)) " +
                "WHERE uuid = ?");

        tryReserveCraftedSoulbound = connection.prepareStatement(
            "UPDATE " + TABLE_NAME + " " +
                "SET CurrentCraftedSoulbound = IFNULL(CurrentCraftedSoulbound, 0) + 1 " +
                "WHERE uuid = ? AND IFNULL(CurrentCraftedSoulbound, 0) < IFNULL(CraftedSoulboundMax, 0)");

        setSkyIslandDefeated = connection.prepareStatement(
            "UPDATE " + TABLE_NAME + " SET SkyIslandDefeated = ? WHERE uuid = ?");
        setDesertDefeated = connection.prepareStatement(
            "UPDATE " + TABLE_NAME + " SET DesertDefeated = ? WHERE uuid = ?");
        setOceanDefeated = connection.prepareStatement(
            "UPDATE " + TABLE_NAME + " SET OceanDefeated = ? WHERE uuid = ?");
        setCaveDefeated = connection.prepareStatement(
            "UPDATE " + TABLE_NAME + " SET CaveDefeated = ? WHERE uuid = ?");

        setStage1Cleared = connection.prepareStatement(
            "UPDATE " + TABLE_NAME + " SET Stage1Cleared = ? WHERE uuid = ?");

        statement.execute
                ("CREATE TABLE IF NOT EXISTS skipplayer(" +
                        "id int auto_increment PRIMARY KEY," +
                        "Name char(16) UNIQUE," +
                        "skip char(5)" +
                        ")");
    }

    public static ResultSet showSkip() throws SQLException {
        return showskip.executeQuery();
    }

    public static ResultSet checkSkip(String player) throws SQLException {
        checkskip.setString(1, player);
        return checkskip.executeQuery();
    }

    public static void updateSkip(String player, String skip) throws SQLException {
        updateskip.setString(1, player);
        updateskip.setString(2, skip);
        updateskip.executeUpdate();
    }

    public static void beFalse(String uuid) throws SQLException {
        befalse.setString(1, uuid);
        befalse.executeUpdate();
    }

    public static void beA(String uuid) throws SQLException {
        bea.setString(1, uuid);
        bea.executeUpdate();
        beFalse(uuid);
    }

    public static void setServer(String uuid) throws SQLException {
        setserver.setString(1, uuid);
        setserver.executeUpdate();
    }

    public static ResultSet join(String uuid) throws SQLException {
        join.setString(1, uuid);
        return join.executeQuery();
    }

    public static void disconnect(sqlPlayer player) throws SQLException {
        disconnect.setString(1, player.name);
        disconnect.setString(2, player.uuid);
        disconnect.setInt(3, player.air);
        disconnect.setFloat(4, player.health);
        disconnect.setString(5, player.enderChestInventory);
        disconnect.setFloat(6, player.exhaustion);
        disconnect.setInt(7, player.foodLevel);
        disconnect.setFloat(8, player.saturationLevel);
        disconnect.setInt(9, player.foodTickTimer);
        disconnect.setString(10, player.main);
        disconnect.setString(11, player.off);
        disconnect.setString(12, player.armor);
        disconnect.setInt(13, player.selectedSlot);
        disconnect.setInt(14, player.experienceLevel);
        disconnect.setFloat(15, player.experienceProgress);
        disconnect.setString(16, player.effects);
        disconnect.executeUpdate();
    }

    public static void ensureRow(String name, String uuid) throws SQLException {
        ensureRow.setString(1, name);
        ensureRow.setString(2, uuid);
        ensureRow.executeUpdate();
    }

    public static void adjustSoulboundMax(String name, String uuid, int delta) throws SQLException {
        ensureRow(name, uuid);
        adjustSoulboundMax.setInt(1, delta);
        adjustSoulboundMax.setString(2, uuid);
        adjustSoulboundMax.executeUpdate();
    }

    public static int[] getCraftedSoulboundCapacity(String name, String uuid) throws SQLException {
        ensureRow(name, uuid);
        getCraftedSoulboundCapacity.setString(1, uuid);
        try (ResultSet rs = getCraftedSoulboundCapacity.executeQuery()) {
            if (rs.next()) {
                return new int[] { rs.getInt("CurrentCraftedSoulbound"), rs.getInt("CraftedSoulboundMax") };
            }
        }
        return new int[] { 0, 0 };
    }

    public static boolean canCraftAnotherSoulbound(String name, String uuid) throws SQLException {
        int[] cap = getCraftedSoulboundCapacity(name, uuid);
        return cap[0] < cap[1];
    }

    public static int[] incrementCurrentCraftedSoulbound(String name, String uuid, int delta) throws SQLException {
        ensureRow(name, uuid);
        incrementCurrentCraftedSoulbound.setInt(1, delta);
        incrementCurrentCraftedSoulbound.setString(2, uuid);
        incrementCurrentCraftedSoulbound.executeUpdate();
        return getCraftedSoulboundCapacity(name, uuid);
    }

    public static boolean tryReserveCraftedSoulbound(String name, String uuid) throws SQLException {
        ensureRow(name, uuid);
        tryReserveCraftedSoulbound.setString(1, uuid);
        return tryReserveCraftedSoulbound.executeUpdate() > 0;
    }

    public static void setSkyIslandDefeated(String name, String uuid, boolean value) throws SQLException {
        ensureRow(name, uuid);
        setSkyIslandDefeated.setBoolean(1, value);
        setSkyIslandDefeated.setString(2, uuid);
        setSkyIslandDefeated.executeUpdate();
    }

    public static void setDesertDefeated(String name, String uuid, boolean value) throws SQLException {
        ensureRow(name, uuid);
        setDesertDefeated.setBoolean(1, value);
        setDesertDefeated.setString(2, uuid);
        setDesertDefeated.executeUpdate();
    }

    public static void setOceanDefeated(String name, String uuid, boolean value) throws SQLException {
        ensureRow(name, uuid);
        setOceanDefeated.setBoolean(1, value);
        setOceanDefeated.setString(2, uuid);
        setOceanDefeated.executeUpdate();
    }

    public static void setCaveDefeated(String name, String uuid, boolean value) throws SQLException {
        ensureRow(name, uuid);
        setCaveDefeated.setBoolean(1, value);
        setCaveDefeated.setString(2, uuid);
        setCaveDefeated.executeUpdate();
    }

    public static void setStage1Cleared(String name, String uuid, boolean value) throws SQLException {
        ensureRow(name, uuid);
        setStage1Cleared.setBoolean(1, value);
        setStage1Cleared.setString(2, uuid);
        setStage1Cleared.executeUpdate();
    }

    public static void close() throws SQLException {
        connection.close();
    }
}

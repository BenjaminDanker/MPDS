package mpds.mpds;

import java.sql.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import static mpds.mpds.MPDS.ServerName;
import static mpds.mpds.MPDS.config;

public class sql {

    static volatile Connection connection = null;
    private static final Object CONNECTION_LOCK = new Object();
    private static final AtomicBoolean RECOVERY_RUNNING = new AtomicBoolean();
    private static final ConcurrentHashMap<UUID, sqlPlayer> PENDING_SAVES = new ConcurrentHashMap<>();
    private static volatile Path pendingDirectory;
    private static volatile Thread recoveryThread;

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

    public static PreparedStatement getSoulboundMax;

    public static PreparedStatement setSkyIslandDefeated;

    public static PreparedStatement setDesertDefeated;

    public static PreparedStatement setOceanDefeated;

    public static PreparedStatement setCaveDefeated;

    public static PreparedStatement setStage1Cleared;

    public static synchronized void init() throws SQLException {
        synchronized (CONNECTION_LOCK) {
            if (connectionUsable()) return;
            closeConnectionAndStatements();
            try {
                Class.forName("com.mysql.cj.jdbc.Driver");

        connection = DriverManager.getConnection("jdbc:mysql://" + config.get("HOST") + "/" + config.get("DB_NAME")
                + "?connectTimeout=5000&socketTimeout=10000", config.get("USER"), config.get("PASSWD"));

        try (Statement schema = connection.createStatement()) {
            schema.execute("CREATE TABLE IF NOT EXISTS " + TABLE_NAME + "("
                    + "id int AUTO_INCREMENT PRIMARY KEY,Name char(16),uuid char(36) UNIQUE,Air int,Health float,"
                    + "enderChestInventory longtext,exhaustion float,foodLevel int,saturationLevel float,foodTickTimer int,"
                    + "main longtext,off longtext,armor longtext,selectedSlot int,experienceLevel int,experienceProgress float,"
                    + "effects longtext,SoulboundMax int NOT NULL DEFAULT 0,SkyIslandDefeated boolean NOT NULL DEFAULT false,"
                    + "DesertDefeated boolean NOT NULL DEFAULT false,OceanDefeated boolean NOT NULL DEFAULT false,"
                    + "CaveDefeated boolean NOT NULL DEFAULT false,Stage1Cleared boolean NOT NULL DEFAULT false,sync char(5),server text)");
            schema.execute("CREATE TABLE IF NOT EXISTS skipplayer(id int auto_increment PRIMARY KEY,Name char(16) UNIQUE,skip char(5))");
        }

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
                        "SoulboundMax int NOT NULL DEFAULT 0," +
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
            statement.execute("ALTER TABLE " + TABLE_NAME + " CHANGE COLUMN CraftedSoulboundMax SoulboundMax int NOT NULL DEFAULT 0");
        } catch (SQLException ignored) {
        }
        try {
            statement.execute("ALTER TABLE " + TABLE_NAME + " ADD COLUMN SoulboundMax int NOT NULL DEFAULT 0");
        } catch (SQLException ignored) {
        }
        try {
            statement.execute("UPDATE " + TABLE_NAME + " SET SoulboundMax = GREATEST(IFNULL(SoulboundMax, 0), IFNULL(CraftedSoulboundMax, 0))");
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
            "UPDATE " + TABLE_NAME + " SET SoulboundMax = GREATEST(0, IFNULL(SoulboundMax, 0) + ?) WHERE uuid = ?");

        getSoulboundMax = connection.prepareStatement(
            "SELECT IFNULL(SoulboundMax, 0) AS SoulboundMax " +
                "FROM " + TABLE_NAME + " WHERE uuid = ?");

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
            } catch (SQLException | RuntimeException failure) {
                closeConnectionAndStatements();
                throw failure;
            } catch (ClassNotFoundException failure) {
                closeConnectionAndStatements();
                throw new SQLException("MySQL JDBC driver is unavailable", failure);
            }
        }
    }

    private static boolean connectionUsable() {
        try {
            return connection != null && !connection.isClosed() && connection.isValid(2);
        } catch (SQLException ignored) {
            return false;
        }
    }

    private static void closeConnectionAndStatements() {
        PreparedStatement[] statements = {disconnect, bea, befalse, checkskip, join, setserver,
                showskip, updateskip, ensureRow, adjustSoulboundMax, getSoulboundMax,
                setSkyIslandDefeated, setDesertDefeated, setOceanDefeated, setCaveDefeated, setStage1Cleared};
        for (PreparedStatement statement : statements) {
            if (statement != null) try { statement.close(); } catch (SQLException ignored) { }
        }
        Connection previous = connection;
        connection = null;
        disconnect = bea = befalse = checkskip = join = setserver = showskip = updateskip = null;
        ensureRow = adjustSoulboundMax = getSoulboundMax = null;
        setSkyIslandDefeated = setDesertDefeated = setOceanDefeated = setCaveDefeated = setStage1Cleared = null;
        if (previous != null) try { previous.close(); } catch (SQLException ignored) { }
    }

    private static synchronized void ensureReady() throws SQLException {
        if (!connectionUsable()) init();
    }

    public static void awaitReady() throws InterruptedException {
        long delayMillis = 1_000;
        long nextLog = 0;
        while (true) {
            try {
                init();
                return;
            } catch (SQLException failure) {
                long now = System.currentTimeMillis();
                if (now >= nextLog) {
                    MPDS.LOGGER.warn("MPDS database unavailable; retrying connection and statement initialization", failure);
                    nextLog = now + 30_000;
                }
                Thread.sleep(delayMillis);
                delayMillis = Math.min(30_000, delayMillis * 2);
            }
        }
    }

    public static void configurePendingDirectory(Path directory) throws java.io.IOException {
        pendingDirectory = directory;
        Files.createDirectories(directory);
        try (var files = Files.list(directory)) {
            files.filter(path -> path.getFileName().toString().endsWith(".json")).forEach(path -> {
                try {
                    sqlPlayer snapshot = MPDS.gson.fromJson(Files.readString(path), sqlPlayer.class);
                    UUID playerId = snapshot == null || snapshot.uuid == null ? null : UUID.fromString(snapshot.uuid);
                    if (playerId == null || snapshot.name == null) throw new IllegalArgumentException("invalid snapshot");
                    PENDING_SAVES.put(playerId, snapshot);
                } catch (Exception invalid) {
                    MPDS.LOGGER.error("Ignoring malformed MPDS pending save file {}", path.getFileName(), invalid);
                }
            });
        }
    }

    public static void startRecovery() {
        if (!RECOVERY_RUNNING.compareAndSet(false, true)) return;
        recoveryThread = new Thread(() -> {
            long delayMillis = 1_000;
            while (RECOVERY_RUNNING.get()) {
                try {
                    awaitReady();
                    delayMillis = 1_000;
                    flushPendingSaves();
                    Thread.sleep(5_000);
                } catch (InterruptedException stopped) {
                    if (!RECOVERY_RUNNING.get()) return;
                } catch (RuntimeException failure) {
                    MPDS.LOGGER.error("MPDS database recovery worker failed", failure);
                    try { Thread.sleep(delayMillis); } catch (InterruptedException ignored) { }
                    delayMillis = Math.min(30_000, delayMillis * 2);
                }
            }
        }, "MPDS-Database-Recovery");
        recoveryThread.setDaemon(true);
        recoveryThread.start();
    }

    public static void stopRecovery() {
        RECOVERY_RUNNING.set(false);
        Thread worker = recoveryThread;
        if (worker != null) worker.interrupt();
    }

    public static synchronized ResultSet showSkip() throws SQLException {
        ensureReady();
        return showskip.executeQuery();
    }

    public static synchronized ResultSet checkSkip(String player) throws SQLException {
        ensureReady();
        checkskip.setString(1, player);
        return checkskip.executeQuery();
    }

    public static synchronized boolean isSkipped(String player) throws SQLException {
        ensureReady();
        checkskip.setString(1, player);
        try (ResultSet rows = checkskip.executeQuery()) {
            return rows.next() && "true".equalsIgnoreCase(rows.getString("skip"));
        }
    }

    public static synchronized List<String> showSkippedPlayers() throws SQLException {
        ensureReady();
        java.util.ArrayList<String> players = new java.util.ArrayList<>();
        try (ResultSet rows = showskip.executeQuery()) {
            while (rows.next()) if (!"false".equals(rows.getString("skip"))) players.add(rows.getString("Name"));
        }
        return List.copyOf(players);
    }

    public static synchronized void updateSkip(String player, String skip) throws SQLException {
        ensureReady();
        updateskip.setString(1, player);
        updateskip.setString(2, skip);
        updateskip.executeUpdate();
    }

    public static synchronized void beFalse(String uuid) throws SQLException {
        ensureReady();
        befalse.setString(1, uuid);
        befalse.executeUpdate();
    }

    public static synchronized void beA(String uuid) throws SQLException {
        ensureReady();
        bea.setString(1, uuid);
        bea.executeUpdate();
        beFalse(uuid);
    }

    public static synchronized void setServer(String uuid) throws SQLException {
        ensureReady();
        setserver.setString(1, uuid);
        setserver.executeUpdate();
    }

    public static synchronized ResultSet join(String uuid) throws SQLException {
        ensureReady();
        join.setString(1, uuid);
        return join.executeQuery();
    }

    public record StoredPlayer(String sync, String server, sqlPlayer.SqlRow data) { }

    public static synchronized Optional<StoredPlayer> findPlayerData(String uuid) throws SQLException {
        ensureReady();
        join.setString(1, uuid);
        try (ResultSet rows = join.executeQuery()) {
            if (!rows.next()) return Optional.empty();
            return Optional.of(new StoredPlayer(rows.getString("sync"), rows.getString("server"), sqlPlayer.readRow(rows)));
        }
    }

    public static synchronized void disconnect(sqlPlayer player) throws SQLException {
        ensureReady();
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

    public static synchronized void completeDefeatReward(String name, String uuid, String action) throws SQLException {
        ensureReady();
        String column = switch (action) {
            case "desert" -> "DesertDefeated";
            case "cave" -> "CaveDefeated";
            default -> throw new IllegalArgumentException("Unsupported datapack progression action");
        };
        boolean previousAutoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try {
            ensureRow(name, uuid);
            try (PreparedStatement update = connection.prepareStatement(
                    "UPDATE " + TABLE_NAME + " SET SoulboundMax=GREATEST(0,IFNULL(SoulboundMax,0)+1), "
                            + column + "=TRUE WHERE uuid=? AND IFNULL(" + column + ",FALSE)=FALSE")) {
                update.setString(1, uuid);
                update.executeUpdate();
            }
            connection.commit();
        } catch (SQLException | RuntimeException failure) {
            try { connection.rollback(); } catch (SQLException rollbackFailure) { failure.addSuppressed(rollbackFailure); }
            throw failure;
        } finally {
            connection.setAutoCommit(previousAutoCommit);
        }
    }

    public static synchronized void updateInventories(String uuid, String main, String off, String armor, String ender)
            throws SQLException {
        ensureReady();
        try (PreparedStatement update = connection.prepareStatement(
                "UPDATE " + TABLE_NAME + " SET main=?, off=?, armor=?, enderChestInventory=? WHERE uuid=?")) {
            update.setString(1, main);
            update.setString(2, off);
            update.setString(3, armor);
            update.setString(4, ender);
            update.setString(5, uuid);
            update.executeUpdate();
        }
    }

    public static void queueDisconnectSave(sqlPlayer snapshot) {
        if (snapshot == null || snapshot.uuid == null || snapshot.name == null) return;
        UUID id = UUID.fromString(snapshot.uuid);
        PENDING_SAVES.put(id, snapshot);
        persistPendingSnapshot(snapshot);
        startRecovery();
        Thread worker = recoveryThread;
        if (worker != null) worker.interrupt();
    }

    private static void persistPendingSnapshot(sqlPlayer snapshot) {
        Path directory = pendingDirectory;
        if (directory == null) return;
        Path target = directory.resolve(snapshot.uuid + ".json");
        Path temporary = directory.resolve(snapshot.uuid + ".tmp");
        try {
            Files.createDirectories(directory);
            Files.writeString(temporary, MPDS.gson.toJson(snapshot));
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (java.io.IOException failure) {
            MPDS.LOGGER.error("Could not persist pending MPDS save for {}; retaining it in memory", snapshot.uuid, failure);
        }
    }

    public static void flushPendingFor(UUID playerId) throws InterruptedException {
        sqlPlayer snapshot = PENDING_SAVES.get(playerId);
        if (snapshot == null) return;
        long delayMillis = 1_000;
        while (PENDING_SAVES.containsKey(playerId)) {
            try {
                awaitReady();
                flushPendingSnapshot(snapshot);
                if (PENDING_SAVES.get(playerId) != snapshot) return;
            } catch (InterruptedException interrupted) {
                throw interrupted;
            } catch (SQLException failure) {
                MPDS.LOGGER.warn("Retrying pending MPDS save for {}", playerId, failure);
            }
            Thread.sleep(delayMillis);
            delayMillis = Math.min(30_000, delayMillis * 2);
            snapshot = PENDING_SAVES.get(playerId);
            if (snapshot == null) return;
        }
    }

    private static void flushPendingSaves() {
        for (var entry : PENDING_SAVES.entrySet()) {
            try {
                flushPendingSnapshot(entry.getValue());
            } catch (SQLException failure) {
                MPDS.LOGGER.warn("Pending MPDS save remains queued for {}", entry.getKey(), failure);
            }
        }
    }

    private static synchronized void flushPendingSnapshot(sqlPlayer snapshot) throws SQLException {
        ensureReady();
        if (isSkipped(snapshot.name)) beA(snapshot.uuid);
        else disconnect(snapshot);
        UUID id = UUID.fromString(snapshot.uuid);
        if (PENDING_SAVES.remove(id, snapshot)) {
            Path directory = pendingDirectory;
            if (directory != null) try { Files.deleteIfExists(directory.resolve(snapshot.uuid + ".json")); }
            catch (java.io.IOException failure) { MPDS.LOGGER.warn("Saved MPDS data but could not remove pending file for {}", snapshot.uuid, failure); }
        }
    }

    public static synchronized void ensureRow(String name, String uuid) throws SQLException {
        ensureReady();
        ensureRow.setString(1, name);
        ensureRow.setString(2, uuid);
        ensureRow.executeUpdate();
    }

    public static synchronized void adjustSoulboundMax(String name, String uuid, int delta) throws SQLException {
        ensureReady();
        ensureRow(name, uuid);
        adjustSoulboundMax.setInt(1, delta);
        adjustSoulboundMax.setString(2, uuid);
        adjustSoulboundMax.executeUpdate();
    }

    public static synchronized int getSoulboundMax(String name, String uuid) throws SQLException {
        ensureReady();
        ensureRow(name, uuid);
        getSoulboundMax.setString(1, uuid);
        try (ResultSet rs = getSoulboundMax.executeQuery()) {
            if (rs.next()) {
                return rs.getInt("SoulboundMax");
            }
        }
        return 0;
    }

    public static synchronized boolean canCraftAnotherSoulbound(String name, String uuid) throws SQLException {
        return getSoulboundMax(name, uuid) > 0;
    }

    public static synchronized void setSkyIslandDefeated(String name, String uuid, boolean value) throws SQLException {
        ensureReady();
        ensureRow(name, uuid);
        setSkyIslandDefeated.setBoolean(1, value);
        setSkyIslandDefeated.setString(2, uuid);
        setSkyIslandDefeated.executeUpdate();
    }

    public static synchronized void setDesertDefeated(String name, String uuid, boolean value) throws SQLException {
        ensureReady();
        ensureRow(name, uuid);
        setDesertDefeated.setBoolean(1, value);
        setDesertDefeated.setString(2, uuid);
        setDesertDefeated.executeUpdate();
    }

    public static synchronized void setOceanDefeated(String name, String uuid, boolean value) throws SQLException {
        ensureReady();
        ensureRow(name, uuid);
        setOceanDefeated.setBoolean(1, value);
        setOceanDefeated.setString(2, uuid);
        setOceanDefeated.executeUpdate();
    }

    public static synchronized void setCaveDefeated(String name, String uuid, boolean value) throws SQLException {
        ensureReady();
        ensureRow(name, uuid);
        setCaveDefeated.setBoolean(1, value);
        setCaveDefeated.setString(2, uuid);
        setCaveDefeated.executeUpdate();
    }

    public static synchronized void setStage1Cleared(String name, String uuid, boolean value) throws SQLException {
        ensureReady();
        ensureRow(name, uuid);
        setStage1Cleared.setBoolean(1, value);
        setStage1Cleared.setString(2, uuid);
        setStage1Cleared.executeUpdate();
    }

    public static synchronized boolean isSkyIslandDefeated(String name, String uuid) throws SQLException {
        return isDefeatedFlag(name, uuid, "SkyIslandDefeated");
    }

    public static synchronized boolean isDesertDefeated(String name, String uuid) throws SQLException {
        return isDefeatedFlag(name, uuid, "DesertDefeated");
    }

    public static synchronized boolean isOceanDefeated(String name, String uuid) throws SQLException {
        return isDefeatedFlag(name, uuid, "OceanDefeated");
    }

    public static synchronized boolean isCaveDefeated(String name, String uuid) throws SQLException {
        return isDefeatedFlag(name, uuid, "CaveDefeated");
    }

    private static boolean isDefeatedFlag(String name, String uuid, String columnName) throws SQLException {
        ensureReady();
        ensureRow(name, uuid);
        String query = "SELECT IFNULL(" + columnName + ", false) AS flag FROM " + TABLE_NAME + " WHERE uuid = ?";
        try (PreparedStatement statement = connection.prepareStatement(query)) {
            statement.setString(1, uuid);
            try (ResultSet rs = statement.executeQuery()) {
                if (rs.next()) {
                    return rs.getBoolean("flag");
                }
            }
        }
        return false;
    }

    public static synchronized void close() throws SQLException {
        closeConnectionAndStatements();
    }
}

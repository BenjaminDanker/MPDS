package mpds.mpds;

import java.sql.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
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
    /** Players whose row this server has locked and whose state it is responsible for saving. */
    private static final java.util.Set<UUID> OWNED = ConcurrentHashMap.newKeySet();
    private static final String NOW_MILLIS = "CAST(UNIX_TIMESTAMP(NOW(3)) * 1000 AS SIGNED)";
    public static final long LOCK_STALE_MILLIS = 120_000L;
    private static final long HEARTBEAT_MILLIS = 30_000L;
    private static volatile long lastHeartbeat;
    private static volatile Path pendingDirectory;
    private static volatile Thread recoveryThread;

    public static String TABLE_NAME;

    public static PreparedStatement disconnect;
    /** Like disconnect, plus the family inventory columns, for servers with FamilySync. */
    public static PreparedStatement disconnectFamily;

    public static PreparedStatement bea;

    public static PreparedStatement befalse;

    public static PreparedStatement checkskip;

    public static PreparedStatement join;

    public static PreparedStatement setserver;
    public static PreparedStatement trylock;

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

        // The host's shared login (~/.config/mc-network/database.env) when present, otherwise this server's own config.
        var shared = com.silver.authorization.NetworkShared.databaseLogin();
        String host = shared.map(l -> l.hostAndPort()).orElse(config.get("HOST"));
        String database = shared.map(l -> l.database()).orElse(config.get("DB_NAME"));
        String user = shared.map(l -> l.user()).orElse(config.get("USER"));
        String password = shared.map(l -> l.password()).orElse(config.get("PASSWD"));
        connection = DriverManager.getConnection("jdbc:mysql://" + host + "/" + database
                + "?connectTimeout=5000&socketTimeout=10000", user, password);

        try (Statement schema = connection.createStatement()) {
            schema.execute("CREATE TABLE IF NOT EXISTS " + TABLE_NAME + "("
                    + "id int AUTO_INCREMENT PRIMARY KEY,Name char(16),uuid char(36) UNIQUE,Air int,Health float,"
                    + "enderChestInventory longtext,exhaustion float,foodLevel int,saturationLevel float,foodTickTimer int,"
                    + "main longtext,off longtext,armor longtext,selectedSlot int,experienceLevel int,experienceProgress float,"
                    + "effects longtext,SoulboundMax int NOT NULL DEFAULT 0,SkyIslandDefeated boolean NOT NULL DEFAULT false,"
                    + "DesertDefeated boolean NOT NULL DEFAULT false,OceanDefeated boolean NOT NULL DEFAULT false,"
                    + "CaveDefeated boolean NOT NULL DEFAULT false,Stage1Cleared boolean NOT NULL DEFAULT false,"
                    + "ElementUnlocks int NOT NULL DEFAULT 0,SkillPoints int NOT NULL DEFAULT 0,"
                    + "SkillTree varchar(1024) NOT NULL DEFAULT '',sync char(5),server text)");
            // Lease for the sync lock: the owning server renews it; a lock whose lease is older than LOCK_STALE_MILLIS
            // belongs to a server that died and may be taken over.
            schema.execute("ALTER TABLE " + TABLE_NAME + " ADD COLUMN IF NOT EXISTS lock_heartbeat bigint NOT NULL DEFAULT 0");
            schema.execute("CREATE TABLE IF NOT EXISTS skipplayer(id int auto_increment PRIMARY KEY,Name char(16) UNIQUE,skip char(5))");
            schema.execute("CREATE TABLE IF NOT EXISTS branch_wipe(id bigint AUTO_INCREMENT PRIMARY KEY,"
                    + "uuid char(36) NOT NULL,name varchar(16),branch varchar(16) NOT NULL,"
                    + "requested_at timestamp(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),done_at timestamp(3) NULL,"
                    + "INDEX branch_pending(branch, done_at))");
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

        // Taking the lock is one conditional update, so two servers can never both see sync=true and both own it.
        trylock = connection.prepareStatement(
            "UPDATE " + TABLE_NAME + " SET sync=\"false\", server=?, lock_heartbeat=" + NOW_MILLIS + " WHERE uuid = ? AND sync=\"true\"");

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
                        "ElementUnlocks int NOT NULL DEFAULT 0," +
                        "SkillPoints int NOT NULL DEFAULT 0," +
                        "SkillTree varchar(1024) NOT NULL DEFAULT ''," +
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

        for (String column : new String[] {
                "ElementUnlocks int NOT NULL DEFAULT 0",
                "SkillPoints int NOT NULL DEFAULT 0",
                "SkillTree varchar(1024) NOT NULL DEFAULT ''",
                "MagicCompleted boolean NOT NULL DEFAULT false",
                "fmain longtext NULL",
                "foff longtext NULL",
                "farmor longtext NULL",
                "fender longtext NULL",
                "TechCompleted boolean NOT NULL DEFAULT false"}) {
            try {
                statement.execute("ALTER TABLE " + TABLE_NAME + " ADD COLUMN " + column);
            } catch (SQLException ignored) {
            }
        }

        disconnectFamily = connection.prepareStatement("INSERT INTO " + TABLE_NAME +
            " (Name, uuid, Air, Health, enderChestInventory, exhaustion, foodLevel, saturationLevel, foodTickTimer, main, off, armor, selectedSlot, experienceLevel, experienceProgress, effects, fmain, foff, farmor, fender, sync) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, \"true\") " +
            "ON DUPLICATE KEY UPDATE " +
            "Air=VALUES(Air),Health=VALUES(Health),enderChestInventory=VALUES(enderChestInventory),exhaustion=VALUES(exhaustion)," +
            "foodLevel=VALUES(foodLevel),saturationLevel=VALUES(saturationLevel),foodTickTimer=VALUES(foodTickTimer)," +
            "main=VALUES(main),off=VALUES(off),armor=VALUES(armor),selectedSlot=VALUES(selectedSlot)," +
            "experienceLevel=VALUES(experienceLevel),experienceProgress=VALUES(experienceProgress),effects=VALUES(effects)," +
            "fmain=VALUES(fmain),foff=VALUES(foff),farmor=VALUES(farmor),fender=VALUES(fender),sync=VALUES(sync)");

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
        PreparedStatement[] statements = {disconnect, disconnectFamily, bea, befalse, checkskip, join, setserver, trylock,
                showskip, updateskip, ensureRow, adjustSoulboundMax, getSoulboundMax,
                setSkyIslandDefeated, setDesertDefeated, setOceanDefeated, setCaveDefeated, setStage1Cleared};
        for (PreparedStatement statement : statements) {
            if (statement != null) try { statement.close(); } catch (SQLException ignored) { }
        }
        Connection previous = connection;
        connection = null;
        disconnect = disconnectFamily = bea = befalse = checkskip = join = setserver = trylock = showskip = updateskip = null;
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
                    heartbeat();
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

    /** Takes ownership of the player's row for this server; false when another server holds it. */
    public static synchronized boolean tryLock(String uuid) throws SQLException {
        ensureReady();
        trylock.setString(1, ServerName);
        trylock.setString(2, uuid);
        boolean locked = trylock.executeUpdate() == 1;
        if (locked) OWNED.add(UUID.fromString(uuid));
        return locked;
    }

    /** Takes over a lock whose owner stopped renewing it (that server crashed or was killed). */
    public static synchronized boolean takeOverStale(String uuid, String staleOwner) throws SQLException {
        ensureReady();
        try (PreparedStatement takeover = connection.prepareStatement("UPDATE " + TABLE_NAME
                + " SET server=?, lock_heartbeat=" + NOW_MILLIS + " WHERE uuid=? AND sync=\"false\" AND server=?"
                + " AND lock_heartbeat < " + NOW_MILLIS + " - ?")) {
            takeover.setString(1, ServerName);
            takeover.setString(2, uuid);
            takeover.setString(3, staleOwner);
            takeover.setLong(4, LOCK_STALE_MILLIS);
            boolean taken = takeover.executeUpdate() == 1;
            if (taken) OWNED.add(UUID.fromString(uuid));
            return taken;
        }
    }

    /** Marks a row this server already held (for example after its own crash) as owned again, renewing the lease. */
    public static synchronized void reclaim(String uuid) throws SQLException {
        OWNED.add(UUID.fromString(uuid));
        renewLease(UUID.fromString(uuid));
    }

    /** The player left; their queued save (if any) keeps the lease alive until it is written. */
    public static void releaseOwnership(UUID playerId) {
        OWNED.remove(playerId);
    }

    /**
     * The player left before their state was applied: the stored row is unchanged, so give the lock back instead of
     * leaving it held. Does nothing when another server owns the row.
     */
    public static synchronized void unlockUnapplied(UUID playerId) throws SQLException {
        if (!OWNED.remove(playerId)) return;
        ensureReady();
        try (PreparedStatement release = connection.prepareStatement("UPDATE " + TABLE_NAME
                + " SET sync=\"true\" WHERE uuid=? AND server=? AND sync=\"false\"")) {
            release.setString(1, playerId.toString());
            release.setString(2, ServerName);
            release.executeUpdate();
        }
    }

    private static synchronized void renewLease(UUID playerId) throws SQLException {
        ensureReady();
        try (PreparedStatement renew = connection.prepareStatement("UPDATE " + TABLE_NAME
                + " SET lock_heartbeat=" + NOW_MILLIS + " WHERE uuid=? AND server=? AND sync=\"false\"")) {
            renew.setString(1, playerId.toString());
            renew.setString(2, ServerName);
            renew.executeUpdate();
        }
    }

    /** Renews the lease on every row this server owns or still has to save. */
    private static void heartbeat() {
        long now = System.currentTimeMillis();
        if (now - lastHeartbeat < HEARTBEAT_MILLIS) return;
        lastHeartbeat = now;
        java.util.Set<UUID> held = new java.util.HashSet<>(OWNED);
        held.addAll(PENDING_SAVES.keySet());
        for (UUID id : held) {
            try {
                renewLease(id);
            } catch (SQLException failure) {
                MPDS.LOGGER.warn("Could not renew the MPDS lock lease for {}", id, failure);
                return;
            }
        }
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

    /** Writes the snapshot only while this server still owns the row (or the row does not exist yet). */
    private static synchronized boolean saveIfStillOwned(sqlPlayer snapshot) throws SQLException {
        boolean previousAutoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try {
            try (PreparedStatement owner = connection.prepareStatement(
                    "SELECT server FROM " + TABLE_NAME + " WHERE uuid=? FOR UPDATE")) {
                owner.setString(1, snapshot.uuid);
                try (ResultSet row = owner.executeQuery()) {
                    if (row.next()) {
                        String server = row.getString("server");
                        if (server != null && !server.equals(ServerName) && !"*".equals(server)) {
                            connection.commit();
                            return false;
                        }
                    }
                }
            }
            disconnect(snapshot);
            connection.commit();
            return true;
        } catch (SQLException | RuntimeException failure) {
            try { connection.rollback(); } catch (SQLException rollbackFailure) { failure.addSuppressed(rollbackFailure); }
            throw failure;
        } finally {
            connection.setAutoCommit(previousAutoCommit);
        }
    }

    /** One pass over the queued saves before shutdown, so they are written now rather than at the next start. */
    public static void flushPendingSavesNow() {
        if (PENDING_SAVES.isEmpty()) return;
        try {
            ensureReady();
        } catch (SQLException unavailable) {
            MPDS.LOGGER.warn("MPDS database unavailable at shutdown; {} queued save(s) stay on disk for the next start",
                    PENDING_SAVES.size());
            return;
        }
        flushPendingSaves();
    }

    public static synchronized void disconnect(sqlPlayer player) throws SQLException {
        ensureReady();
        if (player.famMain != null) {
            disconnectFamily.setString(1, player.name);
            disconnectFamily.setString(2, player.uuid);
            disconnectFamily.setInt(3, player.air);
            disconnectFamily.setFloat(4, player.health);
            disconnectFamily.setString(5, player.enderChestInventory);
            disconnectFamily.setFloat(6, player.exhaustion);
            disconnectFamily.setInt(7, player.foodLevel);
            disconnectFamily.setFloat(8, player.saturationLevel);
            disconnectFamily.setInt(9, player.foodTickTimer);
            disconnectFamily.setString(10, player.main);
            disconnectFamily.setString(11, player.off);
            disconnectFamily.setString(12, player.armor);
            disconnectFamily.setInt(13, player.selectedSlot);
            disconnectFamily.setInt(14, player.experienceLevel);
            disconnectFamily.setFloat(15, player.experienceProgress);
            disconnectFamily.setString(16, player.effects);
            disconnectFamily.setString(17, player.famMain);
            disconnectFamily.setString(18, player.famOff);
            disconnectFamily.setString(19, player.famArmor);
            disconnectFamily.setString(20, player.famEnder);
            disconnectFamily.executeUpdate();
            return;
        }
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
        UUID id = UUID.fromString(snapshot.uuid);
        if (isSkipped(snapshot.name)) beA(snapshot.uuid);
        else if (!saveIfStillOwned(snapshot)) {
            // Another server took the row over after this one stopped renewing it; its data is newer than ours.
            MPDS.LOGGER.warn("Dropping queued MPDS save for {}: another server owns their data now", snapshot.name);
        }
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

    /** Which story branches a player has finished; this is what opens Magitech and decides their faction there. */
    public record Completions(boolean magic, boolean tech) {
        public static final Completions NONE = new Completions(false, false);
    }

    public static synchronized Completions getCompletions(String uuid) throws SQLException {
        ensureReady();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT IFNULL(MagicCompleted, FALSE) AS m, IFNULL(TechCompleted, FALSE) AS t FROM " + TABLE_NAME + " WHERE uuid = ?")) {
            statement.setString(1, uuid);
            try (ResultSet rs = statement.executeQuery()) {
                if (rs.next()) {
                    return new Completions(rs.getBoolean("m"), rs.getBoolean("t"));
                }
            }
        }
        return Completions.NONE;
    }

    public static synchronized void completeBranch(String name, String uuid, String branch) throws SQLException {
        ensureReady();
        String column = switch (branch) {
            case "magic" -> "MagicCompleted";
            case "tech" -> "TechCompleted";
            default -> throw new IllegalArgumentException("Unsupported branch");
        };
        ensureRow(name, uuid);
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE " + TABLE_NAME + " SET " + column + " = TRUE WHERE uuid = ?")) {
            statement.setString(1, uuid);
            statement.executeUpdate();
        }
    }

    /** A request for one branch server (magic or tech) to erase what it holds about a player. */
    public record BranchWipe(long id, String uuid, String name, String branch) {
    }

    /**
     * Moves a player from one exclusive branch to the other in one transaction: the proxy stops locking them out of
     * {@code to}, locks them out of {@code from} instead, their skill tree is emptied, and {@code from}'s server is asked to wipe them.
     */
    public static synchronized void switchBranch(String name, String uuid, String from, String to) throws SQLException {
        ensureReady();
        ensureRow(name, uuid);
        boolean autoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try {
            try (PreparedStatement statement = connection.prepareStatement(
                    "DELETE FROM wakeup_player_visits WHERE player_uuid = ? AND server_name = ?")) {
                statement.setString(1, uuid);
                statement.setString(2, from);
                statement.executeUpdate();
            }
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO wakeup_player_visits(player_uuid, server_name) VALUES (?, ?) "
                            + "ON DUPLICATE KEY UPDATE last_visited_at = CURRENT_TIMESTAMP(3)")) {
                statement.setString(1, uuid);
                statement.setString(2, to);
                statement.executeUpdate();
            }
            // Leaving a branch also starts over on Magitech, where that branch's progress continued.
            for (String branch : new String[] {from, "magitech"}) {
                try (PreparedStatement statement = connection.prepareStatement(
                        "INSERT INTO branch_wipe(uuid, name, branch) VALUES (?, ?, ?)")) {
                    statement.setString(1, uuid);
                    statement.setString(2, name);
                    statement.setString(3, branch);
                    statement.executeUpdate();
                }
            }
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE " + TABLE_NAME + " SET ElementUnlocks = 0, SkillPoints = 0, SkillTree = '', fmain = '', foff = '', farmor = '', fender = '', "
                            + ("magic".equals(from) ? "MagicCompleted" : "tech".equals(from) ? "TechCompleted" : "SkillPoints")
                            + (("magic".equals(from) || "tech".equals(from)) ? " = FALSE" : " = 0") + " WHERE uuid = ?")) {
                statement.setString(1, uuid);
                statement.executeUpdate();
            }
            connection.commit();
        } catch (SQLException failure) {
            connection.rollback();
            throw failure;
        } finally {
            connection.setAutoCommit(autoCommit);
        }
    }

    public static synchronized List<BranchWipe> pendingBranchWipes(String branch) throws SQLException {
        ensureReady();
        List<BranchWipe> found = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT id, uuid, IFNULL(name, '') AS name FROM branch_wipe WHERE branch = ? AND done_at IS NULL ORDER BY id")) {
            statement.setString(1, branch);
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    found.add(new BranchWipe(rs.getLong("id"), rs.getString("uuid"), rs.getString("name"), branch));
                }
            }
        }
        return found;
    }

    public static synchronized void markBranchWiped(long id) throws SQLException {
        ensureReady();
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE branch_wipe SET done_at = CURRENT_TIMESTAMP(3) WHERE id = ?")) {
            statement.setLong(1, id);
            statement.executeUpdate();
        }
    }

    /** Which elements a player may choose (from secondary bosses), their points, and purchased nodes. */
    public record ElementProgress(int unlocks, int skillPoints, String skillTree) {
        public static final int AIR = 1;
        public static final int WATER = 2;
        public static final int EARTH = 4;
        public static final int FIRE = 8;

        public boolean unlocked(int element) {
            return (unlocks & element) != 0;
        }
    }

    public static synchronized ElementProgress getElementProgress(String name, String uuid) throws SQLException {
        ensureReady();
        ensureRow(name, uuid);
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT IFNULL(ElementUnlocks, 0) AS u, IFNULL(SkillPoints, 0) AS p, IFNULL(SkillTree, '') AS t "
                        + "FROM " + TABLE_NAME + " WHERE uuid = ?")) {
            statement.setString(1, uuid);
            try (ResultSet rs = statement.executeQuery()) {
                if (rs.next()) {
                    return new ElementProgress(rs.getInt("u"), rs.getInt("p"), rs.getString("t"));
                }
            }
        }
        return new ElementProgress(0, 0, "");
    }

    /** Sets one element bit. Returns true only when the bit was not already set. */
    public static synchronized boolean unlockElement(String name, String uuid, int elementBit) throws SQLException {
        ensureReady();
        ensureRow(name, uuid);
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE " + TABLE_NAME + " SET ElementUnlocks = IFNULL(ElementUnlocks, 0) | ? "
                        + "WHERE uuid = ? AND (IFNULL(ElementUnlocks, 0) & ?) = 0")) {
            statement.setInt(1, elementBit);
            statement.setString(2, uuid);
            statement.setInt(3, elementBit);
            return statement.executeUpdate() > 0;
        }
    }

    /** Adds (or removes, if negative) skill points; the total never drops below zero. */
    public static synchronized void addSkillPoints(String name, String uuid, int delta) throws SQLException {
        ensureReady();
        ensureRow(name, uuid);
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE " + TABLE_NAME + " SET SkillPoints = GREATEST(0, IFNULL(SkillPoints, 0) + ?) WHERE uuid = ?")) {
            statement.setInt(1, delta);
            statement.setString(2, uuid);
            statement.executeUpdate();
        }
    }

    /**
     * Spends points and replaces the purchased-node list in one step. It only applies when the player
     * can afford it and nothing else changed their tree since it was read, so two servers cannot both spend.
     */
    public static synchronized boolean trySpendSkillPoints(String name, String uuid, int cost, String expectedTree,
            String newTree) throws SQLException {
        ensureReady();
        ensureRow(name, uuid);
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE " + TABLE_NAME + " SET SkillPoints = SkillPoints - ?, SkillTree = ? "
                        + "WHERE uuid = ? AND IFNULL(SkillPoints, 0) >= ? AND IFNULL(SkillTree, '') = ?")) {
            statement.setInt(1, cost);
            statement.setString(2, newTree);
            statement.setString(3, uuid);
            statement.setInt(4, cost);
            statement.setString(5, expectedTree);
            return statement.executeUpdate() > 0;
        }
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

package playeranalyticsforge;

import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.fml.loading.FMLPaths;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class PlayerAnalyticsDb {
    private static final Object LOCK = new Object();
    private static Connection connection;
    private static final ConcurrentHashMap<UUID, Instant> activeSessions = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<UUID, Instant> lastActivityTime = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<UUID, Instant> afkStartTime = new ConcurrentHashMap<>();
    private static final long AFK_TIMEOUT_SECONDS = 300; // 5 minutes

    private PlayerAnalyticsDb() {
    }

    public static void recordEvent(String eventType, ServerPlayer player) {
        synchronized (LOCK) {
            try {
                Connection conn = init();
                String sql = "INSERT INTO player_sessions (player_uuid, player_name, event_type, event_time_utc) VALUES (?, ?, ?, ?)";
                try (PreparedStatement statement = conn.prepareStatement(sql)) {
                    statement.setString(1, player.getUUID().toString());
                    statement.setString(2, player.getGameProfile().getName());
                    statement.setString(3, eventType);
                    statement.setString(4, Instant.now().toString());
                    statement.executeUpdate();
                }
            } catch (SQLException ex) {
                PlayeranalyticsForgeMod.LOGGER.error("Failed to record player event: {}", eventType, ex);
            }
        }
    }

    public static String getSummaryJson() {
        synchronized (LOCK) {
            try {
                Connection conn = init();
                String sql = "SELECT " +
                    "SUM(CASE WHEN event_type='join' THEN 1 ELSE 0 END) AS joins, " +
                    "SUM(CASE WHEN event_type='leave' THEN 1 ELSE 0 END) AS leaves, " +
                    "COUNT(DISTINCT player_uuid) AS unique_players, " +
                    "MAX(event_time_utc) AS last_event_time " +
                    "FROM player_sessions";
                try (Statement statement = conn.createStatement(); ResultSet rs = statement.executeQuery(sql)) {
                    if (rs.next()) {
                        long joins = rs.getLong("joins");
                        long leaves = rs.getLong("leaves");
                        long uniquePlayers = rs.getLong("unique_players");
                        String lastEvent = rs.getString("last_event_time");
                        
                        // Get session stats and total playtime
                        String sessionSql = "SELECT COUNT(*) AS total_sessions, AVG(duration_seconds) AS avg_duration FROM player_session_data";
                        String playtimeSql = "SELECT COALESCE(SUM(total_playtime_seconds), 0) AS total_playtime FROM player_stats";
                        try (Statement sessionStmt = conn.createStatement(); 
                             ResultSet sessionRs = sessionStmt.executeQuery(sessionSql);
                             Statement playtimeStmt = conn.createStatement();
                             ResultSet playtimeRs = playtimeStmt.executeQuery(playtimeSql)) {
                            long totalSessions = 0;
                            long avgDuration = 0;
                            long totalPlaytime = 0;
                            if (sessionRs.next()) {
                                totalSessions = sessionRs.getLong("total_sessions");
                                avgDuration = sessionRs.getLong("avg_duration");
                            }
                            if (playtimeRs.next()) {
                                totalPlaytime = playtimeRs.getLong("total_playtime");
                            }
                            
                            return "{" +
                                "\"joins\":" + joins + "," +
                                "\"leaves\":" + leaves + "," +
                                "\"uniquePlayers\":" + uniquePlayers + "," +
                                "\"lastEvent\":" + toJsonString(lastEvent) + "," +
                                "\"totalSessions\":" + totalSessions + "," +
                                "\"avgSessionDuration\":" + avgDuration + "," +
                                "\"totalPlaytimeSeconds\":" + totalPlaytime +
                                "}";
                        }
                    }
                }
            } catch (SQLException ex) {
                PlayeranalyticsForgeMod.LOGGER.error("Failed to query summary", ex);
            }
            return "{\"joins\":0,\"leaves\":0,\"uniquePlayers\":0,\"lastEvent\":null,\"totalSessions\":0,\"avgSessionDuration\":0,\"totalPlaytimeSeconds\":0}";
        }
    }

    public static String getRecentEventsJson(int limit) {
        synchronized (LOCK) {
            try {
                Connection conn = init();
                String sql = "SELECT player_uuid, player_name, event_type, event_time_utc " +
                    "FROM player_sessions ORDER BY id DESC LIMIT ?";
                try (PreparedStatement statement = conn.prepareStatement(sql)) {
                    statement.setInt(1, limit);
                    try (ResultSet rs = statement.executeQuery()) {
                        StringBuilder json = new StringBuilder();
                        json.append("[");
                        boolean first = true;
                        while (rs.next()) {
                            if (!first) {
                                json.append(",");
                            }
                            first = false;
                            json.append("{");
                            json.append("\"playerUuid\":").append(toJsonString(rs.getString("player_uuid"))).append(",");
                            json.append("\"playerName\":").append(toJsonString(rs.getString("player_name"))).append(",");
                            json.append("\"eventType\":").append(toJsonString(rs.getString("event_type"))).append(",");
                            json.append("\"eventTimeUtc\":").append(toJsonString(rs.getString("event_time_utc")));
                            json.append("}");
                        }
                        json.append("]");
                        return json.toString();
                    }
                }
            } catch (SQLException ex) {
                PlayeranalyticsForgeMod.LOGGER.error("Failed to query recent events", ex);
                return "[]";
            }
        }
    }

    public static String getPlayersJson(int limit) {
        synchronized (LOCK) {
            try {
                Connection conn = init();
                String sql = "WITH session_stats AS (" +
                    "SELECT player_uuid, " +
                    "SUM(CASE WHEN event_type='join' THEN 1 ELSE 0 END) AS joins, " +
                    "SUM(CASE WHEN event_type='leave' THEN 1 ELSE 0 END) AS leaves, " +
                    "MAX(event_time_utc) AS last_seen " +
                    "FROM player_sessions GROUP BY player_uuid" +
                    "), base_players AS (" +
                    "SELECT player_uuid, MAX(player_name) AS player_name FROM player_sessions GROUP BY player_uuid" +
                    ") " +
                    "SELECT b.player_uuid, " +
                    "COALESCE(ps.player_name, b.player_name) AS player_name, " +
                    "COALESCE(ps.kills, 0) AS kills, " +
                    "COALESCE(ps.deaths, 0) AS deaths, " +
                    "COALESCE(ps.total_playtime_seconds, 0) AS total_playtime_seconds, " +
                    "COALESCE(ps.last_seen, ss.last_seen) AS last_seen, " +
                    "COALESCE(ss.joins, 0) AS joins, " +
                    "COALESCE(ss.leaves, 0) AS leaves " +
                    "FROM base_players b " +
                    "LEFT JOIN player_stats ps ON ps.player_uuid = b.player_uuid " +
                    "LEFT JOIN session_stats ss ON ss.player_uuid = b.player_uuid " +
                    "ORDER BY last_seen DESC LIMIT ?";
                try (PreparedStatement statement = conn.prepareStatement(sql)) {
                    statement.setInt(1, limit);
                    try (ResultSet rs = statement.executeQuery()) {
                        StringBuilder json = new StringBuilder();
                        json.append("[");
                        boolean first = true;
                        while (rs.next()) {
                            if (!first) {
                                json.append(",");
                            }
                            first = false;
                            json.append("{");
                            json.append("\"playerUuid\":").append(toJsonString(rs.getString("player_uuid"))).append(",");
                            json.append("\"playerName\":").append(toJsonString(rs.getString("player_name"))).append(",");
                            json.append("\"lastSeen\":").append(toJsonString(rs.getString("last_seen"))).append(",");
                            json.append("\"joins\":").append(rs.getLong("joins")).append(",");
                            json.append("\"leaves\":").append(rs.getLong("leaves")).append(",");
                            long kills = rs.getLong("kills");
                            long deaths = rs.getLong("deaths");
                            long totalPlaytimeSeconds = rs.getLong("total_playtime_seconds");
                            json.append("\"kills\":").append(kills).append(",");
                            json.append("\"deaths\":").append(deaths).append(",");
                            json.append("\"kdRatio\":").append(formatKdRatio(kills, deaths)).append(",");
                            json.append("\"totalPlaytimeSeconds\":").append(totalPlaytimeSeconds);
                            json.append("}");
                        }
                        json.append("]");
                        return json.toString();
                    }
                }
            } catch (SQLException ex) {
                PlayeranalyticsForgeMod.LOGGER.error("Failed to query player list", ex);
                return "[]";
            }
        }
    }

    public static String getPlayerProfileJson(String playerUuid) {
        synchronized (LOCK) {
            try {
                Connection conn = init();
                // Get player basic stats
                String sql = "SELECT p.player_uuid, p.player_name, p.kills, p.deaths, p.total_playtime_seconds, p.last_seen, " +
                    "COALESCE(SUM(a.afk_duration_seconds), 0) AS total_afk_seconds, " +
                    "COALESCE(p.total_playtime_seconds, 0) - COALESCE(SUM(a.afk_duration_seconds), 0) AS active_playtime_seconds, " +
                    "COUNT(DISTINCT CASE WHEN a.afk_duration_seconds > 0 THEN a.id END) AS afk_periods " +
                    "FROM player_stats p " +
                    "LEFT JOIN player_afk_data a ON a.player_uuid = p.player_uuid " +
                    "WHERE p.player_uuid = ?";
                try (PreparedStatement statement = conn.prepareStatement(sql)) {
                    statement.setString(1, playerUuid);
                    try (ResultSet rs = statement.executeQuery()) {
                        if (!rs.next()) {
                            return "{\"error\":\"Player not found\"}";
                        }
                        
                        StringBuilder json = new StringBuilder();
                        json.append("{");
                        json.append("\"playerUuid\":").append(toJsonString(rs.getString("player_uuid"))).append(",");
                        json.append("\"playerName\":").append(toJsonString(rs.getString("player_name"))).append(",");
                        json.append("\"kills\":").append(rs.getLong("kills")).append(",");
                        json.append("\"deaths\":").append(rs.getLong("deaths")).append(",");
                        json.append("\"kdRatio\":").append(formatKdRatio(rs.getLong("kills"), rs.getLong("deaths"))).append(",");
                        json.append("\"totalPlaytimeSeconds\":").append(rs.getLong("total_playtime_seconds")).append(",");
                        json.append("\"activePlaytimeSeconds\":").append(rs.getLong("active_playtime_seconds")).append(",");
                        json.append("\"totalAfkSeconds\":").append(rs.getLong("total_afk_seconds")).append(",");
                        json.append("\"afkPeriods\":").append(rs.getLong("afk_periods")).append(",");
                        json.append("\"lastSeen\":").append(toJsonString(rs.getString("last_seen"))).append(",");
                        
                        // Get recent sessions
                        String sessionsSql = "SELECT COUNT(*) AS session_count FROM player_session_data WHERE player_uuid = ?";
                        try (PreparedStatement sessionsStmt = conn.prepareStatement(sessionsSql)) {
                            sessionsStmt.setString(1, playerUuid);
                            try (ResultSet sessionsRs = sessionsStmt.executeQuery()) {
                                if (sessionsRs.next()) {
                                    json.append("\"sessions\":").append(sessionsRs.getLong("session_count"));
                                }
                            }
                        }
                        json.append("}");
                        return json.toString();
                    }
                }
            } catch (SQLException ex) {
                PlayeranalyticsForgeMod.LOGGER.error("Failed to query player profile", ex);
                return "{\"error\":\"Database error\"}";
            }
        }
    }

    public static String getPlayerSessionsJson(String playerUuid, int limit) {
        synchronized (LOCK) {
            try {
                Connection conn = init();
                String sql = "SELECT player_uuid, player_name, session_start, session_end, duration_seconds " +
                    "FROM player_session_data WHERE player_uuid = ? ORDER BY session_end DESC LIMIT ?";
                try (PreparedStatement statement = conn.prepareStatement(sql)) {
                    statement.setString(1, playerUuid);
                    statement.setInt(2, limit);
                    try (ResultSet rs = statement.executeQuery()) {
                        StringBuilder json = new StringBuilder();
                        json.append("[");
                        boolean first = true;
                        while (rs.next()) {
                            if (!first) {
                                json.append(",");
                            }
                            first = false;
                            json.append("{");
                            json.append("\"sessionStart\":").append(toJsonString(rs.getString("session_start"))).append(",");
                            json.append("\"sessionEnd\":").append(toJsonString(rs.getString("session_end"))).append(",");
                            json.append("\"durationSeconds\":").append(rs.getLong("duration_seconds"));
                            json.append("}");
                        }
                        json.append("]");
                        return json.toString();
                    }
                }
            } catch (SQLException ex) {
                PlayeranalyticsForgeMod.LOGGER.error("Failed to query player sessions", ex);
                return "[]";
            }
        }
    }

    public static void recordServerMetrics(double tps, int playerCount, int entityCount) {
        synchronized (LOCK) {
            try {
                Connection conn = init();
                
                // Get memory stats
                Runtime runtime = Runtime.getRuntime();
                long ramUsedMb = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024);
                long ramMaxMb = runtime.maxMemory() / (1024 * 1024);
                
                // Get CPU usage
                @SuppressWarnings("deprecation")
                double cpuUsage = com.sun.management.OperatingSystemMXBean.class.isInstance(
                    java.lang.management.ManagementFactory.getOperatingSystemMXBean()
                ) ? ((com.sun.management.OperatingSystemMXBean) java.lang.management.ManagementFactory.getOperatingSystemMXBean()).getSystemCpuLoad() * 100 : 0;
                
                String sql = "INSERT INTO server_metrics (recorded_at, tps, ram_used_mb, ram_max_mb, cpu_usage, entity_count, player_count) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?)";
                try (PreparedStatement statement = conn.prepareStatement(sql)) {
                    statement.setString(1, Instant.now().toString());
                    statement.setDouble(2, tps);
                    statement.setLong(3, ramUsedMb);
                    statement.setLong(4, ramMaxMb);
                    statement.setDouble(5, cpuUsage);
                    statement.setInt(6, entityCount);
                    statement.setInt(7, playerCount);
                    statement.executeUpdate();
                }
            } catch (SQLException ex) {
                PlayeranalyticsForgeMod.LOGGER.debug("Failed to record server metrics", ex);
            }
        }
    }

    public static String getServerMetricsJson() {
        synchronized (LOCK) {
            try {
                Connection conn = init();
                String sql = "SELECT " +
                    "AVG(tps) AS avg_tps, " +
                    "MAX(tps) AS max_tps, " +
                    "MIN(tps) AS min_tps, " +
                    "AVG(ram_used_mb) AS avg_ram_used, " +
                    "MAX(ram_used_mb) AS max_ram_used, " +
                    "AVG(cpu_usage) AS avg_cpu, " +
                    "AVG(entity_count) AS avg_entities, " +
                    "MAX(entity_count) AS max_entities, " +
                    "AVG(player_count) AS avg_players " +
                    "FROM server_metrics WHERE recorded_at > datetime('now', '-1 hour')";
                try (Statement statement = conn.createStatement(); ResultSet rs = statement.executeQuery(sql)) {
                    if (rs.next()) {
                        return "{" +
                            "\"avgTps\":" + String.format("%.2f", rs.getDouble("avg_tps")) + "," +
                            "\"maxTps\":" + String.format("%.2f", rs.getDouble("max_tps")) + "," +
                            "\"minTps\":" + String.format("%.2f", rs.getDouble("min_tps")) + "," +
                            "\"avgRamUsedMb\":" + rs.getLong("avg_ram_used") + "," +
                            "\"maxRamUsedMb\":" + rs.getLong("max_ram_used") + "," +
                            "\"avgCpuUsage\":" + String.format("%.2f", rs.getDouble("avg_cpu")) + "," +
                            "\"avgEntities\":" + rs.getLong("avg_entities") + "," +
                            "\"maxEntities\":" + rs.getLong("max_entities") + "," +
                            "\"avgPlayers\":" + rs.getLong("avg_players") +
                            "}";
                    }
                }
            } catch (SQLException ex) {
                PlayeranalyticsForgeMod.LOGGER.error("Failed to query server metrics", ex);
            }
            return "{\"avgTps\":0,\"maxTps\":0,\"minTps\":0,\"avgRamUsedMb\":0,\"maxRamUsedMb\":0,\"avgCpuUsage\":0,\"avgEntities\":0,\"maxEntities\":0,\"avgPlayers\":0}";
        }
    }

    public static String getMetricsHistoryJson(int limit) {
        synchronized (LOCK) {
            try {
                Connection conn = init();
                String sql = "SELECT recorded_at, tps, ram_used_mb, ram_max_mb, cpu_usage, entity_count, player_count " +
                    "FROM server_metrics ORDER BY recorded_at DESC LIMIT ?";
                try (PreparedStatement statement = conn.prepareStatement(sql)) {
                    statement.setInt(1, limit);
                    try (ResultSet rs = statement.executeQuery()) {
                        StringBuilder json = new StringBuilder();
                        json.append("[");
                        boolean first = true;
                        while (rs.next()) {
                            if (!first) {
                                json.append(",");
                            }
                            first = false;
                            json.append("{");
                            json.append("\"recordedAt\":").append(toJsonString(rs.getString("recorded_at"))).append(",");
                            json.append("\"tps\":").append(String.format("%.2f", rs.getDouble("tps"))).append(",");
                            json.append("\"ramUsedMb\":").append(rs.getLong("ram_used_mb")).append(",");
                            json.append("\"ramMaxMb\":").append(rs.getLong("ram_max_mb")).append(",");
                            json.append("\"cpuUsage\":").append(String.format("%.2f", rs.getDouble("cpu_usage"))).append(",");
                            json.append("\"entityCount\":").append(rs.getInt("entity_count")).append(",");
                            json.append("\"playerCount\":").append(rs.getInt("player_count"));
                            json.append("}");
                        }
                        json.append("]");
                        return json.toString();
                    }
                }
            } catch (SQLException ex) {
                PlayeranalyticsForgeMod.LOGGER.error("Failed to query metrics history", ex);
                return "[]";
            }
        }
    }

    public static void close() {
        synchronized (LOCK) {
            if (connection != null) {
                try {
                    connection.close();
                } catch (SQLException ex) {
                    PlayeranalyticsForgeMod.LOGGER.warn("Failed to close SQLite connection", ex);
                } finally {
                    connection = null;
                }
            }
        }
    }

    public static void recordKill(ServerPlayer player) {
        updateStats(player, true);
    }

    public static void recordDeath(ServerPlayer player) {
        updateStats(player, false);
    }

    public static void recordKillDetail(ServerPlayer killer, String victimType, String victimName) {
        synchronized (LOCK) {
            try {
                Connection conn = init();
                String sql = "INSERT INTO kill_details (killer_uuid, killer_name, victim_type, victim_name, kill_count, last_kill_time) " +
                    "VALUES (?, ?, ?, ?, 1, ?) " +
                    "ON CONFLICT(killer_uuid, victim_type, victim_name) DO UPDATE SET " +
                    "killer_name=excluded.killer_name, " +
                    "kill_count=kill_details.kill_count + 1, " +
                    "last_kill_time=excluded.last_kill_time";
                try (PreparedStatement statement = conn.prepareStatement(sql)) {
                    statement.setString(1, killer.getUUID().toString());
                    statement.setString(2, killer.getGameProfile().getName());
                    statement.setString(3, victimType);
                    statement.setString(4, victimName);
                    statement.setString(5, Instant.now().toString());
                    statement.executeUpdate();
                }
            } catch (SQLException ex) {
                PlayeranalyticsForgeMod.LOGGER.error("Failed to record kill detail", ex);
            }
        }
    }

    public static String getKillDetailsJson(int limit) {
        synchronized (LOCK) {
            try {
                Connection conn = init();
                String sql = "SELECT killer_uuid, killer_name, victim_type, victim_name, kill_count, last_kill_time " +
                    "FROM kill_details ORDER BY last_kill_time DESC LIMIT ?";
                try (PreparedStatement statement = conn.prepareStatement(sql)) {
                    statement.setInt(1, limit);
                    try (ResultSet rs = statement.executeQuery()) {
                        StringBuilder json = new StringBuilder();
                        json.append("[");
                        boolean first = true;
                        while (rs.next()) {
                            if (!first) {
                                json.append(",");
                            }
                            first = false;
                            json.append("{");
                            json.append("\"killerUuid\":").append(toJsonString(rs.getString("killer_uuid"))).append(",");
                            json.append("\"killerName\":").append(toJsonString(rs.getString("killer_name"))).append(",");
                            json.append("\"victimType\":").append(toJsonString(rs.getString("victim_type"))).append(",");
                            json.append("\"victimName\":").append(toJsonString(rs.getString("victim_name"))).append(",");
                            json.append("\"killCount\":").append(rs.getLong("kill_count")).append(",");
                            json.append("\"lastKillTime\":").append(toJsonString(rs.getString("last_kill_time")));
                            json.append("}");
                        }
                        json.append("]");
                        return json.toString();
                    }
                }
            } catch (SQLException ex) {
                PlayeranalyticsForgeMod.LOGGER.error("Failed to query kill details", ex);
                return "[]";
            }
        }
    }

    private static Connection init() throws SQLException {
        synchronized (LOCK) {
            if (connection != null) {
                return connection;
            }

            Path configDir = FMLPaths.CONFIGDIR.get();
            Path dbPath = configDir.resolve("playeranalytics.sqlite");
            try {
                Files.createDirectories(configDir);
            } catch (Exception ex) {
                PlayeranalyticsForgeMod.LOGGER.warn("Failed to create config directory: {}", configDir, ex);
            }
            
            String jdbcUrl = "jdbc:sqlite:" + dbPath.toAbsolutePath();

            // Force load SQLite JDBC driver - try to load from runtime libs if needed
            try {
                Class.forName("org.sqlite.JDBC");
                connection = DriverManager.getConnection(jdbcUrl);
            } catch (ClassNotFoundException ex) {
                PlayeranalyticsForgeMod.LOGGER.warn("SQLite JDBC driver not on classpath, attempting to load from libs folder");
                try {
                    Path libPath = FMLPaths.GAMEDIR.get().resolve("libs/sqlite-jdbc-3.45.3.0.jar");
                    if (Files.exists(libPath)) {
                        java.net.URL url = libPath.toUri().toURL();
                        java.net.URLClassLoader loader = new java.net.URLClassLoader(new java.net.URL[]{url}, PlayerAnalyticsDb.class.getClassLoader());
                        Class<?> driverClass = Class.forName("org.sqlite.JDBC", true, loader);
                        java.sql.Driver driver = (java.sql.Driver) driverClass.getDeclaredConstructor().newInstance();
                        java.util.Properties props = new java.util.Properties();
                        connection = driver.connect(jdbcUrl, props);
                        PlayeranalyticsForgeMod.LOGGER.info("Successfully loaded SQLite JDBC driver from libs folder and connected");
                    } else {
                        throw new SQLException("SQLite JDBC driver not available and libs JAR not found", ex);
                    }
                } catch (Exception e) {
                    PlayeranalyticsForgeMod.LOGGER.error("Failed to load SQLite JDBC driver", e);
                    throw new SQLException("SQLite JDBC driver not available", ex);
                }
            }

            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate(
                    "CREATE TABLE IF NOT EXISTS player_sessions (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                        "player_uuid TEXT NOT NULL, " +
                        "player_name TEXT NOT NULL, " +
                        "event_type TEXT NOT NULL, " +
                        "event_time_utc TEXT NOT NULL" +
                    ")"
                );
                statement.executeUpdate(
                    "CREATE TABLE IF NOT EXISTS player_stats (" +
                        "player_uuid TEXT PRIMARY KEY, " +
                        "player_name TEXT NOT NULL, " +
                        "kills INTEGER NOT NULL DEFAULT 0, " +
                        "deaths INTEGER NOT NULL DEFAULT 0, " +
                        "total_playtime_seconds INTEGER NOT NULL DEFAULT 0, " +
                        "last_seen TEXT" +
                    ")"
                );
                // Add playtime column to existing tables (migration)
                try (ResultSet rs = connection.getMetaData().getColumns(null, null, "player_stats", "total_playtime_seconds")) {
                    if (!rs.next()) {
                        statement.executeUpdate("ALTER TABLE player_stats ADD COLUMN total_playtime_seconds INTEGER NOT NULL DEFAULT 0");
                        PlayeranalyticsForgeMod.LOGGER.info("Added total_playtime_seconds column to player_stats table");
                    }
                } catch (SQLException migrationEx) {
                    PlayeranalyticsForgeMod.LOGGER.debug("total_playtime_seconds column already exists or migration not needed");
                }
                statement.executeUpdate(
                    "CREATE TABLE IF NOT EXISTS kill_details (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                        "killer_uuid TEXT NOT NULL, " +
                        "killer_name TEXT NOT NULL, " +
                        "victim_type TEXT NOT NULL, " +
                        "victim_name TEXT, " +
                        "kill_count INTEGER NOT NULL DEFAULT 1, " +
                        "last_kill_time TEXT NOT NULL, " +
                        "UNIQUE(killer_uuid, victim_type, victim_name)" +
                    ")"
                );
                statement.executeUpdate(
                    "CREATE TABLE IF NOT EXISTS player_session_data (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                        "player_uuid TEXT NOT NULL, " +
                        "player_name TEXT NOT NULL, " +
                        "session_start TEXT NOT NULL, " +
                        "session_end TEXT NOT NULL, " +
                        "duration_seconds INTEGER NOT NULL" +
                    ")"
                );
                statement.executeUpdate(
                    "CREATE TABLE IF NOT EXISTS player_afk_data (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                        "player_uuid TEXT NOT NULL, " +
                        "afk_start TEXT NOT NULL, " +
                        "afk_end TEXT NOT NULL, " +
                        "afk_duration_seconds INTEGER NOT NULL" +
                    ")"
                );
                statement.executeUpdate(
                    "CREATE INDEX IF NOT EXISTS idx_afk_player ON player_afk_data(player_uuid)"
                );
                statement.executeUpdate(
                    "CREATE TABLE IF NOT EXISTS server_metrics (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                        "recorded_at TEXT NOT NULL, " +
                        "tps REAL NOT NULL, " +
                        "ram_used_mb INTEGER NOT NULL, " +
                        "ram_max_mb INTEGER NOT NULL, " +
                        "cpu_usage REAL NOT NULL, " +
                        "entity_count INTEGER NOT NULL, " +
                        "player_count INTEGER NOT NULL" +
                    ")"
                );
                statement.executeUpdate(
                    "CREATE INDEX IF NOT EXISTS idx_metrics_timestamp ON server_metrics(recorded_at)"
                );
                statement.executeUpdate(
                    "CREATE INDEX IF NOT EXISTS idx_session_player ON player_session_data(player_uuid)"
                );
                statement.executeUpdate(
                    "CREATE INDEX IF NOT EXISTS idx_session_start ON player_session_data(session_start)"
                );
                
                // Add combat stats columns to existing tables (migration)
                try (ResultSet rs = connection.getMetaData().getColumns(null, null, "kill_details", "weapon_used")) {
                    if (!rs.next()) {
                        statement.executeUpdate("ALTER TABLE kill_details ADD COLUMN weapon_used TEXT DEFAULT 'Unknown'");
                        PlayeranalyticsForgeMod.LOGGER.info("Added weapon_used column to kill_details table");
                    }
                } catch (SQLException migrationEx) {
                    PlayeranalyticsForgeMod.LOGGER.debug("weapon_used column already exists or migration not needed");
                }
                
                try (ResultSet rs = connection.getMetaData().getColumns(null, null, "kill_details", "is_pvp")) {
                    if (!rs.next()) {
                        statement.executeUpdate("ALTER TABLE kill_details ADD COLUMN is_pvp INTEGER NOT NULL DEFAULT 0");
                        PlayeranalyticsForgeMod.LOGGER.info("Added is_pvp column to kill_details table");
                    }
                } catch (SQLException migrationEx) {
                    PlayeranalyticsForgeMod.LOGGER.debug("is_pvp column already exists or migration not needed");
                }
                
                try (ResultSet rs = connection.getMetaData().getColumns(null, null, "player_stats", "pvp_kills")) {
                    if (!rs.next()) {
                        statement.executeUpdate("ALTER TABLE player_stats ADD COLUMN pvp_kills INTEGER NOT NULL DEFAULT 0");
                        PlayeranalyticsForgeMod.LOGGER.info("Added pvp_kills column to player_stats table");
                    }
                } catch (SQLException migrationEx) {
                    PlayeranalyticsForgeMod.LOGGER.debug("pvp_kills column already exists or migration not needed");
                }
                
                try (ResultSet rs = connection.getMetaData().getColumns(null, null, "player_stats", "pve_kills")) {
                    if (!rs.next()) {
                        statement.executeUpdate("ALTER TABLE player_stats ADD COLUMN pve_kills INTEGER NOT NULL DEFAULT 0");
                        PlayeranalyticsForgeMod.LOGGER.info("Added pve_kills column to player_stats table");
                    }
                } catch (SQLException migrationEx) {
                    PlayeranalyticsForgeMod.LOGGER.debug("pve_kills column already exists or migration not needed");
                }
                
                try (ResultSet rs = connection.getMetaData().getColumns(null, null, "player_stats", "kill_streak")) {
                    if (!rs.next()) {
                        statement.executeUpdate("ALTER TABLE player_stats ADD COLUMN kill_streak INTEGER NOT NULL DEFAULT 0");
                        PlayeranalyticsForgeMod.LOGGER.info("Added kill_streak column to player_stats table");
                    }
                } catch (SQLException migrationEx) {
                    PlayeranalyticsForgeMod.LOGGER.debug("kill_streak column already exists or migration not needed");
                }
                
                try (ResultSet rs = connection.getMetaData().getColumns(null, null, "player_stats", "max_kill_streak")) {
                    if (!rs.next()) {
                        statement.executeUpdate("ALTER TABLE player_stats ADD COLUMN max_kill_streak INTEGER NOT NULL DEFAULT 0");
                        PlayeranalyticsForgeMod.LOGGER.info("Added max_kill_streak column to player_stats table");
                    }
                } catch (SQLException migrationEx) {
                    PlayeranalyticsForgeMod.LOGGER.debug("max_kill_streak column already exists or migration not needed");
                }
                
                // Create new tables for weapon stats and death causes
                statement.executeUpdate(
                    "CREATE TABLE IF NOT EXISTS weapon_stats (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                        "player_uuid TEXT NOT NULL, " +
                        "weapon_type TEXT NOT NULL, " +
                        "kill_count INTEGER NOT NULL DEFAULT 0, " +
                        "UNIQUE(player_uuid, weapon_type)" +
                    ")"
                );
                
                statement.executeUpdate(
                    "CREATE TABLE IF NOT EXISTS death_causes (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                        "player_uuid TEXT NOT NULL, " +
                        "cause_type TEXT NOT NULL, " +
                        "cause_count INTEGER NOT NULL DEFAULT 0, " +
                        "last_death_time TEXT NOT NULL, " +
                        "UNIQUE(player_uuid, cause_type)" +
                    ")"
                );
                
                statement.executeUpdate(
                    "CREATE TABLE IF NOT EXISTS player_kill_matrix (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                        "killer_uuid TEXT NOT NULL, " +
                        "victim_uuid TEXT NOT NULL, " +
                        "kill_count INTEGER NOT NULL DEFAULT 0, " +
                        "last_kill_time TEXT NOT NULL, " +
                        "UNIQUE(killer_uuid, victim_uuid)" +
                    ")"
                );
                
                statement.executeUpdate(
                    "CREATE INDEX IF NOT EXISTS idx_weapon_player ON weapon_stats(player_uuid)"
                );
                
                statement.executeUpdate(
                    "CREATE INDEX IF NOT EXISTS idx_death_cause_player ON death_causes(player_uuid)"
                );
                
                statement.executeUpdate(
                    "CREATE INDEX IF NOT EXISTS idx_kill_matrix_killer ON player_kill_matrix(killer_uuid)"
                );
            }

            return connection;
        }
    }

    public static void startSession(ServerPlayer player) {
        activeSessions.put(player.getUUID(), Instant.now());
        lastActivityTime.put(player.getUUID(), Instant.now());
        afkStartTime.remove(player.getUUID());
        PlayeranalyticsForgeMod.LOGGER.debug("Started session for player: {} ({})", player.getGameProfile().getName(), player.getUUID());
    }

    public static void recordPlayerActivity(UUID playerUuid) {
        Instant now = Instant.now();
        Instant lastActivity = lastActivityTime.get(playerUuid);
        
        // Only update if enough time has passed (to avoid excessive updates)
        if (lastActivity == null || Duration.between(lastActivity, now).getSeconds() >= 10) {
            lastActivityTime.put(playerUuid, now);
            
            // If player was AFK, record the AFK period
            Instant afkStart = afkStartTime.get(playerUuid);
            if (afkStart != null) {
                long afkDurationSeconds = Duration.between(afkStart, now).getSeconds();
                recordAfkPeriod(playerUuid, afkStart, now, afkDurationSeconds);
                afkStartTime.remove(playerUuid);
            }
        }
    }

    public static void checkAndRecordAfkStatus(UUID playerUuid) {
        Instant now = Instant.now();
        Instant lastActivity = lastActivityTime.get(playerUuid);
        
        if (lastActivity == null) {
            return;
        }
        
        long idleSeconds = Duration.between(lastActivity, now).getSeconds();
        
        if (idleSeconds >= AFK_TIMEOUT_SECONDS) {
            // Player is AFK
            if (afkStartTime.get(playerUuid) == null) {
                afkStartTime.put(playerUuid, now.minusSeconds(idleSeconds));
            }
        } else {
            // Player is active
            afkStartTime.remove(playerUuid);
        }
    }

    private static void recordAfkPeriod(UUID playerUuid, Instant afkStart, Instant afkEnd, long afkDurationSeconds) {
        synchronized (LOCK) {
            try {
                Connection conn = init();
                String sql = "INSERT INTO player_afk_data (player_uuid, afk_start, afk_end, afk_duration_seconds) VALUES (?, ?, ?, ?)";
                try (PreparedStatement statement = conn.prepareStatement(sql)) {
                    statement.setString(1, playerUuid.toString());
                    statement.setString(2, afkStart.toString());
                    statement.setString(3, afkEnd.toString());
                    statement.setLong(4, afkDurationSeconds);
                    statement.executeUpdate();
                }
            } catch (SQLException ex) {
                PlayeranalyticsForgeMod.LOGGER.debug("Failed to record AFK period", ex);
            }
        }
    }

    public static String getPlaytimeDetailsJson() {
        synchronized (LOCK) {
            try {
                Connection conn = init();
                String sql = "SELECT " +
                    "p.player_uuid, " +
                    "p.player_name, " +
                    "COALESCE(p.total_playtime_seconds, 0) AS total_playtime_seconds, " +
                    "COALESCE(SUM(a.afk_duration_seconds), 0) AS total_afk_seconds, " +
                    "COALESCE(p.total_playtime_seconds, 0) - COALESCE(SUM(a.afk_duration_seconds), 0) AS active_playtime_seconds, " +
                    "COUNT(DISTINCT CASE WHEN a.afk_duration_seconds > 0 THEN a.id END) AS afk_periods " +
                    "FROM player_stats p " +
                    "LEFT JOIN player_afk_data a ON a.player_uuid = p.player_uuid " +
                    "GROUP BY p.player_uuid, p.player_name, p.total_playtime_seconds " +
                    "ORDER BY p.total_playtime_seconds DESC";
                try (Statement statement = conn.createStatement(); ResultSet rs = statement.executeQuery(sql)) {
                    StringBuilder json = new StringBuilder();
                    json.append("[");
                    boolean first = true;
                    while (rs.next()) {
                        if (!first) {
                            json.append(",");
                        }
                        first = false;
                        json.append("{");
                        json.append("\"playerUuid\":").append(toJsonString(rs.getString("player_uuid"))).append(",");
                        json.append("\"playerName\":").append(toJsonString(rs.getString("player_name"))).append(",");
                        json.append("\"totalPlaytimeSeconds\":").append(rs.getLong("total_playtime_seconds")).append(",");
                        json.append("\"totalAfkSeconds\":").append(rs.getLong("total_afk_seconds")).append(",");
                        json.append("\"activePlaytimeSeconds\":").append(rs.getLong("active_playtime_seconds")).append(",");
                        json.append("\"afkPeriods\":").append(rs.getLong("afk_periods"));
                        json.append("}");
                    }
                    json.append("]");
                    return json.toString();
                }
            } catch (SQLException ex) {
                PlayeranalyticsForgeMod.LOGGER.error("Failed to query playtime details", ex);
                return "[]";
            }
        }
    }

    public static void endSession(ServerPlayer player) {
        Instant startTime = activeSessions.remove(player.getUUID());
        if (startTime == null) {
            PlayeranalyticsForgeMod.LOGGER.warn("No session start time found for player: {}", player.getGameProfile().getName());
            return;
        }

        Instant endTime = Instant.now();
        long durationSeconds = Duration.between(startTime, endTime).getSeconds();

        synchronized (LOCK) {
            try {
                Connection conn = init();
                // Record session
                String sql = "INSERT INTO player_session_data (player_uuid, player_name, session_start, session_end, duration_seconds) VALUES (?, ?, ?, ?, ?)";
                try (PreparedStatement statement = conn.prepareStatement(sql)) {
                    statement.setString(1, player.getUUID().toString());
                    statement.setString(2, player.getGameProfile().getName());
                    statement.setString(3, startTime.toString());
                    statement.setString(4, endTime.toString());
                    statement.setLong(5, durationSeconds);
                    statement.executeUpdate();
                }
                
                // Update total playtime in player_stats
                String updatePlaytimeSql = "INSERT INTO player_stats (player_uuid, player_name, total_playtime_seconds, last_seen) " +
                    "VALUES (?, ?, ?, ?) " +
                    "ON CONFLICT(player_uuid) DO UPDATE SET " +
                    "total_playtime_seconds = total_playtime_seconds + ?, " +
                    "player_name = ?, " +
                    "last_seen = ?";
                try (PreparedStatement updateStmt = conn.prepareStatement(updatePlaytimeSql)) {
                    updateStmt.setString(1, player.getUUID().toString());
                    updateStmt.setString(2, player.getGameProfile().getName());
                    updateStmt.setLong(3, durationSeconds);
                    updateStmt.setString(4, endTime.toString());
                    updateStmt.setLong(5, durationSeconds);
                    updateStmt.setString(6, player.getGameProfile().getName());
                    updateStmt.setString(7, endTime.toString());
                    updateStmt.executeUpdate();
                }
                
                PlayeranalyticsForgeMod.LOGGER.info("Recorded session for {}: {} seconds", player.getGameProfile().getName(), durationSeconds);
            } catch (SQLException ex) {
                PlayeranalyticsForgeMod.LOGGER.error("Failed to record session", ex);
            }
        }
    }

    public static String getSessionsJson(int limit) {
        synchronized (LOCK) {
            try {
                Connection conn = init();
                String sql = "SELECT player_uuid, player_name, session_start, session_end, duration_seconds " +
                    "FROM player_session_data ORDER BY session_end DESC LIMIT ?";
                try (PreparedStatement statement = conn.prepareStatement(sql)) {
                    statement.setInt(1, limit);
                    try (ResultSet rs = statement.executeQuery()) {
                        StringBuilder json = new StringBuilder();
                        json.append("[");
                        boolean first = true;
                        while (rs.next()) {
                            if (!first) {
                                json.append(",");
                            }
                            first = false;
                            json.append("{");
                            json.append("\"playerUuid\":").append(toJsonString(rs.getString("player_uuid"))).append(",");
                            json.append("\"playerName\":").append(toJsonString(rs.getString("player_name"))).append(",");
                            json.append("\"sessionStart\":").append(toJsonString(rs.getString("session_start"))).append(",");
                            json.append("\"sessionEnd\":").append(toJsonString(rs.getString("session_end"))).append(",");
                            json.append("\"durationSeconds\":").append(rs.getLong("duration_seconds"));
                            json.append("}");
                        }
                        json.append("]");
                        return json.toString();
                    }
                }
            } catch (SQLException ex) {
                PlayeranalyticsForgeMod.LOGGER.error("Failed to query sessions", ex);
            }
            return "[]";
        }
    }

    private static String toJsonString(String value) {
        if (value == null) {
            return "null";
        }
        String escaped = value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t");
        return "\"" + escaped + "\"";
    }

    private static void updateStats(ServerPlayer player, boolean isKill) {
        synchronized (LOCK) {
            try {
                Connection conn = init();
                String sql;
                if (isKill) {
                    sql = "INSERT INTO player_stats (player_uuid, player_name, kills, deaths, last_seen) " +
                        "VALUES (?, ?, 1, 0, ?) " +
                        "ON CONFLICT(player_uuid) DO UPDATE SET " +
                        "player_name=excluded.player_name, " +
                        "kills=player_stats.kills + 1, " +
                        "last_seen=excluded.last_seen";
                } else {
                    sql = "INSERT INTO player_stats (player_uuid, player_name, kills, deaths, last_seen) " +
                        "VALUES (?, ?, 0, 1, ?) " +
                        "ON CONFLICT(player_uuid) DO UPDATE SET " +
                        "player_name=excluded.player_name, " +
                        "deaths=player_stats.deaths + 1, " +
                        "last_seen=excluded.last_seen";
                }
                try (PreparedStatement statement = conn.prepareStatement(sql)) {
                    statement.setString(1, player.getUUID().toString());
                    statement.setString(2, player.getGameProfile().getName());
                    statement.setString(3, Instant.now().toString());
                    statement.executeUpdate();
                }
            } catch (SQLException ex) {
                PlayeranalyticsForgeMod.LOGGER.error("Failed to update player stats", ex);
            }
        }
    }

    private static String formatKdRatio(long kills, long deaths) {
        if (deaths == 0) {
            return kills == 0 ? "0" : String.format(java.util.Locale.US, "%.2f", (double) kills);
        }
        double ratio = (double) kills / (double) deaths;
        return String.format(java.util.Locale.US, "%.2f", ratio);
    }

    public static void recordWeaponUsage(String killerUuid, String killerName, String weaponType) {
        synchronized (LOCK) {
            try {
                Connection conn = init();
                String sql = "INSERT INTO weapon_stats (player_uuid, weapon_type, kill_count) " +
                    "VALUES (?, ?, 1) " +
                    "ON CONFLICT(player_uuid, weapon_type) DO UPDATE SET " +
                    "kill_count = kill_count + 1";
                try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                    stmt.setString(1, killerUuid);
                    stmt.setString(2, weaponType);
                    stmt.executeUpdate();
                }
            } catch (SQLException ex) {
                PlayeranalyticsForgeMod.LOGGER.error("Failed to record weapon usage", ex);
            }
        }
    }

    public static void recordDeathCause(String playerUuid, String playerName, String causeType) {
        synchronized (LOCK) {
            try {
                Connection conn = init();
                String sql = "INSERT INTO death_causes (player_uuid, cause_type, cause_count, last_death_time) " +
                    "VALUES (?, ?, 1, ?) " +
                    "ON CONFLICT(player_uuid, cause_type) DO UPDATE SET " +
                    "cause_count = cause_count + 1, " +
                    "last_death_time = excluded.last_death_time";
                try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                    stmt.setString(1, playerUuid);
                    stmt.setString(2, causeType);
                    stmt.setString(3, java.time.Instant.now().toString());
                    stmt.executeUpdate();
                }
            } catch (SQLException ex) {
                PlayeranalyticsForgeMod.LOGGER.error("Failed to record death cause", ex);
            }
        }
    }

    public static void recordPlayerKillMatrix(String killerUuid, String victimUuid) {
        synchronized (LOCK) {
            try {
                Connection conn = init();
                String sql = "INSERT INTO player_kill_matrix (killer_uuid, victim_uuid, kill_count, last_kill_time) " +
                    "VALUES (?, ?, 1, ?) " +
                    "ON CONFLICT(killer_uuid, victim_uuid) DO UPDATE SET " +
                    "kill_count = kill_count + 1, " +
                    "last_kill_time = excluded.last_kill_time";
                try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                    stmt.setString(1, killerUuid);
                    stmt.setString(2, victimUuid);
                    stmt.setString(3, java.time.Instant.now().toString());
                    stmt.executeUpdate();
                }
            } catch (SQLException ex) {
                PlayeranalyticsForgeMod.LOGGER.error("Failed to record player kill matrix", ex);
            }
        }
    }

    public static void recordKillStreak(String killerUuid) {
        synchronized (LOCK) {
            try {
                Connection conn = init();
                
                // Update current kill streak
                String updateStreakSql = "INSERT INTO player_stats (player_uuid, player_name, kill_streak, max_kill_streak) " +
                    "VALUES (?, 'Unknown', 1, 1) " +
                    "ON CONFLICT(player_uuid) DO UPDATE SET " +
                    "kill_streak = kill_streak + 1, " +
                    "max_kill_streak = MAX(kill_streak + 1, max_kill_streak)";
                try (PreparedStatement stmt = conn.prepareStatement(updateStreakSql)) {
                    stmt.setString(1, killerUuid);
                    stmt.executeUpdate();
                }
            } catch (SQLException ex) {
                PlayeranalyticsForgeMod.LOGGER.error("Failed to record kill streak", ex);
            }
        }
    }

    public static void resetKillStreak(String playerUuid) {
        synchronized (LOCK) {
            try {
                Connection conn = init();
                String sql = "UPDATE player_stats SET kill_streak = 0 WHERE player_uuid = ?";
                try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                    stmt.setString(1, playerUuid);
                    stmt.executeUpdate();
                }
            } catch (SQLException ex) {
                PlayeranalyticsForgeMod.LOGGER.error("Failed to reset kill streak", ex);
            }
        }
    }

    public static String getWeaponStatsJson() {
        synchronized (LOCK) {
            try {
                Connection conn = init();
                String sql = "SELECT w.player_uuid, w.weapon_type, w.kill_count, p.player_name " +
                    "FROM weapon_stats w " +
                    "LEFT JOIN player_stats p ON w.player_uuid = p.player_uuid " +
                    "ORDER BY w.player_uuid, w.kill_count DESC";
                
                StringBuilder json = new StringBuilder("[");
                try (Statement stmt = conn.createStatement();
                     ResultSet rs = stmt.executeQuery(sql)) {
                    boolean first = true;
                    while (rs.next()) {
                        if (!first) json.append(",");
                        json.append("{\"player_uuid\":\"").append(rs.getString("player_uuid"))
                            .append("\",\"player_name\":\"").append(rs.getString("player_name"))
                            .append("\",\"weapon\":\"").append(rs.getString("weapon_type"))
                            .append("\",\"kills\":").append(rs.getInt("kill_count"))
                            .append("}");
                        first = false;
                    }
                }
                json.append("]");
                return json.toString();
            } catch (SQLException ex) {
                PlayeranalyticsForgeMod.LOGGER.error("Failed to get weapon stats", ex);
                return "[]";
            }
        }
    }

    public static String getCombatStatsJson() {
        synchronized (LOCK) {
            try {
                Connection conn = init();
                String sql = "SELECT player_uuid, player_name, kills, deaths, pvp_kills, pve_kills, kill_streak, max_kill_streak " +
                    "FROM player_stats " +
                    "ORDER BY kills DESC";
                
                StringBuilder json = new StringBuilder("[");
                try (Statement stmt = conn.createStatement();
                     ResultSet rs = stmt.executeQuery(sql)) {
                    boolean first = true;
                    while (rs.next()) {
                        if (!first) json.append(",");
                        int totalKills = rs.getInt("kills");
                        double pvpRatio = totalKills > 0 ? (double) rs.getInt("pvp_kills") / totalKills * 100 : 0;
                        json.append("{\"player_uuid\":\"").append(rs.getString("player_uuid"))
                            .append("\",\"player_name\":\"").append(rs.getString("player_name"))
                            .append("\",\"total_kills\":").append(totalKills)
                            .append(",\"pvp_kills\":").append(rs.getInt("pvp_kills"))
                            .append(",\"pve_kills\":").append(rs.getInt("pve_kills"))
                            .append(",\"pvp_ratio\":").append(String.format("%.1f", pvpRatio))
                            .append(",\"deaths\":").append(rs.getInt("deaths"))
                            .append(",\"kill_streak\":").append(rs.getInt("kill_streak"))
                            .append(",\"max_kill_streak\":").append(rs.getInt("max_kill_streak"))
                            .append(",\"kd_ratio\":\"").append(formatKdRatio(totalKills, rs.getInt("deaths")))
                            .append("\"}");
                        first = false;
                    }
                }
                json.append("]");
                return json.toString();
            } catch (SQLException ex) {
                PlayeranalyticsForgeMod.LOGGER.error("Failed to get combat stats", ex);
                return "[]";
            }
        }
    }

    public static String getDeathCausesJson(String playerUuid) {
        synchronized (LOCK) {
            try {
                Connection conn = init();
                String sql = "SELECT cause_type, cause_count FROM death_causes WHERE player_uuid = ? ORDER BY cause_count DESC";
                
                StringBuilder json = new StringBuilder("[");
                try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                    stmt.setString(1, playerUuid);
                    try (ResultSet rs = stmt.executeQuery()) {
                        boolean first = true;
                        while (rs.next()) {
                            if (!first) json.append(",");
                            json.append("{\"cause\":\"").append(rs.getString("cause_type"))
                                .append("\",\"count\":").append(rs.getInt("cause_count"))
                                .append("}");
                            first = false;
                        }
                    }
                }
                json.append("]");
                return json.toString();
            } catch (SQLException ex) {
                PlayeranalyticsForgeMod.LOGGER.error("Failed to get death causes", ex);
                return "[]";
            }
        }
    }

    public static String getKillMatrixJson(String killerUuid) {
        synchronized (LOCK) {
            try {
                Connection conn = init();
                String sql = "SELECT pkm.victim_uuid, p.player_name, pkm.kill_count, pkm.last_kill_time " +
                    "FROM player_kill_matrix pkm " +
                    "LEFT JOIN player_stats p ON pkm.victim_uuid = p.player_uuid " +
                    "WHERE pkm.killer_uuid = ? " +
                    "ORDER BY pkm.kill_count DESC";
                
                StringBuilder json = new StringBuilder("[");
                try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                    stmt.setString(1, killerUuid);
                    try (ResultSet rs = stmt.executeQuery()) {
                        boolean first = true;
                        while (rs.next()) {
                            if (!first) json.append(",");
                            json.append("{\"victim_uuid\":\"").append(rs.getString("victim_uuid"))
                                .append("\",\"victim_name\":\"").append(rs.getString("player_name"))
                                .append("\",\"kill_count\":").append(rs.getInt("kill_count"))
                                .append(",\"last_kill\":\"").append(rs.getString("last_kill_time"))
                                .append("\"}");
                            first = false;
                        }
                    }
                }
                json.append("]");
                return json.toString();
            } catch (SQLException ex) {
                PlayeranalyticsForgeMod.LOGGER.error("Failed to get kill matrix", ex);
                return "[]";
            }
        }
    }
}

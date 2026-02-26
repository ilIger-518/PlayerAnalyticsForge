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
                
                // Track first join date
                if ("join".equals(eventType)) {
                    String checkSql = "SELECT first_join FROM player_stats WHERE player_uuid = ?";
                    try (PreparedStatement checkStmt = conn.prepareStatement(checkSql)) {
                        checkStmt.setString(1, player.getUUID().toString());
                        try (ResultSet rs = checkStmt.executeQuery()) {
                            if (!rs.next() || rs.getString("first_join") == null) {
                                // First time joining or first_join not set
                                String updateSql = "INSERT INTO player_stats (player_uuid, player_name, first_join) " +
                                    "VALUES (?, ?, ?) " +
                                    "ON CONFLICT(player_uuid) DO UPDATE SET first_join = COALESCE(first_join, excluded.first_join)";
                                try (PreparedStatement updateStmt = conn.prepareStatement(updateSql)) {
                                    updateStmt.setString(1, player.getUUID().toString());
                                    updateStmt.setString(2, player.getGameProfile().getName());
                                    updateStmt.setString(3, Instant.now().toString());
                                    updateStmt.executeUpdate();
                                }
                            }
                        }
                    }
                    
                    // Track hourly activity
                    int hour = java.time.ZonedDateTime.now(java.time.ZoneOffset.UTC).getHour();
                    String hourlySql = "INSERT INTO hourly_activity (hour_of_day, join_count) VALUES (?, 1) " +
                        "ON CONFLICT(hour_of_day) DO UPDATE SET join_count = join_count + 1";
                    try (PreparedStatement hourlyStmt = conn.prepareStatement(hourlySql)) {
                        hourlyStmt.setInt(1, hour);
                        hourlyStmt.executeUpdate();
                    }
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
                
                try (ResultSet rs = connection.getMetaData().getColumns(null, null, "player_stats", "first_join")) {
                    if (!rs.next()) {
                        statement.executeUpdate("ALTER TABLE player_stats ADD COLUMN first_join TEXT");
                        PlayeranalyticsForgeMod.LOGGER.info("Added first_join column to player_stats table");
                    }
                } catch (SQLException migrationEx) {
                    PlayeranalyticsForgeMod.LOGGER.debug("first_join column already exists or migration not needed");
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
                
                // Create tables for activity tracking and insights
                statement.executeUpdate(
                    "CREATE TABLE IF NOT EXISTS daily_activity (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                        "activity_date TEXT NOT NULL, " +
                        "unique_players INTEGER NOT NULL DEFAULT 0, " +
                        "total_sessions INTEGER NOT NULL DEFAULT 0, " +
                        "total_joins INTEGER NOT NULL DEFAULT 0, " +
                        "total_leaves INTEGER NOT NULL DEFAULT 0, " +
                        "avg_session_duration INTEGER NOT NULL DEFAULT 0, " +
                        "UNIQUE(activity_date)" +
                    ")"
                );
                
                statement.executeUpdate(
                    "CREATE TABLE IF NOT EXISTS hourly_activity (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                        "hour_of_day INTEGER NOT NULL, " +
                        "join_count INTEGER NOT NULL DEFAULT 0, " +
                        "UNIQUE(hour_of_day)" +
                    ")"
                );
                
                statement.executeUpdate(
                    "CREATE INDEX IF NOT EXISTS idx_daily_activity_date ON daily_activity(activity_date)"
                );
                
                // World/Dimension tracking tables
                statement.executeUpdate(
                    "CREATE TABLE IF NOT EXISTS world_playtime (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                        "player_uuid TEXT NOT NULL, " +
                        "world_name TEXT NOT NULL, " +
                        "playtime_seconds LONG NOT NULL DEFAULT 0, " +
                        "last_updated TEXT NOT NULL, " +
                        "UNIQUE(player_uuid, world_name)" +
                    ")"
                );
                
                statement.executeUpdate(
                    "CREATE TABLE IF NOT EXISTS world_kills (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                        "killer_uuid TEXT NOT NULL, " +
                        "victim_name TEXT NOT NULL, " +
                        "victim_type TEXT NOT NULL, " +
                        "world_name TEXT NOT NULL, " +
                        "weapon_used TEXT, " +
                        "kill_time_utc TEXT NOT NULL, " +
                        "is_pvp BOOLEAN NOT NULL DEFAULT 0" +
                    ")"
                );
                
                statement.executeUpdate(
                    "CREATE TABLE IF NOT EXISTS world_sessions (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                        "player_uuid TEXT NOT NULL, " +
                        "player_name TEXT NOT NULL, " +
                        "world_name TEXT NOT NULL, " +
                        "joined_time TEXT NOT NULL, " +
                        "left_time TEXT, " +
                        "duration_seconds LONG, " +
                        "is_current BOOLEAN NOT NULL DEFAULT 1" +
                    ")"
                );
                
                statement.executeUpdate(
                    "CREATE INDEX IF NOT EXISTS idx_world_playtime_world ON world_playtime(world_name)"
                );
                
                statement.executeUpdate(
                    "CREATE INDEX IF NOT EXISTS idx_world_playtime_player ON world_playtime(player_uuid)"
                );
                
                statement.executeUpdate(
                    "CREATE INDEX IF NOT EXISTS idx_world_kills_world ON world_kills(world_name)"
                );
                
                statement.executeUpdate(
                    "CREATE INDEX IF NOT EXISTS idx_world_kills_killer ON world_kills(killer_uuid)"
                );
                
                statement.executeUpdate(
                    "CREATE INDEX IF NOT EXISTS idx_world_sessions_world ON world_sessions(world_name)"
                );
                
                statement.executeUpdate(
                    "CREATE INDEX IF NOT EXISTS idx_world_sessions_player ON world_sessions(player_uuid)"
                );
                
                // Network tracking tables
                statement.executeUpdate(
                    "CREATE TABLE IF NOT EXISTS servers (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                        "server_id TEXT NOT NULL UNIQUE, " +
                        "network_name TEXT NOT NULL, " +
                        "server_name TEXT NOT NULL, " +
                        "last_sync TEXT NOT NULL, " +
                        "is_online BOOLEAN NOT NULL DEFAULT 1" +
                    ")"
                );
                
                statement.executeUpdate(
                    "CREATE TABLE IF NOT EXISTS player_server_history (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                        "player_uuid TEXT NOT NULL, " +
                        "player_name TEXT NOT NULL, " +
                        "server_id TEXT NOT NULL, " +
                        "joined_time TEXT NOT NULL, " +
                        "left_time TEXT, " +
                        "duration_seconds LONG, " +
                        "is_current BOOLEAN NOT NULL DEFAULT 1" +
                    ")"
                );
                
                statement.executeUpdate(
                    "CREATE TABLE IF NOT EXISTS network_sync_log (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                        "sync_time TEXT NOT NULL, " +
                        "server_id TEXT NOT NULL, " +
                        "stats_sync_count INTEGER NOT NULL, " +
                        "success BOOLEAN NOT NULL DEFAULT 1, " +
                        "error_message TEXT" +
                    ")"
                );
                
                statement.executeUpdate(
                    "CREATE INDEX IF NOT EXISTS idx_player_server_history_uuid ON player_server_history(player_uuid)"
                );
                
                statement.executeUpdate(
                    "CREATE INDEX IF NOT EXISTS idx_player_server_history_server ON player_server_history(server_id)"
                );
                
                statement.executeUpdate(
                    "CREATE INDEX IF NOT EXISTS idx_servers_network ON servers(network_name)"
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

    public static long getPlayerPlaytimeSeconds(String playerUUID) throws SQLException {
        synchronized (LOCK) {
            Connection conn = init();
            String sql = "SELECT total_playtime_seconds FROM player_stats WHERE player_uuid = ?";
            try (PreparedStatement statement = conn.prepareStatement(sql)) {
                statement.setString(1, playerUUID);
                try (ResultSet rs = statement.executeQuery()) {
                    if (rs.next()) {
                        return rs.getLong("total_playtime_seconds");
                    }
                }
            }
            return 0;
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

    public static void updateDailyActivity() {
        synchronized (LOCK) {
            try {
                Connection conn = init();
                String today = java.time.LocalDate.now(java.time.ZoneOffset.UTC).toString();
                
                // Count unique players who joined today
                String uniquePlayersSql = "SELECT COUNT(DISTINCT player_uuid) FROM player_sessions " +
                    "WHERE DATE(event_time_utc) = ? AND event_type = 'join'";
                
                // Count total sessions that started today
                String sessionsSql = "SELECT COUNT(*) FROM player_session_data WHERE DATE(session_start) = ?";
                
                // Count joins and leaves today
                String eventsSql = "SELECT " +
                    "SUM(CASE WHEN event_type='join' THEN 1 ELSE 0 END) AS joins, " +
                    "SUM(CASE WHEN event_type='leave' THEN 1 ELSE 0 END) AS leaves " +
                    "FROM player_sessions WHERE DATE(event_time_utc) = ?";
                
                // Get average session duration for today
                String avgDurationSql = "SELECT AVG(duration_seconds) FROM player_session_data WHERE DATE(session_start) = ?";
                
                int uniquePlayers = 0;
                int totalSessions = 0;
                int totalJoins = 0;
                int totalLeaves = 0;
                int avgDuration = 0;
                
                try (PreparedStatement stmt = conn.prepareStatement(uniquePlayersSql)) {
                    stmt.setString(1, today);
                    try (ResultSet rs = stmt.executeQuery()) {
                        if (rs.next()) uniquePlayers = rs.getInt(1);
                    }
                }
                
                try (PreparedStatement stmt = conn.prepareStatement(sessionsSql)) {
                    stmt.setString(1, today);
                    try (ResultSet rs = stmt.executeQuery()) {
                        if (rs.next()) totalSessions = rs.getInt(1);
                    }
                }
                
                try (PreparedStatement stmt = conn.prepareStatement(eventsSql)) {
                    stmt.setString(1, today);
                    try (ResultSet rs = stmt.executeQuery()) {
                        if (rs.next()) {
                            totalJoins = rs.getInt("joins");
                            totalLeaves = rs.getInt("leaves");
                        }
                    }
                }
                
                try (PreparedStatement stmt = conn.prepareStatement(avgDurationSql)) {
                    stmt.setString(1, today);
                    try (ResultSet rs = stmt.executeQuery()) {
                        if (rs.next()) avgDuration = rs.getInt(1);
                    }
                }
                
                // Insert or update daily activity
                String insertSql = "INSERT INTO daily_activity (activity_date, unique_players, total_sessions, total_joins, total_leaves, avg_session_duration) " +
                    "VALUES (?, ?, ?, ?, ?, ?) " +
                    "ON CONFLICT(activity_date) DO UPDATE SET " +
                    "unique_players = excluded.unique_players, " +
                    "total_sessions = excluded.total_sessions, " +
                    "total_joins = excluded.total_joins, " +
                    "total_leaves = excluded.total_leaves, " +
                    "avg_session_duration = excluded.avg_session_duration";
                
                try (PreparedStatement stmt = conn.prepareStatement(insertSql)) {
                    stmt.setString(1, today);
                    stmt.setInt(2, uniquePlayers);
                    stmt.setInt(3, totalSessions);
                    stmt.setInt(4, totalJoins);
                    stmt.setInt(5, totalLeaves);
                    stmt.setInt(6, avgDuration);
                    stmt.executeUpdate();
                }
            } catch (SQLException ex) {
                PlayeranalyticsForgeMod.LOGGER.error("Failed to update daily activity", ex);
            }
        }
    }

    public static String getActivityTrendsJson(int days) {
        synchronized (LOCK) {
            try {
                Connection conn = init();
                String sql = "SELECT * FROM daily_activity ORDER BY activity_date DESC LIMIT ?";
                
                StringBuilder json = new StringBuilder("[");
                try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                    stmt.setInt(1, days);
                    try (ResultSet rs = stmt.executeQuery()) {
                        boolean first = true;
                        while (rs.next()) {
                            if (!first) json.append(",");
                            json.append("{\"date\":\"").append(rs.getString("activity_date"))
                                .append("\",\"unique_players\":").append(rs.getInt("unique_players"))
                                .append(",\"total_sessions\":").append(rs.getInt("total_sessions"))
                                .append(",\"total_joins\":").append(rs.getInt("total_joins"))
                                .append(",\"total_leaves\":").append(rs.getInt("total_leaves"))
                                .append(",\"avg_session_seconds\":").append(rs.getInt("avg_session_duration"))
                                .append("}");
                            first = false;
                        }
                    }
                }
                json.append("]");
                return json.toString();
            } catch (SQLException ex) {
                PlayeranalyticsForgeMod.LOGGER.error("Failed to get activity trends", ex);
                return "[]";
            }
        }
    }

    public static String getHourlyActivityJson() {
        synchronized (LOCK) {
            try {
                Connection conn = init();
                String sql = "SELECT hour_of_day, join_count FROM hourly_activity ORDER BY hour_of_day";
                
                StringBuilder json = new StringBuilder("[");
                try (Statement stmt = conn.createStatement();
                     ResultSet rs = stmt.executeQuery(sql)) {
                    boolean first = true;
                    while (rs.next()) {
                        if (!first) json.append(",");
                        json.append("{\"hour\":").append(rs.getInt("hour_of_day"))
                            .append(",\"joins\":").append(rs.getInt("join_count"))
                            .append("}");
                        first = false;
                    }
                }
                json.append("]");
                return json.toString();
            } catch (SQLException ex) {
                PlayeranalyticsForgeMod.LOGGER.error("Failed to get hourly activity", ex);
                return "[]";
            }
        }
    }

    public static String getSessionInsightsJson() {
        synchronized (LOCK) {
            try {
                Connection conn = init();
                
                // Get overall session stats
                String overallSql = "SELECT " +
                    "COUNT(*) AS total_sessions, " +
                    "AVG(duration_seconds) AS avg_duration, " +
                    "MAX(duration_seconds) AS max_duration, " +
                    "MIN(duration_seconds) AS min_duration " +
                    "FROM player_session_data";
                
                // Get sessions per day for last 30 days
                String perDaySql = "SELECT " +
                    "DATE(session_start) AS session_date, " +
                    "COUNT(*) AS sessions, " +
                    "AVG(duration_seconds) AS avg_duration " +
                    "FROM player_session_data " +
                    "WHERE DATE(session_start) >= DATE('now', '-30 days') " +
                    "GROUP BY DATE(session_start) " +
                    "ORDER BY session_date DESC";
                
                StringBuilder json = new StringBuilder("{");
                
                // Overall stats
                try (Statement stmt = conn.createStatement();
                     ResultSet rs = stmt.executeQuery(overallSql)) {
                    if (rs.next()) {
                        json.append("\"total_sessions\":").append(rs.getInt("total_sessions"))
                            .append(",\"avg_duration_seconds\":").append(rs.getInt("avg_duration"))
                            .append(",\"max_duration_seconds\":").append(rs.getInt("max_duration"))
                            .append(",\"min_duration_seconds\":").append(rs.getInt("min_duration"));
                    }
                }
                
                // Per-day breakdown
                json.append(",\"daily_sessions\":[");
                try (Statement stmt = conn.createStatement();
                     ResultSet rs = stmt.executeQuery(perDaySql)) {
                    boolean first = true;
                    while (rs.next()) {
                        if (!first) json.append(",");
                        json.append("{\"date\":\"").append(rs.getString("session_date"))
                            .append("\",\"sessions\":").append(rs.getInt("sessions"))
                            .append(",\"avg_duration\":").append(rs.getInt("avg_duration"))
                            .append("}");
                        first = false;
                    }
                }
                json.append("]}");
                
                return json.toString();
            } catch (SQLException ex) {
                PlayeranalyticsForgeMod.LOGGER.error("Failed to get session insights", ex);
                return "{\"error\":\"Failed to retrieve session insights\"}";
            }
        }
    }

    public static String getLeaderboardJson(String type, int limit) {
        synchronized (LOCK) {
            try {
                Connection conn = init();
                String sql;
                
                // Determine query based on leaderboard type
                switch (type.toLowerCase()) {
                    case "playtime":
                        sql = "SELECT player_uuid, player_name, total_playtime_seconds AS value " +
                              "FROM player_stats " +
                              "ORDER BY total_playtime_seconds DESC " +
                              "LIMIT ?";
                        break;
                    case "kd_ratio":
                        sql = "SELECT player_uuid, player_name, " +
                              "CAST(total_kills AS FLOAT) / NULLIF(total_deaths, 0) AS value " +
                              "FROM player_stats " +
                              "WHERE total_kills > 0 " +
                              "ORDER BY value DESC " +
                              "LIMIT ?";
                        break;
                    case "kill_streak":
                        sql = "SELECT player_uuid, player_name, max_kill_streak AS value " +
                              "FROM player_stats " +
                              "WHERE max_kill_streak > 0 " +
                              "ORDER BY max_kill_streak DESC " +
                              "LIMIT ?";
                        break;
                    case "total_kills":
                        sql = "SELECT player_uuid, player_name, total_kills AS value " +
                              "FROM player_stats " +
                              "ORDER BY total_kills DESC " +
                              "LIMIT ?";
                        break;
                    case "deaths":
                        sql = "SELECT player_uuid, player_name, total_deaths AS value " +
                              "FROM player_stats " +
                              "ORDER BY total_deaths DESC " +
                              "LIMIT ?";
                        break;
                    case "sessions":
                        sql = "SELECT ps.player_uuid, ps.player_name, COUNT(psd.id) AS value " +
                              "FROM player_stats ps " +
                              "LEFT JOIN player_session_data psd ON ps.player_uuid = psd.player_uuid " +
                              "GROUP BY ps.player_uuid, ps.player_name " +
                              "ORDER BY value DESC " +
                              "LIMIT ?";
                        break;
                    case "pvp_kills":
                        sql = "SELECT player_uuid, player_name, pvp_kills AS value " +
                              "FROM player_stats " +
                              "WHERE pvp_kills > 0 " +
                              "ORDER BY pvp_kills DESC " +
                              "LIMIT ?";
                        break;
                    case "pve_kills":
                        sql = "SELECT player_uuid, player_name, pve_kills AS value " +
                              "FROM player_stats " +
                              "WHERE pve_kills > 0 " +
                              "ORDER BY pve_kills DESC " +
                              "LIMIT ?";
                        break;
                    default:
                        return "{\"error\":\"Invalid leaderboard type\"}";
                }
                
                StringBuilder json = new StringBuilder("[");
                try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                    stmt.setInt(1, limit);
                    try (ResultSet rs = stmt.executeQuery()) {
                        boolean first = true;
                        int rank = 1;
                        while (rs.next()) {
                            if (!first) json.append(",");
                            json.append("{\"rank\":").append(rank++)
                                .append(",\"player_uuid\":\"").append(rs.getString("player_uuid"))
                                .append("\",\"player_name\":").append(toJsonString(rs.getString("player_name")))
                                .append(",\"value\":");
                            
                            // Format value based on type
                            if ("kd_ratio".equals(type.toLowerCase())) {
                                double value = rs.getDouble("value");
                                json.append(String.format("%.2f", value));
                            } else if ("playtime".equals(type.toLowerCase())) {
                                long seconds = rs.getLong("value");
                                json.append(seconds); // Return seconds, format on client side
                            } else {
                                json.append(rs.getInt("value"));
                            }
                            
                            json.append("}");
                            first = false;
                        }
                    }
                }
                json.append("]");
                
                return json.toString();
            } catch (SQLException ex) {
                PlayeranalyticsForgeMod.LOGGER.error("Failed to get leaderboard data for type: {}", type, ex);
                return "[{\"error\":\"Failed to retrieve leaderboard\"}]";
            }
        }
    }

    public static String getOnlinePlayersJson() {
        synchronized (LOCK) {
            try {
                Connection conn = init();
                StringBuilder json = new StringBuilder("[");
                boolean first = true;
                
                // Get a snapshot of active sessions to avoid concurrent modification
                java.util.Map<UUID, Instant> sessionSnapshot = new java.util.HashMap<>(activeSessions);
                
                for (java.util.Map.Entry<UUID, Instant> entry : sessionSnapshot.entrySet()) {
                    UUID uuid = entry.getKey();
                    Instant joinTime = entry.getValue();
                    
                    // Get player name from database
                    String sql = "SELECT player_name FROM player_stats WHERE player_uuid = ? LIMIT 1";
                    try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                        stmt.setString(1, uuid.toString());
                        try (ResultSet rs = stmt.executeQuery()) {
                            if (rs.next()) {
                                String playerName = rs.getString("player_name");
                                long sessionDuration = Duration.between(joinTime, Instant.now()).getSeconds();
                                
                                if (!first) json.append(",");
                                json.append("{")
                                    .append("\"player_uuid\":\"").append(uuid.toString()).append("\"")
                                    .append(",\"player_name\":").append(toJsonString(playerName))
                                    .append(",\"join_time\":\"").append(joinTime.toString()).append("\"")
                                    .append(",\"session_duration_seconds\":").append(sessionDuration)
                                    .append("}");
                                first = false;
                            }
                        }
                    }
                }
                
                json.append("]");
                return json.toString();
            } catch (SQLException ex) {
                PlayeranalyticsForgeMod.LOGGER.error("Failed to get online players", ex);
                return "[]";
            }
        }
    }

    public static int getOnlinePlayerCount() {
        return activeSessions.size();
    }

    @SuppressWarnings("null")
    public static void recordWorldPlaytime(ServerPlayer player, String worldName, long playtimeSeconds) {
        synchronized (LOCK) {
            try {
                Connection conn = init();
                String sql = "INSERT INTO world_playtime (player_uuid, world_name, playtime_seconds, last_updated) " +
                    "VALUES (?, ?, ?, ?) " +
                    "ON CONFLICT(player_uuid, world_name) DO UPDATE SET " +
                    "playtime_seconds = playtime_seconds + ?, last_updated = ?";
                try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                    stmt.setString(1, player.getUUID().toString());
                    stmt.setString(2, worldName);
                    stmt.setLong(3, playtimeSeconds);
                    stmt.setString(4, Instant.now().toString());
                    stmt.setLong(5, playtimeSeconds);
                    stmt.setString(6, Instant.now().toString());
                    stmt.executeUpdate();
                }
            } catch (SQLException ex) {
                PlayeranalyticsForgeMod.LOGGER.error("Failed to record world playtime for player: {}", player.getUUID(), ex);
            }
        }
    }

    @SuppressWarnings("null")
    public static void recordWorldKill(ServerPlayer killer, String victimName, String victimType, String worldName, String weaponUsed, boolean isPvP) {
        synchronized (LOCK) {
            try {
                Connection conn = init();
                String sql = "INSERT INTO world_kills (killer_uuid, victim_name, victim_type, world_name, weapon_used, kill_time_utc, is_pvp) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?)";
                try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                    stmt.setString(1, killer.getUUID().toString());
                    stmt.setString(2, victimName);
                    stmt.setString(3, victimType);
                    stmt.setString(4, worldName);
                    stmt.setString(5, weaponUsed);
                    stmt.setString(6, Instant.now().toString());
                    stmt.setBoolean(7, isPvP);
                    stmt.executeUpdate();
                }
            } catch (SQLException ex) {
                PlayeranalyticsForgeMod.LOGGER.error("Failed to record world kill for player: {}", killer.getUUID(), ex);
            }
        }
    }

    @SuppressWarnings("null")
    public static String getWorldDistributionJson() {
        synchronized (LOCK) {
            try {
                Connection conn = init();
                String sql = "SELECT world_name, COUNT(DISTINCT player_uuid) as player_count, " +
                    "SUM(playtime_seconds) as total_playtime, SUM(CASE WHEN is_current=1 THEN 1 ELSE 0 END) as current_players " +
                    "FROM world_playtime " +
                    "GROUP BY world_name " +
                    "ORDER BY player_count DESC";
                
                StringBuilder json = new StringBuilder("[");
                try (Statement stmt = conn.createStatement(); ResultSet rs = stmt.executeQuery(sql)) {
                    boolean first = true;
                    while (rs.next()) {
                        if (!first) json.append(",");
                        json.append("{\"worldName\":").append(toJsonString(rs.getString("world_name")))
                            .append(",\"playerCount\":").append(rs.getInt("player_count"))
                            .append(",\"totalPlaytime\":").append(rs.getLong("total_playtime"))
                            .append(",\"currentPlayers\":0")
                            .append("}");
                        first = false;
                    }
                }
                json.append("]");
                return json.toString();
            } catch (SQLException ex) {
                PlayeranalyticsForgeMod.LOGGER.error("Failed to get world distribution", ex);
                return "[]";
            }
        }
    }

    @SuppressWarnings("null")
    public static String getWorldStatsJson(String worldName) {
        synchronized (LOCK) {
            try {
                Connection conn = init();
                
                // Get world playtime stats
                String playtimeSql = "SELECT COUNT(DISTINCT player_uuid) as unique_players, " +
                    "SUM(playtime_seconds) as total_playtime, " +
                    "AVG(playtime_seconds) as avg_playtime " +
                    "FROM world_playtime WHERE world_name = ?";
                
                // Get world kill stats
                String killsSql = "SELECT COUNT(*) as total_kills, " +
                    "SUM(CASE WHEN is_pvp=1 THEN 1 ELSE 0 END) as pvp_kills, " +
                    "SUM(CASE WHEN is_pvp=0 THEN 1 ELSE 0 END) as pve_kills " +
                    "FROM world_kills WHERE world_name = ?";
                
                StringBuilder json = new StringBuilder("{\"worldName\":").append(toJsonString(worldName));
                
                try (PreparedStatement stmt = conn.prepareStatement(playtimeSql)) {
                    stmt.setString(1, worldName);
                    try (ResultSet rs = stmt.executeQuery()) {
                        if (rs.next()) {
                            json.append(",\"uniquePlayers\":").append(rs.getInt("unique_players"))
                                .append(",\"totalPlaytime\":").append(rs.getLong("total_playtime"))
                                .append(",\"avgPlaytime\":").append(rs.getLong("avg_playtime"));
                        }
                    }
                }
                
                try (PreparedStatement stmt = conn.prepareStatement(killsSql)) {
                    stmt.setString(1, worldName);
                    try (ResultSet rs = stmt.executeQuery()) {
                        if (rs.next()) {
                            json.append(",\"totalKills\":").append(rs.getInt("total_kills"))
                                .append(",\"pvpKills\":").append(rs.getInt("pvp_kills"))
                                .append(",\"pveKills\":").append(rs.getInt("pve_kills"));
                        }
                    }
                }
                
                json.append("}");
                return json.toString();
            } catch (SQLException ex) {
                PlayeranalyticsForgeMod.LOGGER.error("Failed to get world stats for: {}", worldName, ex);
                return "{\"error\":\"Failed to retrieve world stats\"}";
            }
        }
    }

    // Network functionality
    @SuppressWarnings("null")
    public static void recordPlayerServerTransfer(ServerPlayer player, String serverId, String networkName, String serverName) {
        synchronized (LOCK) {
            try {
                Connection conn = init();
                
                // Mark previous server session as ended
                String endPreviousSql = "UPDATE player_server_history SET is_current=0, left_time=?, duration_seconds=? " +
                    "WHERE player_uuid = ? AND is_current = 1";
                try (PreparedStatement stmt = conn.prepareStatement(endPreviousSql)) {
                    stmt.setString(1, Instant.now().toString());
                    long duration = 0; // In real scenario, calculate from joined_time
                    stmt.setLong(2, duration);
                    stmt.setString(3, player.getUUID().toString());
                    stmt.executeUpdate();
                }
                
                // Record new server session
                String recordNewSql = "INSERT INTO player_server_history (player_uuid, player_name, server_id, joined_time, is_current) " +
                    "VALUES (?, ?, ?, ?, 1)";
                try (PreparedStatement stmt = conn.prepareStatement(recordNewSql)) {
                    stmt.setString(1, player.getUUID().toString());
                    stmt.setString(2, player.getGameProfile().getName());
                    stmt.setString(3, serverId);
                    stmt.setString(4, Instant.now().toString());
                    stmt.executeUpdate();
                }
                
                // Register/update server info
                String upsertServerSql = "INSERT OR IGNORE INTO servers (server_id, network_name, server_name, last_sync) " +
                    "VALUES (?, ?, ?, ?) ";
                try (PreparedStatement stmt = conn.prepareStatement(upsertServerSql)) {
                    stmt.setString(1, serverId);
                    stmt.setString(2, networkName);
                    stmt.setString(3, serverName);
                    stmt.setString(4, Instant.now().toString());
                    stmt.executeUpdate();
                }
                
                PlayeranalyticsForgeMod.LOGGER.info("Recorded player {} server transfer to {}", player.getUUID(), serverId);
            } catch (SQLException ex) {
                PlayeranalyticsForgeMod.LOGGER.error("Failed to record player server transfer", ex);
            }
        }
    }

    @SuppressWarnings("null")
    public static String getNetworkStatsJson(String networkName) {
        synchronized (LOCK) {
            try {
                Connection conn = init();
                
                // Get number of servers in network
                String serverCountSql = "SELECT COUNT(*) as count FROM servers WHERE network_name = ?";
                int serverCount = 0;
                try (PreparedStatement stmt = conn.prepareStatement(serverCountSql)) {
                    stmt.setString(1, networkName);
                    try (ResultSet rs = stmt.executeQuery()) {
                        if (rs.next()) serverCount = rs.getInt("count");
                    }
                }
                
                // Get unique players across network
                String playerCountSql = "SELECT COUNT(DISTINCT player_uuid) as count FROM player_server_history " +
                    "WHERE server_id IN (SELECT server_id FROM servers WHERE network_name = ?)";
                int uniquePlayers = 0;
                try (PreparedStatement stmt = conn.prepareStatement(playerCountSql)) {
                    stmt.setString(1, networkName);
                    try (ResultSet rs = stmt.executeQuery()) {
                        if (rs.next()) uniquePlayers = rs.getInt("count");
                    }
                }
                
                // Get total transfers
                String transferCountSql = "SELECT COUNT(*) as count FROM player_server_history " +
                    "WHERE server_id IN (SELECT server_id FROM servers WHERE network_name = ?) AND is_current = 0";
                int totalTransfers = 0;
                try (PreparedStatement stmt = conn.prepareStatement(transferCountSql)) {
                    stmt.setString(1, networkName);
                    try (ResultSet rs = stmt.executeQuery()) {
                        if (rs.next()) totalTransfers = rs.getInt("count");
                    }
                }
                
                StringBuilder json = new StringBuilder("{\"networkName\":").append(toJsonString(networkName))
                    .append(",\"serverCount\":").append(serverCount)
                    .append(",\"uniquePlayers\":").append(uniquePlayers)
                    .append(",\"totalTransfers\":").append(totalTransfers)
                    .append("}");
                
                return json.toString();
            } catch (SQLException ex) {
                PlayeranalyticsForgeMod.LOGGER.error("Failed to get network stats", ex);
                return "{\"error\":\"Failed to retrieve network stats\"}";
            }
        }
    }

    @SuppressWarnings("null")
    public static String getServerComparisonJson(String networkName) {
        synchronized (LOCK) {
            try {
                Connection conn = init();
                
                String sql = "SELECT s.server_id, s.server_name, " +
                    "COUNT(DISTINCT psh.player_uuid) as unique_players, " +
                    "SUM(CASE WHEN psh.is_current=1 THEN 1 ELSE 0 END) as current_players, " +
                    "COUNT(*) as total_visits " +
                    "FROM servers s " +
                    "LEFT JOIN player_server_history psh ON s.server_id = psh.server_id " +
                    "WHERE s.network_name = ? " +
                    "GROUP BY s.server_id, s.server_name " +
                    "ORDER BY current_players DESC";
                
                StringBuilder json = new StringBuilder("[");
                try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                    stmt.setString(1, networkName);
                    try (ResultSet rs = stmt.executeQuery()) {
                        boolean first = true;
                        while (rs.next()) {
                            if (!first) json.append(",");
                            json.append("{\"serverId\":").append(toJsonString(rs.getString("server_id")))
                                .append(",\"serverName\":").append(toJsonString(rs.getString("server_name")))
                                .append(",\"uniquePlayers\":").append(rs.getInt("unique_players"))
                                .append(",\"currentPlayers\":").append(rs.getInt("current_players"))
                                .append(",\"totalVisits\":").append(rs.getInt("total_visits"))
                                .append("}");
                            first = false;
                        }
                    }
                }
                json.append("]");
                return json.toString();
            } catch (SQLException ex) {
                PlayeranalyticsForgeMod.LOGGER.error("Failed to get server comparison", ex);
                return "[]";
            }
        }
    }

    @SuppressWarnings("null")
    public static String getPlayerServerHistoryJson(String playerUuid) {
        synchronized (LOCK) {
            try {
                Connection conn = init();
                
                String sql = "SELECT server_id, joined_time, left_time, duration_seconds, is_current " +
                    "FROM player_server_history " +
                    "WHERE player_uuid = ? " +
                    "ORDER BY joined_time DESC";
                
                StringBuilder json = new StringBuilder("[");
                try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                    stmt.setString(1, playerUuid);
                    try (ResultSet rs = stmt.executeQuery()) {
                        boolean first = true;
                        while (rs.next()) {
                            if (!first) json.append(",");
                            json.append("{\"serverId\":").append(toJsonString(rs.getString("server_id")))
                                .append(",\"joinedTime\":").append(toJsonString(rs.getString("joined_time")))
                                .append(",\"leftTime\":").append(toJsonString(rs.getString("left_time")))
                                .append(",\"durationSeconds\":").append(rs.getLong("duration_seconds"))
                                .append(",\"current\":").append(rs.getBoolean("is_current"))
                                .append("}");
                            first = false;
                        }
                    }
                }
                json.append("]");
                return json.toString();
            } catch (SQLException ex) {
                PlayeranalyticsForgeMod.LOGGER.error("Failed to get player server history", ex);
                return "[]";
            }
        }
    }

    @SuppressWarnings("null")
    public static void logNetworkSync(String serverId, String networkName, int statsCount, boolean success, String errorMessage) {
        synchronized (LOCK) {
            try {
                Connection conn = init();
                String sql = "INSERT INTO network_sync_log (sync_time, server_id, stats_sync_count, success, error_message) " +
                    "VALUES (?, ?, ?, ?, ?)";
                try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                    stmt.setString(1, Instant.now().toString());
                    stmt.setString(2, serverId);
                    stmt.setInt(3, statsCount);
                    stmt.setBoolean(4, success);
                    stmt.setString(5, errorMessage);
                    stmt.executeUpdate();
                }
                PlayeranalyticsForgeMod.LOGGER.debug("Logged network sync: server={}, success={}", serverId, success);
            } catch (SQLException ex) {
                PlayeranalyticsForgeMod.LOGGER.error("Failed to log network sync", ex);
            }
        }
    }

    @SuppressWarnings("null")
    public static String getChurnAnalysisJson() {
        synchronized (LOCK) {
            try {
                Connection conn = init();
                Instant now = Instant.now();
                long days7Ago = now.minus(Duration.ofDays(7)).getEpochSecond();
                long days30Ago = now.minus(Duration.ofDays(30)).getEpochSecond();
                long days90Ago = now.minus(Duration.ofDays(90)).getEpochSecond();
                
                // Count churned players (no activity in various periods)
                String countSql = "SELECT " +
                    "COUNT(CASE WHEN last_seen < ? THEN 1 END) AS churned_7d, " +
                    "COUNT(CASE WHEN last_seen < ? THEN 1 END) AS churned_30d, " +
                    "COUNT(CASE WHEN last_seen < ? THEN 1 END) AS churned_90d, " +
                    "COUNT(*) AS total_unique_players " +
                    "FROM player_stats";
                
                StringBuilder json = new StringBuilder("{");
                try (PreparedStatement stmt = conn.prepareStatement(countSql)) {
                    stmt.setString(1, java.time.Instant.ofEpochSecond(days7Ago).toString());
                    stmt.setString(2, java.time.Instant.ofEpochSecond(days30Ago).toString());
                    stmt.setString(3, java.time.Instant.ofEpochSecond(days90Ago).toString());
                    
                    try (ResultSet rs = stmt.executeQuery()) {
                        if (rs.next()) {
                            long churned7d = rs.getLong("churned_7d");
                            long churned30d = rs.getLong("churned_30d");
                            long churned90d = rs.getLong("churned_90d");
                            long totalPlayers = rs.getLong("total_unique_players");
                            
                            json.append("\"churnedLast7Days\":").append(churned7d).append(",");
                            json.append("\"churnedLast30Days\":").append(churned30d).append(",");
                            json.append("\"churnedLast90Days\":").append(churned90d).append(",");
                            json.append("\"totalUniquePlayers\":").append(totalPlayers).append(",");
                            
                            // Calculate churn rates
                            double churnRate7d = totalPlayers > 0 ? (churned7d * 100.0) / totalPlayers : 0;
                            double churnRate30d = totalPlayers > 0 ? (churned30d * 100.0) / totalPlayers : 0;
                            double churnRate90d = totalPlayers > 0 ? (churned90d * 100.0) / totalPlayers : 0;
                            
                            json.append("\"churnRate7Days\":").append(String.format("%.2f", churnRate7d)).append(",");
                            json.append("\"churnRate30Days\":").append(String.format("%.2f", churnRate30d)).append(",");
                            json.append("\"churnRate90Days\":").append(String.format("%.2f", churnRate90d));
                        }
                    }
                }
                json.append("}");
                return json.toString();
            } catch (SQLException ex) {
                PlayeranalyticsForgeMod.LOGGER.error("Failed to get churn analysis", ex);
                return "{\"error\":\"Failed to fetch churn data\"}";
            }
        }
    }

    @SuppressWarnings("null")
    public static String getChurnedPlayersJson(int daysInactive) {
        synchronized (LOCK) {
            try {
                Connection conn = init();
                Instant threshold = Instant.now().minus(Duration.ofDays(daysInactive));
                
                String sql = "SELECT player_uuid, player_name, last_seen, total_playtime_seconds, kills, deaths, " +
                    "CAST((julianday('now') - julianday(last_seen)) AS INTEGER) AS days_since_seen " +
                    "FROM player_stats " +
                    "WHERE last_seen IS NOT NULL AND last_seen < ? " +
                    "ORDER BY last_seen DESC " +
                    "LIMIT 100";
                
                StringBuilder json = new StringBuilder("[");
                try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                    stmt.setString(1, threshold.toString());
                    try (ResultSet rs = stmt.executeQuery()) {
                        boolean first = true;
                        while (rs.next()) {
                            if (!first) json.append(",");
                            json.append("{")
                                .append("\"playerUuid\":").append(toJsonString(rs.getString("player_uuid"))).append(",")
                                .append("\"playerName\":").append(toJsonString(rs.getString("player_name"))).append(",")
                                .append("\"lastSeen\":").append(toJsonString(rs.getString("last_seen"))).append(",")
                                .append("\"daysSinceSeen\":").append(rs.getInt("days_since_seen")).append(",")
                                .append("\"totalPlaytimeSeconds\":").append(rs.getLong("total_playtime_seconds")).append(",")
                                .append("\"kills\":").append(rs.getLong("kills")).append(",")
                                .append("\"deaths\":").append(rs.getLong("deaths"))
                                .append("}");
                            first = false;
                        }
                    }
                }
                json.append("]");
                return json.toString();
            } catch (SQLException ex) {
                PlayeranalyticsForgeMod.LOGGER.error("Failed to get churned players list", ex);
                return "[]";
            }
        }
    }

    @SuppressWarnings("null")
    public static String getAtRiskPlayersJson() {
        synchronized (LOCK) {
            try {
                Connection conn = init();
                // Players who were active 14-30 days ago but haven't been seen in 7 days
                Instant sevenDaysAgo = Instant.now().minus(Duration.ofDays(7));
                Instant thirtyDaysAgo = Instant.now().minus(Duration.ofDays(30));
                
                String sql = "SELECT player_uuid, player_name, last_seen, total_playtime_seconds, kills, deaths " +
                    "FROM player_stats " +
                    "WHERE last_seen >= ? AND last_seen < ? " +
                    "ORDER BY last_seen DESC " +
                    "LIMIT 100";
                
                StringBuilder json = new StringBuilder("[");
                try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                    stmt.setString(1, thirtyDaysAgo.toString());
                    stmt.setString(2, sevenDaysAgo.toString());
                    try (ResultSet rs = stmt.executeQuery()) {
                        boolean first = true;
                        while (rs.next()) {
                            if (!first) json.append(",");
                            json.append("{")
                                .append("\"playerUuid\":").append(toJsonString(rs.getString("player_uuid"))).append(",")
                                .append("\"playerName\":").append(toJsonString(rs.getString("player_name"))).append(",")
                                .append("\"lastSeen\":").append(toJsonString(rs.getString("last_seen"))).append(",")
                                .append("\"totalPlaytimeSeconds\":").append(rs.getLong("total_playtime_seconds")).append(",")
                                .append("\"kills\":").append(rs.getLong("kills")).append(",")
                                .append("\"deaths\":").append(rs.getLong("deaths"))
                                .append("}");
                            first = false;
                        }
                    }
                }
                json.append("]");
                return json.toString();
            } catch (SQLException ex) {
                PlayeranalyticsForgeMod.LOGGER.error("Failed to get at-risk players list", ex);
                return "[]";
            }
        }
    }
}

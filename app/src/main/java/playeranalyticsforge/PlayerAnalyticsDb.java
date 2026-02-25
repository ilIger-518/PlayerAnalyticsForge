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

public final class PlayerAnalyticsDb {
    private static final Object LOCK = new Object();
    private static Connection connection;

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
                        return "{" +
                            "\"joins\":" + joins + "," +
                            "\"leaves\":" + leaves + "," +
                            "\"uniquePlayers\":" + uniquePlayers + "," +
                            "\"lastEvent\":" + toJsonString(lastEvent) +
                            "}";
                    }
                }
            } catch (SQLException ex) {
                PlayeranalyticsForgeMod.LOGGER.error("Failed to query summary", ex);
            }
            return "{\"joins\":0,\"leaves\":0,\"uniquePlayers\":0,\"lastEvent\":null}";
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
                            json.append("\"kills\":").append(kills).append(",");
                            json.append("\"deaths\":").append(deaths).append(",");
                            json.append("\"kdRatio\":").append(formatKdRatio(kills, deaths));
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
                        "last_seen TEXT" +
                    ")"
                );
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
            }

            return connection;
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
}

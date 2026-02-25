package playeranalyticsforge;

import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.fml.loading.FMLPaths;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;

public final class PlayerAnalyticsDb {
    private static final Object LOCK = new Object();
    private static Connection connection;

    private PlayerAnalyticsDb() {
    }

    public static void recordEvent(String eventType, ServerPlayer player) {
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

            connection = DriverManager.getConnection("jdbc:sqlite:" + dbPath.toAbsolutePath());
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
            }

            return connection;
        }
    }
}

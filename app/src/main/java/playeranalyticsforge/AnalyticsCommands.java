package playeranalyticsforge;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.ChatFormatting;
import net.minecraftforge.fml.loading.FMLPaths;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;

@SuppressWarnings("null")
public final class AnalyticsCommands {
    
    private AnalyticsCommands() {
    }

    @SuppressWarnings("null")
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(
            Commands.literal("analytics")
                .executes(context -> showOwnStats(context))
                .then(Commands.argument("player", EntityArgument.player())
                    .executes(context -> showPlayerStats(context, EntityArgument.getPlayer(context, "player")))
                )
                .then(Commands.literal("reload")
                    .executes(context -> reloadConfig(context.getSource()))
                )
                .then(Commands.literal("export")
                    .executes(context -> exportDataCommand(context.getSource()))
                )
                .then(Commands.literal("backup")
                    .requires(source -> source.hasPermission(2))
                    .executes(context -> backupCommand(context.getSource()))
                )
                .then(Commands.literal("cleanup")
                    .requires(source -> source.hasPermission(2))
                    .executes(context -> cleanupCommand(context.getSource()))
                )
                .then(Commands.literal("debug")
                    .executes(context -> debugCommand(context.getSource()))
                )
                .then(Commands.literal("compare")
                    .then(Commands.argument("player1", EntityArgument.player())
                        .then(Commands.argument("player2", EntityArgument.player())
                            .executes(context -> comparePlayersCommand(context, EntityArgument.getPlayer(context, "player1"), EntityArgument.getPlayer(context, "player2")))
                        )
                    )
                )
                .then(Commands.literal("churn")
                    .executes(context -> churnAnalysisCommand(context.getSource()))
                )
                .then(Commands.literal("updatecheck")
                    .requires(source -> source.hasPermission(2))
                    .executes(context -> updateCheckCommand(context.getSource()))
                )
        );
    }

    @SuppressWarnings("null")
    private static int showOwnStats(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        return displayPlayerStats(context.getSource(), player);
    }

    @SuppressWarnings("null")
    private static int showPlayerStats(CommandContext<CommandSourceStack> context, ServerPlayer targetPlayer) {
        return displayPlayerStats(context.getSource(), targetPlayer);
    }

    @SuppressWarnings("null")
    private static int displayPlayerStats(CommandSourceStack source, ServerPlayer player) {
        try {
            Path configDir = FMLPaths.CONFIGDIR.get();
            Path dbPath = configDir.resolve("playeranalytics.sqlite");
            String jdbcUrl = "jdbc:sqlite:" + dbPath.toAbsolutePath();
            
            String sql = "SELECT * FROM player_stats WHERE player_uuid = ?";
            
            try (Connection conn = DriverManager.getConnection(jdbcUrl);
                 PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, player.getUUID().toString());
                try (ResultSet rs = stmt.executeQuery()) {
                    if (!rs.next()) {
                        source.sendFailure(Component.literal("No statistics found for " + player.getGameProfile().getName()));
                        return 0;
                    }

                    // Header
                    source.sendSuccess(() -> Component.literal("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
                        .withStyle(ChatFormatting.DARK_PURPLE, ChatFormatting.BOLD), false);
                    source.sendSuccess(() -> Component.literal("📊 Analytics for " + player.getGameProfile().getName())
                        .withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD), false);
                    source.sendSuccess(() -> Component.literal("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
                        .withStyle(ChatFormatting.DARK_PURPLE, ChatFormatting.BOLD), false);

                    // Playtime
                    long totalSeconds = rs.getLong("total_playtime_seconds");
                    long activeSeconds = rs.getLong("active_playtime_seconds");
                    long afkSeconds = rs.getLong("total_afk_seconds");
                    String playtime = formatDuration(totalSeconds);
                    String activeTime = formatDuration(activeSeconds);
                    String afkTime = formatDuration(afkSeconds);
                    
                    source.sendSuccess(() -> Component.literal("⏱  Playtime: ")
                        .withStyle(ChatFormatting.AQUA)
                        .append(Component.literal(playtime).withStyle(ChatFormatting.WHITE)), false);
                    source.sendSuccess(() -> Component.literal("   ├─ Active: ")
                        .withStyle(ChatFormatting.GRAY)
                        .append(Component.literal(activeTime).withStyle(ChatFormatting.GREEN)), false);
                    source.sendSuccess(() -> Component.literal("   └─ AFK: ")
                        .withStyle(ChatFormatting.GRAY)
                        .append(Component.literal(afkTime).withStyle(ChatFormatting.YELLOW)), false);

                    // Session stats
                    int joins = rs.getInt("total_joins");
                    int leaves = rs.getInt("total_leaves");
                    source.sendSuccess(() -> Component.literal("🔄 Sessions: ")
                        .withStyle(ChatFormatting.AQUA)
                        .append(Component.literal(joins + " joins").withStyle(ChatFormatting.WHITE))
                        .append(Component.literal(" / ").withStyle(ChatFormatting.GRAY))
                        .append(Component.literal(leaves + " leaves").withStyle(ChatFormatting.WHITE)), false);

                    // Combat stats
                    int totalKills = rs.getInt("total_kills");
                    int pvpKills = rs.getInt("pvp_kills");
                    int pveKills = rs.getInt("pve_kills");
                    int totalDeaths = rs.getInt("total_deaths");
                    double kdRatio = totalDeaths > 0 ? (double) totalKills / totalDeaths : totalKills;
                    int killStreak = rs.getInt("kill_streak");
                    int maxKillStreak = rs.getInt("max_kill_streak");

                    source.sendSuccess(() -> Component.literal("⚔  Combat: ")
                        .withStyle(ChatFormatting.AQUA)
                        .append(Component.literal(totalKills + " kills").withStyle(ChatFormatting.GREEN))
                        .append(Component.literal(" / ").withStyle(ChatFormatting.GRAY))
                        .append(Component.literal(totalDeaths + " deaths").withStyle(ChatFormatting.RED)), false);
                    source.sendSuccess(() -> Component.literal("   ├─ K/D Ratio: ")
                        .withStyle(ChatFormatting.GRAY)
                        .append(Component.literal(String.format("%.2f", kdRatio)).withStyle(ChatFormatting.YELLOW)), false);
                    source.sendSuccess(() -> Component.literal("   ├─ PvP: ")
                        .withStyle(ChatFormatting.GRAY)
                        .append(Component.literal(pvpKills + "").withStyle(ChatFormatting.LIGHT_PURPLE))
                        .append(Component.literal(" | PvE: ").withStyle(ChatFormatting.GRAY))
                        .append(Component.literal(pveKills + "").withStyle(ChatFormatting.LIGHT_PURPLE)), false);
                    source.sendSuccess(() -> Component.literal("   └─ Streak: ")
                        .withStyle(ChatFormatting.GRAY)
                        .append(Component.literal(killStreak + " ").withStyle(ChatFormatting.GOLD))
                        .append(Component.literal("(Max: " + maxKillStreak + ")").withStyle(ChatFormatting.DARK_GRAY)), false);

                    // First join
                    String firstJoin = rs.getString("first_join");
                    if (firstJoin != null) {
                        Instant firstJoinTime = Instant.parse(firstJoin);
                        long daysAgo = Duration.between(firstJoinTime, Instant.now()).toDays();
                        source.sendSuccess(() -> Component.literal("📅 First Join: ")
                            .withStyle(ChatFormatting.AQUA)
                            .append(Component.literal(daysAgo + " days ago").withStyle(ChatFormatting.WHITE)), false);
                    }

                    // Last seen
                    String lastSeen = rs.getString("last_seen");
                    if (lastSeen != null) {
                        source.sendSuccess(() -> Component.literal("👁  Last Seen: ")
                            .withStyle(ChatFormatting.AQUA)
                            .append(Component.literal(lastSeen).withStyle(ChatFormatting.WHITE)), false);
                    }

                    // Footer
                    source.sendSuccess(() -> Component.literal("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
                        .withStyle(ChatFormatting.DARK_PURPLE, ChatFormatting.BOLD), false);
                    source.sendSuccess(() -> Component.literal("💻 View more at http://127.0.0.1:8804/player/" + player.getUUID())
                        .withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC), false);

                    return 1;
                }
            }
        } catch (SQLException ex) {
            PlayeranalyticsForgeMod.LOGGER.error("Failed to retrieve player stats", ex);
            source.sendFailure(Component.literal("Failed to retrieve statistics. Check server logs."));
            return 0;
        }
    }

    private static String formatDuration(long seconds) {
        long days = seconds / 86400;
        long hours = (seconds % 86400) / 3600;
        long minutes = (seconds % 3600) / 60;
        long secs = seconds % 60;

        if (days > 0) {
            return String.format("%dd %dh %dm", days, hours, minutes);
        } else if (hours > 0) {
            return String.format("%dh %dm %ds", hours, minutes, secs);
        } else if (minutes > 0) {
            return String.format("%dm %ds", minutes, secs);
        } else {
            return String.format("%ds", secs);
        }
    }

    @SuppressWarnings("null")
    private static int reloadConfig(CommandSourceStack source) {
        try {
            source.sendSuccess(() -> Component.literal("✓ Config reload scheduled. Please restart the server for full reload.")
                .withStyle(ChatFormatting.YELLOW), false);
            PlayeranalyticsForgeMod.LOGGER.info("Reload command acknowledged. ForgeConfigSpec configuration will be reloaded on next server restart.");
            return 1;
        } catch (Exception ex) {
            PlayeranalyticsForgeMod.LOGGER.error("Failed to reload configuration", ex);
            source.sendFailure(Component.literal("Failed to reload configuration. Check server logs."));
            return 0;
        }
    }

    @SuppressWarnings("null")
    private static int exportDataCommand(CommandSourceStack source) {
        try {
            Path configDir = FMLPaths.CONFIGDIR.get();
            Path dbPath = configDir.resolve("playeranalytics.sqlite");
            String jdbcUrl = "jdbc:sqlite:" + dbPath.toAbsolutePath();

            // Export to CSV
            File csvFile = configDir.resolve("playeranalytics_export_" + System.currentTimeMillis() + ".csv").toFile();
            
            String sql = "SELECT player_uuid, player_name, total_playtime_seconds, active_playtime_seconds, " +
                        "total_afk_seconds, total_joins, total_leaves, total_kills, pvp_kills, pve_kills, " +
                        "total_deaths, kill_streak, max_kill_streak, first_join, last_seen FROM player_stats";
            
            try (Connection conn = DriverManager.getConnection(jdbcUrl);
                 PreparedStatement stmt = conn.prepareStatement(sql);
                 ResultSet rs = stmt.executeQuery();
                 FileWriter writer = new FileWriter(csvFile)) {
                
                // Write header
                writer.write("UUID,Name,Total Playtime (sec),Active Playtime (sec),AFK Time (sec),Joins,Leaves," +
                            "Kills,PvP Kills,PvE Kills,Deaths,Current Streak,Max Streak,First Join,Last Seen\n");
                
                // Write data rows
                int[] count = {0};
                while (rs.next()) {
                    writer.write(String.format("%s,%s,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%s,%s\n",
                        rs.getString("player_uuid"),
                        rs.getString("player_name"),
                        rs.getLong("total_playtime_seconds"),
                        rs.getLong("active_playtime_seconds"),
                        rs.getLong("total_afk_seconds"),
                        rs.getInt("total_joins"),
                        rs.getInt("total_leaves"),
                        rs.getInt("total_kills"),
                        rs.getInt("pvp_kills"),
                        rs.getInt("pve_kills"),
                        rs.getInt("total_deaths"),
                        rs.getInt("kill_streak"),
                        rs.getInt("max_kill_streak"),
                        rs.getString("first_join"),
                        rs.getString("last_seen")
                    ));
                    count[0]++;
                }
                
                final int exportedCount = count[0];
                source.sendSuccess(() -> Component.literal("✓ Exported ")
                    .withStyle(ChatFormatting.GREEN)
                    .append(Component.literal(exportedCount + " players").withStyle(ChatFormatting.YELLOW))
                    .append(Component.literal(" to ").withStyle(ChatFormatting.GREEN))
                    .append(Component.literal(csvFile.getName()).withStyle(ChatFormatting.AQUA)), false);
                PlayeranalyticsForgeMod.LOGGER.info("Exported {} player records to {}", exportedCount, csvFile.getAbsolutePath());
                return 1;
            }
        } catch (SQLException | IOException ex) {
            PlayeranalyticsForgeMod.LOGGER.error("Failed to export data", ex);
            source.sendFailure(Component.literal("Failed to export data. Check server logs."));
            return 0;
        }
    }

    @SuppressWarnings("null")
    private static int backupCommand(CommandSourceStack source) {
        try {
            PlayerAnalyticsDb.createBackup();
            source.sendSuccess(() -> Component.literal("✓ Database backup created")
                .withStyle(ChatFormatting.GREEN), false);
            return 1;
        } catch (Exception ex) {
            PlayeranalyticsForgeMod.LOGGER.error("Failed to create backup", ex);
            source.sendFailure(Component.literal("Failed to create backup. Check server logs."));
            return 0;
        }
    }

    @SuppressWarnings("null")
    private static int cleanupCommand(CommandSourceStack source) {
        try {
            int retentionDays = AnalyticsConfig.DATA_RETENTION_DAYS.get();
            if (retentionDays <= 0) {
                source.sendSuccess(() -> Component.literal("Retention disabled (retentionDays=0). No cleanup performed.")
                    .withStyle(ChatFormatting.YELLOW), false);
                return 1;
            }
            PlayerAnalyticsDb.runRetentionCleanup(retentionDays);
            source.sendSuccess(() -> Component.literal("✓ Retention cleanup completed")
                .withStyle(ChatFormatting.GREEN), false);
            return 1;
        } catch (Exception ex) {
            PlayeranalyticsForgeMod.LOGGER.error("Failed to run cleanup", ex);
            source.sendFailure(Component.literal("Failed to run cleanup. Check server logs."));
            return 0;
        }
    }

    @SuppressWarnings("null")
    private static int debugCommand(CommandSourceStack source) {
        try {
            Path configDir = FMLPaths.CONFIGDIR.get();
            Path dbPath = configDir.resolve("playeranalytics.sqlite");
            String jdbcUrl = "jdbc:sqlite:" + dbPath.toAbsolutePath();

            // Header
            source.sendSuccess(() -> Component.literal("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
                .withStyle(ChatFormatting.DARK_PURPLE, ChatFormatting.BOLD), false);
            source.sendSuccess(() -> Component.literal("🔧 PlayerAnalytics Diagnostics")
                .withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD), false);
            source.sendSuccess(() -> Component.literal("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
                .withStyle(ChatFormatting.DARK_PURPLE, ChatFormatting.BOLD), false);

            // Configuration
            source.sendSuccess(() -> Component.literal("⚙  Configuration:")
                .withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD), false);
            source.sendSuccess(() -> Component.literal("   ├─ Web Server: ")
                .withStyle(ChatFormatting.GRAY)
                .append(Component.literal(AnalyticsConfig.WEB_SERVER_HOST.get() + ":" + AnalyticsConfig.WEB_SERVER_PORT.get())
                    .withStyle(ChatFormatting.WHITE))
                .append(Component.literal(" [" + (AnalyticsConfig.WEB_SERVER_ENABLED.get() ? "ENABLED" : "DISABLED") + "]")
                    .withStyle(AnalyticsConfig.WEB_SERVER_ENABLED.get() ? ChatFormatting.GREEN : ChatFormatting.RED)), false);
            source.sendSuccess(() -> Component.literal("   └─ Features: ")
                .withStyle(ChatFormatting.GRAY)
                .append(Component.literal("Combat=" + (AnalyticsConfig.TRACK_COMBAT.get() ? "✓" : "✗") + " ")
                    .withStyle(AnalyticsConfig.TRACK_COMBAT.get() ? ChatFormatting.GREEN : ChatFormatting.RED))
                .append(Component.literal("Sessions=" + (AnalyticsConfig.TRACK_SESSIONS.get() ? "✓" : "✗") + " ")
                    .withStyle(AnalyticsConfig.TRACK_SESSIONS.get() ? ChatFormatting.GREEN : ChatFormatting.RED))
                .append(Component.literal("Playtime=" + (AnalyticsConfig.TRACK_PLAYTIME.get() ? "✓" : "✗"))
                    .withStyle(AnalyticsConfig.TRACK_PLAYTIME.get() ? ChatFormatting.GREEN : ChatFormatting.RED)), false);

            // Database
            source.sendSuccess(() -> Component.literal("💾 Database:")
                .withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD), false);
            
            try (Connection conn = DriverManager.getConnection(jdbcUrl)) {
                // Check database size
                File dbFile = dbPath.toFile();
                long dbSizeKB = dbFile.length() / 1024;
                source.sendSuccess(() -> Component.literal("   ├─ File: ")
                    .withStyle(ChatFormatting.GRAY)
                    .append(Component.literal(dbFile.getAbsolutePath()).withStyle(ChatFormatting.WHITE)), false);
                source.sendSuccess(() -> Component.literal("   ├─ Size: ")
                    .withStyle(ChatFormatting.GRAY)
                    .append(Component.literal(dbSizeKB + " KB").withStyle(ChatFormatting.YELLOW)), false);
                
                // Count total players
                String playerCountSql = "SELECT COUNT(*) as count FROM player_stats";
                try (PreparedStatement stmt = conn.prepareStatement(playerCountSql);
                     ResultSet rs = stmt.executeQuery()) {
                    if (rs.next()) {
                        int playerCount = rs.getInt("count");
                        source.sendSuccess(() -> Component.literal("   ├─ Players Tracked: ")
                            .withStyle(ChatFormatting.GRAY)
                            .append(Component.literal(playerCount + "").withStyle(ChatFormatting.YELLOW)), false);
                    }
                }
                
                // Count total events
                String eventCountSql = "SELECT COUNT(*) as count FROM player_events";
                try (PreparedStatement stmt = conn.prepareStatement(eventCountSql);
                     ResultSet rs = stmt.executeQuery()) {
                    if (rs.next()) {
                        int eventCount = rs.getInt("count");
                        source.sendSuccess(() -> Component.literal("   ├─ Total Events: ")
                            .withStyle(ChatFormatting.GRAY)
                            .append(Component.literal(eventCount + "").withStyle(ChatFormatting.YELLOW)), false);
                    }
                }
                
                // Count kills
                String killCountSql = "SELECT SUM(total_kills) as count FROM player_stats";
                try (PreparedStatement stmt = conn.prepareStatement(killCountSql);
                     ResultSet rs = stmt.executeQuery()) {
                    if (rs.next()) {
                        long killCount = rs.getLong("count");
                        source.sendSuccess(() -> Component.literal("   └─ Total Kills: ")
                            .withStyle(ChatFormatting.GRAY)
                            .append(Component.literal(killCount + "").withStyle(ChatFormatting.YELLOW)), false);
                    }
                }
                
                source.sendSuccess(() -> Component.literal("✓ Database connection OK").withStyle(ChatFormatting.GREEN), false);
            }

            source.sendSuccess(() -> Component.literal("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
                .withStyle(ChatFormatting.DARK_PURPLE, ChatFormatting.BOLD), false);
            return 1;
        } catch (SQLException ex) {
            PlayeranalyticsForgeMod.LOGGER.error("Failed to get diagnostics", ex);
            source.sendFailure(Component.literal("Failed to get diagnostics. Check server logs."));
            return 0;
        }
    }

    @SuppressWarnings("null")
    private static int comparePlayersCommand(CommandContext<CommandSourceStack> context, ServerPlayer player1, ServerPlayer player2) {
        CommandSourceStack source = context.getSource();
        
        try {
            Path configDir = FMLPaths.CONFIGDIR.get();
            Path dbPath = configDir.resolve("playeranalytics.sqlite");
            String jdbcUrl = "jdbc:sqlite:" + dbPath.toAbsolutePath();

            String sql = "SELECT * FROM player_stats WHERE player_uuid = ?";

            // Get player1 stats
            java.util.Map<String, Object> stats1 = new java.util.HashMap<>();
            try (Connection conn = DriverManager.getConnection(jdbcUrl);
                 PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, player1.getUUID().toString());
                try (ResultSet rs = stmt.executeQuery()) {
                    if (!rs.next()) {
                        source.sendFailure(Component.literal("No statistics found for " + player1.getGameProfile().getName()));
                        return 0;
                    }
                    stats1.put("name", player1.getGameProfile().getName());
                    stats1.put("kills", rs.getInt("total_kills"));
                    stats1.put("deaths", rs.getInt("total_deaths"));
                    stats1.put("playtime", rs.getLong("total_playtime_seconds"));
                    stats1.put("pvpKills", rs.getInt("pvp_kills"));
                }
            }

            // Get player2 stats
            java.util.Map<String, Object> stats2 = new java.util.HashMap<>();
            try (Connection conn = DriverManager.getConnection(jdbcUrl);
                 PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, player2.getUUID().toString());
                try (ResultSet rs = stmt.executeQuery()) {
                    if (!rs.next()) {
                        source.sendFailure(Component.literal("No statistics found for " + player2.getGameProfile().getName()));
                        return 0;
                    }
                    stats2.put("name", player2.getGameProfile().getName());
                    stats2.put("kills", rs.getInt("total_kills"));
                    stats2.put("deaths", rs.getInt("total_deaths"));
                    stats2.put("playtime", rs.getLong("total_playtime_seconds"));
                    stats2.put("pvpKills", rs.getInt("pvp_kills"));
                }
            }

            // Display comparison
            source.sendSuccess(() -> Component.literal("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
                .withStyle(ChatFormatting.DARK_PURPLE, ChatFormatting.BOLD), false);
            source.sendSuccess(() -> Component.literal("📊 Comparing " + stats1.get("name") + " vs " + stats2.get("name"))
                .withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD), false);
            source.sendSuccess(() -> Component.literal("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
                .withStyle(ChatFormatting.DARK_PURPLE, ChatFormatting.BOLD), false);

            int kills1 = (int) stats1.get("kills");
            int kills2 = (int) stats2.get("kills");
            int deaths1 = (int) stats1.get("deaths");
            int deaths2 = (int) stats2.get("deaths");

            // Kills
            source.sendSuccess(() -> Component.literal("Kills: ")
                .withStyle(ChatFormatting.AQUA)
                .append(Component.literal(kills1 + "").withStyle(kills1 > kills2 ? ChatFormatting.GREEN : ChatFormatting.GRAY))
                .append(Component.literal(" vs ").withStyle(ChatFormatting.GRAY))
                .append(Component.literal(kills2 + "").withStyle(kills2 > kills1 ? ChatFormatting.GREEN : ChatFormatting.GRAY)), false);

            // Deaths
            source.sendSuccess(() -> Component.literal("Deaths: ")
                .withStyle(ChatFormatting.AQUA)
                .append(Component.literal(deaths1 + "").withStyle(deaths1 < deaths2 ? ChatFormatting.GREEN : ChatFormatting.GRAY))
                .append(Component.literal(" vs ").withStyle(ChatFormatting.GRAY))
                .append(Component.literal(deaths2 + "").withStyle(deaths2 < deaths1 ? ChatFormatting.GREEN : ChatFormatting.GRAY)), false);

            // K/D Ratio
            double kd1 = deaths1 > 0 ? (double) kills1 / deaths1 : kills1;
            double kd2 = deaths2 > 0 ? (double) kills2 / deaths2 : kills2;
            source.sendSuccess(() -> Component.literal("K/D Ratio: ")
                .withStyle(ChatFormatting.AQUA)
                .append(Component.literal(String.format("%.2f", kd1)).withStyle(kd1 > kd2 ? ChatFormatting.GREEN : ChatFormatting.GRAY))
                .append(Component.literal(" vs ").withStyle(ChatFormatting.GRAY))
                .append(Component.literal(String.format("%.2f", kd2)).withStyle(kd2 > kd1 ? ChatFormatting.GREEN : ChatFormatting.GRAY)), false);

            // Playtime
            long playtime1 = (long) stats1.get("playtime");
            long playtime2 = (long) stats2.get("playtime");
            source.sendSuccess(() -> Component.literal("Playtime: ")
                .withStyle(ChatFormatting.AQUA)
                .append(Component.literal(formatDuration(playtime1)).withStyle(ChatFormatting.WHITE))
                .append(Component.literal(" vs ").withStyle(ChatFormatting.GRAY))
                .append(Component.literal(formatDuration(playtime2)).withStyle(ChatFormatting.WHITE)), false);

            source.sendSuccess(() -> Component.literal("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
                .withStyle(ChatFormatting.DARK_PURPLE, ChatFormatting.BOLD), false);
            return 1;
        } catch (SQLException ex) {
            PlayeranalyticsForgeMod.LOGGER.error("Failed to compare players", ex);
            source.sendFailure(Component.literal("Failed to compare players. Check server logs."));
            return 0;
        }
    }

    @SuppressWarnings("null")
    private static int churnAnalysisCommand(CommandSourceStack source) {
        try {
            Path configDir = FMLPaths.CONFIGDIR.get();
            Path dbPath = configDir.resolve("playeranalytics.sqlite");
            String jdbcUrl = "jdbc:sqlite:" + dbPath.toAbsolutePath();
            
            String sql = "SELECT " +
                "COUNT(CASE WHEN last_seen < datetime('now', '-7 days') THEN 1 END) AS churned_7d, " +
                "COUNT(CASE WHEN last_seen < datetime('now', '-30 days') THEN 1 END) AS churned_30d, " +
                "COUNT(*) AS total_players " +
                "FROM player_stats";
            
            try (Connection conn = DriverManager.getConnection(jdbcUrl);
                 PreparedStatement stmt = conn.prepareStatement(sql)) {
                try (ResultSet rs = stmt.executeQuery()) {
                    if (rs.next()) {
                        source.sendSuccess(() -> Component.literal("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
                            .withStyle(ChatFormatting.DARK_PURPLE, ChatFormatting.BOLD), false);
                        source.sendSuccess(() -> Component.literal("📉 Churn Analysis")
                            .withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD), false);
                        source.sendSuccess(() -> Component.literal("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
                            .withStyle(ChatFormatting.DARK_PURPLE, ChatFormatting.BOLD), false);
                        
                        long churned7d = rs.getLong("churned_7d");
                        long churned30d = rs.getLong("churned_30d");
                        long totalPlayers = rs.getLong("total_players");
                        
                        double churnRate7d = totalPlayers > 0 ? (churned7d * 100.0) / totalPlayers : 0;
                        double churnRate30d = totalPlayers > 0 ? (churned30d * 100.0) / totalPlayers : 0;
                        
                        source.sendSuccess(() -> Component.literal("Total Players: ")
                            .withStyle(ChatFormatting.AQUA)
                            .append(Component.literal(String.valueOf(totalPlayers)).withStyle(ChatFormatting.WHITE)), false);
                        
                        source.sendSuccess(() -> Component.literal("Churned (7 days): ")
                            .withStyle(ChatFormatting.AQUA)
                            .append(Component.literal(churned7d + " ").withStyle(ChatFormatting.LIGHT_PURPLE))
                            .append(Component.literal(String.format("(%.1f%%)", churnRate7d)).withStyle(ChatFormatting.GRAY)), false);
                        
                        source.sendSuccess(() -> Component.literal("Churned (30 days): ")
                            .withStyle(ChatFormatting.AQUA)
                            .append(Component.literal(churned30d + " ").withStyle(ChatFormatting.RED))
                            .append(Component.literal(String.format("(%.1f%%)", churnRate30d)).withStyle(ChatFormatting.GRAY)), false);
                        
                        source.sendSuccess(() -> Component.literal("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
                            .withStyle(ChatFormatting.DARK_PURPLE, ChatFormatting.BOLD), false);
                        return 1;
                    }
                }
            }
            source.sendFailure(Component.literal("No churn data available."));
            return 0;
        } catch (SQLException ex) {
            PlayeranalyticsForgeMod.LOGGER.error("Failed to fetch churn analysis", ex);
            source.sendFailure(Component.literal("Failed to fetch churn analysis. Check server logs."));
            return 0;
        }
    }

    @SuppressWarnings("null")
    private static int updateCheckCommand(CommandSourceStack source) {
        source.sendSuccess(() -> Component.literal("Checking for updates...")
            .withStyle(ChatFormatting.YELLOW), false);

        UpdateChecker.UpdateResult result = UpdateChecker.checkNow();

        if (result.updateAvailable) {
            source.sendSuccess(() -> Component.literal("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
                .withStyle(ChatFormatting.DARK_PURPLE, ChatFormatting.BOLD), false);
            source.sendSuccess(() -> Component.literal("🔔 Update Available!")
                .withStyle(ChatFormatting.GREEN, ChatFormatting.BOLD), false);
            source.sendSuccess(() -> Component.literal("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
                .withStyle(ChatFormatting.DARK_PURPLE, ChatFormatting.BOLD), false);
            source.sendSuccess(() -> Component.literal("Current: ")
                .withStyle(ChatFormatting.AQUA)
                .append(Component.literal(result.currentVersion).withStyle(ChatFormatting.WHITE)), false);
            source.sendSuccess(() -> Component.literal("Latest: ")
                .withStyle(ChatFormatting.AQUA)
                .append(Component.literal(result.latestVersion).withStyle(ChatFormatting.GREEN)), false);
            source.sendSuccess(() -> Component.literal("Download: ")
                .withStyle(ChatFormatting.AQUA)
                .append(Component.literal("https://github.com/ilIger-518/PlayerAnalyticsForge/releases")
                    .withStyle(ChatFormatting.BLUE, ChatFormatting.UNDERLINE)), false);
            source.sendSuccess(() -> Component.literal("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
                .withStyle(ChatFormatting.DARK_PURPLE, ChatFormatting.BOLD), false);
            source.sendSuccess(() -> Component.literal("⚠ Stop the server to update the mod.")
                .withStyle(ChatFormatting.YELLOW, ChatFormatting.ITALIC), false);
        } else {
            source.sendSuccess(() -> Component.literal("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
                .withStyle(ChatFormatting.DARK_PURPLE, ChatFormatting.BOLD), false);
            source.sendSuccess(() -> Component.literal("✓ " + result.message)
                .withStyle(ChatFormatting.GREEN), false);
            if (result.currentVersion != null) {
                source.sendSuccess(() -> Component.literal("Version: ")
                    .withStyle(ChatFormatting.AQUA)
                    .append(Component.literal(result.currentVersion).withStyle(ChatFormatting.WHITE)), false);
            }
            source.sendSuccess(() -> Component.literal("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
                .withStyle(ChatFormatting.DARK_PURPLE, ChatFormatting.BOLD), false);
        }

        return 1;
    }
}

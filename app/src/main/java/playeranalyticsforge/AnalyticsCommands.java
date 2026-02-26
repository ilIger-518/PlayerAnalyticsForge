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

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;

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
}

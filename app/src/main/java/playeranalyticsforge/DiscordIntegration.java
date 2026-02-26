package playeranalyticsforge;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Discord Webhook Integration for PlayerAnalytics
 * Sends events to Discord channels via webhooks
 */
public final class DiscordIntegration {
    private static final DateTimeFormatter TIMESTAMP_FORMAT = DateTimeFormatter.ISO_INSTANT;

    private DiscordIntegration() {
    }

    /**
     * Send a message to Discord via webhook
     */
    public static void sendWebhook(String webhookUrl, String jsonPayload) {
        if (webhookUrl == null || webhookUrl.isEmpty() || !webhookUrl.startsWith("https://")) {
            return;
        }

        new Thread(() -> {
            try {
                URL url = new URL(webhookUrl);
                HttpURLConnection connection = (HttpURLConnection) url.openConnection();
                connection.setRequestMethod("POST");
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                connection.setRequestProperty("User-Agent", "PlayerAnalytics/1.1");
                connection.setDoOutput(true);
                connection.setConnectTimeout(5000);
                connection.setReadTimeout(5000);

                byte[] payload = jsonPayload.getBytes(StandardCharsets.UTF_8);
                connection.setFixedLengthStreamingMode(payload.length);

                try (OutputStream os = connection.getOutputStream()) {
                    os.write(payload);
                    os.flush();
                }

                int responseCode = connection.getResponseCode();
                if (responseCode >= 400) {
                    PlayeranalyticsForgeMod.LOGGER.warn("Discord webhook error: HTTP {}", responseCode);
                }
                connection.disconnect();
            } catch (Exception e) {
                PlayeranalyticsForgeMod.LOGGER.error("Failed to send Discord webhook", e);
            }
        }).start();
    }

    /**
     * Notify Discord of a player join
     */
    public static void notifyPlayerJoin(String playerName, String uuid) {
        if (!AnalyticsConfig.DISCORD_ENABLED.get()) {
            return;
        }

        String webhookUrl = AnalyticsConfig.DISCORD_WEBHOOK_URL.get();
        boolean notifyJoins = AnalyticsConfig.DISCORD_NOTIFY_JOINS.get();

        if (!notifyJoins) {
            return;
        }

        String embed = String.format("""
                {
                  "embeds": [{
                    "title": "⬇️ Player Joined",
                    "description": "**%s**",
                    "color": 3066993,
                    "footer": {"text": "PlayerAnalytics"},
                    "timestamp": "%s"
                  }]
                }
                """, escapeJson(playerName), TIMESTAMP_FORMAT.format(ZonedDateTime.now()));

        sendWebhook(webhookUrl, embed);
    }

    /**
     * Notify Discord of a player leave
     */
    public static void notifyPlayerLeave(String playerName, String uuid, long playtimeSeconds) {
        if (!AnalyticsConfig.DISCORD_ENABLED.get()) {
            return;
        }

        String webhookUrl = AnalyticsConfig.DISCORD_WEBHOOK_URL.get();
        boolean notifyLeaves = AnalyticsConfig.DISCORD_NOTIFY_LEAVES.get();

        if (!notifyLeaves) {
            return;
        }

        String duration = formatDuration(playtimeSeconds);
        String embed = String.format("""
                {
                  "embeds": [{
                    "title": "⬆️ Player Left",
                    "description": "**%s**",
                    "fields": [
                      {"name": "Session Duration", "value": "%s", "inline": true}
                    ],
                    "color": 15158332,
                    "footer": {"text": "PlayerAnalytics"},
                    "timestamp": "%s"
                  }]
                }
                """, escapeJson(playerName), duration, TIMESTAMP_FORMAT.format(ZonedDateTime.now()));

        sendWebhook(webhookUrl, embed);
    }

    /**
     * Notify Discord of a kill
     */
    public static void notifyKill(String killerName, String victimName, String weaponType, boolean isPvP) {
        if (!AnalyticsConfig.DISCORD_ENABLED.get()) {
            return;
        }

        String webhookUrl = AnalyticsConfig.DISCORD_WEBHOOK_URL.get();
        boolean notifyKills = AnalyticsConfig.DISCORD_NOTIFY_KILLS.get();

        if (!notifyKills) {
            return;
        }

        int color = isPvP ? 16711680 : 16776960; // Red for PvP, Yellow for PvE
        String type = isPvP ? "PvP Kill" : "PvE Kill";

        String embed = String.format("""
                {
                  "embeds": [{
                    "title": "⚔️ %s",
                    "description": "**%s** eliminated **%s**",
                    "fields": [
                      {"name": "Weapon", "value": "%s", "inline": true},
                      {"name": "Type", "value": "%s", "inline": true}
                    ],
                    "color": %d,
                    "footer": {"text": "PlayerAnalytics"},
                    "timestamp": "%s"
                  }]
                }
                """, type, escapeJson(killerName), escapeJson(victimName), 
                escapeJson(weaponType), isPvP ? "PvP" : "PvE", color,
                TIMESTAMP_FORMAT.format(ZonedDateTime.now()));

        sendWebhook(webhookUrl, embed);
    }

    /**
     * Notify Discord of a death
     */
    public static void notifyDeath(String playerName, String deathCause) {
        if (!AnalyticsConfig.DISCORD_ENABLED.get()) {
            return;
        }

        String webhookUrl = AnalyticsConfig.DISCORD_WEBHOOK_URL.get();
        boolean notifyDeaths = AnalyticsConfig.DISCORD_NOTIFY_DEATHS.get();

        if (!notifyDeaths) {
            return;
        }

        String embed = String.format("""
                {
                  "embeds": [{
                    "title": "💀 Player Death",
                    "description": "**%s** died",
                    "fields": [
                      {"name": "Cause", "value": "%s", "inline": true}
                    ],
                    "color": 9109504,
                    "footer": {"text": "PlayerAnalytics"},
                    "timestamp": "%s"
                  }]
                }
                """, escapeJson(playerName), escapeJson(deathCause),
                TIMESTAMP_FORMAT.format(ZonedDateTime.now()));

        sendWebhook(webhookUrl, embed);
    }

    /**
     * Notify Discord of server milestones
     */
    public static void notifyMilestone(String title, String description) {
        if (!AnalyticsConfig.DISCORD_ENABLED.get()) {
            return;
        }

        String webhookUrl = AnalyticsConfig.DISCORD_WEBHOOK_URL.get();
        boolean notifyMilestones = AnalyticsConfig.DISCORD_NOTIFY_MILESTONES.get();

        if (!notifyMilestones) {
            return;
        }

        String embed = String.format("""
                {
                  "embeds": [{
                    "title": "🎉 %s",
                    "description": "%s",
                    "color": 7506394,
                    "footer": {"text": "PlayerAnalytics"},
                    "timestamp": "%s"
                  }]
                }
                """, escapeJson(title), escapeJson(description),
                TIMESTAMP_FORMAT.format(ZonedDateTime.now()));

        sendWebhook(webhookUrl, embed);
    }

    /**
     * Notify Discord of server stats
     */
    public static void notifyServerStats(int playerCount, double tps, String serverName) {
        if (!AnalyticsConfig.DISCORD_ENABLED.get()) {
            return;
        }

        String webhookUrl = AnalyticsConfig.DISCORD_WEBHOOK_URL.get();
        boolean notifyStats = AnalyticsConfig.DISCORD_NOTIFY_STATS.get();

        if (!notifyStats) {
            return;
        }

        String tpsColor = tps >= 19.5 ? "🟢" : tps >= 18.0 ? "🟡" : "🔴";

        String embed = String.format("""
                {
                  "embeds": [{
                    "title": "📊 Server Stats - %s",
                    "fields": [
                      {"name": "Players Online", "value": "%d", "inline": true},
                      {"name": "TPS", "value": "%s %.1f", "inline": true}
                    ],
                    "color": 3447003,
                    "footer": {"text": "PlayerAnalytics"},
                    "timestamp": "%s"
                  }]
                }
                """, escapeJson(serverName), playerCount, tpsColor, tps,
                TIMESTAMP_FORMAT.format(ZonedDateTime.now()));

        sendWebhook(webhookUrl, embed);
    }

    /**
     * Escape special characters for JSON
     */
    private static String escapeJson(String str) {
        if (str == null) {
            return "";
        }
        return str
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\b", "\\b")
                .replace("\f", "\\f")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    /**
     * Format duration in seconds to human readable format
     */
    private static String formatDuration(long seconds) {
        long hours = seconds / 3600;
        long minutes = (seconds % 3600) / 60;
        long secs = seconds % 60;

        if (hours > 0) {
            return String.format("%dh %dm %ds", hours, minutes, secs);
        } else if (minutes > 0) {
            return String.format("%dm %ds", minutes, secs);
        } else {
            return String.format("%ds", secs);
        }
    }
}

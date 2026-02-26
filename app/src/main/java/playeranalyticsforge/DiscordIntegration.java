package playeranalyticsforge;

import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.exceptions.InvalidTokenException;

import java.awt.Color;
import java.time.Instant;

/**
 * Discord Bot Integration for PlayerAnalytics
 * Sends events to Discord channels via a bot token
 */
public final class DiscordIntegration {
  private static volatile JDA jda;

  private DiscordIntegration() {
  }

  public static void start() {
    if (!AnalyticsConfig.DISCORD_ENABLED.get()) {
      return;
    }

    String token = AnalyticsConfig.DISCORD_BOT_TOKEN.get();
    if (token == null || token.isBlank()) {
      PlayeranalyticsForgeMod.LOGGER.warn("Discord bot token not set; skipping Discord integration");
      return;
    }

    try {
      jda = JDABuilder.createDefault(token).build();
      PlayeranalyticsForgeMod.LOGGER.info("Discord bot initialization started");
    } catch (InvalidTokenException ex) {
      PlayeranalyticsForgeMod.LOGGER.error("Invalid Discord bot token; Discord integration disabled");
    } catch (Exception ex) {
      PlayeranalyticsForgeMod.LOGGER.error("Failed to initialize Discord bot", ex);
    }
  }

  public static void stop() {
    JDA current = jda;
    jda = null;
    if (current != null) {
      current.shutdownNow();
    }
  }

  private static TextChannel getTargetChannel() {
    JDA current = jda;
    if (current == null) {
      return null;
    }

    String channelId = AnalyticsConfig.DISCORD_CHANNEL_ID.get();
    if (channelId == null || channelId.isBlank()) {
      return null;
    }

    String guildId = AnalyticsConfig.DISCORD_GUILD_ID.get();
    if (guildId != null && !guildId.isBlank()) {
      Guild guild = current.getGuildById(guildId);
      if (guild != null) {
        return guild.getTextChannelById(channelId);
      }
    }

    return current.getTextChannelById(channelId);
  }

  private static void sendEmbed(EmbedBuilder embed) {
    if (!AnalyticsConfig.DISCORD_ENABLED.get()) {
      return;
    }

    TextChannel channel = getTargetChannel();
    if (channel == null) {
      return;
    }

    channel.sendMessageEmbeds(embed.build()).queue(
        success -> {},
        error -> PlayeranalyticsForgeMod.LOGGER.debug("Discord message send failed", error)
    );
  }

    /**
     * Notify Discord of a player join
     */
    public static void notifyPlayerJoin(String playerName, String uuid) {
        boolean notifyJoins = AnalyticsConfig.DISCORD_NOTIFY_JOINS.get();
        if (!notifyJoins) {
            return;
        }

        EmbedBuilder embed = baseEmbed("⬇️ Player Joined")
                .setDescription("**" + escapeMarkdown(playerName) + "**")
                .setColor(new Color(46, 204, 113))
                .setTimestamp(Instant.now());

        sendEmbed(embed);
    }

    /**
     * Notify Discord of a player leave
     */
    public static void notifyPlayerLeave(String playerName, String uuid, long playtimeSeconds) {
        boolean notifyLeaves = AnalyticsConfig.DISCORD_NOTIFY_LEAVES.get();
        if (!notifyLeaves) {
            return;
        }

        String duration = formatDuration(playtimeSeconds);
        EmbedBuilder embed = baseEmbed("⬆️ Player Left")
                .setDescription("**" + escapeMarkdown(playerName) + "**")
                .addField("Session Duration", duration, true)
                .setColor(new Color(231, 76, 60))
                .setTimestamp(Instant.now());

        sendEmbed(embed);
    }

    /**
     * Notify Discord of a kill
     */
    public static void notifyKill(String killerName, String victimName, String weaponType, boolean isPvP) {
        boolean notifyKills = AnalyticsConfig.DISCORD_NOTIFY_KILLS.get();
        if (!notifyKills) {
            return;
        }

        Color color = isPvP ? new Color(231, 76, 60) : new Color(241, 196, 15);
        String type = isPvP ? "PvP Kill" : "PvE Kill";

        EmbedBuilder embed = baseEmbed("⚔️ " + type)
                .setDescription("**" + escapeMarkdown(killerName) + "** eliminated **" + escapeMarkdown(victimName) + "**")
                .addField("Weapon", escapeMarkdown(weaponType), true)
                .addField("Type", isPvP ? "PvP" : "PvE", true)
                .setColor(color)
                .setTimestamp(Instant.now());

        sendEmbed(embed);
    }

    /**
     * Notify Discord of a death
     */
    public static void notifyDeath(String playerName, String deathCause) {
        boolean notifyDeaths = AnalyticsConfig.DISCORD_NOTIFY_DEATHS.get();
        if (!notifyDeaths) {
            return;
        }

        EmbedBuilder embed = baseEmbed("💀 Player Death")
                .setDescription("**" + escapeMarkdown(playerName) + "** died")
                .addField("Cause", escapeMarkdown(deathCause), true)
                .setColor(new Color(155, 89, 182))
                .setTimestamp(Instant.now());

        sendEmbed(embed);
    }

    /**
     * Notify Discord of server milestones
     */
    public static void notifyMilestone(String title, String description) {
        boolean notifyMilestones = AnalyticsConfig.DISCORD_NOTIFY_MILESTONES.get();
        if (!notifyMilestones) {
            return;
        }

        EmbedBuilder embed = baseEmbed("🎉 " + escapeMarkdown(title))
                .setDescription(escapeMarkdown(description))
                .setColor(new Color(155, 89, 182))
                .setTimestamp(Instant.now());

        sendEmbed(embed);
    }

    /**
     * Notify Discord of server stats
     */
    public static void notifyServerStats(int playerCount, double tps, String serverName) {
        boolean notifyStats = AnalyticsConfig.DISCORD_NOTIFY_STATS.get();
        if (!notifyStats) {
            return;
        }

        String tpsColor = tps >= 19.5 ? "🟢" : tps >= 18.0 ? "🟡" : "🔴";

        EmbedBuilder embed = baseEmbed("📊 Server Stats - " + escapeMarkdown(serverName))
                .addField("Players Online", String.valueOf(playerCount), true)
                .addField("TPS", tpsColor + " " + String.format("%.1f", tps), true)
                .setColor(new Color(52, 152, 219))
                .setTimestamp(Instant.now());

        sendEmbed(embed);
    }

    private static EmbedBuilder baseEmbed(String title) {
        return new EmbedBuilder()
                .setTitle(title)
                .setFooter("PlayerAnalytics")
                .setTimestamp(Instant.now());
    }

    private static String escapeMarkdown(String str) {
        if (str == null) {
            return "";
        }
        return str
                .replace("\\", "\\\\")
                .replace("*", "\\*")
                .replace("_", "\\_")
                .replace("~", "\\~")
                .replace("`", "\\`");
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

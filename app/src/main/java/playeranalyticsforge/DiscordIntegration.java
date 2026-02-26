package playeranalyticsforge;

import java.io.File;
import java.net.URL;
import java.net.URLClassLoader;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Discord Bot Integration for PlayerAnalytics (Reflection-based to avoid hard dependency on JDA)
 * Sends events to Discord channels via a bot token using reflection to load JDA dynamically
 */
public final class DiscordIntegration {
    private static volatile Object jda; // Holds JDA instance if available
    private static volatile boolean jdaAvailable = false;
    private static volatile URLClassLoader jdaClassLoader; // Custom class loader for JDA
    
    private DiscordIntegration() {
    }

    static {
        // Try to load JDA from classpath first, then from libs folder
        try {
            Class.forName("net.dv8tion.jda.api.JDABuilder");
            jdaAvailable = true;
        } catch (ClassNotFoundException e) {
            // Try to load from libs directory (working dir is set to 'run' in gradle)
            try {
                List<URL> jarUrls = new ArrayList<>();
                File libsDir = new File("libs");
                if (libsDir.exists() && libsDir.isDirectory()) {
                    File[] jars = libsDir.listFiles((dir, name) -> name.endsWith(".jar"));
                    if (jars != null) {
                        for (File jar : jars) {
                            try {
                                jarUrls.add(jar.toURI().toURL());
                            } catch (Exception ignored) {
                            }
                        }
                    }
                }
                
                if (!jarUrls.isEmpty()) {
                    jdaClassLoader = new URLClassLoader(
                            jarUrls.toArray(new URL[0]),
                            ClassLoader.getSystemClassLoader() // Use system classloader as parent to avoid Forge issues
                    );
                    try {
                        jdaClassLoader.loadClass("net.dv8tion.jda.api.JDABuilder");
                        jdaAvailable = true;
                    } catch (ClassNotFoundException ignored) {
                    }
                }
            } catch (Exception ignored) {
            }
        }
    }

    public static void start() {
        if (!jdaAvailable) {
            PlayeranalyticsForgeMod.LOGGER.debug("JDA library not found; Discord integration unavailable");
            return;
        }

        if (!AnalyticsConfig.DISCORD_ENABLED.get()) {
            return;
        }

        String token = AnalyticsConfig.DISCORD_BOT_TOKEN.get();
        if (token == null || token.isBlank()) {
            PlayeranalyticsForgeMod.LOGGER.debug("Discord bot token not set; skipping Discord integration");
            return;
        }

        try {
            ClassLoader loader = jdaClassLoader != null ? jdaClassLoader : Thread.currentThread().getContextClassLoader();
            Class<?> jdaBuilderClass = loader.loadClass("net.dv8tion.jda.api.JDABuilder");
            java.lang.reflect.Method createDefaultMethod = jdaBuilderClass.getMethod("createDefault", String.class);
            Object builder = createDefaultMethod.invoke(null, token);
            java.lang.reflect.Method buildMethod = builder.getClass().getMethod("build");
            jda = buildMethod.invoke(builder);
            PlayeranalyticsForgeMod.LOGGER.info("Discord bot initialized via JDA");
        } catch (Throwable ex) {  // Catch both Exception and Error (NoClassDefFoundError)
            PlayeranalyticsForgeMod.LOGGER.warn("Failed to initialize Discord bot: {}", ex.getMessage());
            jdaAvailable = false;  // Disable Discord for this session
        }
    }

    public static void stop() {
        if (jda == null || !jdaAvailable) {
            return;
        }

        try {
            java.lang.reflect.Method shutdownMethod = jda.getClass().getMethod("shutdownNow");
            shutdownMethod.invoke(jda);
            jda = null;
        } catch (Exception ex) {
            PlayeranalyticsForgeMod.LOGGER.debug("Failed to shutdown Discord bot", ex);
        }
    }

    private static Object getTargetChannel() {
        if (jda == null || !jdaAvailable) {
            return null;
        }

        String channelId = AnalyticsConfig.DISCORD_CHANNEL_ID.get();
        if (channelId == null || channelId.isBlank()) {
            return null;
        }

        try {
            String guildId = AnalyticsConfig.DISCORD_GUILD_ID.get();
            java.lang.reflect.Method getTextChannelByIdMethod;
            
            if (guildId != null && !guildId.isBlank()) {
                java.lang.reflect.Method getGuildByIdMethod = jda.getClass().getMethod("getGuildById", String.class);
                Object guild = getGuildByIdMethod.invoke(jda, guildId);
                if (guild != null) {
                    getTextChannelByIdMethod = guild.getClass().getMethod("getTextChannelById", String.class);
                    return getTextChannelByIdMethod.invoke(guild, channelId);
                }
            }

            getTextChannelByIdMethod = jda.getClass().getMethod("getTextChannelById", String.class);
            return getTextChannelByIdMethod.invoke(jda, channelId);
        } catch (Exception ex) {
            return null;
        }
    }

    private static void sendEmbed(String title, String description, int color) {
        if (!jdaAvailable) {
            return;
        }

        Object channel = getTargetChannel();
        if (channel == null) {
            return;
        }

        try {
            ClassLoader loader = jdaClassLoader != null ? jdaClassLoader : Thread.currentThread().getContextClassLoader();
            Class<?> embedBuilderClass = loader.loadClass("net.dv8tion.jda.api.EmbedBuilder");
            Object embed = embedBuilderClass.getConstructor().newInstance();
            
            // Set title
            java.lang.reflect.Method setTitleMethod = embedBuilderClass.getMethod("setTitle", String.class);
            setTitleMethod.invoke(embed, title);
            
            // Set description (may be appendDescription in beta versions)
            if (description != null && !description.isEmpty()) {
                try {
                    java.lang.reflect.Method setDescMethod = embedBuilderClass.getMethod("setDescription", String.class);
                    setDescMethod.invoke(embed, description);
                } catch (NoSuchMethodException e) {
                    java.lang.reflect.Method appendDescMethod = embedBuilderClass.getMethod("appendDescription", String.class);
                    appendDescMethod.invoke(embed, description);
                }
            }
            
            // Set color
            Class<?> colorClass = Class.forName("java.awt.Color");
            Object colorObj = colorClass.getConstructor(int.class).newInstance(color);
            java.lang.reflect.Method setColorMethod = embedBuilderClass.getMethod("setColor", colorClass);
            setColorMethod.invoke(embed, colorObj);
            
            // Set timestamp
            Class<?> instantClass = Class.forName("java.time.Instant");
            Object now = instantClass.getMethod("now").invoke(null);
            java.lang.reflect.Method setTimestampMethod = embedBuilderClass.getMethod("setTimestamp", instantClass);
            setTimestampMethod.invoke(embed, now);
            
            // Set footer
            java.lang.reflect.Method setFooterMethod = embedBuilderClass.getMethod("setFooter", String.class);
            setFooterMethod.invoke(embed, "PlayerAnalytics");
            
            // Build the embed
            java.lang.reflect.Method buildMethod = embedBuilderClass.getMethod("build");
            Object builtEmbed = buildMethod.invoke(embed);
            
            // Send via channel
            java.lang.reflect.Method sendMessageEmbedsMethod = channel.getClass().getMethod("sendMessageEmbeds", java.util.List.class);
            Object action = sendMessageEmbedsMethod.invoke(channel, java.util.Arrays.asList(builtEmbed));
            
            // Queue it (fire and forget)
            java.lang.reflect.Method queueMethod = action.getClass().getMethod("queue");
            queueMethod.invoke(action);
        } catch (Exception ex) {
            PlayeranalyticsForgeMod.LOGGER.debug("Failed to send Discord embed", ex);
        }
    }

    /**
     * Notify Discord of a player join
     */
    public static void notifyPlayerJoin(String playerName, String uuid) {
        if (!AnalyticsConfig.DISCORD_ENABLED.get() || !AnalyticsConfig.DISCORD_NOTIFY_JOINS.get()) {
            return;
        }

        sendEmbed("⬇️ Player Joined", "**" + escapeMarkdown(playerName) + "**", 3066993); // Green
    }

    /**
     * Notify Discord of a player leave
     */
    public static void notifyPlayerLeave(String playerName, String uuid, long playtimeSeconds) {
        if (!AnalyticsConfig.DISCORD_ENABLED.get() || !AnalyticsConfig.DISCORD_NOTIFY_LEAVES.get()) {
            return;
        }

        String duration = formatDuration(playtimeSeconds);
        sendEmbed("⬆️ Player Left", "**" + escapeMarkdown(playerName) + "**\nSession Duration: " + duration, 15158332); // Red
    }

    /**
     * Notify Discord of a kill
     */
    public static void notifyKill(String killerName, String victimName, String weaponType, boolean isPvP) {
        if (!AnalyticsConfig.DISCORD_ENABLED.get() || !AnalyticsConfig.DISCORD_NOTIFY_KILLS.get()) {
            return;
        }

        int color = isPvP ? 16711680 : 16776960; // Red for PvP, Yellow for PvE
        String type = isPvP ? "PvP Kill" : "PvE Kill";
        sendEmbed("⚔️ " + type, 
                "**" + escapeMarkdown(killerName) + "** eliminated **" + escapeMarkdown(victimName) + "**\n" +
                "Weapon: " + escapeMarkdown(weaponType) + "\nType: " + (isPvP ? "PvP" : "PvE"), 
                color);
    }

    /**
     * Notify Discord of a death
     */
    public static void notifyDeath(String playerName, String deathCause) {
        if (!AnalyticsConfig.DISCORD_ENABLED.get() || !AnalyticsConfig.DISCORD_NOTIFY_DEATHS.get()) {
            return;
        }

        sendEmbed("💀 Player Death", 
                "**" + escapeMarkdown(playerName) + "** died\nCause: " + escapeMarkdown(deathCause),
                9109504); // Purple
    }

    /**
     * Notify Discord of server milestones
     */
    public static void notifyMilestone(String title, String description) {
        if (!AnalyticsConfig.DISCORD_ENABLED.get() || !AnalyticsConfig.DISCORD_NOTIFY_MILESTONES.get()) {
            return;
        }

        sendEmbed("🎉 " + escapeMarkdown(title), escapeMarkdown(description), 7506394); // Purple
    }

    /**
     * Notify Discord of server stats
     */
    public static void notifyServerStats(int playerCount, double tps, String serverName) {
        if (!AnalyticsConfig.DISCORD_ENABLED.get() || !AnalyticsConfig.DISCORD_NOTIFY_STATS.get()) {
            return;
        }

        String tpsColor = tps >= 19.5 ? "🟢" : tps >= 18.0 ? "🟡" : "🔴";
        sendEmbed("📊 Server Stats - " + escapeMarkdown(serverName),
                "Players Online: " + playerCount + "\nTPS: " + tpsColor + " " + String.format("%.1f", tps),
                3447003); // Blue
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

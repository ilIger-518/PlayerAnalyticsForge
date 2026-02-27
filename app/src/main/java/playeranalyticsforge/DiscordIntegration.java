package playeranalyticsforge;

import java.io.File;
import java.net.URL;
import java.net.URLClassLoader;
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
            Class<?> gatewayIntentClass = loader.loadClass("net.dv8tion.jda.api.requests.GatewayIntent");
            
            // Get GatewayIntent enum values
            java.lang.reflect.Method valuesMethod = gatewayIntentClass.getMethod("values");
            Object[] intents = (Object[]) valuesMethod.invoke(null);
            
            // Find GUILD_MESSAGES and MESSAGE_CONTENT intents
            Object guildMessagesIntent = null;
            Object messageContentIntent = null;
            for (Object intent : intents) {
                String name = intent.toString();
                if ("GUILD_MESSAGES".equals(name)) {
                    guildMessagesIntent = intent;
                } else if ("MESSAGE_CONTENT".equals(name)) {
                    messageContentIntent = intent;
                }
            }
            
            // Create builder with required intents
            java.lang.reflect.Method createLightMethod = jdaBuilderClass.getMethod("createLight", String.class, java.util.Collection.class);
            java.util.List<Object> intentsList = new java.util.ArrayList<>();
            if (guildMessagesIntent != null) {
                intentsList.add(guildMessagesIntent);
            }
            if (messageContentIntent != null) {
                intentsList.add(messageContentIntent);
            }
            Object builder = createLightMethod.invoke(null, token, intentsList);
            
            PlayeranalyticsForgeMod.LOGGER.info("Discord bot builder created with MESSAGE_CONTENT and GUILD_MESSAGES intents");
            
            // Add message listener if chat bridging is enabled
            if (AnalyticsConfig.DISCORD_BRIDGE_CHAT.get()) {
                try {
                    Class<?> eventListenerClass = loader.loadClass("net.dv8tion.jda.api.hooks.EventListener");
                    Object listener = java.lang.reflect.Proxy.newProxyInstance(
                        loader,
                        new Class<?>[]{eventListenerClass},
                        new DiscordMessageHandler()
                    );
                    java.lang.reflect.Method addEventListenersMethod = builder.getClass().getMethod("addEventListeners", Object[].class);
                    addEventListenersMethod.invoke(builder, (Object) new Object[]{listener});
                    PlayeranalyticsForgeMod.LOGGER.info("Discord chat bridge listener registered");
                } catch (Exception ex) {
                    PlayeranalyticsForgeMod.LOGGER.warn("Failed to enable Discord chat bridge: {}", ex.getMessage());
                }
            }
            
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
            PlayeranalyticsForgeMod.LOGGER.error("Discord channel not found! Channel ID: {}. Make sure the bot has access to the channel.", 
                AnalyticsConfig.DISCORD_CHANNEL_ID.get());
            return;
        }

        try {
            ClassLoader loader = jdaClassLoader != null ? jdaClassLoader : Thread.currentThread().getContextClassLoader();
            Class<?> embedBuilderClass = loader.loadClass("net.dv8tion.jda.api.EmbedBuilder");
            Object embed = embedBuilderClass.getConstructor().newInstance();
            
            // Set title
            java.lang.reflect.Method setTitleMethod = embedBuilderClass.getMethod("setTitle", String.class);
            setTitleMethod.invoke(embed, title);
            
            // Set description - try multiple method names
            if (description != null && !description.isEmpty()) {
                boolean descSet = false;
                
                // Try setDescription first
                try {
                    java.lang.reflect.Method m = embedBuilderClass.getMethod("setDescription", String.class);
                    m.invoke(embed, description);
                    descSet = true;
                } catch (NoSuchMethodException ignored) {}
                
                // Try appendDescription
                if (!descSet) {
                    try {
                        java.lang.reflect.Method m = embedBuilderClass.getMethod("appendDescription", String.class);
                        m.invoke(embed, description);
                        descSet = true;
                    } catch (NoSuchMethodException ignored) {}
                }
                
                // If neither works, skip description
                if (!descSet) {
                    PlayeranalyticsForgeMod.LOGGER.debug("Could not set description on EmbedBuilder - method not found");
                }
            }
            
            // Set color
            Class<?> colorClass = Class.forName("java.awt.Color");
            Object colorObj = colorClass.getConstructor(int.class).newInstance(color);
            java.lang.reflect.Method setColorMethod = embedBuilderClass.getMethod("setColor", colorClass);
            setColorMethod.invoke(embed, colorObj);
            
            // Set timestamp (handle JDA 5 beta signature changes)
            if (!trySetTimestamp(embedBuilderClass, embed)) {
                PlayeranalyticsForgeMod.LOGGER.debug("setTimestamp method not found, skipping timestamp");
            }
            
            // Set footer
            java.lang.reflect.Method setFooterMethod = embedBuilderClass.getMethod("setFooter", String.class);
            setFooterMethod.invoke(embed, "PlayerAnalytics");
            
            // Build the embed
            java.lang.reflect.Method buildMethod = embedBuilderClass.getMethod("build");
            Object builtEmbed = buildMethod.invoke(embed);
            
            // Send via channel
            Class<?> messageChannelClass = loader.loadClass("net.dv8tion.jda.api.entities.channel.middleman.MessageChannel");
            java.lang.reflect.Method sendMessageEmbedsMethod = findPublicMethod(messageChannelClass, "sendMessageEmbeds", java.util.Collection.class);
            Object action;
            if (sendMessageEmbedsMethod != null) {
                action = sendMessageEmbedsMethod.invoke(channel, java.util.Arrays.asList(builtEmbed));
            } else {
                Class<?> messageEmbedClass = loader.loadClass("net.dv8tion.jda.api.entities.MessageEmbed");
                Class<?> embedArrayClass = java.lang.reflect.Array.newInstance(messageEmbedClass, 0).getClass();
                sendMessageEmbedsMethod = findPublicMethod(messageChannelClass, "sendMessageEmbeds", embedArrayClass);
                if (sendMessageEmbedsMethod == null) {
                    throw new NoSuchMethodException("sendMessageEmbeds");
                }
                Object embedArray = java.lang.reflect.Array.newInstance(messageEmbedClass, 1);
                java.lang.reflect.Array.set(embedArray, 0, builtEmbed);
                action = sendMessageEmbedsMethod.invoke(channel, embedArray);
            }
            
            // Queue with error callback
            Class<?> consumerClass = loader.loadClass("java.util.function.Consumer");
            Object errorCallback = java.lang.reflect.Proxy.newProxyInstance(
                loader,
                new Class<?>[]{consumerClass},
                (proxy, method, args) -> {
                    if (method.getName().equals("accept") && args != null && args.length > 0) {
                        Throwable error = (Throwable) args[0];
                        PlayeranalyticsForgeMod.LOGGER.error("Failed to send Discord embed: {}", error.getMessage(), error);
                    }
                    return null;
                }
            );
            
            java.lang.reflect.Method queueMethod = action.getClass().getMethod("queue", consumerClass, consumerClass);
            queueMethod.invoke(action, null, errorCallback);
            
            PlayeranalyticsForgeMod.LOGGER.info("Discord embed queued: {}", title);
        } catch (Exception ex) {
            PlayeranalyticsForgeMod.LOGGER.error("Failed to send Discord embed: {}", ex.getMessage(), ex);
        }
    }

    private static boolean trySetTimestamp(Class<?> embedBuilderClass, Object embed) {
        for (java.lang.reflect.Method method : embedBuilderClass.getMethods()) {
            if (!"setTimestamp".equals(method.getName()) || method.getParameterCount() != 1) {
                continue;
            }
            Object timestamp = buildTimestampArgument(method.getParameterTypes()[0]);
            if (timestamp == null) {
                continue;
            }
            try {
                method.invoke(embed, timestamp);
                return true;
            } catch (Exception ex) {
                PlayeranalyticsForgeMod.LOGGER.debug("Failed to set embed timestamp: {}", ex.getMessage());
            }
        }
        return false;
    }

    private static Object buildTimestampArgument(Class<?> paramType) {
        String typeName = paramType.getName();
        if ("java.time.Instant".equals(typeName)) {
            return java.time.Instant.now();
        }
        if ("java.time.OffsetDateTime".equals(typeName)) {
            return java.time.OffsetDateTime.now();
        }
        if ("java.time.ZonedDateTime".equals(typeName)) {
            return java.time.ZonedDateTime.now();
        }
        if ("java.time.LocalDateTime".equals(typeName)) {
            return java.time.LocalDateTime.now();
        }
        if ("java.time.temporal.TemporalAccessor".equals(typeName)) {
            return java.time.OffsetDateTime.now();
        }
        return null;
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

    private static java.lang.reflect.Method findPublicMethod(Class<?> target, String name, Class<?>... params) {
        try {
            return target.getMethod(name, params);
        } catch (NoSuchMethodException ex) {
            return null;
        }
    }

    /**
     * Send a simple chat message to Discord (not an embed)
     */
    public static void sendChatMessage(String playerName, String message) {
        if (jda == null || !jdaAvailable) {
            return;
        }

        if (!AnalyticsConfig.DISCORD_ENABLED.get() || !AnalyticsConfig.DISCORD_BRIDGE_CHAT.get()) {
            return;
        }

        String channelId = AnalyticsConfig.DISCORD_CHANNEL_ID.get();
        if (channelId == null || channelId.isBlank()) {
            return;
        }

        try {
            ClassLoader loader = jdaClassLoader != null ? jdaClassLoader : Thread.currentThread().getContextClassLoader();
            
            // Get channel
            java.lang.reflect.Method getTextChannelByIdMethod = jda.getClass().getMethod("getTextChannelById", String.class);
            Object channel = getTextChannelByIdMethod.invoke(jda, channelId);

            if (channel == null) {
                PlayeranalyticsForgeMod.LOGGER.error("Discord channel not found! Channel ID: {}. Make sure the bot has access to the channel.", channelId);
                return;
            }

            // Send formatted message
            String formattedMessage = "**[" + escapeMarkdown(playerName) + "]** " + escapeMarkdown(message);
            Class<?> messageChannelClass = loader.loadClass("net.dv8tion.jda.api.entities.channel.middleman.MessageChannel");
            java.lang.reflect.Method sendMessageMethod = findPublicMethod(messageChannelClass, "sendMessage", CharSequence.class);
            if (sendMessageMethod == null) {
                sendMessageMethod = findPublicMethod(messageChannelClass, "sendMessage", String.class);
            }
            if (sendMessageMethod == null) {
                throw new NoSuchMethodException("sendMessage");
            }
            Object action = sendMessageMethod.invoke(channel, formattedMessage);
            
            // Queue with error callback
            Class<?> consumerClass = loader.loadClass("java.util.function.Consumer");
            Object errorCallback = java.lang.reflect.Proxy.newProxyInstance(
                loader,
                new Class<?>[]{consumerClass},
                (proxy, method, args) -> {
                    if (method.getName().equals("accept") && args != null && args.length > 0) {
                        Throwable error = (Throwable) args[0];
                        PlayeranalyticsForgeMod.LOGGER.error("Failed to send Discord message: {}", error.getMessage(), error);
                    }
                    return null;
                }
            );
            
            java.lang.reflect.Method queueMethod = action.getClass().getMethod("queue", consumerClass, consumerClass);
            queueMethod.invoke(action, null, errorCallback);

            PlayeranalyticsForgeMod.LOGGER.info("Chat message queued for Discord: [{}] {}", playerName, message);
        } catch (Throwable ex) {
            PlayeranalyticsForgeMod.LOGGER.error("Failed to send chat message to Discord", ex);
        }
    }

    /**
     * Broadcast a Discord message to Minecraft chat
     */
    @SuppressWarnings("null")
    public static void broadcastToMinecraft(String username, String content) {
        try {
            net.minecraft.server.MinecraftServer server = net.minecraftforge.server.ServerLifecycleHooks.getCurrentServer();
            if (server != null) {
                net.minecraft.network.chat.Component message = net.minecraft.network.chat.Component.literal(
                    "§b[Discord] §f<" + username + "> §7" + content
                );
                server.getPlayerList().broadcastSystemMessage(message, false);
                PlayeranalyticsForgeMod.LOGGER.debug("Broadcasted Discord message to Minecraft: <{}> {}", username, content);
            }
        } catch (Exception ex) {
            PlayeranalyticsForgeMod.LOGGER.debug("Failed to broadcast Discord message to Minecraft", ex);
        }
    }

    /**
     * InvocationHandler to handle Discord message events via reflection
     */
    static class DiscordMessageHandler implements java.lang.reflect.InvocationHandler {
        public DiscordMessageHandler() {
        }

        @Override
        public Object invoke(Object proxy, java.lang.reflect.Method method, Object[] args) throws Throwable {
            if (args == null || args.length == 0) {
                return null;
            }

            if ("onEvent".equals(method.getName()) || "onMessageReceived".equals(method.getName())) {
                try {
                    Object event = args[0];
                    String eventClassName = event.getClass().getName();
                    if (!eventClassName.endsWith("MessageReceivedEvent")) {
                        return null;
                    }
                    
                    // Get author
                    java.lang.reflect.Method getAuthorMethod = event.getClass().getMethod("getAuthor");
                    Object author = getAuthorMethod.invoke(event);
                    
                    // Check if author is a bot
                    java.lang.reflect.Method isBotMethod = author.getClass().getMethod("isBot");
                    boolean isBot = (Boolean) isBotMethod.invoke(author);
                    
                    if (isBot) {
                        return null; // Ignore bot messages
                    }
                    
                    // Get channel
                    java.lang.reflect.Method getChannelMethod = event.getClass().getMethod("getChannel");
                    Object channel = getChannelMethod.invoke(event);
                    java.lang.reflect.Method getIdMethod = channel.getClass().getMethod("getId");
                    String channelId = (String) getIdMethod.invoke(channel);
                    
                    // Check if this is our configured channel
                    String configuredChannelId = AnalyticsConfig.DISCORD_CHANNEL_ID.get();
                    if (!channelId.equals(configuredChannelId)) {
                        return null; // Not our channel
                    }
                    
                    // Get message content
                    java.lang.reflect.Method getContentDisplayMethod = event.getClass().getMethod("getMessage");
                    Object message = getContentDisplayMethod.invoke(event);
                    java.lang.reflect.Method getContentRawMethod = message.getClass().getMethod("getContentRaw");
                    String content = (String) getContentRawMethod.invoke(message);
                    
                    // Get username
                    java.lang.reflect.Method getNameMethod = author.getClass().getMethod("getName");
                    String username = (String) getNameMethod.invoke(author);
                    
                    // Broadcast to Minecraft
                    if (content != null && !content.isBlank()) {
                        broadcastToMinecraft(username, content);
                    }
                } catch (Exception ex) {
                    PlayeranalyticsForgeMod.LOGGER.debug("Failed to process Discord message event: {}", ex.getMessage());
                }
            }
            return null;
        }
    }
}

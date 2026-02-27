package playeranalyticsforge;

import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.config.ModConfig;

import java.util.List;

public final class AnalyticsConfig {
    
    private static final ForgeConfigSpec.Builder BUILDER = new ForgeConfigSpec.Builder();
    public static final ForgeConfigSpec SPEC;
    
    // Web Server Configuration
    public static final ForgeConfigSpec.ConfigValue<String> WEB_SERVER_HOST;
    public static final ForgeConfigSpec.IntValue WEB_SERVER_PORT;
    public static final ForgeConfigSpec.BooleanValue WEB_SERVER_ENABLED;
    
    // Security Configuration
    public static final ForgeConfigSpec.BooleanValue REQUIRE_AUTH;
    public static final ForgeConfigSpec.ConfigValue<String> ACCESS_TOKEN;
    public static final ForgeConfigSpec.ConfigValue<List<? extends String>> IP_ALLOWLIST;
    
    // Feature Toggles
    public static final ForgeConfigSpec.BooleanValue TRACK_PLAYTIME;
    public static final ForgeConfigSpec.BooleanValue TRACK_COMBAT;
    public static final ForgeConfigSpec.BooleanValue TRACK_SESSIONS;
    public static final ForgeConfigSpec.BooleanValue TRACK_AFK;
    public static final ForgeConfigSpec.BooleanValue TRACK_ACTIVITY;
    
    // Performance Configuration
    public static final ForgeConfigSpec.IntValue DASHBOARD_REFRESH_INTERVAL;
    public static final ForgeConfigSpec.IntValue METRICS_RECORDING_INTERVAL;
    public static final ForgeConfigSpec.IntValue ACTIVITY_UPDATE_INTERVAL;
    public static final ForgeConfigSpec.IntValue AFK_TIMEOUT_SECONDS;
    
    // Data Retention Configuration
    public static final ForgeConfigSpec.IntValue DATA_RETENTION_DAYS;
    public static final ForgeConfigSpec.BooleanValue AUTO_CLEANUP_ENABLED;
    public static final ForgeConfigSpec.IntValue CLEANUP_INTERVAL_HOURS;
    
    // Network Configuration
    public static final ForgeConfigSpec.BooleanValue NETWORK_ENABLED;
    public static final ForgeConfigSpec.ConfigValue<String> NETWORK_NAME;
    public static final ForgeConfigSpec.ConfigValue<String> SERVER_ID;
    public static final ForgeConfigSpec.ConfigValue<String> CENTRAL_SERVER_URL;
    public static final ForgeConfigSpec.IntValue NETWORK_SYNC_INTERVAL_SECONDS;
    
        // Discord Configuration
        public static final ForgeConfigSpec.BooleanValue DISCORD_ENABLED;
        public static final ForgeConfigSpec.ConfigValue<String> DISCORD_BOT_TOKEN;
        public static final ForgeConfigSpec.ConfigValue<String> DISCORD_CHANNEL_ID;
        public static final ForgeConfigSpec.ConfigValue<String> DISCORD_GUILD_ID;
        public static final ForgeConfigSpec.BooleanValue DISCORD_BRIDGE_CHAT;
        public static final ForgeConfigSpec.BooleanValue DISCORD_NOTIFY_JOINS;
        public static final ForgeConfigSpec.BooleanValue DISCORD_NOTIFY_LEAVES;
        public static final ForgeConfigSpec.BooleanValue DISCORD_NOTIFY_KILLS;
        public static final ForgeConfigSpec.BooleanValue DISCORD_NOTIFY_DEATHS;
        public static final ForgeConfigSpec.BooleanValue DISCORD_NOTIFY_MILESTONES;
        public static final ForgeConfigSpec.BooleanValue DISCORD_NOTIFY_STATS;
    
    static {
        BUILDER.comment("PlayerAnalytics Configuration")
                .push("playeranalytics");
        
        // Web Server
        BUILDER.comment("Web Server Configuration")
                .push("webserver");
        
        WEB_SERVER_HOST = BUILDER
                .comment("Host address for the web server (0.0.0.0 = all interfaces, 127.0.0.1 = localhost only)")
                .define("host", "0.0.0.0");
        
        WEB_SERVER_PORT = BUILDER
                .comment("Port for the web server")
                .defineInRange("port", 8804, 1024, 65535);
        
        WEB_SERVER_ENABLED = BUILDER
                .comment("Enable or disable the web server")
                .define("enabled", true);
        
        BUILDER.pop();
        
        // Security
        BUILDER.comment("Security Configuration")
                .push("security");
        
        REQUIRE_AUTH = BUILDER
                .comment("Require authentication token for web dashboard access")
                .define("requireAuth", false);
        
        ACCESS_TOKEN = BUILDER
                .comment("Access token for web dashboard (only used if requireAuth is true)")
                .define("accessToken", "");
        
        IP_ALLOWLIST = BUILDER
                .comment("List of allowed IP addresses (empty = allow all). Example: [\"127.0.0.1\", \"192.168.1.0/24\"]")
                .defineList("ipAllowlist", List.of(), obj -> obj instanceof String);
        
        BUILDER.pop();
        
        // Features
        BUILDER.comment("Feature Toggles - Enable or disable specific tracking features")
                .push("features");
        
        TRACK_PLAYTIME = BUILDER
                .comment("Track player playtime and sessions")
                .define("trackPlaytime", true);
        
        TRACK_COMBAT = BUILDER
                .comment("Track combat statistics (kills, deaths, weapons)")
                .define("trackCombat", true);
        
        TRACK_SESSIONS = BUILDER
                .comment("Track player sessions (join/leave events)")
                .define("trackSessions", true);
        
        TRACK_AFK = BUILDER
                .comment("Track AFK time and periods")
                .define("trackAFK", true);
        
        TRACK_ACTIVITY = BUILDER
                .comment("Track daily activity trends and patterns")
                .define("trackActivity", true);
        
        BUILDER.pop();
        
        // Performance
        BUILDER.comment("Performance Configuration")
                .push("performance");
        
        DASHBOARD_REFRESH_INTERVAL = BUILDER
                .comment("Dashboard auto-refresh interval in milliseconds")
                .defineInRange("dashboardRefreshInterval", 5000, 1000, 60000);
        
        METRICS_RECORDING_INTERVAL = BUILDER
                .comment("Server metrics recording interval in ticks (20 ticks = 1 second)")
                .defineInRange("metricsRecordingInterval", 200, 20, 12000);
        
        ACTIVITY_UPDATE_INTERVAL = BUILDER
                .comment("Activity update interval in ticks (6000 ticks = 5 minutes)")
                .defineInRange("activityUpdateInterval", 6000, 1200, 72000);
        
        AFK_TIMEOUT_SECONDS = BUILDER
                .comment("Seconds of inactivity before a player is considered AFK")
                .defineInRange("afkTimeoutSeconds", 300, 60, 3600);
        
        BUILDER.pop();
        
        // Data Retention
        BUILDER.comment("Data Retention Configuration")
                .push("dataRetention");
        
        DATA_RETENTION_DAYS = BUILDER
                .comment("Number of days to keep old data (0 = keep forever)")
                .defineInRange("retentionDays", 365, 0, 3650);
        
        AUTO_CLEANUP_ENABLED = BUILDER
                .comment("Automatically delete old data based on retention period")
                .define("autoCleanupEnabled", false);
        
        CLEANUP_INTERVAL_HOURS = BUILDER
                .comment("How often to run cleanup (in hours)")
                .defineInRange("cleanupIntervalHours", 24, 1, 168);
        
        BUILDER.pop();
        
        // Network Configuration
        BUILDER.comment("Network Configuration (Multi-Server Support)")
                .push("network");
        
        NETWORK_ENABLED = BUILDER
                .comment("Enable network features for multi-server setups")
                .define("enabled", false);
        
        NETWORK_NAME = BUILDER
                .comment("Name of the server network (e.g., 'MyNetwork', 'FriendsCluster')")
                .define("networkName", "DefaultNetwork");
        
        SERVER_ID = BUILDER
                .comment("Unique ID for this server (e.g., 'survival-1', 'pvp-main')")
                .define("serverId", "server-1");
        
        CENTRAL_SERVER_URL = BUILDER
                .comment("URL of central server for statistics sync (e.g., 'http://central.example.com:8804')")
                .define("centralServerUrl", "http://localhost:8805");
        
        NETWORK_SYNC_INTERVAL_SECONDS = BUILDER
                .comment("How often to sync stats to central server (in seconds)")
                .defineInRange("syncIntervalSeconds", 300, 60, 3600);
        
        BUILDER.pop();
        
        // Discord Configuration
        BUILDER.comment("Discord Integration - Send events using a Discord bot")
                .push("discord");

        DISCORD_ENABLED = BUILDER
                .comment("Enable Discord bot integration")
                .define("enabled", false);

        DISCORD_BOT_TOKEN = BUILDER
                .comment("Discord bot token (keep this secret; consider using an environment variable in production)")
                .define("botToken", "");

        DISCORD_CHANNEL_ID = BUILDER
                .comment("Target Discord channel ID for notifications")
                .define("channelId", "");

        DISCORD_GUILD_ID = BUILDER
                .comment("Optional Discord server (guild) ID; improves channel lookup reliability")
                .define("guildId", "");
        
        DISCORD_BRIDGE_CHAT = BUILDER
                .comment("Bridge in-game chat to Discord and Discord messages to in-game chat")
                .define("bridgeChat", true);
        
        DISCORD_NOTIFY_JOINS = BUILDER
                .comment("Send notifications when players join")
                .define("notifyJoins", true);
        
        DISCORD_NOTIFY_LEAVES = BUILDER
                .comment("Send notifications when players leave")
                .define("notifyLeaves", true);
        
        DISCORD_NOTIFY_KILLS = BUILDER
                .comment("Send notifications for kills (PvP and PvE)")
                .define("notifyKills", false);
        
        DISCORD_NOTIFY_DEATHS = BUILDER
                .comment("Send notifications when players die")
                .define("notifyDeaths", false);
        
        DISCORD_NOTIFY_MILESTONES = BUILDER
                .comment("Send notifications for server milestones")
                .define("notifyMilestones", true);
        
        DISCORD_NOTIFY_STATS = BUILDER
                .comment("Send periodic server stats summaries")
                .define("notifyStats", false);
        
        BUILDER.pop();
        
        BUILDER.pop();
        
        SPEC = BUILDER.build();
    }
    
    private AnalyticsConfig() {
    }
    
    public static void register() {
        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, SPEC, "playeranalytics-common.toml");
        PlayeranalyticsForgeMod.LOGGER.info("Registered PlayerAnalytics configuration");
    }
}

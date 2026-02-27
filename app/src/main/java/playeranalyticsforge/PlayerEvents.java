package playeranalyticsforge;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.ServerChatEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Mod.EventBusSubscriber(modid = PlayeranalyticsForgeMod.MOD_ID)
public final class PlayerEvents {
    private static long tickCounter = 0;
    private static long activityUpdateCounter = 0;
    private static long pingTrackingCounter = 0;
    private static long qualityCalculationCounter = 0;
    private static long cleanupCounter = 0;
    private static long backupCounter = 0;
    private static final ConcurrentHashMap<UUID, String> playerWorlds = new ConcurrentHashMap<>();

    private PlayerEvents() {
    }

    @SubscribeEvent
    public static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            // Track world
            String worldName = player.serverLevel().dimension().location().toString();
            playerWorlds.put(player.getUUID(), worldName);
            
            // Track connection (IP address)
            try {
                String joinAddress = player.getIpAddress();
                if (joinAddress != null && !joinAddress.isEmpty()) {
                    PlayerAnalyticsDb.recordPlayerConnection(player, joinAddress);
                }
            } catch (Exception ex) {
                PlayeranalyticsForgeMod.LOGGER.debug("Failed to record connection address", ex);
            }
            
            // Check for username changes
            PlayerAnalyticsDb.checkAndRecordUsernameChange(player);
            
            // Track server transfer if network enabled
            if (AnalyticsConfig.NETWORK_ENABLED.get()) {
                String serverId = AnalyticsConfig.SERVER_ID.get();
                String networkName = AnalyticsConfig.NETWORK_NAME.get();
                PlayerAnalyticsDb.recordPlayerServerTransfer(player, serverId, networkName, "This Server");
            }
            
            if (AnalyticsConfig.TRACK_SESSIONS.get()) {
                PlayerAnalyticsDb.recordEvent("join", player);
            }
            if (AnalyticsConfig.TRACK_PLAYTIME.get()) {
                PlayerAnalyticsDb.startSession(player);
            }
            
            // Send Discord notification
            DiscordIntegration.notifyPlayerJoin(player.getGameProfile().getName(), player.getUUID().toString());
            
            PlayeranalyticsForgeMod.LOGGER.info("Player joined: {} ({}) in world: {}", player.getGameProfile().getName(), player.getUUID(), worldName);
        }
    }

    @SubscribeEvent
    public static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            // Remove world tracking
            playerWorlds.remove(player.getUUID());
            
            long playtimeSeconds = 0;
            if (AnalyticsConfig.TRACK_PLAYTIME.get()) {
                PlayerAnalyticsDb.endSession(player);
                // Try to get playtime from database
                try {
                    playtimeSeconds = PlayerAnalyticsDb.getPlayerPlaytimeSeconds(player.getUUID().toString());
                } catch (Exception e) {
                    PlayeranalyticsForgeMod.LOGGER.debug("Could not retrieve playtime", e);
                }
            }
            
            if (AnalyticsConfig.TRACK_SESSIONS.get()) {
                PlayerAnalyticsDb.recordEvent("leave", player);
            }
            
            // Send Discord notification
            DiscordIntegration.notifyPlayerLeave(player.getGameProfile().getName(), player.getUUID().toString(), playtimeSeconds);
            
            PlayeranalyticsForgeMod.LOGGER.info("Player left: {} ({})", player.getGameProfile().getName(), player.getUUID());
        }
    }

    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        AnalyticsWebServer.start();
        DiscordIntegration.start();
            UpdateChecker.start();
    }

    @SubscribeEvent
    public static void onLivingDeath(LivingDeathEvent event) {
        if (!AnalyticsConfig.TRACK_COMBAT.get()) {
            return; // Skip if combat tracking is disabled
        }
        
        if (event.getEntity() instanceof ServerPlayer victim) {
            PlayerAnalyticsDb.recordDeath(victim);
            if (AnalyticsConfig.TRACK_ACTIVITY.get()) {
                PlayerAnalyticsDb.recordPlayerActivity(victim.getUUID());
            }
            
            // Record death cause
            String deathCause = getDeathCause(event.getSource());
            PlayerAnalyticsDb.recordDeathCause(victim.getUUID().toString(), victim.getGameProfile().getName(), deathCause);
            
            // Send Discord death notification
            DiscordIntegration.notifyDeath(victim.getGameProfile().getName(), deathCause);
            
            // Reset kill streak on death
            PlayerAnalyticsDb.resetKillStreak(victim.getUUID().toString());
        }

        if (event.getSource().getEntity() instanceof ServerPlayer killer) {
            String victimType = event.getEntity().getType().getDescription().getString();
            String victimName = null;
            boolean isPvP = false;
            
            if (event.getEntity() instanceof ServerPlayer victim) {
                if (victim.getUUID().equals(killer.getUUID())) {
                    return;
                }
                victimName = victim.getGameProfile().getName();
                isPvP = true;
            }
            
            // Get weapon used
            String weaponUsed = getWeaponName(killer, event.getSource());
            
            // Get world name
            String worldName = killer.serverLevel().dimension().location().toString();
            
            PlayerAnalyticsDb.recordKill(killer);
            PlayerAnalyticsDb.recordKillDetail(killer, victimType, victimName);
            PlayerAnalyticsDb.recordWeaponUsage(killer.getUUID().toString(), killer.getGameProfile().getName(), weaponUsed);
            PlayerAnalyticsDb.recordWorldKill(killer, victimName != null ? victimName : victimType, victimType, worldName, weaponUsed, isPvP);
            PlayerAnalyticsDb.recordKillStreak(killer.getUUID().toString());
            
            // Send Discord kill notification
            DiscordIntegration.notifyKill(killer.getGameProfile().getName(), victimName != null ? victimName : victimType, weaponUsed, isPvP);
            
            // Record PvP or PvE kill
            if (isPvP) {
                PlayerAnalyticsDb.recordPlayerKillMatrix(killer.getUUID().toString(), ((ServerPlayer) event.getEntity()).getUUID().toString());
                // Update PvP kills in player_stats
                try {
                    java.sql.Connection conn = java.sql.DriverManager.getConnection("jdbc:sqlite:./analytics.db");
                    try (java.sql.Statement stmt = conn.createStatement()) {
                        stmt.executeUpdate("UPDATE player_stats SET pvp_kills = pvp_kills + 1 WHERE player_uuid = '" + killer.getUUID().toString() + "'");
                    }
                    conn.close();
                } catch (java.sql.SQLException ex) {
                    PlayeranalyticsForgeMod.LOGGER.debug("Failed to update PvP kills", ex);
                }
            } else {
                // Update PvE kills in player_stats
                try {
                    java.sql.Connection conn = java.sql.DriverManager.getConnection("jdbc:sqlite:./analytics.db");
                    try (java.sql.Statement stmt = conn.createStatement()) {
                        stmt.executeUpdate("UPDATE player_stats SET pve_kills = pve_kills + 1 WHERE player_uuid = '" + killer.getUUID().toString() + "'");
                    }
                    conn.close();
                } catch (java.sql.SQLException ex) {
                    PlayeranalyticsForgeMod.LOGGER.debug("Failed to update PvE kills", ex);
                }
            }
            
            PlayerAnalyticsDb.recordPlayerActivity(killer.getUUID());
        }
    }

    private static String getWeaponName(ServerPlayer player, DamageSource source) {
        // Try to get weapon from player's main hand
        ItemStack mainHand = player.getMainHandItem();
        if (!mainHand.isEmpty()) {
            return mainHand.getHoverName().getString();
        }
        
        // Try to get from damage source description
        String sourceName = source.getMsgId();
        if (sourceName != null && !sourceName.isEmpty()) {
            return sourceName;
        }
        
        return "Unknown";
    }

    private static String getDeathCause(DamageSource source) {
        String msgId = source.getMsgId();
        if (msgId != null && !msgId.isEmpty()) {
            return msgId;
        }
        return "Unknown";
    }

    @SubscribeEvent
    public static void onServerTick(net.minecraftforge.event.TickEvent.ServerTickEvent event) {
        if (event.phase == net.minecraftforge.event.TickEvent.Phase.END) {
            tickCounter++;
            activityUpdateCounter++;
            pingTrackingCounter++;
            qualityCalculationCounter++;
            cleanupCounter++;
            backupCounter++;
            
            long metricsInterval = AnalyticsConfig.METRICS_RECORDING_INTERVAL.get();
            if (tickCounter >= metricsInterval && event.getServer() != null) {
                tickCounter = 0;
                if (AnalyticsConfig.TRACK_ACTIVITY.get()) {
                    recordServerMetrics(event.getServer());
                }
            }
            
            long activityInterval = AnalyticsConfig.ACTIVITY_UPDATE_INTERVAL.get();
            if (activityUpdateCounter >= activityInterval) {
                activityUpdateCounter = 0;
                if (AnalyticsConfig.TRACK_ACTIVITY.get()) {
                    PlayerAnalyticsDb.updateDailyActivity();
                }
            }
            
            // Track ping every 30 seconds (600 ticks)
            if (pingTrackingCounter >= 600 && event.getServer() != null) {
                pingTrackingCounter = 0;
                recordAllPlayerPings(event.getServer());
            }
            
            // Calculate connection quality every 5 minutes (6000 ticks)
            if (qualityCalculationCounter >= 6000 && event.getServer() != null) {
                qualityCalculationCounter = 0;
                calculateAllConnectionQuality(event.getServer());
            }

            int retentionDays = AnalyticsConfig.DATA_RETENTION_DAYS.get();
            if (AnalyticsConfig.AUTO_CLEANUP_ENABLED.get() && retentionDays > 0) {
                long cleanupIntervalTicks = Math.max(1L, AnalyticsConfig.CLEANUP_INTERVAL_HOURS.get()) * 20L * 3600L;
                if (cleanupCounter >= cleanupIntervalTicks) {
                    cleanupCounter = 0;
                    PlayerAnalyticsDb.runRetentionCleanup(retentionDays);
                }
            }

            if (AnalyticsConfig.BACKUP_ENABLED.get()) {
                long backupIntervalTicks = Math.max(1L, AnalyticsConfig.BACKUP_INTERVAL_HOURS.get()) * 20L * 3600L;
                if (backupCounter >= backupIntervalTicks) {
                    backupCounter = 0;
                    PlayerAnalyticsDb.createBackup();
                }
            }
        }
    }
    
    private static void recordAllPlayerPings(net.minecraft.server.MinecraftServer server) {
        try {
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                int ping = player.latency;
                if (ping >= 0) {
                    PlayerAnalyticsDb.recordPlayerPing(player.getUUID(), ping);
                }
            }
        } catch (Exception ex) {
            PlayeranalyticsForgeMod.LOGGER.debug("Failed to record player pings", ex);
        }
    }
    
    private static void calculateAllConnectionQuality(net.minecraft.server.MinecraftServer server) {
        try {
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                PlayerAnalyticsDb.calculateAndRecordConnectionQuality(player.getUUID());
            }
        } catch (Exception ex) {
            PlayeranalyticsForgeMod.LOGGER.debug("Failed to calculate connection quality", ex);
        }
    }

    private static void recordServerMetrics(net.minecraft.server.MinecraftServer server) {
        try {
            if (server == null || server.getTickCount() == 0) {
                return;
            }
            
            // Calculate TPS (simplified - Forge may not expose this easily)
            double tps = Math.min(20.0, 20.0); // Default to 20 if not available
            
            int playerCount = server.getPlayerCount();
            int entityCount = playerCount; // Simplified: just use player count for now
            
            PlayerAnalyticsDb.recordServerMetrics(tps, playerCount, entityCount);
        } catch (Exception ex) {
            PlayeranalyticsForgeMod.LOGGER.debug("Failed to record server metrics", ex);
        }
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        AnalyticsWebServer.stop();
        PlayerAnalyticsDb.close();
        DiscordIntegration.stop();
            UpdateChecker.stop();
    }

    @SubscribeEvent
    public static void onServerChat(ServerChatEvent event) {
        ServerPlayer player = event.getPlayer();
        String message = event.getMessage().getString();
        String playerName = player.getGameProfile().getName();
        
        // Forward to Discord if enabled
        if (AnalyticsConfig.DISCORD_ENABLED.get() && AnalyticsConfig.DISCORD_BRIDGE_CHAT.get()) {
            DiscordIntegration.sendChatMessage(playerName, message);
        }
    }
}

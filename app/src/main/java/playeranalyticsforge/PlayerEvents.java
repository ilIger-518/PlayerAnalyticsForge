package playeranalyticsforge;

import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = PlayeranalyticsForgeMod.MOD_ID)
public final class PlayerEvents {
    private static long tickCounter = 0;
    private static final long METRIC_RECORDING_INTERVAL = 200; // Record every 10 seconds

    private PlayerEvents() {
    }

    @SubscribeEvent
    public static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            PlayerAnalyticsDb.recordEvent("join", player);
            PlayerAnalyticsDb.startSession(player);
            PlayeranalyticsForgeMod.LOGGER.info("Player joined: {} ({})", player.getGameProfile().getName(), player.getUUID());
        }
    }

    @SubscribeEvent
    public static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            PlayerAnalyticsDb.recordEvent("leave", player);
            PlayerAnalyticsDb.endSession(player);
            PlayeranalyticsForgeMod.LOGGER.info("Player left: {} ({})", player.getGameProfile().getName(), player.getUUID());
        }
    }

    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        AnalyticsWebServer.start();
    }

    @SubscribeEvent
    public static void onLivingDeath(LivingDeathEvent event) {
        if (event.getEntity() instanceof ServerPlayer victim) {
            PlayerAnalyticsDb.recordDeath(victim);
            PlayerAnalyticsDb.recordPlayerActivity(victim.getUUID());
        }

        if (event.getSource().getEntity() instanceof ServerPlayer killer) {
            String victimType = event.getEntity().getType().getDescription().getString();
            String victimName = null;
            
            if (event.getEntity() instanceof ServerPlayer victim) {
                if (victim.getUUID().equals(killer.getUUID())) {
                    return;
                }
                victimName = victim.getGameProfile().getName();
            }
            
            PlayerAnalyticsDb.recordKill(killer);
            PlayerAnalyticsDb.recordKillDetail(killer, victimType, victimName);
            PlayerAnalyticsDb.recordPlayerActivity(killer.getUUID());
        }
    }

    @SubscribeEvent
    public static void onServerTick(net.minecraftforge.event.TickEvent.ServerTickEvent event) {
        if (event.phase == net.minecraftforge.event.TickEvent.Phase.END) {
            tickCounter++;
            if (tickCounter >= METRIC_RECORDING_INTERVAL && event.getServer() != null) {
                tickCounter = 0;
                recordServerMetrics(event.getServer());
            }
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
    }
}

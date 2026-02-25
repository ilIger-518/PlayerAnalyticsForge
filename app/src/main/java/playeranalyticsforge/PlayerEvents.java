package playeranalyticsforge;

import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = PlayeranalyticsForgeMod.MOD_ID)
public final class PlayerEvents {
    private PlayerEvents() {
    }

    @SubscribeEvent
    public static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            PlayerAnalyticsDb.recordEvent("join", player);
            PlayeranalyticsForgeMod.LOGGER.info("Player joined: {} ({})", player.getGameProfile().getName(), player.getUUID());
        }
    }

    @SubscribeEvent
    public static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            PlayerAnalyticsDb.recordEvent("leave", player);
            PlayeranalyticsForgeMod.LOGGER.info("Player left: {} ({})", player.getGameProfile().getName(), player.getUUID());
        }
    }

    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        AnalyticsWebServer.start();
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        AnalyticsWebServer.stop();
        PlayerAnalyticsDb.close();
    }
}

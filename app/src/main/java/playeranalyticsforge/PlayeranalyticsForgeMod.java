package playeranalyticsforge;

import com.mojang.logging.LogUtils;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;

@Mod(PlayeranalyticsForgeMod.MOD_ID)
public class PlayeranalyticsForgeMod {
    public static final String MOD_ID = "playeranalytics";
    public static final String MOD_VERSION = "1.3.6";
    public static final Logger LOGGER = LogUtils.getLogger();

    public PlayeranalyticsForgeMod() {
        // Register configuration
        AnalyticsConfig.register();
        
        LOGGER.info("{} initialized", MOD_ID);
    }
}

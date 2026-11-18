package io.github.copule.liminaldimension;

import com.mojang.logging.LogUtils;
import net.neoforged.fml.common.Mod;

@Mod(LiminalDimension.MOD_ID)
public final class LiminalDimension {
    public static final String MOD_ID = "liminal_dimension";

    public LiminalDimension() {
        LogUtils.getLogger().info("Liminal Dimension initialized ({}).", MOD_ID);
    }
}

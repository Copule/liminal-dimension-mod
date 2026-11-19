package io.github.copule.liminaldimension.client;

import com.mojang.logging.LogUtils;
import io.github.copule.liminaldimension.LiminalDimension;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.common.Mod;

@Mod(value = LiminalDimension.MOD_ID, dist = Dist.CLIENT)
public final class LiminalDimensionClient {
    public LiminalDimensionClient() {
        LogUtils.getLogger().info("Liminal Dimension client initialized ({}).", LiminalDimension.MOD_ID);
    }
}

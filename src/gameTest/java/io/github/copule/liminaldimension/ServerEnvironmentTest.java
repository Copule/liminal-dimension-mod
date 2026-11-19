package io.github.copule.liminaldimension;

import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.FunctionGameTestInstance;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestData;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.registries.RegisterEvent;

@EventBusSubscriber(modid = LiminalDimension.MOD_ID)
public final class ServerEnvironmentTest {
    private static final Identifier ID = Identifier.fromNamespaceAndPath(LiminalDimension.MOD_ID, "server_environment");

    @SubscribeEvent
    public static void registerFunction(RegisterEvent event) {
        event.register(Registries.TEST_FUNCTION, ID, () -> ServerEnvironmentTest::verifyServer);
    }

    @SubscribeEvent
    public static void registerTest(RegisterGameTestsEvent event) {
        var environment = event.registerEnvironment(ID);
        var data = new TestData<>(environment, Identifier.withDefaultNamespace("empty"), 40, 0, true);
        event.registerTest(ID, new FunctionGameTestInstance(ResourceKey.create(Registries.TEST_FUNCTION, ID), data));
    }

    private static void verifyServer(GameTestHelper helper) {
        var level = helper.getLevel();
        long start = helper.getTick();
        helper.runAfterDelay(1, () -> {
            helper.assertTrue(helper.getTick() > start, "GameTest tick did not advance");
            helper.assertTrue(!level.isClientSide() && level.getServer().isSameThread(), "Expected logical server thread");
            helper.assertTrue(ModList.get().isLoaded(LiminalDimension.MOD_ID), "Project mod is missing");
            helper.succeed();
        });
    }
}

package fixture.placement;

import java.net.InetSocketAddress;
import java.util.function.Consumer;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.configuration.ServerConfigurationPacketListener;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ConfigurationTask;
import net.minecraft.world.level.Level;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.network.event.RegisterConfigurationTasksEvent;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

/** Optional separate test mod; deliberately never reads a player's save or identity. */
@Mod(value = "liminal_placement_fixture", dist = Dist.DEDICATED_SERVER)
public final class PlacementGuardFixture {
    private static final ResourceKey<Level> STAGED_DIMENSION =
            ResourceKey.create(Registries.DIMENSION, Identifier.parse("liminal_dimension_poc:probe"));
    private static final ConfigurationTask.Type TYPE = new ConfigurationTask.Type("liminal_placement_fixture:probe");

    public PlacementGuardFixture(IEventBus modBus) {
        if (Boolean.getBoolean("liminal.placement.fixture.enabled")) {
            modBus.addListener(this::register);
        }
    }

    private void register(RegisterConfigurationTasksEvent event) {
        ServerConfigurationPacketListener listener = event.getListener();
        if (!listener.getConnection().isMemoryConnection()
                && listener.getConnection().getRemoteAddress() instanceof InetSocketAddress address
                && address.getAddress() != null && address.getAddress().isLoopbackAddress()) {
            event.register(new ProbeTask(listener));
        }
    }

    private static final class ProbeTask implements ConfigurationTask {
        private final ServerConfigurationPacketListener listener;
        private final PlacementGate gate = new PlacementGate();

        private ProbeTask(ServerConfigurationPacketListener listener) {
            this.listener = listener;
        }

        @Override
        public Type type() {
            return TYPE;
        }

        @Override
        public void start(Consumer<Packet<?>> sender) {
            // The queue can start a task from a configuration-packet callback.
            // Defer registry access to the dedicated server thread via tick().
        }

        @Override
        public boolean tick() {
            MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
            return gate.tick(() -> server != null && server.isDedicatedServer()
                    && "127.0.0.1".equals(server.getLocalIp()) && server.getLevel(STAGED_DIMENSION) != null,
                    () -> listener.disconnect(Component.literal(
                            "Placement fixture blocked this connection before spawn: staged probe unavailable.")));
        }
    }
}

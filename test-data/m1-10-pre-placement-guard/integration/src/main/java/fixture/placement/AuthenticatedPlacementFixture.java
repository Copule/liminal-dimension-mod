package fixture.placement;

import com.mojang.logging.LogUtils;
import io.github.copule.liminaldimension.playerdata.PlayerDimensionProbe.Status;
import io.netty.util.AttributeKey;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import net.minecraft.network.CipherDecoder;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.configuration.ServerConfigurationPacketListener;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.dedicated.DedicatedServer;
import net.minecraft.server.network.ConfigurationTask;
import net.minecraft.server.network.ServerConfigurationPacketListenerImpl;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.network.event.RegisterConfigurationTasksEvent;

@Mod(value = "liminal_placement_integration", dist = Dist.DEDICATED_SERVER)
public final class AuthenticatedPlacementFixture {
    static final String ENABLE_PROPERTY = "liminal.placement.integration.enabled";
    private static final ConfigurationTask.Type TYPE = new ConfigurationTask.Type("liminal_placement_integration:check");
    private static final AttributeKey<PlacementAdmission.ChannelTicket> TICKET =
            AttributeKey.valueOf("liminal_placement_integration.ticket");
    private static final long TIMEOUT_NANOS = Duration.ofSeconds(3).toNanos();

    private final ExecutorService reader = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
            new SynchronousQueue<>(), Thread.ofPlatform().daemon(true).name("placement-fixture-reader").factory());
    private volatile DedicatedServer active;
    // Server tick only. Exactly one read is submitted for the lifetime of this mod instance.
    private boolean claimed;

    public AuthenticatedPlacementFixture(IEventBus modBus) {
        if (enabled()) {
            modBus.addListener(this::register);
            NeoForge.EVENT_BUS.addListener(this::started);
            NeoForge.EVENT_BUS.addListener(this::stopping);
        }
    }

    static boolean enabled() {
        return Boolean.getBoolean(ENABLE_PROPERTY);
    }

    private void started(ServerStartedEvent event) {
        if (event.getServer() instanceof DedicatedServer server
                && PlacementScope.isLocalDedicatedServer(server.isDedicatedServer(), server.getLocalIp())) {
            active = server;
        }
    }

    private void stopping(ServerStoppingEvent event) {
        if (event.getServer() == active) {
            active = null;
            reader.shutdownNow();
        }
    }

    private void register(RegisterConfigurationTasksEvent event) {
        DedicatedServer server = active;
        ServerConfigurationPacketListener listener = event.getListener();
        Connection connection = listener.getConnection();
        if (server == null || !local(connection)) {
            return;
        }

        var ticket = new PlacementAdmission.ChannelTicket();
        var previous = connection.channel().attr(TICKET).setIfAbsent(ticket);
        if (previous != null) {
            previous.repeat();
            ticket = previous;
        }
        event.register(new ProbeTask(server, listener, connection, ticket));
    }

    private static boolean local(Connection connection) {
        var channel = connection.channel();
        return channel != null && PlacementScope.isLocalTcp(connection.isMemoryConnection(),
                channel.localAddress(), connection.getRemoteAddress());
    }

    private Future<Status> submit(PlacementAdmission.Input input) {
        if (claimed) {
            return CompletableFuture.completedFuture(Status.UNCONFIRMED);
        }
        claimed = true;
        return reader.submit(() -> PlacementScope.inspect(input));
    }

    private final class ProbeTask implements ConfigurationTask {
        private final DedicatedServer server;
        private final ServerConfigurationPacketListener listener;
        private final Connection connection;
        private final PlacementAdmission.ChannelTicket ticket;
        private final PlacementAdmission admission;

        private ProbeTask(DedicatedServer server, ServerConfigurationPacketListener listener,
                Connection connection, PlacementAdmission.ChannelTicket ticket) {
            this.server = server;
            this.listener = listener;
            this.connection = connection;
            this.ticket = ticket;
            admission = new PlacementAdmission(TIMEOUT_NANOS,
                    result -> LogUtils.getLogger().info("Placement integration: {}", result),
                    result -> {
                        if (isCurrent()) {
                            ticket.disconnectOnce(() -> listener.disconnect(
                                    Component.literal("Placement integration: " + result)));
                        }
                    });
        }

        @Override
        public Type type() {
            return TYPE;
        }

        @Override
        public void start(Consumer<Packet<?>> sender) {
            // Registration and start can run in a packet callback. The tick owns the decision.
        }

        @Override
        public boolean tick() {
            return server.isSameThread()
                    && admission.tick(System.nanoTime(), this::isCurrent, this::capture,
                            AuthenticatedPlacementFixture.this::submit);
        }

        private boolean isCurrent() {
            return active == server && connection.isConnected() && connection.getPacketListener() == listener
                    && listener.isAcceptingMessages();
        }

        private PlacementAdmission.Input capture() {
            if (!ticket.isFirst() || listener.getClass() != ServerConfigurationPacketListenerImpl.class
                    || !PlacementScope.isLocalDedicatedServer(server.isDedicatedServer(), server.getLocalIp())
                    || !local(connection)
                    || !PlacementScope.hasAuthentication(server.getProperties().onlineMode, server.usesAuthentication(),
                            server.getSingleplayerProfile() != null,
                            connection.channel().pipeline().get(CipherDecoder.class) != null, server.acceptsTransfers())
                    || server.getPlayerCount() != 0 || server.getLevel(Level.OVERWORLD) == null
                    || hasOtherConnection()) {
                return null;
            }
            var profile = ((ServerConfigurationPacketListenerImpl) listener).getOwner();
            if (profile == null || !PlacementScope.isSoleWhitelistedIdentity(server.enforceSecureProfile(),
                    server.isUsingWhitelist(), server.isEnforceWhitelist(), profile.id(),
                    server.getPlayerList().getWhiteList().getEntries())) {
                return null;
            }
            Path world = PlacementScope.experimentWorld(Path.of("").toAbsolutePath(),
                    server.getWorldPath(LevelResource.PLAYER_DATA_DIR));
            if (world == null) {
                return null;
            }
            var loaded = server.levelKeys().stream().map(ResourceKey::identifier).collect(Collectors.toUnmodifiableSet());
            return new PlacementAdmission.Input(world, profile.id(), loaded);
        }

        private boolean hasOtherConnection() {
            var all = server.getConnection().getConnections();
            synchronized (all) {
                return all.stream().anyMatch(other -> other != connection && other.isConnected());
            }
        }
    }
}

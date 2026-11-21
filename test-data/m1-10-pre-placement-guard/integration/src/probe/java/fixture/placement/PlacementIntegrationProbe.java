package fixture.placement;

import fixture.placement.PlacementAdmission.Input;
import fixture.placement.PlacementAdmission.Outcome;
import io.github.copule.liminaldimension.playerdata.PlayerDimensionProbe.Status;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.resources.Identifier;

public final class PlacementIntegrationProbe {
    private static final UUID PLAYER = UUID.fromString("00000000-0000-4000-8000-000000000001");
    private static final Identifier OVERWORLD = Identifier.parse("minecraft:overworld");
    private static final Set<Identifier> LOADED = Set.of(OVERWORLD);
    private static final Input INPUT = new Input(Path.of("synthetic"), PLAYER, LOADED);
    private static final long DEADLINE = 20;
    private static int checks;

    private PlacementIntegrationProbe() {}

    public static void main(String[] args) throws Exception {
        verifyIsolation();
        verifySyntheticStorage();
        verifyDecisions();
        verifyFailures();
        verifyRepeatedRegistration();
        System.out.println("Placement integration: " + checks + " synthetic assertions passed.");
    }

    private static void verifyIsolation() throws Exception {
        String property = AuthenticatedPlacementFixture.ENABLE_PROPERTY;
        String original = System.getProperty(property);
        try {
            System.clearProperty(property);
            check(!AuthenticatedPlacementFixture.enabled(), "omitted opt-in must be disabled");
            System.setProperty(property, "false");
            check(!AuthenticatedPlacementFixture.enabled(), "explicit false must be disabled");
            System.setProperty(property, "true");
            check(AuthenticatedPlacementFixture.enabled(), "explicit true must enable");
        } finally {
            if (original == null) {
                System.clearProperty(property);
            } else {
                System.setProperty(property, original);
            }
        }
        check(PlacementScope.isLocalDedicatedServer(true, "127.0.0.1"), "dedicated IPv4 loopback bind");
        check(!PlacementScope.isLocalDedicatedServer(false, "127.0.0.1"), "integrated server rejected");
        for (String bind : List.of("", "0.0.0.0", "localhost", "::1", "127.0.0.2")) {
            check(!PlacementScope.isLocalDedicatedServer(true, bind), "alternate bind rejected");
        }
        InetSocketAddress loopback = socket(127, 0, 0, 1);
        InetSocketAddress remote = socket(192, 0, 2, 1);
        check(PlacementScope.isLocalTcp(false, loopback, loopback), "two loopback TCP endpoints accepted");
        check(!PlacementScope.isLocalTcp(true, loopback, loopback), "memory connection rejected");
        check(!PlacementScope.isLocalTcp(false, loopback, remote), "non-loopback peer rejected");
        check(!PlacementScope.isLocalTcp(false, remote, loopback), "non-loopback local endpoint rejected");
        check(!PlacementScope.isLocalTcp(false, loopback, socket(127, 0, 0, 2)), "alternate loopback rejected");
        check(!PlacementScope.isLocalTcp(false, loopback, InetSocketAddress.createUnresolved("localhost", 25565)),
                "unresolved endpoint rejected");

        check(PlacementScope.hasAuthentication(true, true, false, true, false), "expected auth conditions");
        check(!PlacementScope.hasAuthentication(false, true, false, true, false), "offline configuration rejected");
        check(!PlacementScope.hasAuthentication(true, false, false, true, false), "inactive auth rejected");
        check(!PlacementScope.hasAuthentication(true, true, true, true, false), "singleplayer owner rejected");
        check(!PlacementScope.hasAuthentication(true, true, false, false, false), "missing cipher rejected");
        check(!PlacementScope.hasAuthentication(true, true, false, true, true), "transfer enabled rejected");
    }

    private static InetSocketAddress socket(int a, int b, int c, int d) throws Exception {
        return new InetSocketAddress(InetAddress.getByAddress(new byte[] {(byte) a, (byte) b, (byte) c, (byte) d}),
                25565);
    }

    private static void verifySyntheticStorage() throws Exception {
        Path work = Files.createTempDirectory("placement-integration-fixture-").toRealPath();
        try {
            Path world = Files.createDirectory(work.resolve(PlacementScope.WORLD_DIRECTORY));
            Path files = world.resolve("players/data");
            Path marker = world.resolve(PlacementScope.MARKER_FILE);
            Input input = new Input(world, PLAYER, LOADED);
            check(world.equals(PlacementScope.experimentWorld(work, files)), "non-default experiment level accepted");
            check(PlacementScope.experimentWorld(work, work.resolve("world/players/data")) == null,
                    "default level is outside experiment scope");
            Path ambiguous = work.resolve("other/..").resolve(PlacementScope.WORLD_DIRECTORY).resolve("players/data");
            check(PlacementScope.experimentWorld(work, ambiguous) == null, "embedded parent segment rejected");
            check(PlacementScope.experimentWorld(work, world.resolve("./players/data")) == null,
                    "embedded current-directory segment rejected");
            check(PlacementScope.inspect(input) == Status.UNCONFIRMED, "missing fixture marker rejected");
            Files.writeString(marker, PlacementScope.MARKER_TEXT.replace('1', '2'));
            check(PlacementScope.inspect(input) == Status.UNCONFIRMED, "wrong marker contents rejected");
            Files.writeString(marker, PlacementScope.MARKER_TEXT);
            check(PlacementScope.inspect(input) == Status.NO_PLAYER_DATA, "permitted empty fixture");

            Files.createDirectories(files);
            Path primary = files.resolve(PLAYER + ".dat");
            Path backup = files.resolve(PLAYER + ".dat_old");
            writePlayer(primary, OVERWORLD);
            check(PlacementScope.inspect(input) == Status.DIMENSION_LOADED, "permitted fixture dimension");
            writePlayer(primary, Identifier.parse("integration:removed"));
            writePlayer(backup, OVERWORLD);
            byte[] mainBefore = Files.readAllBytes(primary);
            byte[] backupBefore = Files.readAllBytes(backup);
            check(PlacementScope.inspect(input) == Status.DIMENSION_MISSING, "backup cannot clear missing dimension");
            check(Arrays.equals(mainBefore, Files.readAllBytes(primary)), "primary fixture bytes preserved");
            check(Arrays.equals(backupBefore, Files.readAllBytes(backup)), "backup fixture bytes preserved");
            try (var entries = Files.list(files)) {
                check(entries.count() == 2, "no extra files in synthetic player data directory");
            }
            Files.write(primary, new byte[] {1, 2, 3});
            check(PlacementScope.inspect(input) == Status.UNCONFIRMED, "broken primary remains unconfirmed");
        } finally {
            try (var entries = Files.walk(work)) {
                for (Path path : entries.sorted(Comparator.reverseOrder()).toList()) {
                    Files.delete(path);
                }
            }
        }
    }

    private static void writePlayer(Path file, Identifier dimension) throws Exception {
        var tags = new CompoundTag();
        tags.putInt("DataVersion", 5023);
        tags.putString("Dimension", dimension.toString());
        try (var output = Files.newOutputStream(file)) {
            NbtIo.writeCompressed(tags, output);
        }
    }

    private static void verifyDecisions() {
        for (Status result : Status.values()) {
            var observed = new ArrayList<Outcome>();
            var disconnects = new ArrayList<Outcome>();
            var admission = new PlacementAdmission(DEADLINE, observed::add, disconnects::add);
            var pending = new CompletableFuture<Status>();
            check(!admission.tick(0, () -> true, () -> INPUT, input -> pending), "start cannot advance queue");
            check(!admission.tick(1, () -> true, () -> INPUT, input -> pending), "pending cannot advance queue");
            pending.complete(result);
            boolean allowed = result == Status.NO_PLAYER_DATA || result == Status.DIMENSION_LOADED;
            var outcome = Outcome.valueOf(result.name());
            check(admission.tick(2, () -> true, () -> INPUT, input -> pending) == allowed, "decision table mismatch");
            check(!admission.tick(3, () -> true, () -> INPUT, input -> pending), "terminal may advance only once");
            check(observed.equals(List.of(outcome)), "exactly one status recorded");
            check(disconnects.equals(allowed ? List.of() : List.of(outcome)), "ordinary refusal sends one disconnect");
        }
    }

    private static void verifyFailures() {
        var observed = new ArrayList<Outcome>();
        var disconnects = new AtomicInteger();
        var timedOut = new PlacementAdmission(DEADLINE, observed::add, result -> disconnects.incrementAndGet());
        var running = new CompletableFuture<Status>();
        check(!timedOut.tick(0, () -> true, () -> INPUT, input -> running), "timeout starts pending");
        check(!timedOut.tick(DEADLINE, () -> true, () -> INPUT, input -> running), "timeout must block");
        check(running.isCancelled() && observed.equals(List.of(Outcome.UNCONFIRMED)) && disconnects.get() == 1,
                "timeout cancels read and disconnects once");
        check(!running.complete(Status.NO_PLAYER_DATA), "late result discarded");

        observed.clear();
        disconnects.set(0);
        var expired = new PlacementAdmission(DEADLINE, observed::add, result -> disconnects.incrementAndGet());
        var lateSuccess = new CompletableFuture<Status>();
        check(!expired.tick(0, () -> true, () -> INPUT, input -> lateSuccess), "late result starts pending");
        lateSuccess.complete(Status.DIMENSION_LOADED);
        check(!expired.tick(DEADLINE, () -> true, () -> INPUT, input -> lateSuccess), "ready result after deadline blocks");
        check(observed.equals(List.of(Outcome.UNCONFIRMED)) && disconnects.get() == 1,
                "expired success remains unconfirmed");

        observed.clear();
        disconnects.set(0);
        var abandoned = new PlacementAdmission(DEADLINE, observed::add, result -> disconnects.incrementAndGet());
        var connectionCurrent = new AtomicBoolean(true);
        var finished = CompletableFuture.completedFuture(Status.DIMENSION_LOADED);
        check(!abandoned.tick(0, connectionCurrent::get, () -> INPUT, input -> finished), "ready worker still pending");
        connectionCurrent.set(false);
        check(!abandoned.tick(3, connectionCurrent::get, () -> INPUT, input -> finished), "ordinary disconnect blocks");
        check(observed.equals(List.of(Outcome.ABANDONED)) && disconnects.get() == 0, "ordinary disconnect is no pass");

        verifyUnavailable(CompletableFuture.failedFuture(new IllegalStateException()), INPUT);
        verifyUnavailable(CompletableFuture.completedFuture(Status.DIMENSION_LOADED),
                new Input(INPUT.world(), PLAYER, Set.of()));
        verifyUnavailable(CompletableFuture.completedFuture(Status.NO_PLAYER_DATA), null);

        observed.clear();
        var rejected = new PlacementAdmission(DEADLINE, observed::add, result -> {
            throw new IllegalStateException();
        });
        check(!rejected.tick(0, () -> true, () -> INPUT, input -> { throw new RejectedExecutionException(); }),
                "rejected submit blocks even if disconnect fails");
        check(!rejected.tick(1, () -> true, () -> INPUT, input -> finished), "failed disconnect cannot release queue");
        check(observed.equals(List.of(Outcome.UNCONFIRMED)), "unexpected failures are redacted");
    }

    private static void verifyUnavailable(CompletableFuture<Status> result, Input revalidated) {
        var diagnostics = new ArrayList<Outcome>();
        var disconnects = new ArrayList<Outcome>();
        var admission = new PlacementAdmission(DEADLINE, diagnostics::add, disconnects::add);
        check(!admission.tick(0, () -> true, () -> INPUT, input -> result), "unverified worker cannot advance");
        check(!admission.tick(1, () -> true, () -> revalidated, input -> result), "failure or stale input must block");
        check(diagnostics.equals(List.of(Outcome.UNCONFIRMED)) && disconnects.equals(diagnostics),
                "failure status emitted once");
    }

    private static void verifyRepeatedRegistration() {
        var ticket = new PlacementAdmission.ChannelTicket();
        var disconnects = new AtomicInteger();
        var first = new PlacementAdmission(DEADLINE, result -> {}, result -> ticket.disconnectOnce(
                disconnects::incrementAndGet));
        var second = new PlacementAdmission(DEADLINE, result -> {}, result -> ticket.disconnectOnce(
                disconnects::incrementAndGet));
        var result = CompletableFuture.completedFuture(Status.DIMENSION_LOADED);
        check(ticket.isFirst(), "new connection ticket");
        check(!first.tick(0, () -> true, () -> ticket.isFirst() ? INPUT : null, input -> result),
                "initial task awaits result");
        ticket.repeat();
        check(!first.tick(1, () -> true, () -> ticket.isFirst() ? INPUT : null, input -> result),
                "repeated configuration invalidates pending task");
        check(!second.tick(1, () -> true, () -> ticket.isFirst() ? INPUT : null, input -> result),
                "second task cannot advance");
        check(disconnects.get() == 1, "one disconnect across repeated tasks on a channel");
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
        checks++;
    }
}

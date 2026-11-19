package io.github.copule.liminaldimension.persistence;

import com.mojang.logging.LogUtils;
import io.github.copule.liminaldimension.LiminalDimension;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import com.mojang.serialization.DataResult;
import net.minecraft.world.level.Level;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.FunctionGameTestInstance;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.GameTestServer;
import net.minecraft.gametest.framework.TestData;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.registries.RegisterEvent;

import static io.github.copule.liminaldimension.persistence.FoundationProbeData.SCHEMA;
import static io.github.copule.liminaldimension.persistence.LoadResult.State.*;

@EventBusSubscriber(modid = LiminalDimension.MOD_ID)
public final class PersistenceGameTests {
    @FunctionalInterface
    private interface Check { void run(GameTestHelper h, ServerLevel level, Path world) throws Exception; }

    private record Case(Identifier id, Check check) {
        Case(String name, Check check) { this(Identifier.fromNamespaceAndPath(LiminalDimension.MOD_ID, name), check); }
    }

    private static final List<Case> CASES = List.of(
            new Case("snapshot_scope_roundtrip", PersistenceGameTests::roundTrip),
            new Case("snapshot_interrupted_stage", PersistenceGameTests::interruptedStage),
            new Case("snapshot_migration", PersistenceGameTests::migration),
            new Case("snapshot_protection", PersistenceGameTests::protection),
            new Case("snapshot_save_failure", PersistenceGameTests::saveFailure),
            new Case("snapshot_thread_and_dimension", PersistenceGameTests::threadAndDimension),
            new Case("snapshot_read_errors", PersistenceGameTests::readErrors));

    @SubscribeEvent
    public static void functions(RegisterEvent event) {
        for (Case test : CASES) event.register(Registries.TEST_FUNCTION, test.id(), () -> helper -> {
            var level = helper.getLevel();
            var server = level.getServer();
            Path world = server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
            helper.assertTrue(server instanceof GameTestServer && server.isSameThread()
                    && world.equals(Path.of("universe/gametestworld").toAbsolutePath().normalize()), "Expected isolated GameTest world");
            try {
                test.check().run(helper, level, world);
            } catch (Exception ex) {
                LogUtils.getLogger().error("Disk probe {} failed in {}", test.id(), world, ex);
                throw new IllegalStateException(test.id() + ": " + ex, ex);
            }
            helper.succeed();
        });
    }

    @SubscribeEvent
    public static void instances(RegisterGameTestsEvent event) {
        for (Case test : CASES) {
            var data = new TestData<>(event.registerEnvironment(test.id()), Identifier.withDefaultNamespace("empty"), 80, 0, true);
            event.registerTest(test.id(), new FunctionGameTestInstance(ResourceKey.create(Registries.TEST_FUNCTION, test.id()), data));
        }
    }

    private static Path path(Path root, String key) {
        return root.resolve("data/liminal_dimension").resolve(key + ".dat");
    }

    private static void roundTrip(GameTestHelper h, ServerLevel level, Path world) throws Exception {
        var server = level.getServer();
        String key = "probe/scopes";
        var global = SavedSnapshotStore.global(server, key, SCHEMA);
        var local = SavedSnapshotStore.level(level, key, SCHEMA);
        Path globalFile = path(world, key);
        var dim = level.dimension().identifier();
        Path localFile = path(world.resolve("dimensions").resolve(dim.getNamespace()).resolve(dim.getPath()), key);
        h.assertTrue(global.result().state() == MISSING && local.result().state() == MISSING
                && Files.notExists(globalFile) && Files.notExists(localFile), "Opening missing keys created data");
        var a = new FoundationProbeData("global", "first");
        var b = new FoundationProbeData("dimension", "separate");
        global.save(a);
        var stale = SavedSnapshotStore.global(server, key, SCHEMA);
        local.save(b);
        var changed = new FoundationProbeData("global", "second");
        global.save(changed);
        try {
            stale.save(a);
            h.fail("Stale handle overwrote a newer snapshot");
        } catch (IOException expected) {
            h.assertTrue(expected.getMessage().contains("changed"), "Unexpected conflict cause");
        }
        for (Path file : List.of(globalFile, localFile)) {
            var root = NbtIo.readCompressed(file, NbtAccounter.create(8192));
            h.assertTrue(root.getIntOr("DataVersion", -1) == net.minecraft.SharedConstants.getCurrentVersion().dataVersion().version(),
                    "Wrong native DataVersion at " + file);
            h.assertTrue(root.getCompoundOrEmpty("data")
                    .getIntOr("schema_version", 0) == 2, "Missing V2 at " + file);
        }
        h.assertTrue(SavedSnapshotStore.global(server, key, SCHEMA).result().snapshot().orElseThrow().equals(changed), "Wrong global reload");
        h.assertTrue(SavedSnapshotStore.level(level, key, SCHEMA).result().snapshot().orElseThrow().equals(b), "Wrong level reload");
        LogUtils.getLogger().info("M1-02 scope global={} value={} level={} value={}", globalFile, changed, localFile, b);
    }

    private static void interruptedStage(GameTestHelper h, ServerLevel level, Path world) throws Exception {
        String key = "probe/interrupted";
        Path target = path(world, key);
        Path stage = target.resolveSibling("interrupted.pending.dat");
        var server = level.getServer();
        var original = new FoundationProbeData("committed", "");
        SavedSnapshotStore.global(server, key, SCHEMA).save(original);
        String before = hash(target);
        NbtIo.writeCompressed(envelope(2, "marker", "abandoned"), stage);
        Path nativeOrphan = stage.resolveSibling("interrupted.pending.dat123.neoforge-tmp");
        Files.writeString(nativeOrphan, "owned by an earlier process");
        var reopened = SavedSnapshotStore.global(server, key, SCHEMA);
        h.assertTrue(reopened.result().snapshot().orElseThrow().equals(original), "Orphan became authoritative");
        // This unrelated storage flush must not publish the orphan.
        server.getDataStorage().saveAndJoin();
        h.assertTrue(before.equals(hash(target)), "Unrequested write modified target");
        var next = new FoundationProbeData("explicit-retry", "");
        String orphanHash = hash(stage);
        reopened.save(next);
        h.assertTrue(Files.readString(nativeOrphan).equals("owned by an earlier process") && orphanHash.equals(hash(stage)) && SavedSnapshotStore.global(server, key, SCHEMA)
                .result().snapshot().orElseThrow().equals(next), "Explicit commit disturbed orphan or failed to publish");
    }

    private static CompoundTag envelope(int version, String field, String value) {
        var payload = new CompoundTag();
        payload.putString(field, value);
        var data = new CompoundTag();
        data.putInt("schema_version", version);
        data.put("payload", payload);
        var root = new CompoundTag();
        root.put("data", data);
        return root;
    }

    private static void migration(GameTestHelper h, ServerLevel level, Path world) throws Exception {
        String key = "probe/migration";
        Path file = path(world, key);
        Files.createDirectories(file.getParent());
        NbtIo.write(envelope(1, "legacy_marker", "from-v1"), file);
        String before = hash(file);
        var store = SavedSnapshotStore.global(level.getServer(), key, SCHEMA);
        h.assertTrue(store.result().state() == MIGRATED && before.equals(hash(file)), "Read modified V1");
        level.getServer().getDataStorage().saveAndJoin();
        h.assertTrue(before.equals(hash(file)), "Native flush changed unopened-for-write V1");
        var value = store.result().snapshot().orElseThrow();
        h.assertTrue(value.equals(new FoundationProbeData("from-v1", "")), "Migration lost marker");
        Path untouched = path(world, "probe/readonly_v1");
        Files.copy(file, untouched);
        var readonly = SavedSnapshotStore.global(level.getServer(), "probe/readonly_v1", SCHEMA);
        level.getServer().getDataStorage().saveAndJoin();
        String after = hash(untouched);
        h.assertTrue(readonly.result().state() == MIGRATED && before.equals(after), "V1 write during global flush");
        LogUtils.getLogger().info("M1-02 readonly-v1 path={} before={} after={}", untouched, before, after);
        store.save(value);
        var loaded = SavedSnapshotStore.global(level.getServer(), key, SCHEMA).result();
        h.assertTrue(loaded.state() == LOADED && loaded.snapshot().orElseThrow().equals(value), "V2 reload failed");
    }

    private static void protection(GameTestHelper h, ServerLevel level, Path world) throws Exception {
        var server = level.getServer();
        for (String name : List.of("broken", "future", "future_game", "trailer", "bad_payload",
                "gzip_tail", "gzip_buffer_tail", "empty_member", "zero_version", "negative_version", "long_version",
                "string_version", "missing_data", "byte_schema", "oversize")) {
            String key = "probe/" + name;
            Path file = path(world, key);
            var previouslyMissing = SavedSnapshotStore.global(server, key, SCHEMA);
            Files.createDirectories(file.getParent());
            if (name.equals("broken")) {
                Files.write(file, new byte[]{0x1f, (byte) 0x8b, 8});
            } else {
                var root = envelope(name.equals("future") ? 19 : 2,
                        name.equals("bad_payload") ? "unexpected" : "marker", "preserve");
                if (name.equals("future_game")) root.putInt("DataVersion", Integer.MAX_VALUE);
                switch (name) {
                    case "zero_version" -> root.putInt("DataVersion", 0);
                    case "negative_version" -> root.putInt("DataVersion", -1);
                    case "long_version" -> root.putLong("DataVersion", 2L);
                    case "string_version" -> root.putString("DataVersion", "2");
                    case "missing_data" -> root.remove("data");
                    case "byte_schema" -> root.getCompoundOrEmpty("data").putByte("schema_version", (byte) 2);
                    case "oversize" -> root.putByteArray("oversize", new byte[8 << 20]);
                }
                NbtIo.writeCompressed(root, file);
                if (name.startsWith("gzip_")) {
                    Files.write(file, new byte[name.equals("gzip_tail") ? 1 : 16385], java.nio.file.StandardOpenOption.APPEND);
                }
                if (name.equals("empty_member")) {
                    try (var empty = new java.util.zip.GZIPOutputStream(
                            Files.newOutputStream(file, java.nio.file.StandardOpenOption.APPEND))) {
                        empty.finish();
                    }
                }
                if (name.equals("trailer")) {
                    byte[] bytes = Files.readAllBytes(file);
                    Files.write(file, Arrays.copyOf(bytes, bytes.length - 4));
                }
            }
            String before = hash(file);
            var expected = name.startsWith("future") ? FUTURE : CORRUPT;
            var opened = SavedSnapshotStore.global(server, key, SCHEMA);
            h.assertTrue(opened.result().state() == expected, "Unexpected state " + opened.result());
            for (var handle : List.of(previouslyMissing, opened)) {
                try {
                    handle.save(new FoundationProbeData("forbidden", ""));
                    h.fail("Protected write succeeded");
                } catch (IllegalStateException denied) {
                    h.assertTrue(denied.getMessage().startsWith(expected.name()), "Unexpected refusal");
                }
            }
            server.getDataStorage().saveAndJoin();
            String after = hash(file);
            h.assertTrue(before.equals(after), "Protected bytes changed");
            LogUtils.getLogger().info("M1-02 protected state={} path={} before={} after={}", expected, file, before, after);
        }
    }

    private static void saveFailure(GameTestHelper h, ServerLevel level, Path world) throws Exception {
        String key = "probe/failure";
        Path file = path(world, key);
        var store = SavedSnapshotStore.global(level.getServer(), key, SCHEMA);
        var original = new FoundationProbeData("original", "");
        store.save(original);
        String before = hash(file);
        var proposed = new FoundationProbeData("candidate", "");
        Path missingSource = file.resolveSibling("absent-at-publish.dat");
        h.assertTrue(Files.notExists(missingSource), "Expected missing source fixture");
        try {
            store.save(proposed, (stage, target) -> {
                h.assertTrue(Files.isSameFile(target, file) && Files.size(stage) > 0, "Expected verified native stage");
                Files.move(missingSource, target, java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            });
            h.fail("Atomic move from missing source succeeded");
        } catch (IOException expected) {
            h.assertTrue(before.equals(hash(file)) && store.result().snapshot().orElseThrow().equals(original),
                    "Failed publication changed target or observation");
        }
        // Preflight succeeds, the native queue's independent encoding fails.
        var calls = new AtomicInteger();
        var codec = FoundationProbeData.V2.flatXmap(DataResult::success,
                value -> calls.incrementAndGet() == 2 ? DataResult.error(() -> "injected native encoding failure")
                        : DataResult.success(value));
        var schema = VersionedSchema.first(FoundationProbeData.V1).next(codec, marker -> new FoundationProbeData(marker, ""));
        try {
            SavedSnapshotStore.global(level.getServer(), key, schema).save(proposed);
            h.fail("Native encoder failure was acknowledged");
        } catch (IOException expected) {
            h.assertTrue(calls.get() == 2 && expected.getCause() != null, "Expected native encoding cause");
        }
        level.getServer().getDataStorage().saveAndJoin();
        String after = hash(file);
        h.assertTrue(before.equals(after) && SavedSnapshotStore.global(level.getServer(), key, SCHEMA)
                .result().snapshot().orElseThrow().equals(original), "Save failure lost original");
        try (var siblings = Files.list(file.getParent())) {
            h.assertTrue(siblings.noneMatch(p -> p.getFileName().toString().startsWith(".pending-")), "Leaked owned stage");
        }
        LogUtils.getLogger().info("M1-02 move/encode-failure path={} before={} after={}", file, before, after);
    }

    private static void threadAndDimension(GameTestHelper h, ServerLevel level, Path world) throws Exception {
        var server = level.getServer();
        String key = "probe/dim_thread";
        var nether = java.util.Objects.requireNonNull(server.getLevel(Level.NETHER));
        var overworld = SavedSnapshotStore.level(server.overworld(), key, SCHEMA);
        var other = SavedSnapshotStore.level(nether, key, SCHEMA);
        var first = new FoundationProbeData("overworld", "");
        var second = new FoundationProbeData("nether", "");
        overworld.save(first);
        other.save(second);
        boolean denied = CompletableFuture.supplyAsync(() -> {
            try {
                other.save(first);
                return false;
            } catch (IllegalStateException ex) {
                return ex.getMessage().contains("server thread");
            } catch (IOException ex) {
                throw new java.io.UncheckedIOException(ex);
            }
        }).join();
        h.assertTrue(denied && SavedSnapshotStore.level(nether, key, SCHEMA).result().snapshot().orElseThrow().equals(second)
                && SavedSnapshotStore.level(server.overworld(), key, SCHEMA).result().snapshot().orElseThrow().equals(first),
                "Off-thread write or cross-dimension alias");
    }

    private static void readErrors(GameTestHelper h, ServerLevel level, Path world) throws Exception {
        var server = level.getServer();
        String key = "probe/read_error";
        var store = SavedSnapshotStore.global(server, key, SCHEMA);
        var value = new FoundationProbeData("first", "");
        store.save(value);
        Path file = path(world, key);
        Files.write(file, new byte[]{1, 2});
        String before = hash(file);
        try {
            store.save(value);
            h.fail("Loaded handle overwrote newly damaged target");
        } catch (IllegalStateException expected) {
            h.assertTrue(before.equals(hash(file)), "Damaged target changed");
        }
        String symlink = "probe/symlink";
        Path link = path(world, symlink);
        try {
            Files.createSymbolicLink(link, file.getFileName());
        } catch (IOException | UnsupportedOperationException unavailable) {
            LogUtils.getLogger().info("M1-02 symlink fixture unavailable: {}", unavailable.toString());
        }
        if (Files.isSymbolicLink(link)) {
            var linked = SavedSnapshotStore.global(server, symlink, SCHEMA);
            h.assertTrue(linked.result().state() == IO_ERROR, "Refused link expected");
            try {
                linked.save(value);
                h.fail("Followed file symlink");
            } catch (IllegalStateException expected) {
                h.assertTrue(Files.isSymbolicLink(link) && before.equals(hash(file)), "Link target changed");
            }
            LogUtils.getLogger().info("M1-02 symlink refusal verified");
        }
        String directory = "probe/directory";
        Files.createDirectory(path(world, directory));
        var blocked = SavedSnapshotStore.global(server, directory, SCHEMA);
        h.assertTrue(blocked.result().state() == IO_ERROR, "Directory was classified as data");
        try {
            blocked.save(value);
            h.fail("Directory was replaced");
        } catch (IllegalStateException expected) {
            h.assertTrue(Files.isDirectory(path(world, directory)), "Directory was lost");
        }
    }

    private static String hash(Path file) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
    }
}

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
            new Case("snapshot_save_failure", PersistenceGameTests::saveFailure));

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
                throw new IllegalStateException("Disk probe failed in " + world, ex);
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
            h.assertTrue(NbtIo.readCompressed(file, NbtAccounter.create(8192)).getCompoundOrEmpty("data")
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
        var reopened = SavedSnapshotStore.global(server, key, SCHEMA);
        h.assertTrue(reopened.result().snapshot().orElseThrow().equals(original), "Orphan became authoritative");
        // This unrelated storage flush must not publish the orphan.
        server.getDataStorage().saveAndJoin();
        h.assertTrue(before.equals(hash(target)), "Unrequested write modified target");
        var next = new FoundationProbeData("explicit-retry", "");
        reopened.save(next);
        h.assertTrue(Files.notExists(stage) && SavedSnapshotStore.global(server, key, SCHEMA)
                .result().snapshot().orElseThrow().equals(next), "Fresh commit did not replace abandoned stage");
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
        var value = store.result().snapshot().orElseThrow();
        h.assertTrue(value.equals(new FoundationProbeData("from-v1", "")), "Migration lost marker");
        store.save(value);
        var loaded = SavedSnapshotStore.global(level.getServer(), key, SCHEMA).result();
        h.assertTrue(loaded.state() == LOADED && loaded.snapshot().orElseThrow().equals(value), "V2 reload failed");
    }

    private static void protection(GameTestHelper h, ServerLevel level, Path world) throws Exception {
        var server = level.getServer();
        for (String name : List.of("broken", "future", "future_game", "trailer", "bad_payload")) {
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
                NbtIo.writeCompressed(root, file);
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
        // The final component fits NTFS/ext4. The native writer's temporary name exceeds 255.
        String key = "probe/" + "f".repeat(241);
        Path file = path(world, key);
        Files.createDirectories(file.getParent());
        NbtIo.writeCompressed(envelope(2, "marker", "original"), file);
        String before = hash(file);
        var store = SavedSnapshotStore.global(level.getServer(), key, SCHEMA);
        h.assertTrue(store.result().state() == LOADED, "Expected original data");
        try {
            store.save(new FoundationProbeData("must-not-commit", ""));
            h.fail("Native I/O failure was acknowledged");
        } catch (IOException expected) {
            h.assertTrue(expected.getMessage().contains("Native save verification failed"), "Expected failed native write: " + expected);
        }
        level.getServer().getDataStorage().saveAndJoin();
        var reloaded = SavedSnapshotStore.global(level.getServer(), key, SCHEMA).result();
        String after = hash(file);
        h.assertTrue(before.equals(after) && reloaded.snapshot().orElseThrow().marker().equals("original"), "Save failure lost original");
        LogUtils.getLogger().info("M1-02 native-write-failure path={} before={} after={}", file, before, after);
    }

    private static String hash(Path file) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
    }
}

package io.github.copule.liminaldimension.persistence;

import com.mojang.serialization.DynamicOps;
import io.github.copule.liminaldimension.LiminalDimension;
import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Objects;
import java.util.zip.GZIPInputStream;
import net.minecraft.SharedConstants;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.level.storage.SavedDataStorage;

import static io.github.copule.liminaldimension.persistence.LoadResult.State.*;

/** Low-frequency, explicit, server-thread commits. Each key belongs to exactly one schema. */
public final class SavedSnapshotStore<T> {
    private static final long NBT_BUDGET = 8L << 20;

    private static final class Snapshot<T> extends SavedData {
        final T value;
        Snapshot(T value) { this.value = value; }
    }

    private record Observation<T>(CompoundTag root, LoadResult<T> result) {}

    private final MinecraftServer server;
    private final SavedDataStorage storage;
    private final DynamicOps<Tag> ops;
    private final VersionedSchema<T> schema;
    private final SavedDataType<Snapshot<T>> stagingType;
    private final Path file;
    private final Path staging;
    private Observation<T> observed;

    public static <T> SavedSnapshotStore<T> global(MinecraftServer server, String key, VersionedSchema<T> schema) {
        return new SavedSnapshotStore<>(server, server.getDataStorage(), server.getWorldPath(LevelResource.DATA),
                server.registryAccess().createSerializationContext(NbtOps.INSTANCE), key, schema);
    }

    public static <T> SavedSnapshotStore<T> level(ServerLevel level, String key, VersionedSchema<T> schema) {
        var server = level.getServer();
        Path folder = DimensionType.getStorageFolder(level.dimension(), server.getWorldPath(LevelResource.ROOT));
        return new SavedSnapshotStore<>(server, level.getDataStorage(), folder.resolve("data"),
                level.registryAccess().createSerializationContext(NbtOps.INSTANCE), key, schema);
    }

    private SavedSnapshotStore(MinecraftServer server, SavedDataStorage storage, Path folder,
                               DynamicOps<Tag> ops, String key, VersionedSchema<T> schema) {
        this.server = server;
        checkThread();
        this.storage = storage;
        this.ops = ops;
        this.schema = schema;
        if (!key.matches("[a-z0-9_-]+(/[a-z0-9_-]+)*")) throw new IllegalArgumentException("Invalid storage key");
        var id = Identifier.fromNamespaceAndPath(LiminalDimension.MOD_ID, key);
        var stagingId = id.withSuffix(".pending");
        file = id.withSuffix(".dat").resolveAgainst(folder);
        staging = stagingId.withSuffix(".dat").resolveAgainst(folder);
        stagingType = new SavedDataType<>(stagingId,
                () -> { throw new IllegalStateException("Explicit snapshot required"); },
                schema.currentCodec().xmap(Snapshot::new, data -> data.value));
        observed = inspect(file);
    }

    public LoadResult<T> result() { return observed.result(); }

    /** Publishes only after a verified native save. Reopen after a conflict. */
    public void save(T value) throws IOException {
        checkThread();
        Objects.requireNonNull(value);
        requireUnchanged();
        // Preflight the encoder before touching either file.
        Tag expected = schema.encode(ops, value).getOrThrow(IOException::new);
        // An interrupted prior commit may have left a stage. Never acknowledge its bytes as a new save.
        Files.deleteIfExists(staging);
        var candidate = new Snapshot<>(value);
        try {
            storage.set(stagingType, candidate); // SavedDataStorage.set calls setDirty().
            storage.scheduleSave().join();
            Observation<T> written = inspect(staging);
            if (written.result().state() != LOADED || !expected.equals(written.root().get("data"))
                    || !value.equals(written.result().snapshot().orElseThrow())) {
                throw new IOException("Native save verification failed at " + staging + ": " + written.result().detail());
            }
            requireUnchanged();
            Files.move(staging, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            observed = written;
        } catch (IOException | RuntimeException ex) {
            try {
                Files.deleteIfExists(staging);
            } catch (IOException cleanup) {
                ex.addSuppressed(cleanup);
            }
            throw ex;
        } finally {
            candidate.setDirty(false);
        }
    }

    private void requireUnchanged() throws IOException {
        observed.result().requireWritable();
        var now = inspect(file);
        now.result().requireWritable();
        if (!Objects.equals(observed.root(), now.root())) throw new IOException("Snapshot changed at " + file);
    }

    private void checkThread() {
        if (!server.isSameThread()) throw new IllegalStateException("Expected logical server thread");
    }

    private Observation<T> inspect(Path path) {
        try {
            if (Files.notExists(path, LinkOption.NOFOLLOW_LINKS)) {
                return new Observation<>(null, LoadResult.empty(MISSING, path.toString()));
            }
            if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Expected regular file");
            CompoundTag root = readRoot(path);
            Tag dataVersion = root.get("DataVersion");
            if (dataVersion != null) {
                if (!(dataVersion instanceof IntTag version)) {
                    return new Observation<>(root, LoadResult.empty(CORRUPT, "Invalid Minecraft DataVersion"));
                }
                if (version.intValue() > SharedConstants.getCurrentVersion().dataVersion().version()) {
                    return new Observation<>(root, LoadResult.empty(FUTURE, "Minecraft DataVersion=" + version.intValue()));
                }
            }
            Tag data = root.get("data");
            var result = data == null ? LoadResult.<T>empty(CORRUPT, "Missing data") : schema.decode(ops, data);
            return new Observation<>(root, result);
        } catch (IOException | RuntimeException ex) {
            return new Observation<>(null, LoadResult.empty(CORRUPT, path + ": " + ex));
        }
    }

    // Native readers accept gzip and plain NBT. Consume through EOF to verify the gzip trailer as well.
    private static CompoundTag readRoot(Path path) throws IOException {
        try (var raw = new BufferedInputStream(Files.newInputStream(path))) {
            raw.mark(2);
            boolean gzip = raw.read() == 0x1f && raw.read() == 0x8b;
            raw.reset();
            try (InputStream content = gzip ? new GZIPInputStream(raw) : raw;
                 var input = new DataInputStream(content)) {
                CompoundTag root = NbtIo.read(input, NbtAccounter.create(NBT_BUDGET));
                if (input.read() != -1) throw new IOException("Trailing NBT data");
                return root;
            }
        }
    }
}

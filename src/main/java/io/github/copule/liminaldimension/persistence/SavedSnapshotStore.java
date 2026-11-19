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
import java.nio.file.FileSystemException;
import java.nio.file.NoSuchFileException;
import java.nio.file.attribute.BasicFileAttributes;
import net.minecraft.core.HolderLookup;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;
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
    private final ServerLevel level;
    private final Path folder;
    private final HolderLookup.Provider registries;
    private final Identifier id;
    private final DynamicOps<Tag> ops;
    private final VersionedSchema<T> schema;
    private final Path file;
    private Observation<T> observed;

    public static <T> SavedSnapshotStore<T> global(MinecraftServer server, String key, VersionedSchema<T> schema) {
        return new SavedSnapshotStore<>(server, null, server.getWorldPath(LevelResource.DATA),
                server.registryAccess(), key, schema);
    }

    public static <T> SavedSnapshotStore<T> level(ServerLevel level, String key, VersionedSchema<T> schema) {
        var server = level.getServer();
        Path folder = DimensionType.getStorageFolder(level.dimension(), server.getWorldPath(LevelResource.ROOT));
        return new SavedSnapshotStore<>(server, level, folder.resolve("data"),
                level.registryAccess(), key, schema);
    }

    private SavedSnapshotStore(MinecraftServer server, ServerLevel level, Path folder,
                               HolderLookup.Provider registries, String key, VersionedSchema<T> schema) {
        this.server = server;
        checkThread();
        this.level = level;
        this.folder = folder;
        this.registries = registries;
        this.ops = registries.createSerializationContext(NbtOps.INSTANCE);
        this.schema = schema;
        validateKey(key);
        id = Identifier.fromNamespaceAndPath(LiminalDimension.MOD_ID, key);
        file = id.withSuffix(".dat").resolveAgainst(folder);
        observed = inspect(file);
    }

    static void validateKey(String key) {
        // Intersection of the two 26.3 FileUtil filename modes, restricted to our ASCII key grammar.
        if (!key.matches("[a-z0-9_-]+(/[a-z0-9_-]+)*")) throw new IllegalArgumentException("Invalid storage key");
        // ASCII components use one byte each. Reserve space for the published leaf's .dat suffix.
        if (key.length() - key.lastIndexOf('/') - 1 > 251) throw new IllegalArgumentException("Storage filename too long");
        for (String part : key.split("/")) {
            if (part.length() > 255) throw new IllegalArgumentException("Storage path segment too long");
            if (part.matches("con|prn|aux|nul|com|(?:com|lpt)[0-9]")) {
                throw new IllegalArgumentException("Reserved storage key segment: " + part);
            }
        }
    }

    public LoadResult<T> result() { return observed.result(); }

    /** Synchronous server-thread I/O for one key. Reopen after a conflict. */
    public void save(T value) throws IOException {
        save(value, (source, target) -> Files.move(source, target,
                StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING));
    }

    @FunctionalInterface
    interface Publisher { void move(Path source, Path target) throws IOException; }

    void save(T value, Publisher publisher) throws IOException {
        checkThread();
        Objects.requireNonNull(value);
        requireUnchanged();
        Tag expected = schema.encode(ops, value).getOrThrow(IOException::new);
        net.minecraft.util.FileUtil.createDirectoriesSafe(file.getParent());
        // CREATE_NEW establishes ownership. Prior process remnants are never opened or unlinked.
        Path staging = Files.createTempFile(file.getParent(), ".pending-", ".dat");
        try {
            writeNative(staging, value);
            Observation<T> written = inspect(staging);
            if (written.result().state() != LOADED || written.root().getIntOr("DataVersion", -1) != gameVersion()
                    || !expected.equals(written.root().get("data")) || !value.equals(written.result().snapshot().orElseThrow())) {
                throw new IOException("Native save verification failed at " + staging + ": " + written.result().detail());
            }
            requireUnchanged();
            publisher.move(staging, file);
            observed = written;
        } catch (IOException | RuntimeException ex) {
            try {
                Files.deleteIfExists(staging);
            } catch (IOException cleanup) {
                ex.addSuppressed(cleanup);
            }
            throw ex;
        }
    }

    private void writeNative(Path stage, T value) throws IOException {
        String name = stage.getFileName().toString();
        String directory = id.getPath().substring(0, id.getPath().lastIndexOf('/') + 1);
        var stagingId = id.withPath(directory + name.substring(0, name.length() - ".dat".length()));
        var type = new SavedDataType<Snapshot<T>>(stagingId,
                () -> { throw new IllegalStateException("Explicit snapshot required"); },
                schema.currentCodec().xmap(Snapshot::new, data -> data.value));
        var candidate = new Snapshot<>(value);
        // Closing this single-entry storage flushes only our candidate and releases its cache.
        try (var writer = new SavedDataStorage(level, folder, server.getFixerUpper(), registries)) {
            writer.set(type, candidate); // Calls setDirty().
        } catch (RuntimeException ex) {
            throw new IOException("Native save failed at " + stage, ex);
        }
    }

    private static int gameVersion() {
        return SharedConstants.getCurrentVersion().dataVersion().version();
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
            var attrs = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!attrs.isRegularFile()) {
                return new Observation<>(null, LoadResult.empty(IO_ERROR, "Expected regular file at " + path));
            }
            CompoundTag root = readRoot(path);
            Tag dataVersion = root.get("DataVersion");
            if (dataVersion != null) {
                if (!(dataVersion instanceof IntTag version) || version.intValue() <= 0) {
                    return new Observation<>(root, LoadResult.empty(CORRUPT, "Invalid Minecraft DataVersion"));
                }
                if (version.intValue() > gameVersion()) {
                    return new Observation<>(root, LoadResult.empty(FUTURE, "Minecraft DataVersion=" + version.intValue()));
                }
            }
            Tag data = root.get("data");
            var result = data == null ? LoadResult.<T>empty(CORRUPT, "Missing data") : schema.decode(ops, data);
            return new Observation<>(root, result);
        } catch (NoSuchFileException missing) {
            return new Observation<>(null, LoadResult.empty(MISSING, path.toString()));
        } catch (FileSystemException ex) {
            return new Observation<>(null, LoadResult.empty(IO_ERROR, path + ": " + ex));
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
            try (InputStream content = gzip ? GzipCompressorInputStream.builder()
                    .setInputStream(raw).setDecompressConcatenated(false).get() : raw;
                 var input = new DataInputStream(content)) {
                CompoundTag root = NbtIo.read(input, NbtAccounter.create(NBT_BUDGET));
                if (input.read() != -1) throw new IOException("Trailing NBT data");
                if (gzip && raw.read() != -1) throw new IOException("Trailing bytes after gzip member");
                return root;
            }
        }
    }
}

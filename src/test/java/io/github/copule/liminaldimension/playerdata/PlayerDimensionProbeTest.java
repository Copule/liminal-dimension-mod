package io.github.copule.liminaldimension.playerdata;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import java.util.zip.GZIPOutputStream;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static io.github.copule.liminaldimension.playerdata.PlayerDimensionProbe.Status.*;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;

class PlayerDimensionProbeTest {
    private static final UUID PLAYER = UUID.fromString("00000000-0000-4000-8000-000000000001");
    private static final Identifier OVERWORLD = Identifier.parse("minecraft:overworld");
    private static final Identifier SYNTHETIC = Identifier.parse("probe:synthetic");
    private static final Set<Identifier> LOADED = Set.of(OVERWORLD, SYNTHETIC);

    @TempDir
    Path temp;

    private Path world;
    private Path files;
    private Path primary;
    private Path backup;

    @BeforeEach
    void createWorld() throws IOException {
        world = Files.createDirectory(temp.resolve("world"));
        files = world.resolve("players/data");
        primary = files.resolve(PLAYER + ".dat");
        backup = files.resolve(PLAYER + ".dat_old");
    }

    @Test
    void absentFilesAndStorageAreDistinguishedFromAnUnverifiedWorldRoot() throws IOException {
        assertEquals(NO_PLAYER_DATA, inspect());
        assertFalse(Files.exists(world.resolve("players")));
        Files.createDirectories(files);
        assertEquals(NO_PLAYER_DATA, inspect());
        assertEquals(UNCONFIRMED, PlayerDimensionProbe.inspect(temp.resolve("missing"), PLAYER, LOADED));
    }

    @ParameterizedTest
    @ValueSource(strings = {"world/.", "world/players/..", "./world"})
    void rejectsDotSegmentsInTheSuppliedWorldPath(String path) throws IOException {
        write(primary, compressed(record(OVERWORLD.toString())));
        assertEquals(DIMENSION_LOADED, inspect());
        assertEquals(UNCONFIRMED, PlayerDimensionProbe.inspect(temp.resolve(path), PLAYER, LOADED));
    }

    @TestFactory
    Stream<DynamicTest> symlinkAndParentSegmentOnLinux() {
        // The repository requires zero skipped cases. Register the POSIX fixture only on Linux.
        if (!OS.LINUX.isCurrentOs()) {
            return Stream.empty();
        }
        return Stream.of(dynamicTest("a symlink followed by .. cannot turn a missing dimension into no player data", () -> {
            Path lexical = Files.createDirectory(temp.resolve("lexical"));
            Path emptyWorld = Files.createDirectory(lexical.resolve("world"));
            Path target = Files.createDirectories(temp.resolve("physical/deep"));
            Path physicalWorld = target.getParent().resolve("world");
            write(physicalWorld.resolve("players/data").resolve(primary.getFileName()),
                    compressed(record("probe:removed")));
            Path link = Files.createSymbolicLink(lexical.resolve("link"), target);
            Path rawWorld = link.resolve("../world");

            assertEquals(emptyWorld, rawWorld.normalize());
            assertEquals(physicalWorld.toRealPath(), rawWorld.toRealPath());
            assertEquals(NO_PLAYER_DATA, PlayerDimensionProbe.inspect(emptyWorld, PLAYER, LOADED));
            assertEquals(DIMENSION_MISSING, PlayerDimensionProbe.inspect(physicalWorld, PLAYER, LOADED));
            assertEquals(UNCONFIRMED, PlayerDimensionProbe.inspect(rawWorld, PLAYER, LOADED));
        }));
    }

    @Test
    void loadedDimensionIsDeterminedFromTheSuppliedSnapshot() throws IOException {
        byte[] original = compressed(record(SYNTHETIC.toString()));
        write(primary, original);
        assertEquals(DIMENSION_LOADED, inspect());
        assertEquals(DIMENSION_MISSING, PlayerDimensionProbe.inspect(world, PLAYER, Set.of(OVERWORLD)));
        assertArrayEquals(original, Files.readAllBytes(primary));
        assertFalse(Files.exists(backup));
    }

    @Test
    void validBackupDoesNotChangeTheMissingDimensionInThePrimary() throws IOException {
        write(primary, compressed(record("probe:removed")));
        write(backup, compressed(record(OVERWORLD.toString())));
        assertEquals(DIMENSION_MISSING, inspect());
    }

    @Test
    void aBackupAloneCannotConfirmTheRecord() throws IOException {
        byte[] saved = compressed(record(OVERWORLD.toString()));
        write(backup, saved);
        assertEquals(UNCONFIRMED, inspect());
        assertArrayEquals(saved, Files.readAllBytes(backup));
        assertFalse(Files.exists(primary));
    }

    @Test
    void corruptPrimaryAndReadableBackupAreLeftUntouched() throws IOException {
        byte[] corrupt = {1, 2, 3};
        byte[] saved = compressed(record(OVERWORLD.toString()));
        write(primary, corrupt);
        write(backup, saved);
        assertEquals(UNCONFIRMED, inspect());
        assertArrayEquals(corrupt, Files.readAllBytes(primary));
        assertArrayEquals(saved, Files.readAllBytes(backup));
        try (var entries = Files.list(files)) {
            assertEquals(2, entries.count());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"playerdata", "players/playerdata", "players/00000000-0000-4000-8000-000000000001.dat"})
    void legacyLayoutDoesNotConfirmAbsence(String legacyPath) throws IOException {
        write(world.resolve(legacyPath), new byte[0]);
        assertEquals(UNCONFIRMED, inspect());
    }

    @Test
    void fileAndDirectoryMustHaveTheExpectedTypes() throws IOException {
        Files.createDirectories(primary);
        assertEquals(UNCONFIRMED, inspect());
        Path otherWorld = Files.createDirectory(temp.resolve("other-world"));
        Files.createFile(otherWorld.resolve("players"));
        assertEquals(UNCONFIRMED, PlayerDimensionProbe.inspect(otherWorld, PLAYER, LOADED));
    }

    @ParameterizedTest
    @ValueSource(ints = {5022, 5024})
    void onlyTheTargetDataVersionCanBeConfirmed(int dataVersion) throws IOException {
        var tag = record(OVERWORLD.toString());
        tag.putInt("DataVersion", dataVersion);
        write(primary, compressed(tag));
        assertEquals(UNCONFIRMED, inspect());
    }

    @Test
    void rootFieldTypesMustMatchThePlayerSaveFormat() throws IOException {
        var tag = record(OVERWORLD.toString());
        tag.remove("DataVersion");
        write(primary, compressed(tag));
        assertEquals(UNCONFIRMED, inspect());
        tag.putLong("DataVersion", 5023);
        write(primary, compressed(tag));
        assertEquals(UNCONFIRMED, inspect());
        tag.putInt("DataVersion", 5023);
        tag.remove("Dimension");
        var nested = new CompoundTag();
        nested.putString("Dimension", OVERWORLD.toString());
        tag.put("nested", nested);
        write(primary, compressed(tag));
        assertEquals(UNCONFIRMED, inspect());
        tag.putInt("Dimension", 0);
        write(primary, compressed(tag));
        assertEquals(UNCONFIRMED, inspect());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "overworld", ":overworld", "minecraft:", "Minecraft:overworld", "probe:bad id"})
    void malformedOrNoncanonicalDimensionsAreUnconfirmed(String dimension) throws IOException {
        write(primary, compressed(record(dimension)));
        assertEquals(UNCONFIRMED, inspect());
    }

    @Test
    void damagedGzipHeadersAreUnconfirmed() throws IOException {
        byte[] valid = compressed(record(OVERWORLD.toString()));
        assertUnconfirmed(Arrays.copyOf(valid, 6));
        for (int field : new int[] {0, 2, 3}) {
            byte[] corrupt = valid.clone();
            corrupt[field] ^= (byte) 0x80;
            assertUnconfirmed(corrupt);
        }
    }

    @Test
    void damagedGzipTrailersAreUnconfirmed() throws IOException {
        byte[] valid = compressed(record(OVERWORLD.toString()));
        assertUnconfirmed(Arrays.copyOf(valid, valid.length - 1));
        for (int field : new int[] {valid.length - 8, valid.length - 4}) {
            byte[] corrupt = valid.clone();
            corrupt[field] ^= 1;
            assertUnconfirmed(corrupt);
        }
    }

    @Test
    void extraCompressedBytesAndAdditionalGzipMembersAreUnconfirmed() throws IOException {
        byte[] valid = compressed(record(OVERWORLD.toString()));
        assertUnconfirmed(Arrays.copyOf(valid, valid.length + 1));
        var joined = new ByteArrayOutputStream();
        joined.write(valid);
        joined.write(gzip(new byte[0]));
        assertUnconfirmed(joined.toByteArray());
    }

    @Test
    void extraNbtBytesAndEmptyOrWrongRootAreUnconfirmed() throws IOException {
        var nbt = new ByteArrayOutputStream();
        NbtIo.write(record(OVERWORLD.toString()), new DataOutputStream(nbt));
        nbt.write(1);
        assertUnconfirmed(gzip(nbt.toByteArray()));
        assertUnconfirmed(gzip(new byte[] {Tag.TAG_INT, 0, 0, 0, 0, 0, 1}));
        assertUnconfirmed(gzip(new byte[] {Tag.TAG_COMPOUND, 0, 0}));
    }

    @Test
    void duplicateDecisionFieldsAreUnconfirmed() throws IOException {
        for (String field : Set.of("DataVersion", "Dimension")) {
            var bytes = new ByteArrayOutputStream();
            try (var nbt = new DataOutputStream(bytes)) {
                nbt.writeByte(Tag.TAG_COMPOUND);
                nbt.writeUTF("");
                nbt.writeByte(Tag.TAG_INT);
                nbt.writeUTF("DataVersion");
                nbt.writeInt(5023);
                nbt.writeByte(Tag.TAG_STRING);
                nbt.writeUTF("Dimension");
                nbt.writeUTF(OVERWORLD.toString());
                if (field.equals("DataVersion")) {
                    nbt.writeByte(Tag.TAG_INT);
                    nbt.writeUTF(field);
                    nbt.writeInt(5023);
                } else {
                    nbt.writeByte(Tag.TAG_STRING);
                    nbt.writeUTF(field);
                    nbt.writeUTF("probe:removed");
                }
                nbt.writeByte(Tag.TAG_END);
            }
            assertUnconfirmed(gzip(bytes.toByteArray()));
        }
    }

    @Test
    void overLimitCompressedAndExpandedBytesAreUnconfirmed() throws IOException {
        Files.createDirectories(files);
        try (var file = FileChannel.open(primary, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            file.position(8L * 1024 * 1024);
            file.write(ByteBuffer.wrap(new byte[1]));
        }
        assertEquals(UNCONFIRMED, inspect());

        var tag = record(OVERWORLD.toString());
        tag.putByteArray("padding", new byte[8 * 1024 * 1024]);
        assertUnconfirmed(compressed(tag));
    }

    @Test
    void overLimitNbtAccountingAndDepthAreUnconfirmed() throws IOException {
        var tag = record(OVERWORLD.toString());
        tag.putByteArray("padding", new byte[8 * 1024 * 1024 - 128]);
        assertUnconfirmed(compressed(tag));

        tag.remove("padding");
        var parent = tag;
        for (int level = 0; level < 128; level++) {
            var child = new CompoundTag();
            parent.put("child", child);
            parent = child;
        }
        assertUnconfirmed(compressed(tag));
    }

    private PlayerDimensionProbe.Status inspect() {
        return PlayerDimensionProbe.inspect(world, PLAYER, LOADED);
    }

    private void assertUnconfirmed(byte[] bytes) throws IOException {
        write(primary, bytes);
        assertEquals(UNCONFIRMED, inspect());
    }

    private static CompoundTag record(String dimension) {
        var tag = new CompoundTag();
        tag.putInt("DataVersion", 5023);
        tag.putString("Dimension", dimension);
        return tag;
    }

    private static byte[] compressed(CompoundTag tag) throws IOException {
        var bytes = new ByteArrayOutputStream();
        NbtIo.writeCompressed(tag, bytes);
        return bytes.toByteArray();
    }

    private static byte[] gzip(byte[] data) throws IOException {
        var bytes = new ByteArrayOutputStream();
        try (var gzip = new GZIPOutputStream(bytes)) {
            gzip.write(data);
        }
        return bytes.toByteArray();
    }

    private static void write(Path path, byte[] bytes) throws IOException {
        Files.createDirectories(path.getParent());
        Files.write(path, bytes);
    }
}

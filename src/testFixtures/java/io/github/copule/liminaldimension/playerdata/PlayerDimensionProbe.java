package io.github.copule.liminaldimension.playerdata;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Set;
import java.util.UUID;
import java.util.zip.CRC32;
import java.util.zip.Inflater;
import java.util.zip.InflaterInputStream;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.EndTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtException;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.nbt.TagType;
import net.minecraft.nbt.visitors.CollectToTag;
import net.minecraft.resources.Identifier;

import static java.nio.file.LinkOption.NOFOLLOW_LINKS;

/** Reads one synthetic Java 26.3 player record in a stable, caller-owned fixture tree. */
public final class PlayerDimensionProbe {
    private static final int DATA_VERSION = 5023;
    private static final int MAX_BYTES = 8 * 1024 * 1024;
    private static final int MAX_NBT_DEPTH = 128;
    private static final int GZIP_HEADER_BYTES = 10;
    private static final int GZIP_TRAILER_BYTES = 8;

    public enum Status {
        NO_PLAYER_DATA,
        DIMENSION_LOADED,
        DIMENSION_MISSING,
        UNCONFIRMED
    }

    private PlayerDimensionProbe() {}

    public static Status inspect(Path world, UUID playerId, Set<Identifier> loadedDimensions) {
        try {
            Path root = world.toAbsolutePath().normalize();
            if (!isPlainDirectory(root, attributesIfPresent(root))) {
                return Status.UNCONFIRMED;
            }
            Path players = root.resolve("players");
            var playersAttributes = attributesIfPresent(players);
            if (playersAttributes != null && !isPlainDirectory(players, playersAttributes)) {
                return Status.UNCONFIRMED;
            }

            Path data = players.resolve("data");
            var dataAttributes = playersAttributes == null ? null : attributesIfPresent(data);
            if (dataAttributes != null && !isPlainDirectory(data, dataAttributes)) {
                return Status.UNCONFIRMED;
            }

            Path primary = data.resolve(playerId + ".dat");
            var primaryAttributes = dataAttributes == null ? null : attributesIfPresent(primary);
            if (primaryAttributes == null) {
                boolean backupPresent = dataAttributes != null
                        && attributesIfPresent(data.resolve(playerId + ".dat_old")) != null;
                boolean legacyPresent = attributesIfPresent(root.resolve("playerdata")) != null
                        || (playersAttributes != null && hasLegacyEntry(players, playerId));
                return backupPresent || legacyPresent ? Status.UNCONFIRMED : Status.NO_PLAYER_DATA;
            }
            if (!primaryAttributes.isRegularFile() || primaryAttributes.size() > MAX_BYTES
                    || !primary.equals(primary.toRealPath())) {
                return Status.UNCONFIRMED;
            }

            byte[] encoded;
            try (var file = Files.newInputStream(primary, NOFOLLOW_LINKS)) {
                encoded = file.readNBytes(MAX_BYTES + 1);
            }
            return inspectPrimary(encoded, loadedDimensions);
        } catch (IOException | SecurityException | UnsupportedOperationException ignored) {
            return Status.UNCONFIRMED;
        }
    }

    private static BasicFileAttributes attributesIfPresent(Path path) throws IOException {
        try {
            return Files.readAttributes(path, BasicFileAttributes.class, NOFOLLOW_LINKS);
        } catch (NoSuchFileException missing) {
            return null;
        }
    }

    private static boolean isPlainDirectory(Path path, BasicFileAttributes attributes) throws IOException {
        return attributes != null && attributes.isDirectory() && path.equals(path.toRealPath());
    }

    private static boolean hasLegacyEntry(Path players, UUID playerId) throws IOException {
        return attributesIfPresent(players.resolve("playerdata")) != null
                || attributesIfPresent(players.resolve(playerId + ".dat")) != null
                || attributesIfPresent(players.resolve(playerId + ".dat_old")) != null;
    }

    private static Status inspectPrimary(byte[] encoded, Set<Identifier> loadedDimensions) {
        try {
            byte[] decoded = readGzipMember(encoded);
            // PlayerDataStorage writes an unnamed compound. NbtIo.parse skips the root name.
            if (decoded.length < 3 || decoded[0] != Tag.TAG_COMPOUND || decoded[1] != 0 || decoded[2] != 0) {
                return Status.UNCONFIRMED;
            }
            try (var nbt = new DataInputStream(new ByteArrayInputStream(decoded))) {
                var visitor = new PlayerTagCollector();
                NbtIo.parse(nbt, visitor, new NbtAccounter(MAX_BYTES, MAX_NBT_DEPTH));
                if (visitor.rejected || nbt.read() != -1 || !(visitor.getResult() instanceof CompoundTag root)
                        || !(root.get("DataVersion") instanceof IntTag version) || version.value() != DATA_VERSION
                        || !(root.get("Dimension") instanceof StringTag dimensionTag)) {
                    return Status.UNCONFIRMED;
                }
                String stored = dimensionTag.value();
                Identifier dimension = Identifier.tryParse(stored);
                if (dimension == null || dimension.getPath().isEmpty() || !dimension.toString().equals(stored)) {
                    return Status.UNCONFIRMED;
                }
                return loadedDimensions.contains(dimension) ? Status.DIMENSION_LOADED : Status.DIMENSION_MISSING;
            }
        } catch (IOException | NbtException | IllegalArgumentException ignored) {
            return Status.UNCONFIRMED;
        }
    }

    private static byte[] readGzipMember(byte[] encoded) throws IOException {
        // Accept the flag-free header emitted by the game's GZIPOutputStream.
        if (encoded.length <= GZIP_HEADER_BYTES + GZIP_TRAILER_BYTES || encoded.length > MAX_BYTES
                || encoded[0] != 0x1f || encoded[1] != (byte) 0x8b || encoded[2] != 8 || encoded[3] != 0) {
            throw new IOException();
        }
        int trailerStart = encoded.length - GZIP_TRAILER_BYTES;
        try (var inflater = new Inflater(true);
                var stream = new InflaterInputStream(
                        new ByteArrayInputStream(encoded, GZIP_HEADER_BYTES, encoded.length - GZIP_HEADER_BYTES),
                        inflater)) {
            byte[] decoded = stream.readNBytes(MAX_BYTES + 1);
            if (decoded.length > MAX_BYTES || !inflater.finished()
                    || inflater.getBytesRead() != trailerStart - GZIP_HEADER_BYTES) {
                throw new IOException();
            }
            var trailer = ByteBuffer.wrap(encoded, trailerStart, GZIP_TRAILER_BYTES).order(ByteOrder.LITTLE_ENDIAN);
            var crc = new CRC32();
            crc.update(decoded);
            if (trailer.getInt() != (int) crc.getValue() || trailer.getInt() != decoded.length) {
                throw new IOException();
            }
            return decoded;
        }
    }

    private static final class PlayerTagCollector extends CollectToTag {
        private boolean hasVersion;
        private boolean hasDimension;
        private boolean rejected;

        @Override
        public EntryResult visitEntry(TagType<?> type, String name) {
            if (depth() == 1) {
                if ("DataVersion".equals(name)) {
                    rejected = hasVersion;
                    hasVersion = true;
                } else if ("Dimension".equals(name)) {
                    rejected = hasDimension;
                    hasDimension = true;
                }
            }
            return rejected ? EntryResult.HALT : super.visitEntry(type, name);
        }

        @Override
        public ValueResult visitList(TagType<?> type, int length) {
            if (type == EndTag.TYPE && length != 0) {
                rejected = true;
                return ValueResult.HALT;
            }
            return super.visitList(type, length);
        }
    }
}

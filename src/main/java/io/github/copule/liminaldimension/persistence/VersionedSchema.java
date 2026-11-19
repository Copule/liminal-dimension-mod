package io.github.copule.liminaldimension.persistence;

import com.google.gson.JsonPrimitive;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.Decoder;
import com.mojang.serialization.DynamicOps;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import net.minecraft.nbt.IntTag;

import static io.github.copule.liminaldimension.persistence.LoadResult.State.*;

public final class VersionedSchema<T> {
    private record Envelope<T>(int version, T payload) {}

    private final List<Decoder<T>> readers;
    private final Codec<T> current;

    private VersionedSchema(List<Decoder<T>> readers, Codec<T> payload) {
        this.readers = List.copyOf(readers);
        int version = readers.size();
        Codec<Envelope<T>> envelope = RecordCodecBuilder.create(instance -> instance.group(
                Codec.intRange(version, version).fieldOf("schema_version").forGetter(Envelope::version),
                payload.fieldOf("payload").forGetter(Envelope::payload)
        ).apply(instance, Envelope::new));
        current = envelope.xmap(Envelope::payload, value -> new Envelope<>(version, value));
    }

    public static <T> VersionedSchema<T> first(Codec<T> payload) {
        return new VersionedSchema<>(List.of(payload), payload);
    }

    /** Append exactly one revision. Older readers run every intervening migration in order. */
    public <U> VersionedSchema<U> next(Codec<U> payload, Function<T, U> migrate) {
        var upgraded = new ArrayList<Decoder<U>>();
        for (var reader : readers) upgraded.add(reader.map(migrate));
        upgraded.add(payload);
        return new VersionedSchema<>(upgraded, payload);
    }

    Codec<T> currentCodec() { return current; }

    public <A> DataResult<A> encode(DynamicOps<A> ops, T value) {
        return current.encodeStart(ops, value);
    }

    public <A> LoadResult<T> decode(DynamicOps<A> ops, A input) {
        var fields = ops.getMap(input).result();
        if (fields.isEmpty()) return LoadResult.empty(CORRUPT, "Expected a data object");
        A raw = fields.get().get("schema_version");
        int version;
        if (raw instanceof IntTag tag) {
            version = tag.intValue();
        } else if (raw instanceof JsonPrimitive json && json.isNumber()) {
            // Preserve the token spelling (including decimal point / exponent), never narrow a Number.
            String token = json.getAsString();
            if (!token.matches("[1-9][0-9]*")) return LoadResult.empty(CORRUPT, "Expected positive integer token");
            try {
                version = Integer.parseInt(token);
            } catch (NumberFormatException overflow) {
                return LoadResult.empty(FUTURE, "schema_version exceeds int range");
            }
        } else {
            return LoadResult.empty(CORRUPT, "Expected schema_version as NBT INT or JSON integer token");
        }
        if (version < 1) return LoadResult.empty(CORRUPT, "Expected positive schema_version");
        if (version > readers.size()) return LoadResult.empty(FUTURE, "schema_version=" + version);
        A payload = fields.get().get("payload");
        if (payload == null) return LoadResult.empty(CORRUPT, "Missing payload");
        var decoded = readers.get(version - 1).parse(ops, payload);
        if (decoded.isError()) return LoadResult.empty(CORRUPT, decoded.error().orElseThrow().message());
        return new LoadResult<>(version == readers.size() ? LOADED : MIGRATED, Optional.of(decoded.getOrThrow()), "");
    }
}

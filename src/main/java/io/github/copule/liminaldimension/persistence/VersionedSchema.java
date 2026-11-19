package io.github.copule.liminaldimension.persistence;

import com.google.gson.JsonElement;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.DynamicOps;
import com.mojang.serialization.JsonOps;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.math.BigDecimal;
import java.util.Objects;
import java.util.Optional;

import static io.github.copule.liminaldimension.persistence.LoadResult.State.*;

public final class VersionedSchema<T> {
    private record Envelope<T>(int version, T payload) {}

    private final Codec<T> migratedV1;
    private final Codec<T> v2;
    private final Codec<T> current;

    /** migratedV1 decodes the historical payload directly to an immutable V2 value. */
    public VersionedSchema(Codec<T> migratedV1, Codec<T> v2) {
        this.migratedV1 = Objects.requireNonNull(migratedV1);
        this.v2 = Objects.requireNonNull(v2);
        Codec<Envelope<T>> envelope = RecordCodecBuilder.create(instance -> instance.group(
                Codec.intRange(2, 2).fieldOf("schema_version").forGetter(Envelope::version),
                v2.fieldOf("payload").forGetter(Envelope::payload)
        ).apply(instance, Envelope::new));
        current = envelope.xmap(Envelope::payload, value -> new Envelope<>(2, value));
    }

    Codec<T> currentCodec() {
        return current;
    }

    public <A> DataResult<A> encode(DynamicOps<A> ops, T value) {
        return current.encodeStart(ops, value);
    }

    public <A> LoadResult<T> decode(DynamicOps<A> ops, A input) {
        var fields = ops.getMap(input).result();
        if (fields.isEmpty()) return LoadResult.empty(CORRUPT, "Expected a data object");
        A raw = fields.get().get("schema_version");
        if (raw == null) return LoadResult.empty(CORRUPT, "Missing schema_version");
        // Some ops coerce booleans or truncate a Number when decoding Codec.INT.
        // Inspect only the scalar and classify the version before touching payload.
        if (ops.getNumberValue(raw).isError()) return LoadResult.empty(CORRUPT, "Expected numeric schema_version");
        JsonElement scalar = ops.convertTo(JsonOps.INSTANCE, raw);
        if (!scalar.isJsonPrimitive() || !scalar.getAsJsonPrimitive().isNumber()) {
            return LoadResult.empty(CORRUPT, "Expected numeric schema_version");
        }
        BigDecimal version;
        try {
            version = scalar.getAsBigDecimal().stripTrailingZeros();
        } catch (ArithmeticException | NumberFormatException ex) {
            return LoadResult.empty(CORRUPT, "Invalid schema_version");
        }
        if (version.scale() > 0 || version.signum() <= 0) return LoadResult.empty(CORRUPT, "Invalid schema_version");
        if (version.compareTo(BigDecimal.TWO) > 0) return LoadResult.empty(FUTURE, "schema_version=" + version);
        boolean migrated = version.compareTo(BigDecimal.ONE) == 0;
        A payload = fields.get().get("payload");
        if (payload == null) return LoadResult.empty(CORRUPT, "Missing payload");
        var decoded = (migrated ? migratedV1 : v2).parse(ops, payload);
        if (decoded.isError()) return LoadResult.empty(CORRUPT, decoded.error().orElseThrow().message());
        return new LoadResult<>(migrated ? MIGRATED : LOADED, Optional.of(decoded.getOrThrow()), "");
    }
}

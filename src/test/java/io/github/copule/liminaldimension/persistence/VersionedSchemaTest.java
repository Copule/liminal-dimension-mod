package io.github.copule.liminaldimension.persistence;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import com.mojang.serialization.Codec;
import java.util.Optional;
import java.util.stream.Stream;
import net.minecraft.nbt.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static io.github.copule.liminaldimension.persistence.FoundationProbeData.SCHEMA;
import static io.github.copule.liminaldimension.persistence.LoadResult.State.*;
import static org.junit.jupiter.api.Assertions.*;

class VersionedSchemaTest {
    private LoadResult<FoundationProbeData> decode(String json) {
        return SCHEMA.decode(JsonOps.INSTANCE, JsonParser.parseString(json));
    }

    @Test void currentRoundTrip() {
        var value = new FoundationProbeData("probe", "explicit");
        JsonElement encoded = SCHEMA.encode(JsonOps.INSTANCE, value).getOrThrow();
        assertEquals(2, encoded.getAsJsonObject().get("schema_version").getAsInt());
        var decoded = SCHEMA.decode(JsonOps.INSTANCE, encoded);
        assertEquals(LOADED, decoded.state());
        assertEquals(value, decoded.snapshot().orElseThrow());
    }

    @Test void migrateLegacyMarker() {
        var result = decode("""
                {"schema_version":1,"payload":{"legacy_marker":"old"}}
                """);
        assertEquals(MIGRATED, result.state());
        var value = result.snapshot().orElseThrow();
        assertEquals(new FoundationProbeData("old", ""), value);
        assertEquals(LOADED, SCHEMA.decode(JsonOps.INSTANCE, SCHEMA.encode(JsonOps.INSTANCE, value).getOrThrow()).state());
    }

    @Test void absentOptionalFieldDefaults() {
        assertEquals(new FoundationProbeData("x", ""), decode("""
                {"schema_version":2,"payload":{"marker":"x"}}
                """).snapshot().orElseThrow());
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "[]", "{\"schema_version\":2}", "{\"schema_version\":2,\"payload\":{}}",
            "{\"schema_version\":2,\"payload\":{\"marker\":\"x\",\"note\":{}}}",
            "{\"schema_version\":true}", "{\"schema_version\":2.1}", "{\"schema_version\":0}"})
    void corruptDataLocksWrites(String json) {
        var result = decode(json);
        assertEquals(CORRUPT, result.state());
        assertTrue(result.snapshot().isEmpty());
        assertThrows(IllegalStateException.class, result::requireWritable);
    }

    @ParameterizedTest
    @ValueSource(strings = {"3", "4294967298"})
    void futureVersionLocksWritesBeforePayloadDecode(String version) {
        var result = decode("{\"schema_version\":" + version + "}");
        assertEquals(FUTURE, result.state());
        assertThrows(IllegalStateException.class, result::requireWritable);
    }

    @ParameterizedTest
    @ValueSource(strings = {"2.0", "2e0", "2.00", "1e20", "-1", "\"2\"", "null", "true"})
    void rejectsNonIntegerJsonTokenWithValidPayload(String token) {
        assertEquals(CORRUPT, decode("{\"schema_version\":" + token + ",\"payload\":{\"marker\":\"x\",\"legacy_marker\":\"v1\"}}").state());
    }

    static Stream<Tag> invalidNbtVersions() {
        return Stream.of(ByteTag.valueOf(true), ByteTag.valueOf((byte) 2), ShortTag.valueOf((short) 2),
                LongTag.valueOf(2), FloatTag.valueOf(2), DoubleTag.valueOf(2), DoubleTag.valueOf(Double.NaN),
                IntTag.valueOf(-1), IntTag.valueOf(0), StringTag.valueOf("2"));
    }

    @ParameterizedTest @MethodSource("invalidNbtVersions")
    void rejectsWrongNbtRepresentation(Tag version) {
        var payload = new CompoundTag();
        payload.putString("legacy_marker", "v1");
        payload.putString("marker", "v2");
        var input = new CompoundTag();
        input.put("payload", payload);
        input.put("schema_version", version);
        assertEquals(CORRUPT, SCHEMA.decode(NbtOps.INSTANCE, input).state());
    }

    @Test void continuousTypedUpgradesAndStandaloneV1() {
        var first = VersionedSchema.first(Codec.INT);
        var second = first.next(Codec.STRING, i -> "v" + i);
        var third = second.next(Codec.BOOL, s -> s.equals("v7"));
        var old = first.encode(JsonOps.INSTANCE, 7).getOrThrow();
        var middle = second.encode(JsonOps.INSTANCE, "v6").getOrThrow();
        assertEquals(LOADED, first.decode(JsonOps.INSTANCE, old).state());
        assertEquals(FUTURE, first.decode(JsonOps.INSTANCE, middle).state());
        assertEquals(Optional.of("v7"), second.decode(JsonOps.INSTANCE, old).snapshot());
        assertEquals(Optional.of(true), third.decode(JsonOps.INSTANCE, old).snapshot());
        assertEquals(Optional.of(false), third.decode(JsonOps.INSTANCE, middle).snapshot());
        var current = third.encode(NbtOps.INSTANCE, true).getOrThrow();
        assertEquals(LOADED, third.decode(NbtOps.INSTANCE, current).state());
        assertEquals(CORRUPT, SCHEMA.decode(JsonOps.INSTANCE, JsonParser.parseString("{\"schema_version\":1,\"payload\":{}}")).state());
    }

    @ParameterizedTest
    @ValueSource(strings = {"con", "prn", "nul", "aux", "com1", "com9", "lpt1", "lpt9", "com0", "x/aux", "x/nul/y",
            "../a", "a/../b", "x//y", "x/", "/x", "x.", "x.pending", "x\\y", "C:x", "NUL", "clock$"})
    void rejectsNonPortableKey(String key) {
        assertThrows(IllegalArgumentException.class, () -> SavedSnapshotStore.validateKey(key));
    }

    @Test void validPortableKeyAndStateInvariants() {
        SavedSnapshotStore.validateKey("conduit/com10/probe_1-x");
        SavedSnapshotStore.validateKey("x".repeat(255) + "/" + "y".repeat(251));
        assertThrows(IllegalArgumentException.class, () -> SavedSnapshotStore.validateKey("y".repeat(252)));
        assertThrows(IllegalArgumentException.class, () -> SavedSnapshotStore.validateKey("x".repeat(256) + "/y"));
        assertThrows(IllegalArgumentException.class, () -> new LoadResult<>(LOADED, Optional.empty(), ""));
        assertThrows(IllegalArgumentException.class, () -> new LoadResult<>(FUTURE, Optional.of(1), ""));
        assertThrows(IllegalStateException.class, () -> LoadResult.empty(IO_ERROR, "unreadable").requireWritable());
    }
}

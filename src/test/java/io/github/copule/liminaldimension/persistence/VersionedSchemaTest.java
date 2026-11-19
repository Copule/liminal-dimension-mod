package io.github.copule.liminaldimension.persistence;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import org.junit.jupiter.api.Test;
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
}

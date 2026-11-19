package io.github.copule.liminaldimension.persistence;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

/** Immutable format fixture shared by the two test source sets. */
public record FoundationProbeData(String marker, String note) {
    public static final Codec<FoundationProbeData> V1 = Codec.STRING.fieldOf("legacy_marker").codec()
            .xmap(marker -> new FoundationProbeData(marker, ""), FoundationProbeData::marker);
    public static final Codec<FoundationProbeData> V2 = RecordCodecBuilder.create(instance -> instance.group(
            Codec.STRING.fieldOf("marker").forGetter(FoundationProbeData::marker),
            Codec.STRING.optionalFieldOf("note", "").forGetter(FoundationProbeData::note)
    ).apply(instance, FoundationProbeData::new));
    public static final VersionedSchema<FoundationProbeData> SCHEMA = new VersionedSchema<>(V1, V2);
}

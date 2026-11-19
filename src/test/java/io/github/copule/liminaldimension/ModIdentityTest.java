package io.github.copule.liminaldimension;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModIdentityTest {
    @Test
    void generatedMetadataDeclaresTheEntrypointIdAndDisplayName() throws IOException {
        try (var input = ModIdentityTest.class.getResourceAsStream("/META-INF/neoforge.mods.toml")) {
            assertNotNull(input, "Generated mod descriptor is missing");
            var lines = new String(input.readAllBytes(), StandardCharsets.UTF_8).lines()
                    .dropWhile(line -> !line.trim().equals("[[mods]]"))
                    .skip(1)
                    .takeWhile(line -> !line.trim().startsWith("["))
                    .map(String::trim)
                    .toList();
            assertEquals("liminal_dimension", LiminalDimension.MOD_ID);
            assertTrue(lines.contains("modId=\"" + LiminalDimension.MOD_ID + "\""));
            assertTrue(lines.contains("displayName=\"未完全死亡\""));
        }
    }
}

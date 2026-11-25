package io.github.copule.liminaldimension;

import com.electronwill.nightconfig.core.UnmodifiableConfig;
import com.electronwill.nightconfig.toml.TomlParser;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.jar.JarFile;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.Remapper;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProductionJarTest {
    private static final String MOD_ID = "liminal_dimension";
    private static final String MOD_PACKAGE = "io/github/copule/liminaldimension/";
    private static final String CLIENT_PACKAGE = MOD_PACKAGE + "client/";
    private static final String COMMON_ENTRY = MOD_PACKAGE + "LiminalDimension";
    private static final String CLIENT_ENTRY = CLIENT_PACKAGE + "LiminalDimensionClient";
    private static final String SNAPSHOT_STORE = MOD_PACKAGE + "persistence/SavedSnapshotStore";
    private static final String MOD_ANNOTATION = "Lnet/neoforged/fml/common/Mod;";
    private static final String SUBSCRIBER_ANNOTATION = "Lnet/neoforged/fml/common/EventBusSubscriber;";
    private static final String DIST_DESCRIPTOR = "Lnet/neoforged/api/distmarker/Dist;";
    private static final Set<String> BOTH_SIDES = Set.of("CLIENT", "DEDICATED_SERVER");
    private static final Set<String> CLIENT_ONLY = Set.of("CLIENT");

    @Test
    void productionJarContainsOnlyMainClassesAndTheExpectedModMetadata() throws IOException {
        try (var jar = openJar()) {
            var packagedClasses = jar.stream().map(entry -> entry.getName())
                    .filter(name -> name.endsWith(".class")).sorted().toList();
            assertEquals(mainClasses().stream().sorted().toList(), packagedClasses,
                    "Production JAR must contain exactly the main source set's classes");

            var descriptor = jar.getJarEntry("META-INF/neoforge.mods.toml");
            assertNotNull(descriptor, "Production JAR is missing its NeoForge metadata");
            try (var reader = new InputStreamReader(jar.getInputStream(descriptor), StandardCharsets.UTF_8)) {
                var metadata = new TomlParser().parse(reader);
                List<UnmodifiableConfig> mods = metadata.get("mods");
                assertEquals(1, mods.size());
                var mod = mods.getFirst();
                assertEquals(MOD_ID, mod.get("modId"));
                assertEquals("未完全死亡", mod.get("displayName"));
                assertEquals(System.getProperty("liminal.modVersion"), mod.get("version"));

                List<UnmodifiableConfig> dependencies = metadata.get("dependencies." + MOD_ID);
                var expected = Map.of(
                        "minecraft", System.getProperty("liminal.minecraftRange"),
                        "neoforge", "[" + System.getProperty("liminal.neoVersion") + ",)");
                assertEquals(expected.size(), dependencies.size());
                var ids = new HashSet<String>();
                for (var dependency : dependencies) {
                    String id = dependency.get("modId");
                    assertTrue(expected.containsKey(id) && ids.add(id), "Unexpected dependency: " + id);
                    assertEquals(expected.get(id), dependency.get("versionRange"), id);
                    assertEquals("required", dependency.get("type"), id);
                    assertEquals("BOTH", dependency.get("side"), id);
                }
            }
        }
    }

    @Test
    void productionJarExcludesDatagenCaches() throws IOException {
        try (var jar = openJar()) {
            var caches = jar.stream().map(entry -> entry.getName())
                    .filter(name -> name.startsWith(".cache/") || name.contains("/.cache/"))
                    .sorted().toList();
            assertTrue(caches.isEmpty(), () -> "Datagen cache leaked into production: " + caches);
        }
    }

    @Test
    void productionJarExcludesTheDimensionTestPack() throws IOException {
        try (var jar = openJar()) {
            var fixtureEntries = jar.stream().map(entry -> entry.getName())
                    .filter(name -> name.contains("data/liminal_dimension_poc/")
                            || name.contains("m1-04-dimension-poc/"))
                    .sorted().toList();
            assertTrue(fixtureEntries.isEmpty(), () -> "Dimension test pack leaked into production: " + fixtureEntries);
        }
    }

    @Test
    void productionClassesRespectPhysicalSideBoundaries() throws IOException {
        var entrypoints = new TreeSet<String>();
        var violations = new TreeSet<String>();
        try (var jar = openJar()) {
            for (var entry : jar.stream().filter(item -> item.getName().endsWith(".class")).toList()) {
                try (var input = jar.getInputStream(entry)) {
                    var reader = new ClassReader(input);
                    var name = reader.getClassName();
                    var clientClass = name.startsWith(CLIENT_PACKAGE);
                    var node = new ClassNode();
                    // Let ASM interpret descriptors, signatures, annotations and bytecode operands.
                    reader.accept(new ClassRemapper(node, new Remapper(Opcodes.ASM9) {
                        @Override
                        public String map(String referenced) {
                            if (!clientClass && isClientClass(referenced)) {
                                violations.add(name + " -> " + referenced);
                            }
                            if (clientClass && (referenced.equals(SNAPSHOT_STORE)
                                    || referenced.startsWith(SNAPSHOT_STORE + "$"))) {
                                violations.add(name + " -> " + referenced + " (server persistence)");
                            }
                            return referenced;
                        }
                    }), 0);

                    if (node.visibleAnnotations == null) {
                        continue;
                    }
                    for (var annotation : node.visibleAnnotations) {
                        if (MOD_ANNOTATION.equals(annotation.desc)) {
                            entrypoints.add(name);
                            assertEquals(MOD_ID, value(annotation, "value"), name);
                            assertEquals(clientClass ? CLIENT_ONLY : BOTH_SIDES,
                                    sides(annotation, "dist"), name);
                        }
                        if (clientClass && SUBSCRIBER_ANNOTATION.equals(annotation.desc)) {
                            assertEquals(CLIENT_ONLY, sides(annotation, "value"), name);
                        }
                    }
                }
            }
        }
        assertEquals(Set.of(COMMON_ENTRY, CLIENT_ENTRY), entrypoints);
        assertTrue(violations.isEmpty(), () -> "Forbidden production class references:\n" + String.join("\n", violations));
    }

    private static JarFile openJar() throws IOException {
        return new JarFile(System.getProperty("liminal.productionJar"));
    }

    private static Set<String> mainClasses() throws IOException {
        var classes = new HashSet<String>();
        var dirs = System.getProperty("liminal.mainClassDirs").split(Pattern.quote(java.io.File.pathSeparator));
        for (var dir : dirs) {
            var root = Path.of(dir);
            if (!Files.isDirectory(root)) {
                continue;
            }
            try (var paths = Files.walk(root)) {
                for (var path : paths.filter(Files::isRegularFile).toList()) {
                    if (path.getFileName().toString().endsWith(".class")) {
                        classes.add(root.relativize(path).toString().replace(java.io.File.separatorChar, '/'));
                    }
                }
            }
        }
        return classes;
    }

    private static boolean isClientClass(String name) {
        return name.startsWith(CLIENT_PACKAGE)
                || name.startsWith("net/minecraft/client/")
                || name.startsWith("net/neoforged/neoforge/client/")
                || name.startsWith("com/mojang/blaze3d/");
    }

    private static Object value(AnnotationNode annotation, String key) {
        List<Object> values = annotation.values == null ? List.of() : annotation.values;
        for (int index = 0; index < values.size(); index += 2) {
            if (key.equals(values.get(index))) {
                return values.get(index + 1);
            }
        }
        return null;
    }

    private static Set<String> sides(AnnotationNode annotation, String key) {
        var value = value(annotation, key);
        // Both FML side arrays default to both physical sides.
        if (value == null) {
            return BOTH_SIDES;
        }
        var names = new ArrayList<String>();
        for (var item : (List<?>) value) {
            var constant = (String[]) item;
            assertEquals(DIST_DESCRIPTOR, constant[0]);
            names.add(constant[1]);
        }
        return Set.copyOf(names);
    }
}

package io.github.copule.liminaldimension.transaction;

import static org.junit.jupiter.api.Assertions.*;

import io.github.copule.liminaldimension.transaction.RecoveryTransaction.Record;
import io.github.copule.liminaldimension.transaction.RecoveryTransaction.Request;
import io.github.copule.liminaldimension.transaction.RecoveryTransaction.Stage;
import io.github.copule.liminaldimension.transaction.RecoveryTransaction.Store;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class RecoveryTransactionTest {
    private static final Request REQUEST = new Request(
            UUID.fromString("00000000-0000-0000-0000-000000000001"), "fixture-effect");

    @TempDir Path dir;

    @Test
    void retryDoesNotRewriteCommittedRecord() throws IOException {
        MemoryStore store = new MemoryStore();
        Record first = new RecoveryTransaction(store).complete(REQUEST);
        Record second = new RecoveryTransaction(store).complete(REQUEST);
        assertEquals(first, second);
        assertEquals(List.of(Stage.PREPARED, Stage.APPLIED, Stage.COMMITTED), store.writes);
    }

    @Test
    void differentPayloadForSameIdCannotReplaceDurableSnapshot() throws IOException {
        MemoryStore store = new MemoryStore();
        Record first = new RecoveryTransaction(store).complete(REQUEST);
        assertThrows(IllegalArgumentException.class, () -> new RecoveryTransaction(store).complete(
                new Request(REQUEST.id(), "different")));
        assertEquals(first, store.value.orElseThrow());
        assertEquals(3, store.writes.size());
    }

    @Test
    void losingCompareAndReplaceUsesWinnerAndDoesNotPublishEffectAgain() throws IOException {
        MemoryStore store = new MemoryStore() {
            @Override public boolean replace(UUID id, Optional<Record> expected, Record next) throws IOException {
                boolean accepted = super.replace(id, expected, next);
                return next.stage() != Stage.APPLIED && accepted;
            }
        };
        assertEquals(Stage.COMMITTED, new RecoveryTransaction(store).complete(REQUEST).stage());
        assertEquals(List.of(Stage.PREPARED, Stage.APPLIED, Stage.COMMITTED), store.writes);
    }

    @Test
    void storeThatDoesNotAdvanceFailsRatherThanLooping() {
        Store violation = new Store() {
            @Override public Optional<Record> read(UUID id) { return Optional.empty(); }
            @Override public boolean replace(UUID id, Optional<Record> expected, Record next) throws IOException {
                return false;
            }
        };
        assertTimeoutPreemptively(Duration.ofSeconds(2),
                () -> assertThrows(IOException.class, () -> new RecoveryTransaction(violation).complete(REQUEST)));
    }

    @ParameterizedTest
    @EnumSource(Stage.class)
    void ambiguousAcknowledgementAfterEachPublicationCanBeRetried(Stage interruption) throws IOException {
        MemoryStore store = new MemoryStore() {
            @Override public boolean replace(UUID id, Optional<Record> expected, Record next) throws IOException {
                boolean accepted = super.replace(id, expected, next);
                if (accepted && next.stage() == interruption) throw new IOException("lost acknowledgement");
                return accepted;
            }
        };
        assertThrows(IOException.class, () -> new RecoveryTransaction(store).complete(REQUEST));
        assertEquals(interruption, store.value.orElseThrow().stage());
        assertEquals(Stage.COMMITTED, new RecoveryTransaction(store).complete(REQUEST).stage());
        assertEquals(List.of(Stage.PREPARED, Stage.APPLIED, Stage.COMMITTED), store.writes);
    }

    @ParameterizedTest
    @EnumSource(Stage.class)
    void abruptJvmExitAfterEachPublicationResumesFromFile(Stage interruption) throws Exception {
        Path mainClasses = Path.of(RecoveryTransaction.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        Path testClasses = Path.of(CrashWorker.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        String classpath = mainClasses + java.io.File.pathSeparator + testClasses;
        Path java = Path.of(System.getProperty("java.home"), "bin", "java");
        Process child = new ProcessBuilder(java.toString(), "-cp", classpath,
                CrashWorker.class.getName(), dir.toString(), interruption.name())
                .redirectErrorStream(true).redirectOutput(dir.resolve("child.log").toFile()).start();
        try {
            assertTrue(child.waitFor(15, TimeUnit.SECONDS), "crash worker timed out");
            assertEquals(74, child.exitValue(), () -> readLog(dir.resolve("child.log")));
        } finally {
            if (child.isAlive()) child.destroyForcibly();
        }
        FixtureStore store = new FixtureStore(dir);
        Record afterCrash = store.read(REQUEST.id()).orElseThrow();
        assertEquals(interruption, afterCrash.stage());
        assertEquals(interruption == Stage.PREPARED ? Optional.empty() : Optional.of(REQUEST.effect()),
                afterCrash.publishedEffect());
        Record complete = new RecoveryTransaction(store).complete(REQUEST);
        assertEquals(Stage.COMMITTED, complete.stage());
        assertEquals(Optional.of(REQUEST.effect()), complete.publishedEffect());
        assertEquals(switch (interruption) {
            case PREPARED -> List.of(Stage.APPLIED, Stage.COMMITTED);
            case APPLIED -> List.of(Stage.COMMITTED);
            case COMMITTED -> List.of();
        }, store.writes);
    }

    @Test
    void malformedFixtureIsNotTreatedAsAbsent() throws IOException {
        FixtureStore store = new FixtureStore(dir);
        byte[] broken = {1, 2, 3};
        Path file = store.file(REQUEST.id());
        Files.write(file, broken);
        assertThrows(IOException.class, () -> new RecoveryTransaction(store).complete(REQUEST));
        assertArrayEquals(broken, Files.readAllBytes(file));
    }

    private static String readLog(Path path) {
        try { return Files.readString(path); }
        catch (IOException e) { return e.toString(); }
    }

    private static class MemoryStore implements Store {
        Optional<Record> value = Optional.empty();
        final List<Stage> writes = new ArrayList<>();

        @Override public Optional<Record> read(UUID id) { return value; }

        @Override public boolean replace(UUID id, Optional<Record> expected, Record next) throws IOException {
            if (!value.equals(expected)) return false;
            value = Optional.of(next);
            writes.add(next.stage());
            return true;
        }
    }

    /** Test-only single-writer adapter; not a storage API or multi-process locking design. */
    private static class FixtureStore implements Store {
        private final Path dir;
        final List<Stage> writes = new ArrayList<>();

        FixtureStore(Path dir) { this.dir = dir; }

        Path file(UUID id) { return dir.resolve(id + ".fixture"); }

        @Override public Optional<Record> read(UUID id) throws IOException {
            try (DataInputStream input = new DataInputStream(Files.newInputStream(file(id)))) {
                if (input.readInt() != 1) throw new IOException("Unknown fixture format");
                Stage stage = Stage.valueOf(input.readUTF());
                Request request = new Request(UUID.fromString(input.readUTF()), input.readUTF());
                Optional<String> effect = input.readBoolean() ? Optional.of(input.readUTF()) : Optional.empty();
                if (input.read() != -1) throw new IOException("Trailing fixture bytes");
                return Optional.of(new Record(request, stage, effect));
            } catch (NoSuchFileException e) {
                return Optional.empty();
            } catch (IllegalArgumentException e) {
                throw new IOException("Malformed fixture", e);
            }
        }

        @Override public boolean replace(UUID id, Optional<Record> expected, Record next) throws IOException {
            if (!read(id).equals(expected)) return false;
            Path temp = Files.createTempFile(dir, "transaction-", ".tmp");
            try {
                try (FileChannel channel = FileChannel.open(temp, StandardOpenOption.WRITE);
                     DataOutputStream output = new DataOutputStream(Channels.newOutputStream(channel))) {
                    output.writeInt(1);
                    output.writeUTF(next.stage().name());
                    output.writeUTF(next.request().id().toString());
                    output.writeUTF(next.request().effect());
                    output.writeBoolean(next.publishedEffect().isPresent());
                    if (next.publishedEffect().isPresent()) output.writeUTF(next.publishedEffect().orElseThrow());
                    output.flush();
                    channel.force(true);
                }
                Files.move(temp, file(id), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                writes.add(next.stage());
                return true;
            } finally {
                Files.deleteIfExists(temp);
            }
        }
    }

    public static final class CrashWorker {
        public static void main(String[] args) throws IOException {
            Stage interruption = Stage.valueOf(args[1]);
            FixtureStore store = new FixtureStore(Path.of(args[0])) {
                @Override public boolean replace(UUID id, Optional<Record> expected, Record next) throws IOException {
                    boolean accepted = super.replace(id, expected, next);
                    if (accepted && next.stage() == interruption) Runtime.getRuntime().halt(74);
                    return accepted;
                }
            };
            new RecoveryTransaction(store).complete(REQUEST);
            throw new IllegalStateException("Missing crash point");
        }
    }
}

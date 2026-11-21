package io.github.copule.liminaldimension.transaction;

import java.io.IOException;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * A technical recovery prototype: the synthetic effect and its marker are
 * published in the SAME record. No external action or game state is touched.
 */
public final class RecoveryTransaction {
    private final Store store;

    public RecoveryTransaction(Store store) {
        this.store = Objects.requireNonNull(store, "store");
    }

    /**
     * Complete, retry, or resume the same immutable request by UUID. An I/O
     * failure can have an unknown outcome; reopen and retry the SAME request.
     * Committed records must be retained while their UUIDs can be retried.
     */
    public Record complete(Request request) throws IOException {
        Objects.requireNonNull(request, "request");
        Stage minimumStage = null;
        while (true) {
            Optional<Record> current = store.read(request.id());
            Record next;
            if (current.isEmpty()) {
                if (minimumStage != null) throw invalidStore();
                next = new Record(request, Stage.PREPARED, Optional.empty());
            } else {
                Record record = current.get();
                if (!record.request().id().equals(request.id())) throw invalidStore();
                if (!record.request().equals(request)) {
                    throw new IllegalArgumentException("Transaction ID already has a different request");
                }
                if (minimumStage != null && record.stage().ordinal() < minimumStage.ordinal()) {
                    throw invalidStore();
                }
                if (record.stage() == Stage.COMMITTED) return record;
                next = switch (record.stage()) {
                    case PREPARED -> new Record(request, Stage.APPLIED, Optional.of(request.effect()));
                    case APPLIED -> new Record(request, Stage.COMMITTED, record.publishedEffect());
                    case COMMITTED -> throw new AssertionError("terminal stage handled above");
                };
            }
            store.replace(request.id(), current, next);
            // A successful write or a concurrent winner both advance the stage.
            // Always reread the authoritative record before continuing.
            minimumStage = next.stage();
        }
    }

    private static IOException invalidStore() {
        return new IOException("Transaction store violated its monotonic snapshot contract");
    }

    public record Request(UUID id, String effect) {
        public Request {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(effect, "effect");
            if (effect.isBlank() || effect.length() > 256) {
                throw new IllegalArgumentException("Expected a nonblank effect of at most 256 code units");
            }
        }
    }

    public enum Stage {
        PREPARED, APPLIED, COMMITTED
    }

    public record Record(Request request, Stage stage, Optional<String> publishedEffect) {
        public Record {
            Objects.requireNonNull(request, "request");
            Objects.requireNonNull(stage, "stage");
            Objects.requireNonNull(publishedEffect, "publishedEffect");
            if (stage == Stage.PREPARED ? publishedEffect.isPresent()
                    : !publishedEffect.equals(Optional.of(request.effect()))) {
                throw new IllegalArgumentException("Stage does not match the published effect");
            }
        }
    }

    public interface Store {
        /**
         * Return empty only for a definitely absent transaction. Read or format
         * errors must throw rather than be represented as absence.
         * Reads of a key cannot regress or return a removed record.
         */
        Optional<Record> read(UUID id) throws IOException;

        /**
         * Compare against expected and atomically replace the entire record.
         * False means another writer progressed it; true means next is published.
         * May throw even after publication, e.g. if acknowledgement is lost.
         */
        boolean replace(UUID id, Optional<Record> expected, Record next) throws IOException;
    }
}

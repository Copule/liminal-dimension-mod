package io.github.copule.liminaldimension.persistence;

import java.util.Objects;
import java.util.Optional;

/** Only loaded or migrated data has a snapshot. Snapshots must be immutable. */
public record LoadResult<T>(State state, Optional<T> snapshot, String detail) {
    public enum State { MISSING, LOADED, MIGRATED, CORRUPT, FUTURE, IO_ERROR }

    public LoadResult {
        Objects.requireNonNull(state);
        Objects.requireNonNull(detail);
        if (snapshot.isPresent() != (state == State.LOADED || state == State.MIGRATED)) {
            throw new IllegalArgumentException("Snapshot does not match " + state);
        }
    }

    static <T> LoadResult<T> empty(State state, String detail) {
        return new LoadResult<>(state, Optional.empty(), detail);
    }

    public void requireWritable() {
        if (state == State.CORRUPT || state == State.FUTURE || state == State.IO_ERROR) {
            throw new IllegalStateException(state + ": " + detail);
        }
    }
}

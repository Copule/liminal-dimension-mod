package fixture.placement;

import io.github.copule.liminaldimension.playerdata.PlayerDimensionProbe.Status;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import net.minecraft.resources.Identifier;

/** The live adapter touches this state only on the dedicated server tick. */
final class PlacementAdmission {
    record Input(Path world, UUID player, Set<Identifier> loaded) {
        Input {
            loaded = Set.copyOf(loaded);
        }
    }

    enum Outcome {
        NO_PLAYER_DATA, DIMENSION_LOADED, DIMENSION_MISSING, UNCONFIRMED, ABANDONED
    }

    /** Shared by every registration on a channel, including accidental repeats. */
    static final class ChannelTicket {
        private volatile boolean repeated;
        private final AtomicBoolean disconnected = new AtomicBoolean();

        void repeat() {
            repeated = true;
        }

        boolean isFirst() {
            return !repeated;
        }

        void disconnectOnce(Runnable action) {
            if (disconnected.compareAndSet(false, true)) {
                action.run();
            }
        }
    }

    private final PlacementGate terminal = new PlacementGate();
    private final long timeout;
    private final Consumer<Outcome> report;
    private final Consumer<Outcome> disconnect;
    private Input input;
    private Future<Status> pending;
    private long started;
    private Outcome outcome;

    PlacementAdmission(long timeout, Consumer<Outcome> report, Consumer<Outcome> disconnect) {
        this.timeout = timeout;
        this.report = report;
        this.disconnect = disconnect;
    }

    boolean tick(long now, BooleanSupplier current, Supplier<Input> capture, Function<Input, Future<Status>> submit) {
        if (outcome != null) {
            return false;
        }
        try {
            if (!current.getAsBoolean()) {
                return complete(Outcome.ABANDONED);
            }
            if (pending == null) {
                input = capture.get();
                if (input == null) {
                    return complete(Outcome.UNCONFIRMED);
                }
                pending = Objects.requireNonNull(submit.apply(input));
                started = now;
                return false;
            }
            if (now - started >= timeout) {
                return complete(Outcome.UNCONFIRMED);
            }
            return switch (pending.state()) {
                case RUNNING -> false;
                case FAILED, CANCELLED -> complete(Outcome.UNCONFIRMED);
                case SUCCESS -> acceptResult(current, capture);
            };
        } catch (RuntimeException unavailable) {
            return complete(Outcome.UNCONFIRMED);
        }
    }

    private boolean acceptResult(BooleanSupplier current, Supplier<Input> capture) {
        Status result = pending.resultNow();
        if (!input.equals(capture.get())) {
            return complete(Outcome.UNCONFIRMED);
        }
        if (!current.getAsBoolean()) {
            return complete(Outcome.ABANDONED);
        }
        return complete(switch (result) {
            case NO_PLAYER_DATA -> Outcome.NO_PLAYER_DATA;
            case DIMENSION_LOADED -> Outcome.DIMENSION_LOADED;
            case DIMENSION_MISSING -> Outcome.DIMENSION_MISSING;
            case UNCONFIRMED -> Outcome.UNCONFIRMED;
        });
    }

    private boolean complete(Outcome result) {
        // Terminal outcomes are published before any best-effort effects.
        outcome = result;
        if (pending != null) {
            try {
                pending.cancel(true);
            } catch (RuntimeException unavailable) {
                // A failed cancellation must not affect the already selected outcome.
            }
            pending = null;
        }
        input = null;
        try {
            report.accept(result);
        } catch (RuntimeException unavailable) {
            // Diagnostics are best effort.
        }
        if (result == Outcome.ABANDONED) {
            return false;
        }
        return terminal.tick(() -> result == Outcome.NO_PLAYER_DATA || result == Outcome.DIMENSION_LOADED, () -> {
            try {
                disconnect.accept(result);
            } catch (RuntimeException unavailable) {
                // Preserve the latched block. Vanilla must not see an exception and send a second disconnect.
            }
        });
    }
}

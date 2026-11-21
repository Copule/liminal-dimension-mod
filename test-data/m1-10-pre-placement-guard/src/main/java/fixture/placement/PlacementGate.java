package fixture.placement;

import java.util.function.BooleanSupplier;

/** Single-connection decision cell; called exclusively from the dedicated server tick. */
final class PlacementGate {
    private enum Decision { PENDING, ALLOW, BLOCK }
    private Decision decision = Decision.PENDING;

    boolean tick(BooleanSupplier referencePresent, Runnable disconnect) {
        if (decision == Decision.PENDING) {
            boolean present;
            try {
                present = referencePresent.getAsBoolean();
            } catch (RuntimeException unavailable) {
                present = false;
            }
            decision = present ? Decision.ALLOW : Decision.BLOCK;
            if (decision == Decision.BLOCK) {
                // Latch first: even a failed disconnect must not release the task queue.
                disconnect.run();
            }
        }
        return decision == Decision.ALLOW;
    }
}

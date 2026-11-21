package fixture.placement;

/** Pure-Java gate/queue contract tests; runnable independently from Minecraft. */
public final class PlacementGateProbe {
    public static void main(String[] args) {
        loadedProbeContinues();
        missingProbeHoldsQueue();
        failedLookupBlocks();
        failedDisconnectCannotReleaseQueue();
        System.out.println("PASS: 4 placement gate contracts");
    }

    private static void loadedProbeContinues() {
        PlacementGate gate = new PlacementGate();
        int[] prepareCalls = {0};
        if (gate.tick(() -> true, () -> { throw new AssertionError("unexpected disconnect"); })) {
            prepareCalls[0]++;
        }
        check(prepareCalls[0] == 1, "healthy normal queue advance");
    }

    private static void missingProbeHoldsQueue() {
        PlacementGate gate = new PlacementGate();
        int[] disconnects = {0};
        Runnable disconnect = () -> disconnects[0]++;
        check(!gate.tick(() -> false, disconnect), "don't advance on missing dimension");
        check(!gate.tick(() -> true, disconnect), "denial remains latched on later ticks");
        check(disconnects[0] == 1, "disconnect once");
    }

    private static void failedLookupBlocks() {
        PlacementGate gate = new PlacementGate();
        int[] disconnects = {0};
        check(!gate.tick(() -> { throw new IllegalStateException(); }, () -> disconnects[0]++),
                "failed lookup must never advance");
        check(disconnects[0] == 1, "disconnect after failed lookup");
    }

    private static void failedDisconnectCannotReleaseQueue() {
        PlacementGate gate = new PlacementGate();
        try {
            gate.tick(() -> false, () -> { throw new IllegalStateException(); });
            throw new AssertionError("disconnect failure should reach the listener's error handling");
        } catch (IllegalStateException expected) {
            check(!gate.tick(() -> true, () -> { throw new AssertionError("repeated disconnect"); }),
                    "queue remains blocked even after failed disconnect");
        }
    }

    private static void check(boolean condition, String reason) {
        if (!condition) throw new AssertionError(reason);
    }
}

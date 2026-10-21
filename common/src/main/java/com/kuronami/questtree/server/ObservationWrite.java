package com.kuronami.questtree.server;

/**
 * The rules an observation task's quiet write follows. It skips {@code ProgressService.bump} because a look that lasts
 * a while must not send a packet every tick, but it still checks the task's conditions and announces the TASK event on
 * the same first reach as {@code bump} does.
 */
final class ObservationWrite {

    private ObservationWrite() {
    }

    /** Whether the run may be written: its conditions pass and it changed. */
    static boolean writes(long before, long run, boolean conditionsMet) {
        return conditionsMet && before != run;
    }

    /** Whether this write is the first one to reach the target, so the TASK listener hears it once. */
    static boolean announces(long before, long run, long target, boolean conditionsMet) {
        return conditionsMet && before < target && run >= target;
    }

    /** Whether the node scan runs: only after a write that reached the target, never on a quiet tick. */
    static boolean rechecks(long before, long run, long target, boolean conditionsMet) {
        return writes(before, run, conditionsMet) && run >= target;
    }
}

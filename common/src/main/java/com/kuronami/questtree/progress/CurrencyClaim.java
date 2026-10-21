package com.kuronami.questtree.progress;

/**
 * What a currency reward does when the economy mod is missing or its payment fails (2026-10-21 review): without a
 * provider the reward is taken but pays nothing (a pack may be played without an economy mod), while a provider that
 * fails keeps the reward unclaimed so the player can try again. Game-free so the rule can be tested.
 */
public final class CurrencyClaim {

    public enum Outcome {
        PAID, NO_PROVIDER, FAILED
    }

    private CurrencyClaim() {
    }

    public static Outcome resolve(boolean providerAvailable, boolean paid) {
        if (!providerAvailable) {
            return Outcome.NO_PROVIDER;
        }
        return paid ? Outcome.PAID : Outcome.FAILED;
    }

    /** Whether the claim finishes. A missing economy mod does; a failed payment does not. */
    public static boolean completes(Outcome outcome) {
        return outcome != Outcome.FAILED;
    }
}

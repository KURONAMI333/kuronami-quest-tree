package com.kuronami.questtree.client;

/** Kept separate so a dedicated server never loads the client progress classes for a barrier check. */
public final class ClientBarrier {

    private ClientBarrier() {
    }

    public static boolean allows(String mode, String key) {
        return ClientProgress.barrierAllows(mode, key);
    }
}

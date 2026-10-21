package com.kuronami.questtree.progress;

import java.lang.reflect.Method;
import java.util.Set;

/**
 * Pack Stages' already-synced client copy, read without a compile dependency. The mod sends its stages to the client;
 * a stage barrier must see them or the client keeps a gate closed that the server would open (2026-10-21 review).
 */
public final class PackStagesClient {

    private static final Method STAGES = lookup();

    private PackStagesClient() {
    }

    public static boolean has(String stage) {
        if (stage == null || stage.isEmpty() || STAGES == null) {
            return false;
        }
        try {
            Object value = STAGES.invoke(null);
            return value instanceof Set<?> set && set.contains(stage);
        } catch (ReflectiveOperationException | RuntimeException ex) {
            return false;
        }
    }

    private static Method lookup() {
        try {
            return Class.forName("com.kuronami.packstages.client.ClientStageState").getMethod("stages");
        } catch (ReflectiveOperationException | LinkageError | RuntimeException ex) {
            return null;
        }
    }
}

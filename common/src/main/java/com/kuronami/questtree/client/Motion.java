package com.kuronami.questtree.client;

import net.minecraft.Util;
import net.minecraft.sounds.SoundEvents;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Timing of the result animations (MOTION_SOUND.md), started by progress updates and read by the screen. Everything
 * here is a one-off response to something the player did or earned; nothing loops or idles.
 */
final class Motion {

    /** Node key -> when it was unlocked (lines to its children grow, then the children light up in turn). */
    static final Map<String, Long> UNLOCKED = new HashMap<>();
    /** Node key -> when an exclusive choice closed it (the cross is stamped). */
    static final Map<String, Long> STAMPED = new HashMap<>();
    /** "node#task" -> when items were handed in (the count bumps). */
    static final Map<String, Long> SUBMITTED = new HashMap<>();
    /** Node key -> when it lit up as newly open after an unlock (a short brightening). */
    static final Map<String, Long> OPENED = new HashMap<>();

    private record Sound(long at, float pitch) {
    }

    private static final List<Sound> SOUNDS = new ArrayList<>();
    /** Shown fill per node, easing towards the real value (360ms). */
    private static final Map<String, double[]> FILLS = new HashMap<>();

    static final long LINE_GROW = 300;
    static final long CHILD_STEP = 110;

    private Motion() {
    }

    /** Schedules a pling, used for each skill a unlock opens (rising a little each time). */
    static void plingLater(long delay, float pitch) {
        SOUNDS.add(new Sound(Util.getMillis() + delay, pitch));
    }

    /** Called every frame by the screen and the HUD: plays due sounds. */
    static void tick() {
        long now = Util.getMillis();
        for (Iterator<Sound> it = SOUNDS.iterator(); it.hasNext(); ) {
            Sound s = it.next();
            if (s.at <= now) {
                ProgressFeedback.play(SoundEvents.NOTE_BLOCK_PLING.value(), s.pitch, 0.35f);
                it.remove();
            }
        }
    }

    /**
     * The fill to draw for a node: eases to the real value over about 360ms when progress moves. The first time a node
     * is drawn it starts at its real value, so opening the screen never animates.
     */
    static double fill(String key, double target) {
        double[] f = FILLS.get(key);
        long now = Util.getMillis();
        if (f == null) {
            FILLS.put(key, new double[]{target, now});
            return target;
        }
        double dt = Math.min(100, now - f[1]);
        f[1] = now;
        // exponential approach: about 95% of the way in 360ms
        f[0] += (target - f[0]) * (1 - Math.exp(-dt / 120.0));
        if (Math.abs(target - f[0]) < 0.002) {
            f[0] = target;
        }
        return f[0];
    }

    /** 0..1 progress of an animation started at {@code at} lasting {@code ms}; -1 when not running. */
    static float progress(Map<String, Long> map, String key, long ms) {
        Long at = map.get(key);
        if (at == null) {
            return -1;
        }
        long t = Util.getMillis() - at;
        if (t < 0) {
            // scheduled for later (the unlock chain staggers its steps)
            return -1;
        }
        if (t > ms) {
            map.remove(key);
            return -1;
        }
        return t / (float) ms;
    }

    /** Opening the screen shows the current fills at once (no catch-up animation on open). */
    static void resetFills() {
        FILLS.clear();
    }

    static void clear() {
        UNLOCKED.clear();
        STAMPED.clear();
        SUBMITTED.clear();
        OPENED.clear();
        SOUNDS.clear();
        FILLS.clear();
    }
}

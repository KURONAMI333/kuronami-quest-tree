package com.kuronami.questtree.progress;

/**
 * What an addon's listener did with a {@code custom} task click (FTB Quests' {@code CustomTask.Data}: getProgress,
 * setProgress, addProgress and completing the task). A result that no listener touched is {@link #handled()} false,
 * which leaves the button itself as the progress, as it was before listeners existed (2026-10-20).
 */
public final class CustomTaskResult {

    private final long current;
    private final long max;
    private boolean handled;
    private long progress;
    private boolean complete;

    public CustomTaskResult(long current, long max) {
        this.current = Math.max(0, current);
        this.max = Math.max(1, max);
        this.progress = this.current;
    }

    /** The progress the task has now (FTB Quests' {@code Data.getProgress}). */
    public long current() {
        return current;
    }

    public long max() {
        return max;
    }

    public void setProgress(long value) {
        handled = true;
        complete = false;
        progress = Math.max(0, Math.min(value, max));
    }

    public void addProgress(long amount) {
        setProgress(progress + amount);
    }

    public void markComplete() {
        handled = true;
        complete = true;
        progress = max;
    }

    public boolean handled() {
        return handled;
    }

    public boolean complete() {
        return complete;
    }

    /** The progress to store: the listener's value, clamped to 0..max. */
    public long progress() {
        return handled ? progress : current;
    }

    /** The button's own behaviour when no listener handled the click: one step, never past the task's maximum. */
    public static long buttonProgress(long current, long max) {
        return Math.min(Math.max(0, current) + 1, Math.max(1, max));
    }
}

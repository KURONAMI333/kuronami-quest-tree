package com.kuronami.questtree.client;

import com.kuronami.questtree.Constants;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;

/**
 * The screen's own sounds (assets/kuronami_quest_tree/sounds.json). Deliberately not vanilla sounds: a pickup or a
 * level-up chime from a menu would be mistaken for something that happened in the world. They are only played on the
 * client through {@link ProgressFeedback#play}, which looks them up by name, so they need no registry entry; a
 * resource pack can replace any of them.
 */
public final class QtSounds {

    public static final SoundEvent CLAIM = of("ui.claim");
    public static final SoundEvent SUBMIT = of("ui.submit");
    public static final SoundEvent FILL = of("ui.fill");
    public static final SoundEvent UNLOCK = of("ui.unlock");
    public static final SoundEvent OPEN = of("ui.open");
    public static final SoundEvent LEVEL = of("ui.level");
    public static final SoundEvent EXCLUDE = of("ui.exclude");
    public static final SoundEvent LOCKED = of("ui.locked");
    public static final SoundEvent CLICK = of("ui.click");
    public static final SoundEvent CHAPTER = of("ui.chapter");
    public static final SoundEvent TOAST_IN = of("ui.toast_in");
    public static final SoundEvent TOAST_OUT = of("ui.toast_out");

    private QtSounds() {
    }

    private static SoundEvent of(String path) {
        return SoundEvent.createVariableRangeEvent(ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, path));
    }
}

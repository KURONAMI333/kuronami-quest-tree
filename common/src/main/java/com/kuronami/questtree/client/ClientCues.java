package com.kuronami.questtree.client;

import com.kuronami.questtree.network.CuePayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;

/** Plays the server's non-chat notices: short toasts, party status, the lock cue and the refusal sound. */
public final class ClientCues {

    private ClientCues() {
    }

    public static void onCue(CuePayload payload) {
        switch (payload.kind()) {
            case "notice" -> NoticeToast.show(payload.b(), Component.translatable(payload.a()));
            case "party_invite" -> NoticeToast.show("team",
                    Component.translatable("screen.kuronami_quest_tree.party.title"),
                    Component.translatable("screen.kuronami_quest_tree.party.invited_by", payload.a()));
            case "party_kick" -> NoticeToast.show("team",
                    Component.translatable("commands.kuronami_quest_tree.party.kicked_you"));
            case "party_result" -> PartyStatus.set(payload.b().isBlank()
                    ? Component.translatable(payload.a())
                    : Component.translatable(payload.a(), payload.b()));
            case "restricted" -> {
                RestrictionCue.show();
                playLocked(1f);
            }
            case "denied" -> playLocked(0.8f);
            default -> {
            }
        }
    }

    private static void playLocked(float pitch) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.getSoundManager() != null) {
            minecraft.getSoundManager().play(SimpleSoundInstance.forUI(QtSounds.LOCKED, 1f, pitch));
        }
    }

    public static void clear() {
        PartyStatus.clear();
        RestrictionCue.clear();
    }
}

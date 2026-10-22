package com.kuronami.questtree.server;

import com.kuronami.questtree.network.CuePayload;
import com.kuronami.questtree.platform.Services;
import net.minecraft.server.level.ServerPlayer;

/** Sends the gameplay notices that replaced chat and the action bar (2026-10-22). */
final class Cues {

    private Cues() {
    }

    /** A short toast: the message key and the icon it reads with. */
    static void notice(ServerPlayer player, String key, String icon) {
        send(player, new CuePayload("notice", key, icon));
    }

    /** The target's invitation: a toast and the count badge on the party rail button. */
    static void partyInvite(ServerPlayer player, String from) {
        send(player, new CuePayload("party_invite", from, ""));
    }

    /** The target was removed from their party. */
    static void partyKick(ServerPlayer player) {
        send(player, new CuePayload("party_kick", "", ""));
    }

    /** The result of a party screen action, kept inside the party screen. */
    static void partyResult(ServerPlayer player, String key, String name) {
        send(player, new CuePayload("party_result", key, name == null ? "" : name));
    }

    /** A restriction refused an action: the lock cue, with the reason on the item's tooltip. */
    static void restricted(ServerPlayer player) {
        send(player, new CuePayload("restricted", "", ""));
    }

    /** A link or action did nothing: the mod's short refusal sound, no text. */
    static void denied(ServerPlayer player) {
        send(player, new CuePayload("denied", "", ""));
    }

    private static void send(ServerPlayer player, CuePayload payload) {
        if (player != null) {
            Services.PLATFORM.sendToPlayer(player, payload);
        }
    }
}

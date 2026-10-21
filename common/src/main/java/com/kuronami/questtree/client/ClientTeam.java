package com.kuronami.questtree.client;

import com.kuronami.questtree.Constants;
import com.kuronami.questtree.network.TeamPayload;
import com.kuronami.questtree.team.TeamView;
import net.minecraft.network.chat.Component;

/** The team view sent with the progress packet; replaced whole, like the progress. */
public final class ClientTeam {

    private static TeamView view = TeamView.EMPTY;

    private ClientTeam() {
    }

    public static void onTeam(TeamPayload payload) {
        try {
            view = TeamView.parse(payload.json());
        } catch (RuntimeException ex) {
            Constants.LOG.error("Could not read the team from the server", ex);
        }
    }

    public static TeamView view() {
        return view;
    }

    /** The party row's title: the FTB team name, otherwise the built-in party named after its owner. */
    public static Component title() {
        if (!view.name().isBlank()) {
            return Component.literal(view.name());
        }
        if (view.inTeam() && !view.ownerName().isBlank()) {
            return Component.translatable("screen.kuronami_quest_tree.party.named", view.ownerName());
        }
        return Component.translatable("screen.kuronami_quest_tree.party.title");
    }

    public static void onLogout() {
        view = TeamView.EMPTY;
    }
}

package com.kuronami.questtree.client;

import com.kuronami.questtree.team.TeamView;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The party screen, opened from the rail row above Settings (2026-10-21). One panel: the team name, its members and
 * the actions that apply to the player's role. Invitations are answered here too, so the party never needs a command.
 * FTB Teams keeps its own membership screen; when it manages the team this shows the roster read-only, because its
 * GUI message cannot be linked from the optional dependency.
 */
public final class PartyScreen extends Screen {

    private static final int ROW_H = 22;
    private static final int PANEL_W = 340;

    private final Screen parent;
    private boolean picking;
    private int scroll;
    private final List<Hit> hits = new ArrayList<>();

    private record Hit(int x, int y, int w, int h, Runnable run) {
        boolean hit(double mx, double my) {
            return mx >= x && mx < x + w && my >= y && my < y + h;
        }
    }

    public PartyScreen(Screen parent) {
        super(Component.translatable("screen.kuronami_quest_tree.party.title"));
        this.parent = parent;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        renderBackground(g, mouseX, mouseY, partialTick);
        g.fill(0, 0, width, height, Ui.GROUND);
        hits.clear();
        TeamView team = ClientTeam.view();
        long now = System.currentTimeMillis();
        List<TeamView.Invite> invites = team.activeInvites(now);
        List<Pick> picks = picking ? picks(team) : List.of();
        List<Row> rows = new ArrayList<>();
        if (picking) {
            for (Pick pick : picks) {
                rows.add(Row.player(pick.name(), pick.id(), false, false, false, false, () -> {
                    ClientProgress.send("party_invite", pick.id(), -1);
                    ProgressFeedback.click();
                    picking = false;
                }));
            }
        } else if (team.inTeam()) {
            for (TeamView.Member m : team.members()) {
                boolean canKick = !team.ftbManaged() && team.isOwner() && !m.self();
                rows.add(Row.player(m.name(), m.id(), m.online(), m.owner(), m.self(), canKick,
                        canKick ? () -> {
                            ClientProgress.send("party_kick", m.id(), -1);
                            ProgressFeedback.click();
                        } : null));
            }
        } else {
            for (TeamView.Invite invite : invites) {
                Component party = invite.partyName().isBlank()
                        ? Component.translatable("screen.kuronami_quest_tree.party.title")
                        : Component.translatable("screen.kuronami_quest_tree.party.named", invite.partyName());
                rows.add(Row.invite(party, invite.fromName()));
            }
        }

        int pw = Math.min(PANEL_W, width - 16);
        int px = (width - pw) / 2;
        int maxRows = Math.max(1, (height - 16 - 92) / ROW_H);
        int shown = Math.min(rows.size(), maxRows);
        scroll = Math.max(0, Math.min(scroll, Math.max(0, rows.size() - shown)));
        int ph = 48 + Math.max(1, shown) * ROW_H + 30;
        int py = Math.max(8, (height - ph) / 2);

        Ui.surface(g, px, py, pw, ph, Ui.R4, Ui.PANEL_TRANSLUCENT);
        Ui.outline(g, px, py, pw, ph, Ui.R4, Ui.HAIRLINE);
        Ui.text(g, font, font.plainSubstrByWidth(ClientTeam.title().getString(), Ui.textRoom(pw - 48)), px + 12, py + 10, Ui.TEXT);
        if (team.ftbManaged()) {
            Ui.text(g, font, Component.translatable("screen.kuronami_quest_tree.party.ftb"), px + 12, py + 21, Ui.DIM);
        } else if (team.inTeam()) {
            Ui.text(g, font, Component.translatable(team.members().size() == 1
                            ? "screen.kuronami_quest_tree.party.members_one"
                            : "screen.kuronami_quest_tree.party.members", team.members().size()),
                    px + 12, py + 21, Ui.DIM);
        }
        Tiles.icon(g, "cross", px + pw - 16, py + 10, 8, Ui.MUTED);
        hits.add(new Hit(px + pw - 22, py + 4, 20, 20, this::onClose));

        int listTop = py + 36;
        if (rows.isEmpty()) {
            Component empty = picking
                    ? Component.translatable("screen.kuronami_quest_tree.party.no_players")
                    : team.ftbManaged()
                    ? Component.translatable("screen.kuronami_quest_tree.party.ftb")
                    : team.inTeam()
                    ? Component.translatable("screen.kuronami_quest_tree.party.no_invites")
                    : Component.translatable("screen.kuronami_quest_tree.party.no_team");
            Ui.text(g, font, empty, px + 12, listTop + 6, Ui.DIM);
        }
        for (int i = 0; i < shown; i++) {
            Row row = rows.get(i + scroll);
            int ry = listTop + i * ROW_H;
            boolean hover = row.run() != null && mouseX >= px + 6 && mouseX < px + pw - 6
                    && mouseY >= ry && mouseY < ry + ROW_H - 2;
            if (hover) {
                Ui.surface(g, px + 6, ry, pw - 12, ROW_H - 2, Ui.R2, Ui.OVERLAY_1);
            }
            int tx = px + 14;
            if (!picking && team.inTeam()) {
                Tiles.icon(g, "dot", tx, ry + 7, 6, row.online() ? Tiles.GREEN_TEXT : Ui.DIM);
                tx += 10;
            }
            if (row.invite()) {
                Ui.text(g, font, font.plainSubstrByWidth(row.name().getString(), Ui.textRoom(pw - 130)), tx, ry + 2, Ui.TEXT);
                Ui.text(g, font, Component.translatable("screen.kuronami_quest_tree.party.invited_by", row.from()),
                        tx, ry + 12, Ui.DIM);
                int bw = 46;
                int dx = px + pw - 12 - bw;
                button(g, Component.translatable("screen.kuronami_quest_tree.party.decline"), dx, ry + 2, bw, mouseX, mouseY,
                        () -> {
                            ClientProgress.send("party_decline", "", -1);
                            ProgressFeedback.click();
                        });
                button(g, Component.translatable("screen.kuronami_quest_tree.party.accept"), dx - bw - 6, ry + 2, bw,
                        mouseX, mouseY, () -> {
                            ClientProgress.send("party_accept", "", -1);
                            ProgressFeedback.click();
                        });
            } else {
                Ui.text(g, font, font.plainSubstrByWidth(row.name().getString(), Ui.textRoom(pw - (row.run() != null ? 120 : 40))),
                        tx, ry + 6, Ui.TEXT);
                int right = px + pw - 12;
                if (row.owner()) {
                    Component owner = Component.translatable("screen.kuronami_quest_tree.party.owner");
                    Ui.textRight(g, font, owner, right, ry + 6, Tiles.GOLD);
                    right -= Ui.textWidth(font, owner) + 8;
                }
                if (row.self()) {
                    Component you = Component.translatable("screen.kuronami_quest_tree.party.you");
                    Ui.textRight(g, font, you, right, ry + 6, Ui.DIM);
                    right -= Ui.textWidth(font, you) + 8;
                }
                if (row.kick() && row.run() != null) {
                    button(g, Component.translatable("screen.kuronami_quest_tree.party.kick"), right - 40, ry + 2, 40,
                            mouseX, mouseY, row.run());
                } else if (row.run() != null) {
                    hits.add(new Hit(px + 6, ry, pw - 12, ROW_H - 2, row.run()));
                }
            }
        }

        int ay = py + ph - 24;
        if (picking) {
            button(g, Component.translatable("screen.kuronami_quest_tree.party.back"), px + 12, ay, 70, mouseX, mouseY,
                    () -> picking = false);
        } else if (team.ftbManaged()) {
            // FTB Teams has no command and its screen message cannot be linked without its own mappings: roster only
        } else if (team.inTeam()) {
            button(g, Component.translatable("screen.kuronami_quest_tree.party.invite"), px + 12, ay, 60, mouseX, mouseY,
                    () -> picking = true);
            button(g, Component.translatable("screen.kuronami_quest_tree.party.leave"), px + 78, ay, 60, mouseX, mouseY,
                    () -> {
                        ClientProgress.send("party_leave", "", -1);
                        ProgressFeedback.click();
                    });
            if (team.isOwner()) {
                button(g, Component.translatable("screen.kuronami_quest_tree.party.disband"), px + 144, ay, 70, mouseX, mouseY,
                        () -> {
                            ClientProgress.send("party_disband", "", -1);
                            ProgressFeedback.click();
                        });
            }
        } else {
            button(g, Component.translatable("screen.kuronami_quest_tree.party.create"), px + 12, ay, 100, mouseX, mouseY,
                    () -> {
                        ClientProgress.send("party_create", "", -1);
                        ProgressFeedback.click();
                    });
        }
    }

    private List<Pick> picks(TeamView team) {
        List<Pick> out = new ArrayList<>();
        var connection = minecraft == null ? null : minecraft.getConnection();
        if (connection == null) {
            return out;
        }
        for (PlayerInfo info : connection.getOnlinePlayers()) {
            String id = info.getProfile().getId().toString();
            if (team.member(id) != null || (minecraft.player != null && id.equals(minecraft.player.getUUID().toString()))) {
                continue;
            }
            out.add(new Pick(id, info.getProfile().getName()));
        }
        out.sort(Comparator.comparing(Pick::name, String.CASE_INSENSITIVE_ORDER));
        return out;
    }

    private void button(GuiGraphics g, Component label, int x, int y, int w, int mouseX, int mouseY, Runnable run) {
        boolean hover = mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + 20;
        Ui.surface(g, x, y, w, 20, Ui.R2, hover ? Ui.OVERLAY_2 : Ui.OVERLAY_1);
        Ui.outline(g, x, y, w, 20, Ui.R2, Ui.HAIRLINE);
        Ui.textCenter(g, font, label, x + w / 2, y + 6, hover ? Ui.TEXT : Ui.MUTED);
        hits.add(new Hit(x, y, w, 20, run));
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (button != 0) {
            return super.mouseClicked(mx, my, button);
        }
        for (int i = hits.size() - 1; i >= 0; i--) {
            if (hits.get(i).hit(mx, my)) {
                hits.get(i).run().run();
                return true;
            }
        }
        return super.mouseClicked(mx, my, button);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double dx, double dy) {
        TeamView team = ClientTeam.view();
        int count = picking ? picks(team).size() : team.inTeam() ? team.members().size()
                : team.activeInvites(System.currentTimeMillis()).size();
        int maxRows = Math.max(1, (height - 16 - 92) / ROW_H);
        if (count > maxRows) {
            scroll = Math.max(0, Math.min(scroll + (dy < 0 ? 1 : -1), count - maxRows));
            return true;
        }
        return super.mouseScrolled(mx, my, dx, dy);
    }

    @Override
    public boolean keyPressed(int key, int scan, int modifiers) {
        if (key == org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE) {
            if (picking) {
                picking = false;
                return true;
            }
            onClose();
            return true;
        }
        return super.keyPressed(key, scan, modifiers);
    }

    @Override
    public void onClose() {
        if (minecraft != null) {
            minecraft.setScreen(parent);
        }
    }

    private record Pick(String id, String name) {
    }

    private record Row(Component name, String id, String from, boolean online, boolean owner, boolean self,
                       boolean kick, boolean invite, Runnable run) {
        static Row player(String name, String id, boolean online, boolean owner, boolean self, boolean kick, Runnable run) {
            return new Row(Component.literal(name), id, "", online, owner, self, kick, false, run);
        }

        static Row invite(Component party, String from) {
            return new Row(party, "", from == null ? "" : from, false, false, false, false, true, null);
        }
    }
}

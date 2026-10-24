# Gallery plan (CurseForge primary, Modrinth the same set)

Source of the existing shots: the NeoForge dev tour (`:neoforge:clientTest`, world `ppworld`) writing to
`neoforge/run/screenshots/`, run 2026-10-23 16:57-16:58. The Fabric set in `fabric/runs/client/screenshots/` is
older (2026-10-23 13:57) and lacks `questtree_63_hud_notice.png` and `questtree_51a_face_west.png`; use the NeoForge
files for the gallery.

The two newest changes (the notice card above the hotbar, and the recipe-viewer cards) were edited after that run
(`ProgressHud.java` 17:18, `QuestViewerJeiCategory.java`/`CrateViewerJeiCategory.java` 17:17), so every shot that
contains them is stale and must be retaken before upload. The other shots are unaffected by those edits.

Rules kept: one featured image only, 2:1; the rest are real in-game screens, no logo, no mock-ups. All images use
English UI text and must not show `aa_devtour` ids, debug labels or the dev world's placeholder names.

## 1. Featured: the whole book on one map

- **Shows:** a chapter as a connected tree, with the chapter rail at the side and a few nodes in different states.
- **Must be legible:** at least eight node titles, the connector lines and their direction, the done/open/locked
  difference, and the chapter list.
- **Existing:** `questtree_05_big_chapter.png` (fallback `questtree_01_first_open.png`).
- **Missing:** only the 2:1 crop. Recapture with the dev tour only if the crop cuts node titles or drops the rail
  (open the big chapter, then `tourZoom` twice for a denser view).

## 2. One quest open: tasks, rewards, state

- **Shows:** the detail panel of a selected quest next to the map.
- **Must be legible:** quest title, description, each task with its have/need count, the reward row, and the quest's
  state (claimable or in progress).
- **Existing:** `questtree_02_detail.png`.
- **Missing:** nothing for the gallery; retake only if the selected quest's tasks are already all complete, which
  hides the counts.

## 3. Skill chapter

- **Shows:** a skill tree with point costs and ranks instead of quest tasks.
- **Must be legible:** the points counter, at least one node cost, the locked/unlocked difference, and connector
  lines.
- **Existing:** `questtree_06_skills.png` (`questtree_07_skills_take.png` shows the spend/claim step).
- **Missing:** nothing.

## 4. Quest and crate cards in JEI/REI/EMI

- **Shows:** the recipe viewer open on the Quests category for one item, with a quest card; and the Loot crates
  category on its own card.
- **Must be legible:** quest name, chapter, state text and its mark, the `Needs:` (or `Rewards:`) line, the item
  slot, and for a crate its table id, `N possible rewards` and the reward grid. A long title must visibly end in an
  ellipsis inside the card, and no line may touch the card edge. The card's own panel must read as the quest screen's
  panel, with no JEI/REI frame around it.
- **Existing:** `questtree_48_viewer_uses.png` (quest cards) and `questtree_50_viewer_crates.png` (crate card).
- **Missing:** both are stale (the final card-frame and label edits landed after the run). Retake with the dev tour
  (`et + 217` for the quest category, `qt + 18` for the crate category) and pick a quest whose name is long enough to
  prove the ellipsis. Use one of the two as the gallery image; the other can be an extra image if a seventh is wanted.

## 5. Progress over the world: tracker and notice

- **Shows:** the game screen with the pinned-quest tracker at the side and the one-line notice above the hotbar.
- **Must be legible:** on the notice, the quest title, the task name and the have/need count (or held count); on the
  tracker, at least two rows with item icons and counts. The notice must sit above the hotbar and not touch the action
  bar text.
- **Existing:** `questtree_63_hud_notice.png` (new tour step), `questtree_08_hud_notice.png` (natural in-play notice)
  and `questtree_10_tracker.png` (tracker alone).
- **Missing:** `63` and `08` predate the card restyle of `ProgressHud`, so retake both (tour step at `qt + 174`); use
  `10_tracker` only if a clean tracker row is needed. Confirm the tracker is fully on screen at the current scale.

## 6. In-game authoring

- **Shows:** edit mode on the same map, with the node palette and a node being edited.
- **Must be legible:** that this is the same screen with edit controls, a task/reward field, and the export action.
- **Existing:** `questtree_29_editor_map.png`, `questtree_30_node_edit.png`.
- **Missing:** nothing for the gallery; the editor was not part of the newest edits. Prefer `30_node_edit` if the
  palette alone leaves the editing work unclear.

Alternates if a slot is dropped: `questtree_34_party.png` (team/party screen) for team progress, or
`questtree_51_task_screens.png` (quest and barrier blocks in the world). Do not use `questtree_47_inventory_button.png`
alone as a gallery image; the item is already described in the body.

## Status 2026-10-24 17:13 (NeoForge dev tour re-run at HEAD `9d9a7b2` + `14f17cb`)

All 63 shots were retaken. What the re-run showed, against this plan's own rules:

- The map shots (`01`, `05`, `22`, `23`) are taken at a zoom where node titles are not drawn: the featured image shows
  icons and connectors only, and the hovered node's title appears in a tooltip. A featured image needs a closer zoom on
  a chapter whose nodes have titles (the dev fixture chapter, 11 nodes, or a purpose-built showcase chapter).
- The ATM chapters (`23`, `24`, `05`) draw missing-item icons (red slash) because the dev client has none of that
  pack's mods. They cannot be used in the gallery.
- `48_viewer_uses` shows the chapter line as the pack's raw Japanese title ("はじめの一歩") while the quest titles are
  English, and the dev chat lines ("Summoned new Villager", "Teleported Dev to ...") are visible. Both break the
  English-only / no-debug rule. `50_viewer_crates` and `63_hud_notice` also carry the dev chat.
- `_docs/gallery/01_map_2x1.png` is the ATM chapter above: not usable as is.

Next pass (not done): a showcase chapter with English titles only, a zoom where titles render, the chat cleared before
each shot (`Gui.getChat().clearMessages(true)` in `DevTour.shot`), then re-crop `_docs/gallery/`.

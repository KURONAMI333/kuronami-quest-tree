# Restyling Kuronami's Quest Tree with a resource pack

Everything the progression screen draws comes from files a resource pack can replace. Nothing visual is fixed in code.

## Colours: `assets/kuronami_quest_tree/gui/palette.json`

One JSON object of colour names to `"#RRGGBB"` or `"#AARRGGBB"`. A pack only needs the keys it changes; the rest keep
the mod's values. The shipped file lists every key with its default. The screen reads the palette when the book
arrives and every time it opens, so after `F3 + T` reopen the screen to see a change.

| Keys | What they colour |
|---|---|
| `text`, `text_muted`, `text_dim`, `text_bright`, `text_error` | Text, from main to quietest; the selected row; a search with no match |
| `ground`, `panel`, `panel_translucent`, `rail_closed`, `floating` | The map behind the tree; the chapter list and detail panel; the closed chapter list; tooltips |
| `accent` | The selected chapter's mark and the button that spends skill points |
| `badge`, `badge_text` | Count badges (rewards waiting, points to spend) |
| `overlay_hover`, `overlay_selected`, `overlay_pressed`, `hairline`, `divider`, `focus`, `disabled` | Rows and buttons under the pointer or selected, panel edges, separators, the focused search field, disabled buttons |
| `tile_locked`, `tile_open`, `tile_white`, `progress`, `progress_dark`, `done`, `done_dark`, `done_bright`, `done_settled`, `done_text`, `reward`, `excluded`, `ink` | Quest and skill tiles by state: not yet, can start, in progress, done, rewards waiting, shut by an exclusive choice; dark text on light tiles. `progress_surface` (not in the shipped file) sets the waterline of a quest in progress; without it the line is a lighter `progress` |
| `line_open`, `line_closed` | Dependency lines (walked lines use `done`); every line has a dark edge in the `ground` colour |
| `line_requires`, `line_unlocks` | Around the quest under the pointer (or the selected one): the lines into it (what it needs) and out of it (what it opens); the other lines fade |
| `selection`, `optional_outline`, `mark`, `flash`, `scrollbar`, `scrollbar_hover`, `minimap_view`, `minimap_open` | Selection ring, optional quests' dashed outline, small marks on tiles, state-change flash, scrollbars, minimap |
| `glass_regular`, `glass_clear`, `glass_tint` | Liquid Glass: the tone the blurred picture behind the chapter list, details, search, zoom buttons and tooltips is pulled towards (alpha = how far); the minimap's dimming layer (alpha = how much) and its tint. With glass turned off (`"glass": false` in `config/kuronami_quest_tree-client.json`) the flat `panel_translucent`, `rail_closed` and `floating` are used instead |
| `hud_back`, `hud_text`, `hud_muted`, `toast_track` | The pinned-quest overlay over the world, and the experience toast's bar |

## Shapes and icons: `assets/kuronami_quest_tree/textures/gui/`

- `shape/<name>.png` and `shape/<name>_ring.png`: white masks for tiles (`square`, `rsquare`, `circle`, `diamond`,
  `octagon`, `pentagon`, `gear`, `heart`) and their outlines. They are tinted by the palette, so draw them white,
  square, at least 16 pixels (128 is used). The tile's rim, its light and shade bands, the box its item icon fits in
  and where lines meet its edge are all worked out from the shape's own picture, so a replaced shape gets all of
  them without anything else to supply.
  `rsquare.png` is also nine-sliced for every rounded panel (corners take 23 of its 128 pixels); `rsquare_edge.png` is
  the 1-pixel panel edge (corners 12 of 32).
- `glass_mask.png`: what lies outside the glass minimap's rounded corners, opaque outside and clear inside (corners 23
  of 128, nine-sliced).
- `icon/<name>.png`: small white glyphs (`head` is the line arrowhead, pointing right with its tip at the right edge;
  `fold` the group fold mark in the chapter list, drawn pointing right and turned down while the group is open;
  `dot` the badge, `pin`, `lock`, `tick`, `cross`, `link`, `plus`, `minus`, `center`).

## Sounds

`assets/kuronami_quest_tree/sounds.json` names every interface sound (`ui.claim`, `ui.unlock`, `ui.open`, …); a pack
can point them at its own files.

# Restyling Kuronami's Quest Tree with a resource pack

Everything the progression screen draws comes from files a resource pack can replace. Nothing visual is fixed in code.

Quest text is different: a pack's titles and descriptions live in its data pack under `data/<namespace>/progression/lang/`, not in a resource pack. See `DATA_MODEL.md`, "Translating a quest book".

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
| `hud_back`, `hud_text`, `hud_muted`, `toast_track`, `toast_fill`, `toast_edge` | The pinned-quest overlay over the world; the experience toast's bar, its body and its 1-pixel frame (the advancement toast's own sprite is fixed at a 2-pixel corner, so the mod draws the frame from these) |
| `hud_tracker`, `hud_tracker_edge`, `hud_tracker_edge_bottom` | The tracker card's frame. The defaults match Jade's dark tooltip at its default 0.7 alpha: `#B1131313` inside, `#B1383838` at the top and sides, `#B1242424` at the bottom |

### Per-chapter and per-quest colours: `assets/kuronami_quest_tree/gui/theme.json`

FTB Quests lets a pack restyle one chapter or one quest through theme selectors; the same idea is here as a small
file beside the palette. It can override the tile colours for one chapter, one quest, or both:

```json
{
  "chapters": {
    "mypack:magic": { "done_settled": "#FF7A4FC8", "progress": "#FF9B6BE0" },
    "mypack:deep/ruins": { "tile_locked": "#FF4A4A55" }
  },
  "quests": {
    "mypack:magic/the_first_spell": { "done_settled": "#FFFF9B4F", "done_bright": "#FFFFC46B" }
  }
}
```

Chapter keys are chapter ids (`namespace:path`), quest keys are node keys (`chapter id/node id`), both as written in
the chapter JSON. The keys inside are palette names: the done tile's `done_settled` and its rewards-waiting
`done_bright`, the open and in-progress tile's `tile_open`, a skill's affordable `tile_white`, not-yet `tile_locked`,
the progress water's `progress`, and its top line's `progress_surface`; others are ignored for now. Values are
`"#RRGGBB"` or `"#AARRGGBB"`.

Resolution is the palette default, then the quest's chapter, then the quest itself, so a chapter recolours all of its
quests and a quest entry wins over its chapter's. A key no entry sets keeps the palette's colour, so a pack that only
wants one chapter coloured does not need to copy the palette. `progress_surface` follows the resolved `progress`
unless it is set too. High contrast (`ClientConfig.highContrast()`) ignores these overrides, as it does the palette,
because it is a readability switch.

The file is read when the book arrives and every time the screen opens, like the palette, so after `F3 + T` reopen the
screen. A missing file means no overrides. Chapter icons, sounds and shapes stay in their own files above; this file
only changes tile colours.

### Corner radius: `corner_radius`

`corner_radius` is the palette's one non-colour key: a whole number of GUI pixels, default `2`, and `0` draws square
corners. Every surface and edge on the screen follows it — panels, the chapter list, buttons, rows, fields, chips,
count badges, scrollbars, the HUD tracker and the toasts. The white masks keep their 23-pixel (fill) and 12-pixel
(edge) corners and are nine-sliced to the requested radius, so any value from 0 up works. Quest tile shapes and the
belt's chevrons are content, not chrome, and keep the shape drawn in their own pictures.

## Ground and tile face: `assets/kuronami_quest_tree/textures/gui/`

Two optional pictures carry the two largest flat surfaces, and both are tinted like everything else. A pack without
them keeps the screen exactly as it is: the palette's `ground` fill and each tile's flat state colour.

- `ground.png`: the map background, drawn in place of the flat `ground` colour. One picture pixel is one GUI pixel; the
  picture is repeated from the top-left corner across the whole screen, and tinted by `ground`, so draw it white or grey
  and let the palette (or high contrast) choose the hue. The picture has to meet itself at its edges, because it tiles.
- `patch/tile.png`: the face of a quest or skill tile, inside the shape's rim. It is scaled once to fit each tile and
  tinted by that tile's state colour — the palette's `tile_locked`, `tile_open`, `done_settled`, `done_bright` or
  `tile_white`, including any `gui/theme.json` override — so draw it white or grey and let the state choose the hue.
  Its transparency lets the rim show through. It is clipped to the shape's own face, so every shape works, and the
  progress water stays flat: only the face above it takes the picture.

Both are read when the book arrives and every time the screen opens, like the palette, so after `F3 + T` reopen the
screen. Like the shapes, they are pictures, not colours: high contrast still ignores the palette and theme overrides,
not these files.

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
- `line/belt.png`: one tile of the conveyor belt the lines are drawn with, 32 pixels along the line and 64 across it. It
  is repeated along each line and moves towards the later quest (only around the node under the pointer or selected):
  one chevron pointing right whose arms reach both long edges, over a faint band, with its shading in the picture
  itself, white, tinted by the line's colour. One tile covers the belt's full width across and half of it along the
  line, so the arms meet the edges and the period is half the belt width. It repeats left to right, so its left and
  right edges must meet.
- 章アイコンは、章 JSON の `icon` にテクスチャを指すと `renderItem` の代わりに 16×16 で描く。
  `"icon": "mypack:textures/gui/chapter.png"`、`"icon": "#mypack:gui/chapter"`、または
  `{"icon": {"type": "texture", "texture": "mypack:gui/chapter"}}`。名前空間を省くとパックの名前空間。
  テクスチャはファイルの大きさにかかわらず 16×16 の枠いっぱいに縮小して描く（16×16 でなくても切れない）。
- `icon/<name>.png`: small white glyphs (`fold` the group fold mark in the chapter list, drawn pointing right and turned down while the group is open;
  `dot` the badge, `search` the search at the top of the chapter list, `pin`, `lock`, `tick`, `cross`, `link`, `plus`, `minus`, `center`).

## Sounds

`assets/kuronami_quest_tree/sounds.json` names every interface sound (`ui.claim`, `ui.unlock`, `ui.open`, …); a pack
can point them at its own files.

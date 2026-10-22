# Writing quest descriptions

A quest description goes in the node JSON's `description` or in the language file key `progression.<namespace>.<chapter>.<node>.desc`. Lines wrap to the screen width, so there is no need to line-break by hand.

## Lines and colours

- `\n` starts a new line. Imported FTB Quests descriptions (an array of lines) are also treated as one line each.
- `{@pagebreak}` is a paragraph break, with space before and after.
- `&0` to `&f` set the colour; `&l` `&o` `&n` `&m` `&k` `&r` set formatting. Write `\&` for a literal `&`.
- Colour and formatting carry over to wrapped lines. A word longer than the width is broken mid-word.

```json
"description": "&6The journey begins\n&7Before leaving the village, bring an &eiron sword&7."
```

## Links

The form is `{kind:target display text}`. Without display text, the target is shown as-is. Links are drawn in the accent colour and open when clicked.

| Form | What a click does |
|---|---|
| `{open_url:https://example.com Download page}` | Shows a confirmation, then opens the browser (`http` / `https` only) |
| `{quest:two/iron The Iron Age}` | Opens that quest. A bare node name is looked up in the same chapter |
| `{recipe:minecraft:iron_ingot Iron ingot}` | Opens that item's recipes in the recipe viewer (only when JEI / REI / EMI is installed) |

The target cannot contain spaces; the display text can.

```json
"description": "Materials: {recipe:minecraft:iron_ingot Iron ingot}. Next: {quest:two/iron The Iron Age}."
```

## Inline items

`{item:<id>}` draws the item's icon in the sentence, and hovering it shows the vanilla item tooltip (name, line, mod). Click it to open its recipes when JEI / REI / EMI is installed.

```json
"description": "Bring {item:minecraft:diamond} and {item:minecraft:iron_ingot 4}."
```

- The id may leave out `minecraft:`; an id this pack does not have is shown as written, so a typo stays visible.
- A second word is the stack size (`4` or `x4`); it is shown as `x4` beside the icon and is only a label.
- The icon is 10px and wraps with the line; a line holding only an item is not a paragraph break.

## Images

```json
"description": "{image:example:textures/gui/map.png width:180 height:120}"
```

- The target must be a `<namespace>:<path>` texture path. `https://...`, file paths, paths starting with `/`, and paths containing capitals are not images and are shown as written.
- `width` and `height` are optional (default 64). The image is fitted to the screen width when drawn.
- Attributes other than `width` / `height` are ignored.

## JSON text components

JSON descriptions written by FTB Quests or Pufferfish's Skills are also read. A line is interpreted as a text component only when the **whole line** is `{...}` or `[...]`; that line's `clickEvent` (`open_url` / `change_page`) is treated like the links above. Plain text and JSON may be mixed in one description. When only part of a line is JSON, the line is shown as written.

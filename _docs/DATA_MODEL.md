---
name: progression-data-model
description: The JSON shape of a progression data pack. One model for quests and skills, and the target of the importers.
---

# Progression data model

A pack lives in `data/<namespace>/progression/` of a data pack. The server reads it and sends the result to clients. Display text lives in the pack's own language files (`data/<namespace>/progression/lang/<locale>.json`), not in the JSON below.

## One shape for quests and skills

| Quest Tree | FTB Quests | Pufferfish's Skills |
|---|---|---|
| chapter `chapter` | chapter | category |
| chapter group `group` | chapter group | — |
| node `node` | quest | skill (placement) + definition (contents) |
| requirements `requires` | dependencies + dependency_requirement + min_required_dependencies | connections (normal) + root |
| exclusivity `exclusive` | max_completable_dependents | connections (exclusive) + exclusive_root |
| tasks `tasks` | tasks | — |
| cost `cost` | — | cost, required_points, required_spent_points |
| rewards `rewards` | rewards + reward tables | rewards |
| points `points` (per chapter) | — | experience, level, exchange, spent_points_limit |
| decoration `decorations` | chapter images | background |

A node opens by finishing all of its tasks, by paying points, or by both. Quests and skills can also share one chapter.

## Files

```
data/<ns>/progression/
  settings.json                 pack-wide defaults (automatic rewards, team rewards, lock hint, emergency items and more)
  groups.json                   chapter groups (order and icon)
  chapters/<chapter>.json       one chapter (nodes, decoration, points)
  reward_tables/<table>.json    reward tables (random, choice, all, loot crate)
  patches/<anything>.json       replace only part of another data pack's chapter or node
  restrictions.json             item and block use gated by conditions (merged across namespaces)
```

`settings.json` holds pack-wide settings and is read from the first namespace that provides one. A missing key takes its default.

| Key | Type | Default | Meaning |
|---|---|---|---|
| `emergency.items` | array | empty | Items given by the emergency button on the progression screen. Either `"minecraft:bread"` or `{"item":"minecraft:bread","count":3}`. |
| `emergency.cooldown_seconds` | integer | 3600 | Seconds between emergency uses. `0` means no cooldown. The wait is saved per player and survives leaving and rejoining a team. |
| `wiki_url` / `guide_url` | string | none | External links shown in the settings screen. Only `http` and `https` are accepted. |
| `show_lock_icons` | boolean | `true` | Draw the padlock badge on a node whose chapter is locked. |
| `lock_message` | string | none | The book's own refusal text, shown instead of the built-in "locked" message. |
| `hide_excluded_quests` | boolean | `false` | Keep quests excluded by a dependency out of the recipe-viewer list, not only out of the book. |
| `disable_gui` | boolean | `false` | The book refuses to open. |
| `grid_scale` | number | `0.5` | Editor snapping step; FTB Quests' `1` is a coarse grid. |
| `pause_game` | boolean | `false` | A single-player world pauses while the book is open. |
| `detection_delay` | integer | `20` | Ticks a detector waits after it is triggered before accepting the player. |
| `default_reward_team` | boolean | `false` | Team-wide rewards for rewards that do not say `team` themselves. |
| `default_consume_items` | boolean | `false` | Item tasks consume their items unless the task says otherwise. |
| `default_autoclaim_rewards` | `default` / `enabled` / `disabled` | `default` | Claim rewards as soon as the quest completes. `default` leaves each reward's own `auto` in charge. |
| `suppress_all_autoclaiming` | boolean | `false` | Turns automatic claiming off for every reward. |
| `default_quest_shape` | string | none | Shape for nodes that set none and have no role that decides one. |
| `default_quest_disable_jei` | boolean | `false` | Hide quests from recipe viewers unless a quest sets `hide_from_recipe_viewer` itself. |
| `preset` / `presets` | string / object | none | Named visual presets (`{"shape": "...", "size": ...}`) and the file-wide default. A node's `preset` wins over its chapter's, which wins over this one. |

For compatibility, `items` and `cooldown_seconds` directly under the root are also read. Whether the quest book drops on death is not a pack setting; it is `drop_book_on_death` in `config/kuronami_quest_tree-server.json`.

`patches` replaces part of another data pack's chapter or node.

## Translations

Pack text is stored in the data pack's own language files, separate from the mod's UI language files (`assets/kuronami_quest_tree/lang/<locale>.json`).

- Location: `data/<namespace>/progression/lang/<locale>.json`. One flat JSON object per locale; the importers write here too.
- Locale names match Minecraft: `en_us`, `ja_jp`, `de_de`, `fr_fr`, `es_es`, `zh_cn`, `ru_ru`, `pt_br`, `ko_kr`. A player receives the selected locale plus `en_us`; a missing key falls back to `en_us`.
- Key shapes (`<ns>` is the data pack namespace, `<chapter>` is the file name under `chapters/`):
  - chapter: `progression.<ns>.<chapter>.title`
  - chapter group: `progression.<ns>.group.<group>.title`
  - node: `progression.<ns>.<chapter>.<node>.title`, `.subtitle`, `.desc`
  - task title override: `<text>.title`, where `<text>` is the task's `text` value
  - `visibility.lock_hint`: a key shows its translation; any other string is shown as written
- Values are strings. `\n` starts a new page. FTB colour codes such as `&a` work. Values are displayed directly, not passed through translation, so there are no `%s` placeholders.
- To add a locale, copy `en_us.json`, translate the values, and change nothing else except the file name.
- A resource pack's `assets/<namespace>/lang/<locale>.json` is also read through vanilla's language lookup, but only chapter titles, group titles and `lock_hint` use it as a fallback. Subtitles and descriptions only read the data pack's language file, so put body text in the data pack.
- Untranslated strings: a `title`, `subtitle` or `description` written directly on a chapter or node wins over a language key. Decoration `hover` is a list of strings shown as written; it does not look up language keys.

## Chapters

```json
{
  "icon": {"id": "minecraft:iron_ingot"},
  "group": "main",
  "order": 3,
  "points": null,
  "background": {"texture": "ftb:textures/quests/backgrounds/mining.png", "fit": "fill_height"},
  "layout": "free",
  "decorations": [{"image": "ftb:textures/quests/ars/scribetable.png", "x": 8, "y": -2.5, "w": 4.2, "h": 3, "rotation": 0, "order": 0,
                   "visible_after": "pick", "click": {"type": "node", "target": "pick"}, "hover": "progression.<ns>.<chapter>.deco.1"}],
  "defaults": {"shape": "square", "size": 1, "detail_width": null, "hide_lines": false, "consume_items": false, "repeatable": false},
  "visibility": {"always_hidden": false, "lock_hint": null},
  "autofocus": "pick",
  "preset": null,
  "nodes": {"pick": {"...": "..."}}
}
```

- The title and subtitle come from `progression.<ns>.<chapter>.title` and `.subtitle`. A string written directly in the data is also accepted, but is then not translated.
- A chapter with `points` is a skill chapter.
- `visibility.lock_hint` is the text shown when a chapter closed by, for example, `points.unlocked_by_default: false` appears greyed out in the chapter list. It is either a language key or a literal string; when omitted it says "locked". `visibility.always_hidden` keeps a chapter out of the list entirely.
- `defaults` supplies node defaults. `hide_lines`, `consume_items`, `repeatable` and `detail_width` are the chapter-wide forms of the matching node fields.
- `autofocus` is the id of the node the book opens on when the chapter is first opened.
- `preset` names a file-wide visual preset; it applies to this chapter's nodes that name none of their own.

## Nodes

```json
{
  "x": 2, "y": 0, "icon": {"id": "minecraft:iron_pickaxe"},
  "shape": "square", "size": 1, "icon_scale": 1, "preset": null, "guide_page": null, "milestone": false,
  "requires": {"nodes": ["ingot"], "mode": "all", "min": null,
               "points": 0, "spent_points": 0, "exclusions": 1, "mods": []},
  "exclusive": {"group": "hammer", "max": 1},
  "cost": 0,
  "tasks": [{"type": "item", "item": {"id": "minecraft:iron_ingot"}, "count": 3, "consume": false}],
  "rewards": [{"type": "item", "item": {"id": "minecraft:iron_ingot", "count": 4}}],
  "optional": false,
  "tasks_required": null,
  "ranks": null,
  "toggleable": false,
  "repeat": {"cooldown": 0, "max": null},
  "sequential_tasks": false,
  "visibility": {"hidden_until": "deps_visible", "requires": null, "hide_details_until_startable": false, "hide_text_until_complete": false, "invisible_until_tasks": null, "hide_lock_icon": false},
  "hide_from_recipe_viewer": null,
  "lines": {"hide_to_deps": false, "hide_to_dependents": false},
  "toast": true,
  "links": [],
  "detail_width": null
}
```

- A node without a title uses the display name of its first task. This matches FTB Quests, and DarkRPG depends on it for 1,777 of its 1,892 quests.
- Title, subtitle and description are read from `progression.<ns>.<chapter>.<node>.title` / `.subtitle` / `.desc`. The description is a list of pages and may use `&a` colour codes, images (`{image:...}`), page breaks and links. ATM10 uses 1,552 lines of colour codes and 63 page breaks.
- `icon` takes an item (`{"id": "minecraft:iron_pickaxe"}`), a texture (`{"type": "texture", "texture": "ftb:textures/gui/x.png"}`) or an entity face (`{"type": "entity", "entity": "minecraft:zombie"}`). The importer turns FTB Quests' custom icons into the last two.
- `icon_scale` grows or shrinks the icon printed in the tile (default `1`, clamped to 0.25–4); `detail_width` is the detail panel's width in GUI pixels, overriding the chapter default.
- `preset` names a visual preset from `settings.json`. When the preset or the pack's `default_quest_shape` sets a shape or size, it wins over the role-based shape.
- `guide_page` opens a guide book page from the tile: a URL opens the browser, a leading `/` runs a command, and anything else asks Patchouli to open that entry. Without Patchouli the tile reports that the guide is missing.
- `visibility.hide_lock_icon` keeps the padlock badge off this node. `hide_from_recipe_viewer` is `true` / `false` / unset; unset follows `default_quest_disable_jei`.
- `shape` accepts the eleven FTB Quests shapes: `square`, `rsquare`, `circle`, `diamond`, `hexagon`, `octagon`, `pentagon`, `gear`, `heart`, `none`, and the default. It is the tile outline; the fill stays inside that outline. `hexagon` is drawn as a square.
- When neither the node nor the chapter `defaults` sets `shape`, the node's role chooses it: `milestone: true` is a pentagon, a node with `exclusive` (or `exclusive_with`) is a diamond, a repeatable quest is a circle, and everything else is a square. If several apply, milestone wins, then exclusivity, then repetition. A milestone is drawn 1.5 times larger unless `size` is set. Chapters imported from FTB Quests always write `defaults.shape` so the original shape is preserved.
- `mode` is `all`, `any`, `all_started` or `any_started`.
- `exclusive` allows at most `max` completed nodes from the same `group`.
- `tasks_required` asks how many tasks are enough to open the node. `null` means all of them.
- A node with `repeat` starts over after each claim. `cooldown` is the wait in seconds before it can be repeated, and `max` is the total number of times it may be completed (omitted means unlimited). A repeatable quest whose action cannot be repeated (kill, checkmark, delivery) does not repeat without a `cooldown`. Item tasks in a repeatable quest are delivered rather than merely held. Once a repeatable quest has been completed at least once, requirements and exclusivity treat it as completed.
- `ranks` takes the same node in stages: `[{"cost": 1, "rewards": [...]}, ...]`.
- `visibility.requires` is a separate "becomes visible when" condition; it is not the same as the opening requirement `requires`.
- A chapter can also have `requires` (for example a Pack Stages stage). Players who do not meet it do not see the chapter at all.
- `shared: false` keeps this node's completion, task counters, repeat count and cooldown per player instead of per team. Rewards are per player anyway, except a reward with `team`, which is paid once per team. When omitted, everything is team-shared as before.

## Tasks

`tasks[].type` can be:

`item` (`count`, `consume`, `match_components`, `only_from_crafting`), `checkmark`, `kill`, `advancement`, `structure`, `biome`, `dimension`, `location`, `observation`, `stat`, `xp`, `fluid`, `energy`, `break`, `place_block`, `stage`, `custom`, `craft`, `use_item`, `right_click_block`, `interact_entity`, `tame`, `breed`, `ride`, `pickup`, `interaction`, `reach_day`.

Every task also accepts `optional`, `icon`, `title` and `conditions`.

Action tasks replace FTB Quests' Task Screen by counting the player's own actions. `craft` counts completed items taken out (use `item` for the result and `recipe` to narrow the recipe); `use_item` counts uses; `right_click_block` counts right-clicks on blocks; `interact_entity` counts right-clicks on mobs; `tame`, `breed`, `ride` and `pickup` count taming, breeding, riding and picking up. `interaction` is the generic form and takes one `action` (`craft`, `use_item`, `right_click_block`, `interact_entity`, `tame`, `breed`, `ride`, `pickup`); the FTB Quests importer uses it. `use_item`, `right_click_block` and `interact_entity` can also narrow by `item`; `right_click_block` can narrow by `block`, and `interact_entity` by `entity`.

`kill` can narrow by `entity`, `entity_tag`, `name` (exact display name) and `nbt` (partial SNBT match).

`fluid` takes `fluid` (an id or `#tag`) and `amount` (mB, default 1000, one bucket). It counts the total matching liquid in the inventory, with a bucket counting as 1000. `energy` takes `amount` (FE). The task screen's energy input counts FE poured into it; the inventory check counts the single stack holding the most energy, not the total. NeoForge always has an energy API; on Fabric the task needs the optional Team Reborn Energy mod (FTB Quests bundles it), and without it an `energy` task stays at 0 and the pack validator reports a `WARNING`.

An `energy` task can be filled at a task screen (place one and bind it to the task), or completed by carrying the fullest single stack when a pack checks the inventory instead. A quest asking for millions of FE is satisfied by carrying a battery that can store that much.

`observation` takes `observe_type` (`block`, `block_tag`, `block_state`, `entity_type`, `entity_type_tag`), `target` and `ticks` (default 0, immediate). It counts consecutive ticks with the crosshair on the target and resets to 0 when the crosshair moves away. `break` takes `block` (id or `#tag`; empty means any block) and `count` (default 1) and counts blocks broken in both survival and creative, capped at `count`. `place_block` takes the same `block` and `count` and counts blocks placed by the player, capped at `count`. `reach_day` takes `day` (0 or more, default 0) and counts the world's day counter (`getDayTime / 24000`), so `/time set` can move it.

### Requirements and the item counter

`requires` also accepts Pufferfish's Skills' `required_*` fields. `points` is the chapter points that must remain at unlock (default 0); `spent_points` is the points that must already have been spent in that chapter (default 0); `exclusions` is how many exclusive competitors may be taken before this node closes (default 1); `mods` lists mod ids that must all be present (default empty). `nodes` is read with `mode` (`all`, `any`, `all_started`, `any_started`; default `all`) and `min` (default 0). `required_skills` maps to `nodes` / `min`, and 0 becomes `root`. While a requirement is unmet the node is `LOCKED`, but its data is not erased. `points` and `spent_points` are not re-applied to a node that already has at least one rank; this matches Pufferfish's Skills, whose state check returns an already-taken skill as unlocked first, so taking a rank does not lock the next rank.

Item counting:

- Without `consume`, the task shows the number currently held; the count falls when items are spent.
- Once `count` is reached, the task stays complete (as in FTB Quests). The display shows the number currently held, not the highest number ever held.
- Counting newly obtained items, or held plus crafted, is not supported: dropping and re-picking, chests, machines and recipes that can be run in both directions create loopholes that cannot be fully anticipated.
- Use `consume` (delivery) when a quest should require collecting in several batches.
- `only_from_crafting`: the inventory scan ignores the task, so merely holding the item does not count. A crafted result matching `item` counts the amount produced, up to `count`. This is FTB Quests' option of the same name.

## Rewards

`rewards[].type` can be:

`item` (`random_bonus`), `xp`, `xp_levels`, `table` (`mode`: `random`, `choice`, `all`, `loot`), `command` (`permission_level`, `silent`), `advancement`, `stage`, `toast`, `attribute`, `points`, `tag`, `scoreboard`, `custom`, `dummy`, `currency`.

Every reward also accepts `team`, `auto`, `exclude_from_claim_all`, `ignore_reward_blocking` (alias `ignore_blocking`), `conditions`, `icon` and `title`.

- `stage` grants a Pack Stages stage. With `"team": true` the stage is recorded on the team and also given to every online member through Pack Stages; `"remove": true` takes it back. `/quest_tree team_stage add|remove <players> <stage>` does the same from a command.
- `dummy` grants nothing and exists to show only an icon in the reward list (FTB Quests' dummy reward). An unknown arbitrary reward from an importer keeps its original kind in `original_type`.
- `currency` takes `amount` and pays through FTB Library's currency hook. With no economy mod registered, it logs once and pays nothing; the quest still completes.
- `command` runs `command` through the claiming player's command source at `permission_level` (default 2); `silent` is true unless it is set to `false`. At runtime the level is capped by `reward_command_permission_level` in `config/kuronami_quest_tree-server.json` (default 2; the highest is 4), so a pack's level 4 runs at level 2 unless the server owner raises the cap. In the in-game editor, adding a command reward, or changing its command fields or `permission_level`, needs permission level 4; ordinary rewards can be written at level 2.
- `trophy` (`{"type": "trophy", "trophy": "<ns>:<id>"}`) grants a Kuronami's Trophies trophy by running `trophy award @s <id>`. It is paid once per player, and the imprint is set when received. The screen shows the real trophy in the reward slot before it is claimed. If Kuronami's Trophies is absent, nothing happens.
- `icon` on any reward shows that icon in the reward list, including for rewards such as `command` that have nothing else to display.
- `points` adds to the chapter's points (`amount`, default 1). A non-number or a value of 0 or less is treated as 0 both when granting and when revoking; neither side is allowed to go negative alone. Without `chapter` the points go to the node's chapter; with it, to the chapter id (`<ns>:<chapter>`).

### Reward order and claims

A claim is recorded by the reward's position in `rewards`, not by its contents, so changing that list changes which reward is already claimed.

The in-game editor keeps the records in step. When a node's rewards are reordered, inserted or deleted, it matches the reward objects before and after, moves each claim to the new position of the same reward, and drops a claim whose reward was removed. Identical rewards are paired from the front in order. Pending rewards, reward rolls and skill records move with the same mapping.

A hand edit gets no such guarantee. Append a new reward at the end and do not reorder existing ones; a reorder or an insert before an existing reward can make players' claims shift.

### One-time and revocable rewards

`xp`, `xp_levels`, `item`, `table`, `advancement`, `command`, `currency` and `custom` are paid only once per team for the same rank, even after a reset and re-take. `custom` also fires an add-on event only once. The exception is a `command` with `lock_command`: its `command` runs again on every re-take, because a respec runs `lock_command` and the command must be run again to unlock.

The paid record is kept by the team; any team member's take counts once. A reward is recorded only when it was actually paid: unmet conditions, a failed payment and a declined `choice` table do not record it, so a later take can pay it.

A respec revokes only the rewards that are both revocable (`points`, `tag`, `attribute`, a `command` with `lock_command`) and present in the record, once each, then clears them from the record. One-off rewards are neither revoked nor removed from the record.

### Locking a chapter

Locking a chapter removes the effects but keeps the `points`, `attribute`, `tag` and `lock_command` records. Unlocking restores `attribute`, `tag` and `lock_command` rewards that are in the record to every member; rewards without a record are not restored. A locked chapter has already run every `lock_command` and removed every tag, so a reset or respec while it is locked only cleans the records and never repeats those effects.

The chapter's open or locked state belongs to the team. A lock or unlock changes it for every member, and a member joining a team where either side has the chapter closed joins with it closed.

## Reward tables

```json
{"rolls": 1, "empty_weight": 0, "entries": [{"weight": 10, "reward": {"type": "item", "item": {"id": "minecraft:diamond"}}}],
 "loot_crate": null}
```

A table pays its entries in order. An entry that was paid is marked in the player's saved table progress, so a retry after a later entry failed hands out only the entries still missing. When the whole table is through, the marks and the recorded roll are removed together. A `choice` table pays one entry and has nothing to mark.

## Conditions

Tasks and rewards accept `"conditions": [...]`. A task is counted, and a reward is paid, only when every condition is met. A reward whose conditions are unmet is skipped: it does not hold the node open as unclaimed. The node finishes and can repeat.

The five built-in conditions and kinds registered by other mods or KubeJS may be mixed in the same list:

```json
{"type": "stage", "stage": "chapter/one"}
{"type": "score", "objective": "points", "min": 10}
{"type": "item", "item": {"id": "minecraft:diamond", "count": 3}}
{"type": "advancement", "advancement": "minecraft:story/root", "criterion": null}
{"type": "party_size", "min": 2}
{"type": "example:enabled"}
```

`stage` checks a Pack Stages stage, including one granted to the team; `score` checks a scoreboard value (also spelled `scoreboard`); `item` checks the inventory; `advancement` checks an advancement; `party_size` checks the number of online party members.

The five built-in kinds may be written without a namespace (`item`) or with Quest Tree's namespace (`kuronami_quest_tree:item`); both are read as built in. Any other namespace is always looked up in the registration table, even when the id matches a built-in.

A registered kind is written as `<namespace>:<id>` and registered through the public API or KubeJS. Registered kinds are matched by the namespaced id, and their check is delegated to the handler. A kind that is in neither table counts as unmet, so a written gate fails closed. Validation reports an unregistered kind as `WARNING` and keeps the node.

Conditions are evaluated once, when the reward is granted. Revocation (respec, reset) and login re-granting follow the team's paid record and do not re-evaluate conditions. A reward that was not paid because its conditions were unmet has no record and is never touched by revocation or re-granting. Skill `points` rewards count toward respec checks and revocation only when they are in the record. A skill reward's conditions are checked against the player who took it, while its effects (`attribute`, `tag` and so on) apply to every team member. The record is shared by the team.

## Restrictions

`restrictions.json` stops an item or a block until the rule's conditions hold. It is read from every namespace and merged in resource order, so an add-on pack can add its own rules beside the base pack's.

```json
{"rules": [
  {"id": "iron_tools",
   "items": ["minecraft:iron_sword"],
   "item_tags": ["minecraft:swords"],
   "blocks": ["minecraft:iron_ore"],
   "block_tags": ["minecraft:ores"],
   "actions": ["use", "attack", "place", "break", "equip"],
   "message": "Finish the trial first",
   "conditions": [{"type": "stage", "stage": "trial/one"}]}
]}
```

- `id` is required and global across namespaces. A second namespace writing the same id is ignored with a `WARNING`; the first one wins.
- At least one of `items`, `item_tags`, `blocks`, `block_tags` is required. Tags are written as ordinary tag ids (`minecraft:swords`, no leading `#`).
- `actions` is any of `equip`, `use`, `attack`, `place`, `break`, `craft`. Omitted or null means all six; an empty list or an unknown name is reported (`WARNING`) and applies to nothing.
- `conditions` uses the same five built-in kinds and registered kinds as tasks and rewards. A rule with no conditions is reported (`WARNING`): it would keep its items shut forever.
- `message` is the reason shown in the action bar; it is shown as written (not translated). Without it the mod shows its own line. A refusal is shown at most once a second per player.
- `equip` covers the armour slots and the offhand. It is checked every five ticks, and the players' checks are spread by UUID; an unchanged set of worn item ids with the same rules and progress revision is skipped. A restricted item is put back into the inventory (or dropped when full) within that window. The main hand is not checked, because moving an item into it happens constantly.
- `use` covers using the item in the air, on a block, or on an entity; it also covers using a restricted block (a chest, a door, and so on).
- `craft` is checked when a crafting grid's result is taken, by a click or a shift-click; the result stays in the slot and the ingredients are untouched. Furnace, smoker, blast furnace and brewing results are not covered.
- `place` is checked in `BlockItem#place`: it matches the item being placed and the block it would become. A placement that does not go through `BlockItem` is not covered. There is no creative-mode or permission-level exemption; a restricted item is refused for every player. `break` matches the block and the item held to break it. A refused action never drops anything or counts toward a task.
- Restrictions are enforced on the server only. The client mod does not need to know them; the synced bundle carries the rules for the editor.
- A fake player (one with no connection, or a mod's own `ServerPlayer` subclass, such as Create's deployer) is not restricted and gets no progress record.
- Problems in the file (a missing id, an unreadable target, an unknown action, a missing condition) are `WARNING`s and appear in `/quest_tree validate`, for the pack and for the overlay. Item and block ids and the `item_tags` / `block_tags` names are checked against the loaded game, and conditions get the same check as a quest condition; none of these problems can drop a node.

## Skills

A chapter with a `points` object is a skill chapter.

```json
"points": {
  "per_level": "level^1.5 * 100",
  "level_limit": 24,
  "start": 0,
  "spent_limit": null,
  "unlocked_by_default": true,
  "sources": [{"type": "mine_block", "...": "same expression shape as Pufferfish's Skills"}],
  "exchange": {"xp_levels": 30, "level_limit": 5},
  "pool": null,
  "stop_when_maxed": true,
  "reset_on_death": false,
  "respec": {"cost": null}
}
```

Experience and source conditions use Pufferfish's Skills' expression, variables and operations unchanged. The FTB official packs rely heavily on conditional expressions, and translating them into another shape would break during import.

Chapters that write the same `pool` name share experience and points.

`points.stop_when_maxed` (default false) stops the chapter at `level_limit`. Once the level reaches the limit, the chapter no longer reads its sources or accepts experience, and any experience that would have gone past the last level is discarded instead of being saved. Without it, the level still stops at the limit but experience earned at the cap keeps adding to the saved total, which cannot raise the level further.

### Exchange

`points.exchange.xp_levels` is the number of vanilla levels paid per exchange, and `level_limit` is how many exchanges are allowed (omitted means unlimited). `cost_per_level` makes the cost depend on how many were bought before (starting at 0):

- `{"type": "values", "data": {"values": [5, 10, 20]}}` uses the Nth value; past the end it repeats the last value.
- `{"type": "expression", "data": {"expression": "5 + level * 10"}}` evaluates with `level` set to the number bought.
- When both are present, `cost_per_level` wins.

This maps directly from Pufferfish's Skills' `exchange.json`. If `cost_per_level` cannot be read (missing or non-object `data`, `values` not an array, a non-number value, a broken expression), the fixed `xp_levels` cost is used. Expressions are limited to 256 characters and 32 levels of nesting; a longer one also falls back to the fixed cost, so pack data cannot crash the server. Validation reports these as `WARNING` and the chapter still works.

### Respec

`points.respec.cost` is the points kept back and paid when the screen's "respec" button returns the chapter's points (omitted or `null` means free). An admin `respec` command charges nothing.

Only the team owner can press "respec" and "exchange" on the screen, and the server enforces the same rule. A respec takes the `points` rewards of the taken ranks out of the destination chapter's pool, so it also checks that the destination pool has enough left. If even one is short, the respec is refused and names the pool; this closes the path where spending another chapter's points and then respecing drives that chapter negative. The respec cost is not refunded. When FTB Teams cannot read the owner, nobody on that team can press it.

### Taking a skill back

Taking a skill back through respec or reset removes its attribute bonuses (all ranks and quest rewards), runs each `lock_command` and removes each tag, as Pufferfish's Skills does. Points are shared, so they are taken back once for the taker before the per-player effects run; a respec is refused earlier if those points were already spent.

A locked chapter already ran its `lock_command` and removed its tags, so a reset or respec while locked must not repeat those command or tag effects. The points and the paid records are still cleaned up.

## Teams and stages

Completion, task counters, repeat counts and cooldowns are team-shared unless a node sets `shared: false`. Rewards are per player; a reward with `team: true` is paid once per team, and a team stage is recorded once for the team.

When a team member takes a skill, the reward's conditions are checked against that member, but its effects (attributes, tags and so on) apply to every member.

Each player's saved stages are compared with the team's wanted stages on login. Missing team stages are added, and team stages the player should no longer hold are removed. Stages granted directly to the player are then added again, because the stage mod may not report them back after a relog. Removing a team stage therefore leaves a directly granted stage in place.

A stage barrier opens when the stage is in the player's own stages or in the team's. If it is in neither, both the server and the client ask Pack Stages, so a stage granted by another mod opens the gate on both sides. On login only stages the team no longer holds are removed, so an offline removal is followed without stripping a stage another mod granted.

Chapter open and locked state is shared by the team in the same way.

### Personal nodes

Completion, task counters, repeat counts and cooldowns of a `shared: false` node are kept for each player. When a pack cannot be read, saved personal progress is left alone. When the pack loads and every node is shared, the saved personal values move to the team's progress.

## Reward blocking

`/quest_tree rewards block` stops the pay-out of quest rewards, not the quest or the skill.

- Tasks, node completion, quest claims and taking a skill continue while rewards are blocked.
- Only quest rewards are affected. Skill node rewards (the take, a respec, a rank, and a member's own command or tag) are never blocked and never enter the pending list; taking a skill is never stopped.
- A blocked quest reward is recorded as taken for its node and kept in the player's pending list. When the block is lifted, pending rewards are paid in their original order.
- A repeatable quest starting its next round keeps the pending rewards of the round it just claimed, so a blocked pay-out is still owed afterward. Resetting the node forgets them together with the node's saved reward rolls.
- The block is judged per quest reward. `attribute` rewards are part of the skill effect and are always applied, even while quest pay-outs are blocked.
- `ignore_reward_blocking` / `ignore_blocking` still exempts one quest reward.
- A skill reward is never kept waiting: a skill taken while quest rewards are blocked already had its effect, and login re-applies its attributes.

`/quest_tree rewards block|unblock` accepts an offline player (a name from the server's profile cache). The block and pending quest rewards are kept for that player. Lifting the block pays immediately for an online player; for an offline player the pending rewards are paid at the next login, where a still-blocked flag keeps them waiting.

## Emergency items

The emergency button on the progression screen gives the items in `emergency.items`. Its cooldown is `emergency.cooldown_seconds` (default 3600; 0 disables it) and is saved per player, surviving team changes.

Whether the quest book drops on death is the server config `drop_book_on_death`.

## Blocks

`quest_task_block` (quest board) opens the quest or chapter recorded on it when right-clicked. `quest_barrier` (quest barrier) lets through only a player who has completed the recorded quest or holds the recorded stage; other entities never pass. Both move their key from the placed item's custom data to the block entity. `quest_detector` force-progresses a quest (or one task, as `<node>#<index>`) for every player within its radius on a redstone rising edge. Operators can hand them out with:

```
/quest_tree block task <players> <node>
/quest_tree block barrier <players> node <node>
/quest_tree block barrier <players> stage <stage>
/quest_tree block barrier <players> node|stage <key> teleport <dimension> <pos> [relative] [facing <yaw> <pitch>]
/quest_tree block detector <players> <node|node#index> [radius]
```

A barrier destination is absolute by default; `relative` measures it from the barrier block. `facing` keeps the player's current rotation when omitted. A missing or unknown dimension falls back to the player's current level. The detector radius defaults to 8 and is capped at 64. Both blocks keep their settings on the dropped item.

A chapter on a quest board is written with `/quest_tree giver set <chapter>` while looking at it; the block entity keeps it as `quest_chapter` next to the old `quest_node`.

When Jade is installed, a quest barrier's overlay shows its quest or stage and whether the local player may pass. Jade is optional; the mod works without it.

## Quest givers

A quest giver opens the book on one quest or chapter when a player right-clicks it. The server only sends the target; the client applies its own hidden and locked rules, so a giver never reveals something the player may not see.

| Kind | Stored in | Link it with |
|---|---|---|
| Entity | `<world>/data/kuronami_quest_tree_givers.dat`, by entity UUID | `/quest_tree giver set <quest\|chapter>` while looking at it |
| Quest board | the block entity's `quest_node` / `quest_chapter` | `/quest_tree giver set <quest\|chapter>` while looking at it |

```
/quest_tree giver set <namespace>:<chapter>/<node>
/quest_tree giver set <namespace>:<chapter>
/quest_tree giver list
/quest_tree giver clear
/quest_tree giver clear <uuid>
```

- Both need permission level 2 and a player source, because the target is what that player is looking at. `/quest_tree giver clear` on an entity removes its link; on a quest board it empties the block.
- `quest_task_block` without a link opens the book where it would normally open. A linked entity keeps its ordinary interaction while the player sneaks, so a villager can still trade.
- A hidden chapter opens nothing. A locked chapter opens at the chapter list with that pack's hint, not its map. A visible quest opens on its node and a visible chapter opens on its chapter.
- A giver target survives a restart. The entity list is its own file so a broken link cannot take player progress with it; a board's link travels with the block.
- `/quest_tree giver list` prints every entity link as `<uuid> -> <node|chapter>|<id>`. `/quest_tree giver clear <uuid>` removes one without looking at the entity; the plain `clear` still works on what the player is looking at.
- A linked entity that dies or is discarded loses its link. Chunk unloads and dimension changes keep it.
- A link whose quest or chapter is no longer in the book opens nothing and shows a short line with the missing id in the action bar.

## Overlay and editor

Edits made in game are stored in an overlay; the data pack itself is never rewritten.

```
<world>/kuronami_quest_tree/progression/
  chapters/<namespace>/<chapter path>.json
  revisions.json
```

Reading applies, lowest precedence first: pack chapters, reward tables and groups; pack `patches/*.json`; the overlay. The overlay is per chapter and can also delete a pack chapter. No `/reload` is needed: every save applies the layers, validates, increments a generation and resends the result to everyone (at most once per tick).

A chapter file:

```json
{
  "revision": 3,
  "base": true,
  "replace": false,
  "remove": false,
  "edited": false,
  "chapter": { "title": "..." },
  "removed": ["subtitle"],
  "nodes": {
    "a": { "action": "replace", "node": { "x": 1, "y": 2 } },
    "b": { "action": "remove", "node": { "...": "contents at deletion" }, "edited": true },
    "c": { "action": "add", "node": { "...": "new node" } }
  }
}
```

- `revision`: version of this chapter. A client sends the last version it saw; an older one is refused.
- `base`: whether the pack had this chapter when it was first edited.
- `replace`: use `chapter` as the whole chapter instead of merging it into the pack's chapter. `create_chapter` sets this.
- `remove`: tombstone for the chapter, with the contents at deletion kept in `chapter`.
- `edited`: whether the overlay had already changed the chapter before it was tombstoned. Restoring uses this to offer either the pack's original chapter or the edited version.
- `chapter`: only the top-level keys changed by the edit, each holding its **new** value. With `replace: false` it is merged key by key into the pack chapter, and only the node entries below apply. Changing one nested value does not remove its siblings (changing `display.title` keeps the pack's `display.icon`).
- `removed`: top-level keys that an RFC 7396 `null` deleted from the pack. With `replace: false`, these are removed from the pack chapter before merging. A `replace: true` chapter such as `create_chapter` does not use it.
- `nodes`: `action` is `add`, `replace` or `remove`. `remove` keeps the contents so it can be restored. `edited` says whether it was changed before deletion.
- `revisions.json`: a small record (`{"<chapter id>": version}`) so deleting a chapter file does not roll the version back. The larger of the file's `revision` and this record is used. Clients learn the current version from the sync bundle's `revisions` map.

When writing by hand, an omitted `action` is read as `add`. An unreadable file is reported as `ERROR` and writes to that chapter are refused.

Editor operations (`chapterId` is `<namespace>:<chapter path>`; only `create_chapter` sends an empty chapter id and names the namespace in `args.namespace`; the server assigns the new id and returns it as `createdId`):

| Operation | Arguments |
|---|---|
| `create_node` | `{"node": {...}}` |
| `update_node` | `{"id": "...", "patch": {...}}` (RFC 7386; `null` deletes a key) |
| `move_node` | `{"id": "...", "x": 0, "y": 0}` |
| `delete_node` / `restore_node` | `{"id": "..."}` |
| `create_chapter` | `{"namespace": "...", "chapter": {...}}` (the server reassigns initial node ids and rewrites `requires` / `exclusive_with` between them) |
| `update_chapter` | `{"patch": {...}}` (`nodes` not allowed; RFC 7396 against the whole chapter; `null` also deletes pack keys) |
| `delete_chapter` / `restore_chapter` | `{}` |
| `update_table` | `{"id": "...", "create": true?, "table": {...}}` (whole table; `patch` also accepted) |
| `delete_table` / `restore_table` | `{"id": "..."}` |
| `link` / `unlink` | `{"from": "...", "to": "..."}` (`to` is a node name in the same chapter, or `chapter/node` across chapters) |
| `undo` / `redo` | `{}` |

Node ids may contain only `[a-z0-9_]`; other ids are refused. Ids are never renamed, because a rename would break references, exclusivity, translation keys and player progress at once.

Validation and undo:

- Before writing, the candidate chapter goes through the same validation as a pack. The set of book-wide `ERROR`s is compared before and after; one new `ERROR` refuses the write, including a deletion that breaks another chapter's assumptions. Existing `ERROR`s do not block editing.
- A refusal returns the validation message and leaves the file unchanged.
- Undo is a snapshot per chapter and per table, 50 steps deep, stacked and unwound from the same end. Table history is separate from chapter history, so the same id does not mix. It is lost on server restart. What remains on disk is the tombstone, a `.bak` of the version just before a write, and `revisions.json` / `table_revisions.json`.
- Writes go to a temporary file in the same folder and then replace the target (`ATOMIC_MOVE`, or `REPLACE_EXISTING` where that is unavailable), so a crash cannot leave a half-written chapter file.

Commands:

- `/quest_tree edit on|off` (no argument toggles): editor operations are accepted only at permission level 2 with edit mode on. Logging out or stopping the server turns it off.
- `/quest_tree export <namespace> [force]`: writes the validated book as an ordinary data pack in `datapacks/quest_tree_export_<namespace>`, without enabling it or running `/reload`. The namespace keeps `[a-z0-9_.-]` for chapter matching; only the folder name is made safe (a namespace containing `-` or `.` still exports every chapter). Translations for that namespace (keys beginning `progression.<ns>.`) go into `lang/`. The number of nodes dropped by validation is printed in chat. An existing folder is refused unless `force` is given; `force` may replace it.

In-game editing:

- The server sends whether editing is allowed (permission 2 and `/quest_tree edit on`) on join and on every change. When allowed, a gear appears next to the pin on the title row, and `E` also toggles edit mode.
- Selection: click, Ctrl+click, Shift+drag rectangle, Ctrl+A. Dragging moves on half-grid steps and Alt disables it. Arrow keys move (Shift moves 5). Right-click opens a menu. Ctrl+C / V / D, holding `L` while dragging or "link from here" in the menu, Ctrl+Z / Ctrl+Shift+Z / Ctrl+Y. Keys are not captured while an EditBox has focus.
- Operations are sent one at a time and the next one uses the previous reply's revision. Moves are drawn at the provisional position before the reply and are put back if refused. A refusal shows the validation message, shortened. An old revision makes the server resend the bundle.
- Paste assigns new ids on the server. The creation reply maps old ids to new ones, and links inside the copy are made afterward.
- Right-click "edit", double-clicking a node, or Enter with a single selection opens the node editor. Title, description, shape, size, icon, visibility and requirements, and repetition are on one page; tasks and rewards are cards that can be added, reordered and deleted. Opening a card shows the fields for its type.
- One schema defines each task and reward type; the fields shown and the round-trip tests read the same table. Changing the type deletes the keys that belong only to the old type and keeps shared and unknown keys.
- Items, entities, advancements, tables and similar values are chosen in a searchable picker. An id not in the list can still be typed (for tags and for ids from mods the client does not have).
- A screen holds a working copy of the JSON and sends one `update_node` / `update_chapter` RFC 7396 patch when it closes. Save and delete use the revision from when the screen opened; if anything else changed in the meantime, the write is refused. There is no write on every keypress. A refusal shows the validation message under the offending field and keeps the screen open; pressing Esc or × again discards and closes. An old revision makes the server resend the bundle, and the screen closes with a short map notice.
- Chapter settings are edited on one screen (title, icon, defaults, visibility, skill points, delete chapter); a new chapter asks for a namespace and an optional title, and the server assigns the id.
- A reward table is edited as a whole (`update_table`, with a table-specific revision). Opening a reward whose `table` id does not exist creates it with `create: true` on save. Deleting writes a tombstone (`remove: true` and the last contents), and the restore screen brings it back with `restore_table` (if the pack copy is unchanged, the overlay entry is removed and the pack is used again). Undo restores the previous table file.
- Deleted chapters, tables and nodes appear in the sync bundle's `deleted` list. The restore screen sends `restore_chapter`, `restore_table` or `restore_node` from that list.
- Editor screens use only the existing surfaces, rows and palette; colours are never written directly. Text is limited to labels, values, state and failure reasons, with no explanatory paragraphs.
- The map's right-click menu also offers "chapters", "new chapter", "deleted", "export" and "try as player". Each goes through the same one-at-a-time operation queue.
- Export from the menu runs `/quest_tree export <namespace>` (the chapter's namespace if none is supplied) and returns only the output folder name; it never prints a server path. A failure returns the reason, not success.
- "Try as player" runs `/quest_tree reset @s <chapter>` and turns edit mode off only when the command succeeded. It refuses if the chapter is not in the book, if the player is in a party (progress is shared, so it would erase other members' chapter progress), or if the command fails. It echoes the request's revision because it writes no chapter.

Not implemented:

- Confirmation on a real client.
- A UI for links across chapters.
- Editing the translation files themselves (only overriding displayed text is possible).
- Automatic merge across chapter revisions (an old revision is refused and refetched).

## Validation

Every pack file is checked once at load. The check does not depend on the game (registry lookup is the only injected entry point, so it runs in JUnit). Problems are logged as `ERROR` or `WARNING` in the form `file:line path - message`. A one-line summary is printed, and `/quest_tree validate` (permission level 2) shows the totals plus the first five problems of each chapter. When an operator joins, one line reports whether errors exist. An error never stops the whole pack.

A second pass runs on the tick after the server starts and after each `/reload`, once the levels exist and the tags are bound. Only a pack load schedules it: an editor save validates the bundle it publishes but does not run this pass or write the report. `/quest_tree validate` runs it on demand. It asks the loaded world about the content that the reload pass cannot see: tags (missing, or defined but empty), biomes, structures, dimensions, advancements, recipes, observation block states and block/entity tags. These findings are always `WARNING`s, because this pass runs after the book is already published and must never remove a node. They are grouped by chapter in the log and in `/quest_tree validate`; the full list is written to `quest_tree_validation.txt` in the world folder (disable with `validation_report: false` in `config/kuronami_quest_tree-server.json`).

If validation itself throws, that one failure is recorded as `ERROR` and loading continues. If building the model throws after validation (a shape validation missed), that one failure is recorded as `ERROR` and the previous contents stay in use: publishing something the server cannot model would give clients progress the server does not have.

`ERROR` means "does not work as written": the reader throws (the server tick stops), the reader drops a task (the node completes the moment it is read), or the unlock condition changes so a written gate disappears. Only that node is removed from the model; everything else keeps running.

`WARNING` means "works, but not as written": a reward is silently skipped (the reward giver catches the reader's exception), a value falls back to a default, or an unlock condition is looser than written. The node stays.

- Reward problems are `WARNING` by default, because the reward giver catches everything. The one exception is a reward table that contains itself: paying it would recurse until an uncatchable stack overflow. A table cannot be removed, so the node that pays it is removed as `ERROR`.
- Reference severity depends on what happens to the written gate. The model drops names it cannot resolve, so an unresolved `requires` name can open a node earlier than written. With `mode: "all"` (the default) and no `min` (or `min` 0 or less), the gate disappears entirely: `ERROR`. With `any`, `one` or a `min`, the same number is still required: `WARNING`. An unresolved `exclusive_with` name only removes the conflict display and the node still opens and completes as written, so it is always `WARNING`.
- A `requires` naming a nonexistent node and one naming a removed node follow the same rules and severity, because at runtime both are the same "name does not resolve" state. For the former, the `ERROR` node is removed; for the latter, the `ERROR` is not removed, so one unrelated typo does not delete a written node. The same applies when pointing at a node removed as unreachable.
- Reachability: check by fixed-point iteration using the same rules as opening. Start from the nodes that open first, keep opening nodes whose conditions are met, and stop when nothing changes; nodes still closed are unreachable by any play. Remove them as `ERROR`. A `root` opens regardless of what it writes. Loop detection alone gives false positives: the Pufferfish importer makes both sides of a two-way link `mode: "any"`, so an `A → B → A` loop with one `any` side is normal (the other branch opens it).
- Types: a task or reward `type` must be known. An unknown task is `ERROR` (it can never be counted); an unknown reward is `WARNING` (it is skipped and pays nothing). An unknown `observation.observe_type` is `ERROR`.
- Required fields (an `item` task's `item`, a `fluid` task's `fluid`, a `location`'s three `position` values, an `observation`'s `target`, and so on). A missing task field is `ERROR`; a missing reward field is `WARNING`.
- Number types. `count`, `amount`, `ticks`, `item.count`, and a `location`'s `position` / `size` are read as integers during the server tick. `count` and `ticks` are read through a type guard and an unreadable shape is skipped, so they are `WARNING` (and fall back to 1). The others (`amount`, `item.count`) and readable but non-integer values (`"many"`, `1.5`) have no guard, so they are `ERROR`. The JSON reader accepts a numeric string such as `"16"`, so that is not an `ERROR`.
- `optional` and `consume` are read as booleans during the tick; an object, array or null throws (`ERROR`), while a number or word is read as false (`WARNING`).
- `rolls`, `weight`, `empty_weight`, `min`, `max`, `cost`, `tasks_required`, an attribute's `value` and xp's `amount` either fall back to a default or are caught by the reward giver: `WARNING`.
- Zero or less: `count` and `amount` are `WARNING` (the target is raised to 1). `weight` of 0 or less is `WARNING` (that entry can never be chosen). An empty `break.block` means "any block", so it is not warned about.
- `repeat`: a repeat checks `done + 1 >= max`, so a `max` of 1 or less never repeats (`WARNING`). `cooldown` is read as a number when a claim is confirmed, so a non-integer is `ERROR`; 0 or less is read as "no cooldown" (`WARNING`). A non-numeric `max` is read as unlimited (`WARNING`).
- Reward tables: when an `entries` element is not an object, or `weight` cannot be read, the table fails while being assembled and pays nothing (the `WARNING` text says so).
- Shape: a chapter must be an object and its `nodes` an object (otherwise `ERROR`; the reader turns it into an empty object, so the chapter becomes empty). Each `nodes` value must be an object (otherwise `ERROR`; the reader skips it). `tasks` must be an array; `rewards`, `ranks` and a reward table's `entries` must be arrays. `requires` must be an object; `repeat` must be a boolean or object. `requires.nodes` and `exclusive_with` must be arrays whose elements can be read as names (numbers and booleans are converted to strings and resolve as names; objects, arrays and null throw, so `ERROR`). `root`, `optional` and `hidden` must be booleans; `mode` and `group` must be strings; `shape` and `milestone` must be strings. A reward table's `mode` is also read as a string, so it must be a string (otherwise that reward is skipped, `WARNING`). Values that fall back to a default are `WARNING`; values that throw are `ERROR`.
- Duplicate keys in one object are `WARNING`: The JSON reader silently keeps the last one, so the check looks at the text.
- JSON syntax. A file that cannot be read is `ERROR`. References to that chapter remain `WARNING`, because their existence cannot be confirmed.
- Id lookup checks only real items, blocks, fluids, entities and attributes. A missing id is `WARNING`; a missing namespace is `WARNING`. Tags (`#`) and dynamic registries (biome, structure, dimension, stat) are not checked at load, because their contents cannot be known then. Tag contents are final only after all reload listeners have run, so reading them inside a listener gives an empty result on first start and the previous contents on `/reload`; both are false warnings.
- Known but not yet working kinds (a reward table's `loot_crate`) are `WARNING`.
- Duplicate node ids across chapters are not checked: node keys are qualified by the chapter id, so they cannot collide, and using the same id in two chapters is normal.

## Chapter backgrounds

A chapter map can have any number of images behind it, drawn bottom to top. One image is a still background; two or more moving at different speeds give depth when the map moves. Images are resources, so a resource pack can replace them.

```json
"background": {"layers": [
  {"texture": "mypack:textures/gui/backgrounds/nebula.png", "speed": 0.3},
  {"texture": "mypack:textures/gui/backgrounds/stars.png", "speed": 0.6, "alpha": 0.8}
]}
```

- `texture` (required): full path to the image.
- `speed`: how much it moves with the map. 1 = same as the map, 0 = still (default 0.5). Zoom follows the same ratio.
- `tile`: repeat the image (default true). With false, one image is stretched to the screen.
- `size`: image width at 1× zoom in GUI pixels (default: the image's pixel width).
- `alpha`: 0 to 1 (default 1).
- Short form: `"background": "mypack:textures/..."` is one still image.

Quest Tree itself ships no background images. Without one, the palette's `ground` colour is used.

## Importing existing progress

Importing existing FTB Quests or Pufferfish's Skills progress is a separate workflow; see [MIGRATION.md](MIGRATION.md). It merges only, never removes, keeps the larger counter, and refuses a second `apply` of the same source unless `force` is given. Completion times and claim times are not carried because there is no field for them.

## Extending tasks, rewards and conditions

Other mods can register task and reward kinds (Pufferfish's Skills has add-ons that add kinds, for example) and experience sources. Conditions are registered through the public API or KubeJS. See [API.md](API.md).

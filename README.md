# Kuronami's Quest Tree

Quest books and skill trees in one screen, defined by data packs. Imports existing FTB Quests quest books and Pufferfish's Skills trees with one command.

- Minecraft 1.21.1, NeoForge and Fabric
- Status: early development. Progress is tracked per player and saved with the world; rewards are claimed on the screen.

## Addons

Task, reward and condition types can be registered from another mod, and progression events are available. See [_docs/API.md](_docs/API.md). KubeJS scripts can register the same handlers on NeoForge.

## Commands

Permission level 2.

| Command | |
|---|---|
| `/quest_tree import ftbquests [namespace]` | Convert `config/ftbquests/quests` into the world data pack `quest_tree_import` and reload |
| `/quest_tree import puffish [namespace]` | Convert every Pufferfish's Skills category found in data packs and `config/puffish_skills` |
| `/quest_tree status` | Number of loaded chapters |
| `/quest_tree reset <players> [chapter or node]` | Forget progress (everything, one chapter, or one node) |
| `/quest_tree complete <players> <node>` | Finish a quest's tasks; rewards stay claimable |
| `/quest_tree respec <players> <chapter>` | Give back every point spent in a skill chapter |
| `/quest_tree points <players> <chapter> <amount>` | Add (or remove) points to spend |
| `/quest_tree experience <players> <chapter> <amount>` | Add experience to a skill chapter |

Chapter ids are `<namespace>:<chapter>`, node ids `<namespace>:<chapter>/<node>`.

The original FTB Quests and Pufferfish's Skills files are only read. Settings that could not be carried over are listed in chat and in `import_report_<namespace>.json` next to the data pack.

## Data

`data/<namespace>/progression/`:

| Path | |
|---|---|
| `chapters/<id>.json` | One chapter: nodes, decorations, and for skill chapters a `points` block |
| `reward_tables/<id>.json` | Weighted reward tables |
| `groups.json` | Chapter groups and their order |
| `lang/<locale>.json` | Text shipped with the data (titles, descriptions) |
| `patches/<id>.json` | Partial changes to a chapter another pack or mod ships (`"target": "<ns>:<chapter>"`) |

Description markup (colours, links, images) is in [_docs/DESCRIPTION_TEXT.md](_docs/DESCRIPTION_TEXT.md).

## Keys

| Key | |
|---|---|
| **U** | Open or close the screen |
| **Y** | Show or hide pinned quests at the side of the game screen |

Both can be rebound in Controls.

## Experience sources

Skill chapters take Pufferfish's Skills experience sources as they are (`variables` with `operations`, `experience` as an expression or a list of `condition`/`expression` cases). Supported now: `mine_block`, `kill_entity`, `craft_item`, `smelt_item`, `eat_food`, `fish_item`, `take_damage`, `deal_damage`, `heal`, `increase_stat`. An operation the mod does not know gives no experience and is logged once.

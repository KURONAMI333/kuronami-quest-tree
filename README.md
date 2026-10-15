# Pack Progress

Quest books and skill trees in one screen, defined by data packs. Imports existing FTB Quests quest books and Pufferfish's Skills trees with one command.

- Minecraft 1.21.1, NeoForge and Fabric
- Status: early development. Progress is tracked per player and saved with the world; rewards are claimed on the screen.

## Commands

Permission level 2.

| Command | |
|---|---|
| `/pack_progress import ftbquests [namespace]` | Convert `config/ftbquests/quests` into the world data pack `pack_progress_import` and reload |
| `/pack_progress import puffish [namespace]` | Convert every Pufferfish's Skills category found in data packs and `config/puffish_skills` |
| `/pack_progress status` | Number of loaded chapters |

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

## Keys

| Key | |
|---|---|
| **U** | Open or close the screen |
| **Y** | Show or hide pinned quests at the side of the game screen |

Both can be rebound in Controls.

## Experience sources

Skill chapters take Pufferfish's Skills experience sources as they are (`variables` with `operations`, `experience` as an expression or a list of `condition`/`expression` cases). Supported now: `mine_block`, `kill_entity`, `craft_item`, `smelt_item`, `eat_food`, `fish_item`. An operation the mod does not know gives no experience and is logged once.

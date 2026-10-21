# Kuronami's Quest Tree

Quest books and skill trees in one screen, defined by data packs. Imports existing FTB Quests quest books and Pufferfish's Skills trees with one command.

- Minecraft 1.21.1, NeoForge and Fabric
- Status: early development. Progress is shared with the team you are in and saved with the world; each player claims their own rewards.

## Addons

Task, reward and condition types can be registered from another mod, and progression events are available. See [_docs/API.md](_docs/API.md). KubeJS scripts can register the same handlers on NeoForge.

## Commands

Most commands need permission level 2. `migrate` needs level 4 (or the server console); `party` is open to every player.

| Command | |
|---|---|
| `/quest_tree import ftbquests [namespace]` | Convert `config/ftbquests/quests` into the world data pack `quest_tree_import` and reload |
| `/quest_tree import puffish [namespace]` | Convert every Pufferfish's Skills category found in data packs and `config/puffish_skills` |
| `/quest_tree status` | Number of loaded chapters |
| `/quest_tree validate` | Problems found by the last data pack reload |
| `/quest_tree reset <players> [chapter or node]` | Forget progress (everything, one chapter, or one node) |
| `/quest_tree complete <players> <node>` | Finish a quest's tasks; rewards stay claimable |
| `/quest_tree respec <players> <chapter>` | Give back every point spent in a skill chapter |
| `/quest_tree lock <players> <chapter>` | Hide one chapter from those players |
| `/quest_tree unlock <players> <chapter>` | Show it again |
| `/quest_tree points <players> <chapter> <amount>` | Add (or remove) points to spend |
| `/quest_tree experience <players> <chapter> <amount>` | Add experience to a skill chapter |
| `/quest_tree edit [on|off]` | Turn the in-game editor on or off for yourself |
| `/quest_tree export <namespace> [force]` | Write the edited book as the data pack `quest_tree_export_<namespace>` |
| `/quest_tree migrate ftbquests [namespace] [dry_run|apply [force]]` | Copy FTB Quests progress into the team records; see `_docs/MIGRATION.md` |
| `/quest_tree migrate puffish [namespace] [dry_run|apply [force]]` | Copy Pufferfish's Skills progress into the team records |
| `/quest_tree party <create\|invite <player>\|accept\|leave\|kick <player>\|disband\|info>` | Built-in party, used when FTB Teams is not installed |
| `/quest_tree block task <players> <node>` | Give a quest task block for that node |
| `/quest_tree block barrier node <players> <node>` | Give a barrier that opens while that quest is done |
| `/quest_tree block barrier stage <players> <stage>` | Give a barrier that opens while the stage is set |
| `/quest_tree rewards block <players>` | Stop those players from claiming rewards |
| `/quest_tree rewards unblock <players>` | Allow claims again |
| `/quest_tree rewards status <players>` | Report whether rewards are blocked |
| `/quest_tree team_stage add <players> <stage>` | Add a stage to the players' team progress |
| `/quest_tree team_stage remove <players> <stage>` | Remove a stage from the team progress |

Chapter ids are `<namespace>:<chapter>`, node ids `<namespace>:<chapter>/<node>`.

The original FTB Quests and Pufferfish's Skills files are only read. Settings that could not be carried over are listed in chat and in `import_report_<namespace>.json` next to the data pack.

## Teams

Progress is shared with the team a player is in. If FTB Teams is installed its parties are used. Otherwise players can
form a party with `/quest_tree party`; an invitation lasts five minutes, and the owner passes the party to the oldest
remaining member when they leave. Without a team a player progresses alone. Claimed rewards stay with the player who
claimed them.

Team progress is saved in `<world>/data/kuronami_quest_tree.dat`.

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
| **K** | Open or close the screen |
| **J** | Show or hide pinned quests at the side of the game screen |

Both can be rebound in Controls.

## Experience sources

Skill chapters take Pufferfish's Skills experience sources as they are (`variables` with `operations`, `experience` as an expression or a list of `condition`/`expression` cases). Supported now: `mine_block`, `kill_entity`, `craft_item`, `enchant_item`, `smelt_item`, `eat_food`, `fish_item`, `take_damage`, `deal_damage`, `heal`, `increase_stat`. An operation the mod does not know gives no experience and is logged once.

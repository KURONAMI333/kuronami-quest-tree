# Moving existing progress into Kuronami's Quest Tree

`/quest_tree migrate ftbquests [namespace] [dry_run|apply]` and `/quest_tree migrate puffish [namespace] [dry_run|apply]`
copy progress from FTB Quests (and FTB Teams) or from Pufferfish's Skills into KQT's team records. Operators at level 4 and
the server console only. Without `dry_run` or `apply` the command is a dry run.

## Procedure

1. Stop the server and copy the whole world folder. An apply also copies KQT's own store (below), but not the FTB Quests or
   Pufferfish's Skills files, which are only read.
2. Start the server. Keep the original book folder (`config/ftbquests/quests` or `defaultconfigs/ftbquests/quests`): the
   migration reads the book again to find the task and reward positions. Keep `<world>/ftbquests/` and, for team members,
   `<world>/ftbteams/` (FTB Quests does not have to be installed; FTB Teams only matters if it is installed in the pack you
   keep). For Pufferfish's Skills the file is `<world>/data/puffish_skills.dat`.
3. Import the book under a namespace you will keep. Node keys contain it; a later import under another namespace makes
   every migrated key orphaned.
   - `/quest_tree import ftbquests <namespace>` (default `ftbquests`)
   - `/quest_tree import puffish <namespace>` (default `skills`)
4. Dry run: `/quest_tree migrate ftbquests <namespace> dry_run` (puffish: namespace is optional and found by itself when only
   one namespace holds imported skill chapters). Read the counts and the first 20 unresolved ids in chat; the full report is
   `<world>/kuronami_quest_tree/migration-<yyyyMMdd-HHmmss>.txt`.
5. Apply: the same command with `apply`. It saves the world's data, copies `data/kuronami_quest_tree.dat` to
   `data/kuronami_quest_tree.dat.migrate-<time>.bak`, writes the merged records and refreshes players who are online.
6. Verify: run `dry_run` again (every `*_added` counter must read 0), then open a few chapters in game as a migrated player
   and compare the completed counts and unclaimed rewards with the report.

To undo an apply, stop the server and put the `.bak` file back over `data/kuronami_quest_tree.dat`.

## What is carried (FTB Quests)

| FTB Quests | KQT |
|---|---|
| `completed` (quest ids) | completed nodes of the team |
| `task_progress` | task counters (`<node>#<index>`), larger value wins |
| `claimed_rewards` of a player | that player's claimed rewards (`<node>#<index>`) |
| `claimed_rewards` under the all-zero uuid (team reward) | claimed once for the whole KQT team |
| `completion_count`, `repeatable` | repeat count and cooldown, larger value wins |
| `started` | not carried; only counted as `started_not_carried` |
| `player_data` (pins), `rewards_blocked`, timestamps | not carried |

A node is marked claimed for a player only when every reward of the node is claimed by them or by the team. Ids in
`completed` that belong to tasks, chapters or the book itself (id 1) are expected and are not reported. The KQT team a FTB Quests team lands in is
whatever the server resolves for its members now: with FTB Teams the FTB Quests team itself, with KQT's own parties the
party or the player alone. FTB Teams `server` teams are read from their `ranks`; that membership has not been confirmed
against a live world.

## What is carried (Pufferfish's Skills)

| Pufferfish's Skills | KQT |
|---|---|
| `unlocked_skills` | taken skills (rank 1) |
| `experience` (total) | level and experience inside the level, walked down the chapter's `per_level` curve and `level_limit` |
| spent points | `spent` grows by the cost of each skill the run takes |
| `points` (total of all sources) | `bonus = total - start - level`, never below 0 (`points_over_source` counts the shortfall) |
| `unlocked` on a category locked by default | the chapter is opened for the team |
| exchange `level` (how often experience was exchanged for a point) | exchange count, larger value wins; the points it gave are in the `points` total |

## Applying twice

An apply records its source (`ftbquests:<namespace>` or `puffish:<namespace or *>`) and a hash of the input files in the
KQT store. A second `apply` of the same source is refused, because after people have played it could bring back
repeatable state that was cleared and skills that were erased. `apply force` repeats it anyway. A dry run of an applied
source says so in the report notes. The FTB Quests hash covers the team files in `<world>/ftbquests/`, the Pufferfish's
Skills hash the whole `puffish_skills.dat`.


## Commands, side by side

The FTB Quests commands an operator is used to, and what to run here. All need permission level 2. KQT publishes a write
at once, so a command that says "created" is followed by a book that has it (the editor waits a few ticks to batch).

| FTB Quests | KQT | Difference |
|---|---|---|
| `locked <team> on\|off` | `/quest_tree book_lock <players> on\|off` | KQT locks the whole team's book from opening. Progress still counts, as it does on the FTB Quests team record. |
| `change_progress <players> <id> <progress>` | `/quest_tree complete <players> <node>` | FTB Quests takes `reset` or `complete`; KQT's `complete` does not claim rewards. |
| `change_progress ... complete-all` | `/quest_tree complete_all <players>` | Skill chapters are left alone; their nodes are taken with points. |
| (no partial value) | `/quest_tree progress <players> <node> <task> <value>` | KQT addition: the value is clamped to 0..the task's target. The task counts from 1. |
| `generate_chapter <items>` | `/quest_tree chapter generate creative\|inventory\|container <pos>` | The chapter is written at once instead of opening an editor screen. One quest per distinct item, 40 to a row, a blank row between namespaces. |
| `export_reward_table <id> [pos]` | `/quest_tree table export <id> [pos]` | Item rewards only; other reward kinds stay in the table. Without `pos`, the container the player looks at is used. |
| `import_reward_table <id> [pos]` | `/quest_tree table import <id> [pos]` | Item id and count only (no components). Weight 1.0, one roll, empty weight 0. |
| `remove_empty_reward_tables` | `/quest_tree table delete_empty` | A table with only a weight-0 entry is not empty. |
| `open_book [id]` | `/quest_tree open_book [<chapter or node>]` | A player only; the console cannot open a screen. |
| `clear_item_display_cache` | `/quest_tree clear_display_cache` | KQT keeps no display cache, so this only answers the command. |
| `cleanup_stale_translations` | `/quest_tree cleanup_translations show\|execute` | `show` lists; `execute` rewrites the world data packs' `progression/lang/<locale>.json` and reports keys left in read-only packs. Run `/reload` to make the cleaned files the ones the server reads. |

## Rules the commands follow

- Merge only: nothing is removed; sets are united and counters take the larger value. A second apply changes nothing.
- Nothing is guessed. An id that is not in the book, a node the imported pack does not have, a task or reward position that
  differs between book and pack, or a pack task whose text names another task id is listed as unresolved and skipped.
  `unresolved` kinds in the report: `completed`, `task_progress`, `reward`, `node_missing`, `task_index`, `reward_index`,
  `task_moved`, `category`, `skill`, `unreadable_file`, `unreadable_key`.
- Tasks and rewards of types the importer does not convert are counted (`tasks_not_converted`, `rewards_not_converted`),
  not reported as unresolved.
- Renaming a chapter file after the import changes every node key in it; re-import under the same namespace first, then
  migrate.
- Completion times and claim times are not kept (KQT has no field for them).

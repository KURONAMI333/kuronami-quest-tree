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

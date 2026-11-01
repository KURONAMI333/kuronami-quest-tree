# Changelog

## 1.0.0

- First release.
- Quests and skill trees are drawn on one map, with dependencies, optional tasks, exclusive branches, repeatable quests, pins, search, zoom and a minimap.
- Team progress: FTB Teams parties when the mod is installed, or the built-in party commands otherwise. Claimed rewards stay with the player who claimed them.
- In-game editor: add, move, link and delete nodes; edit tasks, rewards, conditions, chapters and reward tables; undo and restore deleted chapters and nodes; export the result as a data pack.
- Import from FTB Quests and Pufferfish's Skills, with a report of settings that could not be carried over. A task or reward of a type nothing handles (an add-on's kind, such as FTB Money) is kept as written, so a mod that registers that type can use it; until then it is neither counted nor shown, and the quest around it still works.
- Copy existing FTB Quests or Pufferfish's Skills player progress into the new team records. `dry_run` first, then `apply`; the progress file is backed up before it changes.
- A quest book item opens the screen. A quest block opens one quest, and a barrier block opens only after a quest or stage is reached.
- JEI, REI and EMI can open a quest from an item, and quest descriptions can open a recipe, another quest or a link.
- Item tooltips show which quests need the item.
- A paper theme in the settings screen: a light paper look for the map, panels and tiles. On a light ground, text and the colour codes in imported names and descriptions are darkened until they read; on a chapter's own dark background picture, the top bar's text follows that picture. High-contrast mode turns the theme off. Themes are resource-pack files, so a pack can restyle or replace one.
- Loot crates: a crate item and a crate block roll a reward table. The recipe viewers show the chance of each reward, and a table can guarantee an entry after a run of misses.
- Stage tasks and stage conditions count a stage the book itself granted on both loaders, not only through Pack Stages.
- A save whose main file is missing is restored from the backup instead of starting empty.
- Quest givers: right-click a linked entity or quest board to open the book on a quest or chapter. `/quest_tree giver set|clear`, `QuestTreeApi.open` and KubeJS `QuestTree.open` link one from a command, another mod or an NPC script.
- `/quest_tree validate` reports problems in overlay restriction rules, not only in the pack's own restrictions file.
- The rest of the FTB Quests operator commands: `/quest_tree book_lock` closes a team's book, `progress` sets one task counter, `complete_all` finishes every quest, `open_book` opens the book at a chapter or quest, `chapter generate` makes a chapter from the creative list, a player's inventory or a container, and `table export|import|delete_empty` moves reward tables in and out of containers and removes empty ones. `cleanup_translations` finds and deletes language keys the book no longer reads.
- Other mods can register task, reward and condition types and listen for progression events. KubeJS can do the same on NeoForge.
- Restrictions on the `attack` action are enforced on the held item, and a build layout never loads chunks from the placement tick.
- A progress payload over the network limit now tells the player and online operators that the book is out of date instead of only writing a log line.
- Reward tables roll the same entries the editor and the recipe viewers show: an unreadable weight can no longer win in a crate while looking impossible elsewhere. A crate whose whole table is unreadable is no longer consumed for nothing, and the ceiling check reads such an entry as unpayable.
- Daily and weekly rounds still end while a team's book is locked, and the detector block no longer moves tasks while it is locked.

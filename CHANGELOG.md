# Changelog

## 0.1.0 (alpha)

- First alpha release.
- Quests and skill trees are drawn on one map, with dependencies, optional tasks, exclusive branches, repeatable quests, pins, search, zoom and a minimap.
- Team progress: FTB Teams parties when the mod is installed, or the built-in party commands otherwise. Claimed rewards stay with the player who claimed them.
- In-game editor: add, move, link and delete nodes; edit tasks, rewards, conditions, chapters and reward tables; undo and restore deleted chapters and nodes; export the result as a data pack.
- Import from FTB Quests and Pufferfish's Skills, with a report of settings that could not be carried over.
- Copy existing FTB Quests or Pufferfish's Skills player progress into the new team records. `dry_run` first, then `apply`; the progress file is backed up before it changes.
- A quest book item opens the screen. A quest block opens one quest, and a barrier block opens only after a quest or stage is reached.
- JEI, REI and EMI can open a quest from an item, and quest descriptions can open a recipe, another quest or a link.
- Item tooltips show which quests need the item.
- Quest givers: right-click a linked entity or quest board to open the book on a quest or chapter. `/quest_tree giver set|clear`, `QuestTreeApi.open` and KubeJS `QuestTree.open` link one from a command, another mod or an NPC script.
- `/quest_tree validate` reports problems in overlay restriction rules, not only in the pack's own restrictions file.
- Other mods can register task, reward and condition types and listen for progression events. KubeJS can do the same on NeoForge.
- Alpha note: data and save formats may still change between alpha versions. Back up a world before importing, migrating or editing a book on it.

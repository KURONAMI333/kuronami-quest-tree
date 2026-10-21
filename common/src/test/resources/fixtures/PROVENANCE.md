# Fixture provenance

Every file under this directory is synthetic test data written for this repository. None of it is copied from a
published modpack: there are no pack names, author names, or real quest texts, and the ids are sequential or
deliberately invalid (`10000000000TASKS`). The FTB Quests files mimic the layout of a real book so the importer is
exercised on the same shapes; the Pufferfish's Skills files do the same for a skill tree.

| File | Covers |
|---|---|
| `ftbquests/quests/chapter_groups.snbt` | Empty chapter group list |
| `ftbquests/quests/chapters/basics.snbt` | Two chapters and order: item tasks, xp rewards, default shapes |
| `ftbquests/quests/chapters/apiculture.snbt` | A large chapter: decorations before and after a quest, a random reward table reference, a cross-chapter dependency, mixed shapes and task/reward kinds |
| `ftbquests/quests/data.snbt` | Book settings (version, reward defaults) |
| `ftbquests/quests/lang/en_us/chapters/basics.snbt` | Chapter and quest titles, a description with an inline image |
| `ftbquests/quests/lang/en_us/chapters/apiculture.snbt` | Chapter and quest titles for the large chapter |
| `ftbquests/quests/lang/ja_jp/chapters/basics.snbt` | A second locale |
| `ftbquests/quests/reward_tables/10000000025c2bf8.snbt` | Weighted item rewards |
| `ftbquests_tasks/quests/chapters/tasks.snbt` | One quest per convertible task kind (block, fluid, energy, observation, and so on) |
| `puffish/mining/category.json` | Category title, icon, and default unlock |
| `puffish/mining/connections.json` | Normal and exclusive connections between skills |
| `puffish/mining/definitions.json` | Skill definitions with attribute rewards |
| `puffish/mining/experience.json` | A level curve and an experience source |
| `puffish/mining/skills.json` | Node positions and the tree root |

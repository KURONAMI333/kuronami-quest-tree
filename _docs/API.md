# API for other mods

The Quest Tree API lives in `com.kuronami.questtree.api` in `common` and has the same shape on NeoForge and Fabric. A mod that uses it references Quest Tree as `compileOnly` and can keep it optional at runtime (when Quest Tree is absent, the registration calls are simply never made).

## Registering a type

```java
ResourceLocation id = ResourceLocation.fromNamespaceAndPath("example", "level");
QuestTreeApi.registerTaskType(id, context -> context.player().experienceLevel);
QuestTreeApi.registerRewardType(id, context -> context.player().giveExperienceLevels(5));
QuestTreeApi.registerConditionType(id, context -> context.player().experienceLevel >= 30);
```

Registering the same id again replaces the previous handler (a Java registration). `unregisterTaskType` / `unregisterRewardType` / `unregisterConditionType` also exist, for script reloads.

For description colours, links and images, see [_docs/DESCRIPTION_TEXT.md](DESCRIPTION_TEXT.md).

On the data side, write the registered id as-is.

```json
{
  "tasks": [
    { "type": "example:level", "count": 30,
      "conditions": [{ "type": "example:enabled" }] }
  ],
  "rewards": [
    { "type": "example:prize", "conditions": [{ "type": "example:enabled" }] }
  ]
}
```

- **Tasks**: on each server tick, `QuestTaskType.progress(TaskContext)` is called and the returned value is saved as progress. `count` / `amount` can be read through `maxProgress()`. `currentProgress()` is this task's current value.
- **Rewards**: `QuestRewardType.give(RewardContext)` is called when a claim is confirmed. Quest Tree catches and logs exceptions. Permission, distance and similar checks are the handler's responsibility.
- **Conditions**: a task advances and a reward is given only when every entry in `conditions` is true. The five built-in types (`stage`, `score`, `item`, `advancement`, `party_size`) and registered types are evaluated by the same check. A type counts as built-in only when it has no namespace (`item`) or uses `kuronami_quest_tree:item`; any other namespace (`example:item`) always looks in the registry even when the id matches a built-in. Registered types are matched by their full namespaced id, and a type found in neither place is false (fail closed). `QuestTreeApi.conditionsMet(player, node, data, task)` is the public entry point that runs the same check.
- Registered types are excluded from `ProgressValidator`'s unknown-type warnings and errors. Conditions treat built-ins and registered types as known; anything else is a warning (the node is not dropped).

## Editing a registered type

The in-game editor draws a registered type beside the built-in ones. Without a declaration it shows the type's own members as one JSON row; describe the fields to get one row per member instead.

```java
ResourceLocation heldLevel = ResourceLocation.fromNamespaceAndPath("example", "held_level");

QuestTreeApi.registerTaskType(heldLevel, context -> context.player().experienceLevel);
QuestTreeApi.describeTaskType(heldLevel, List.of(
        QuestField.integer("level", "example.field.level"),
        QuestField.choice("scope", "example.field.scope", "", "self", "team")));

ResourceLocation prize = ResourceLocation.fromNamespaceAndPath("example", "prize");
QuestTreeApi.registerRewardType(prize, context -> context.player().addItem(new ItemStack(Items.DIAMOND)));
QuestTreeApi.describeRewardType(prize, List.of(QuestField.integer("count", "example.field.count")));
```

A path is written the same way Quest Tree's own fields are: dotted, with array indexes in brackets (`level`, `item.id`, `drops[0].chance`). An empty value clears the member. `kind` picks the control:

| `kind` | Control |
|---|---|
| `text` / `multiline` | a single line / a paragraph box |
| `int` / `decimal` | a number; text that does not parse is not written and the row says so |
| `bool` | a toggle; off removes the member |
| `enum` | a value cycled from `options`; `""` means not written |
| `item`, `entity`, `dimension`, `biome`, `structure`, `advancement` | an id with a search list; typing an id the list lacks still works |
| `ids` | a JSON array of ids, as one comma-separated line |
| `json` | any JSON value as compact text; unreadable text is not written and the row says so |

`label` is a translation key; a blank label shows the path. The type's own name in the picker is `screen.kuronami_quest_tree.editor.task.<id>` / `.reward.<id>` when a resource pack or lang file supplies it, and the id otherwise. Do not declare `type`, `title`, `icon`, `optional`, `text` or `imported_unmapped` (and for a reward `team`, `auto` or `exclude_from_claim_all`); the editor already draws them for every type, and a declaration for one is ignored. `describeTaskType(id, List.of())` removes an earlier declaration, and a type that declares nothing (or only shared members) keeps the JSON row.

A type with no declaration edits every member except the shared ones as one compact JSON object; leaving a member out removes it. Changing a type drops the members only the old type owned, so switching from a registered type to a built-in one removes what the registered type's row held.

On KubeJS (NeoForge), a script passes the same list as JSON text:

```js
QuestTree.registerTask('example:held_level', context => context.player().experienceLevel)
QuestTree.describeTask('example:held_level', '[{"path":"level","kind":"int","label":"example.field.level"}]')
```

## Opening a quest or chapter

```java
QuestTreeApi.open(player, "example:basics/start"); // one quest
QuestTreeApi.open(player, "example:basics");       // one chapter
```

The target is a quest key (`<namespace>:<chapter>/<node>`) or a chapter id (`<namespace>:<chapter>`); a value that is not in the loaded book returns false and sends nothing. The player's client applies its own hidden and locked rules, so this is the same path a quest giver uses and cannot reveal a hidden quest or a locked chapter's map. Safe to call from a server-side interaction handler; an NPC mod can open its own dialogue this way without touching Quest Tree's screens.

## Progression events

```java
QuestTreeApi.addListener(event -> {
    if (event.kind() == QuestTreeApi.QuestEvent.Kind.COMPLETE) {
        // event.player(), event.node().key, event.task()
    }
});
```

| `Kind` | When |
|---|---|
| `COMPLETE` | All of a quest's tasks are done |
| `CLAIM` | The last reward has been claimed |
| `TASK` | A task's progress reaches its target (`task` is that task's position) |

Listeners run on the server thread and are called once at that moment. `player` is the member who performed the action. An exception is logged and the next listener still runs.

## Replacing how a type looks (client)

`com.kuronami.questtree.api.client.QuestTreeClientApi` is the client half: a registered task or reward type keeps its built-in row unless the mod that registered it says what the row looks like. Register from the client side of your mod, once, before the book is opened.

```java
ResourceLocation level = ResourceLocation.fromNamespaceAndPath("example", "level");

QuestTreeClientApi.registerTaskDisplay(level, task -> new QuestTreeClientApi.Display(
        new ItemStack(Items.EXPERIENCE_BOTTLE),
        Component.translatable("task.example.level"),
        Component.literal((task.current() >= task.required() ? task.required() : task.current()) + "/" + task.required())));

QuestTreeClientApi.registerRewardDisplay(level, reward -> new QuestTreeClientApi.Display(
        new ItemStack(Items.DIAMOND), Component.translatable("reward.example.prize"), null));
```

| Field | Task row | Reward row |
|---|---|---|
| `icon` | the icon left of the name; empty keeps the built-in one | the icon in the slot; empty keeps the built-in one |
| `name` | the task's line; null keeps the built-in one | a line drawn under the icon row (where a reward table's name goes); null keeps the built-in one |
| `progress` | the right-aligned count; null keeps the built-in `have/count` | not drawn |

What arrives at the callback is what the pack wrote for that task or reward (`TaskView.json()`, `RewardView.json()`), plus `current()` / `required()` for a task. The JSON is already a copy: changing it changes nothing in the book.

```java
QuestTreeClientApi.unregisterTaskDisplay(level.toString()); // back to the built-in row
```

Registering an id again replaces the handler. If a callback throws, that row falls back to the built-in look, the first failure of that type is logged once, and the type is not asked again for the rest of the session. The class is client-only: a dedicated server never loads it.

## KubeJS (NeoForge only)

KubeJS 7 is NeoForge-only on 1.21.1, so the integration class lives only in `:neoforge`. KubeJS reads `kubejs.plugins.txt` from the jar; without KubeJS the classes are never loaded.

```js
QuestTreeEvents.questCompleted(event => console.info(event.quest))
QuestTreeEvents.taskCompleted(event => console.info(event.quest + ' #' + event.task))

QuestTree.registerTask('example:held_level', context => context.player().experienceLevel)
QuestTree.registerCondition('example:level_30', context => context.player().experienceLevel >= 30)
QuestTree.open(player, 'example:basics/start')
```

A type registered with `QuestTree.registerCondition` is written in the same `conditions` array as the five built-in types (`stage`, `score`, `item`, `advancement`, `party_size`), using its namespaced id. Built-in types work without any script registration. The context's node is null when the condition is used by a restriction rather than by a task or a reward, so an implementation must not rely on it.

A reload removes only the ids registered by that script kind (startup / server / client). Registering an id that already exists from a script is refused and returns false. Register at the top level of the script.

## Recipe viewers

JEI / REI / EMI are all optional. Each viewer's plugin swaps in `RecipeViewer`, so "open the recipe" from the quest screen and opening a quest from an item in the viewer use the same path. All three are `compileOnly` and no dependency is written into the jar.

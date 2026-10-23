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

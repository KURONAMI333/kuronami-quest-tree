# 他 MOD 向け API

Quest Tree の API は `common` の `com.kuronami.questtree.api` にあり、NeoForge と Fabric の両方で同じ形で使える。API を使う MOD は Quest Tree を `compileOnly` で参照し、実行時は任意依存にできる（Quest Tree が無ければ登録も呼ばれない）。

## 種類を登録する

```java
ResourceLocation id = ResourceLocation.fromNamespaceAndPath("example", "level");
QuestTreeApi.registerTaskType(id, context -> context.player().experienceLevel);
QuestTreeApi.registerRewardType(id, context -> context.player().giveExperienceLevels(5));
QuestTreeApi.registerConditionType(id, context -> context.player().experienceLevel >= 30);
```

同じ id の再登録は置き換え。`unregisterTaskType` / `unregisterRewardType` / `unregisterConditionType` もある（スクリプト再読み込み用）。

データ側は登録した id をそのまま書く。

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

- **タスク**: サーバーの tick で `QuestTaskType.progress(TaskContext)` を呼び、返した値を進捗として保存する。`count` / `amount` は `maxProgress()` で読める。`currentProgress()` はこのタスクの現在値。
- **報酬**: 受取が確定した時に `QuestRewardType.give(RewardContext)` を呼ぶ。例外は Quest Tree が捕まえてログする。権限・距離などの検証はハンドラ側の責任。
- **条件**: `conditions` の全項目が true の時だけ、タスクは進み、報酬は渡される。未登録の条件 type は false（fail closed）。条件はタスクの進捗書き込み・提出・報酬受取の各所で評価される。
- 登録した type は `ProgressValidator` の未知 type 警告/エラーから除外される。条件は未登録なら警告（ノードは落とさない）。

## 進行イベント

```java
QuestTreeApi.addListener(event -> {
    if (event.kind() == QuestTreeApi.QuestEvent.Kind.COMPLETE) {
        // event.player(), event.node().key, event.task()
    }
});
```

| `Kind` | いつ |
|---|---|
| `COMPLETE` | クエストのタスクが揃った時 |
| `CLAIM` | 最後の報酬を受け取った時 |
| `TASK` | タスクの進捗が目標に届いた時（`task` はその位置） |

リスナーはサーバースレッドで、その瞬間に 1 回だけ呼ばれる。`player` はその行動をしたメンバー。例外はログして次のリスナーへ進む。

## KubeJS（NeoForge のみ）

KubeJS 7 は 1.21.1 では NeoForge 専用なので、連携クラスは `:neoforge` にだけ入っている。jar の `kubejs.plugins.txt` を KubeJS が読み、KubeJS が無ければクラスはロードされない。

```js
QuestTreeEvents.questCompleted(event => console.info(event.quest))
QuestTreeEvents.taskCompleted(event => console.info(event.quest + ' #' + event.task))

QuestTree.registerTask('example:held_level', context => context.player().experienceLevel)
QuestTree.registerCondition('example:level_30', context => context.player().experienceLevel >= 30)
```

サーバースクリプトの再読み込み時は、スクリプトが登録した id だけを外す。Java の MOD が登録したものは残る。

## レシピビューア

JEI / REI / EMI はすべて任意。それぞれのプラグインが `RecipeViewer` を差し替え、クエスト画面の「レシピを開く」と、ビューア側のアイテムからクエストを開く操作を同じ経路にする。3 つとも `compileOnly` で、jar には依存を書かない。

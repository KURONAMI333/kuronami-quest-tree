---
name: progression-data-model
description: 汎用進行MODのデータの形（データパックの JSON）。FTB Quests と Pufferfish's Skills の両方を1つの型で表し、インポーターの変換先になる。実パック6本の使用実態から必要な項目を決めた。
updated: 2026-10-22
---

# データの形（案 v1）

置き場はデータパックの `data/<namespace>/progression/`。サーバーが読み、クライアントへ送る。文言は言語ファイル（`data/<namespace>/progression/lang/<locale>.json`）に置く。

決め方: 6本のパック（ATM10・FTB Skies 2・FTB Evolution・Prominence II・DarkRPG・Mystic Horizons）の定義を全部数え、使われている項目はすべて表せるようにした。

## 1つの型にまとめる

| このMOD | FTB Quests | Pufferfish's Skills |
|---|---|---|
| 章 `chapter` | chapter | category |
| 章の群 `group` | chapter group | （無い） |
| ノード `node` | quest | skill（配置）＋ definition（中身） |
| 開く条件 `requires` | dependencies ＋ dependency_requirement ＋ min_required_dependencies | 接続（normal）・root |
| 排他 `exclusive` | max_completable_dependents | 接続（exclusive）・exclusive_root |
| やること `tasks` | tasks | （無い） |
| 費用 `cost` | （無い） | cost・required_points・required_spent_points |
| 報酬 `rewards` | rewards ＋ reward table | rewards |
| ポイント `points`（章ごと） | （無い） | experience・level・exchange・spent_points_limit |
| 装飾 `decorations` | chapter images | background |

ノードは「やることを全部済ませる」「ポイントを払う」の片方か両方で開く。クエストとスキルを同じ章に混ぜることもできる。

## ファイル

```
data/<ns>/progression/
  settings.json                 全体の既定値（自動受取・チーム報酬・ロック時の文言・緊急アイテムなど）
  groups.json                   章の群（並び順・アイコン）
  chapters/<chapter>.json       章1つ（ノード・装飾・ポイントの設定）
  reward_tables/<table>.json    報酬テーブル（ランダム・1つ選ぶ・全部・ルートクレート）
  patches/<anything>.json       他のデータパックの章・ノードの一部だけを上書きする
```

`patches` は Mystic Horizons で必要と分かった。別MOD（SimplySkills）が持つスキルツリーの一部を、パック側から書き換えている。

## Translating a quest book

パックの本の文言はデータパック側の言語ファイルに置く。MOD 本体の UI（`assets/kuronami_quest_tree/lang/<locale>.json`）とは別物。

- 置き場は `data/<namespace>/progression/lang/<locale>.json`。1 locale 1ファイルのフラットな JSON オブジェクトで、インポーターもここへ書き出す。
- locale 名は Minecraft と同じ（`en_us`, `ja_jp`, `de_de`, `fr_fr`, `es_es`, `zh_cn`, `ru_ru`, `pt_br`, `ko_kr`）。プレイヤーには選択中の locale と `en_us` だけが送られ、無いキーは `en_us` に落ちる。
- キーの形（`<ns>` はデータパックの名前空間、`<chapter>` は `chapters/<chapter>.json` の名前）:
  - 章: `progression.<ns>.<chapter>.title`
  - 章の群: `progression.<ns>.group.<group>.title`
  - ノード: `progression.<ns>.<chapter>.<node>.title` / `.subtitle` / `.desc`
  - タスク名の上書き: タスクの `text` に書いた値を前置きにした `<text>.title`
  - `visibility.lock_hint`: キーを書けばその訳、キーでない文字列ならそのまま表示
- 値は文字列。`\n` で改ページ、FTB の色コード（`&a` など）も使える。翻訳キーではなくそのまま表示されるので `%s` の穴埋めは無い。
- locale を足すには `en_us.json` をコピーして値を訳し、キーとファイル名以外を変えない。
- リソースパックの `assets/<namespace>/lang/<locale>.json` もバニラの I18n として読まれるが、効くのは章題・群題・`lock_hint` だけ（`ClientModel.component` のフォールバック）。`subtitle` と `desc` はデータ側の言語ファイルしか見ないので、本文はデータパックに入れる。
- 翻訳されない文字列: ノードや章に直接書いた `title` / `subtitle` / `description` は言語キーより優先される。装飾の `hover` は配列の文字列をそのまま表示する（言語キーを引かない）。

## 章

```json
{
  "icon": {"id": "minecraft:iron_ingot"},
  "group": "main",
  "order": 3,
  "points": null,
  "background": {"texture": "ftb:textures/quests/backgrounds/mining.png", "fit": "fill_height"},
  "layout": "free",
  "decorations": [{"image": "ftb:textures/quests/ars/scribetable.png", "x": 8, "y": -2.5, "w": 4.2, "h": 3, "rotation": 0, "order": 0,
                   "visible_after": "pick", "click": {"type": "node", "target": "pick"}, "hover": "progression.<ns>.<chapter>.deco.1"}],
  "defaults": {"shape": "square", "size": 1, "hide_lines": false, "consume_items": false, "repeatable": false},
  "visibility": {"always_hidden": false, "lock_hint": null},
  "autofocus": "pick",
  "nodes": {"pick": {"...": "..."}}
}
```

- 題名・副題は言語ファイルのキー `progression.<ns>.<chapter>.title` から自動で引く。データに直接書いた文字列も受け付ける（その時は翻訳されない）。
- `points` がある章はスキルの章になる。
- `visibility.lock_hint` は、`points.unlocked_by_default: false` などで閉じている章を章一覧に灰色で出した時に、マウスを乗せると出る文言。言語キーか直接の文字列。省略時は「ロック中」。`visibility.always_hidden` の章は今までどおり一覧に出さない。
- `points.exchange.xp_levels` は交換1回に払うバニラのレベル、`level_limit` は交換できる回数（省略で無制限）。`cost_per_level` を書くと、その回までに買った数（最初は 0）で値を引く。`{"type": "values", "data": {"values": [5, 10, 20]}}` は買った数番目（最後を超えたら最後の値）、`{"type": "expression", "data": {"expression": "5 + level * 10"}}` は `level` に買った数を入れて評価する。両方ある時は `cost_per_level` が勝つ。Pufferfish's Skills の `exchange.json` からそのまま移す。
  - `cost_per_level` が読めない時（`data` が無い・オブジェクトでない、`values` が配列でない、値が数値でない、式が壊れている）は、その時点の `xp_levels` の固定費用に戻る。式は 256 文字・入れ子 32 段まで。超えた式も固定費用に戻す（パックのデータでサーバーを落とさない）。`ProgressValidator` はこれらを `WARNING` にする（章は動く）。
- `points.respec.cost` は画面の「振り直し」で章のポイントを返してもらう時に残して払うポイント（省略・null で無料）。管理者コマンドの respec は費用を取らない。
- 画面の「振り直し」「交換」はチームの持ち主だけが押せる（サーバーも同じ判定をする）。振り直しは、取った段の `points` 報酬を払い先の章のプールから引くので、払い先のプールが引けるだけ残っているかも見る。1つでも足りなければ、そのプール名を理由にして拒否する（別章のポイントを使い切ってから振り直して、その章をマイナスにする経路を塞ぐ）。振り直しの費用は返らない。FTB Teams の持ち主が読めない時は、そのチームでは誰も押せない。

```json
"points": {
  "per_level": "level^1.5 * 100",
  "level_limit": 24,
  "start": 0,
  "spent_limit": null,
  "unlocked_by_default": true,
  "sources": [{"type": "mine_block", "...": "Pufferfish's Skills と同じ計算式の書き方"}],
  "exchange": {"xp_levels": 30, "level_limit": 5},
  "pool": null,
  "stop_when_maxed": true,
  "reset_on_death": false,
  "respec": {"cost": null}
}
```

  `pool` に同じ名前を書いた章どうしは、経験値とポイントを共有する。

  経験値の式と経験値源の条件は、Pufferfish's Skills の書き方（expression・variables・operations）をそのまま受け付ける。FTB の公式パックが条件付きの式を多用しているため、別の書き方に置き換えると移行で必ず崩れる。

## ノード

```json
{
  "x": 2, "y": 0, "icon": {"id": "minecraft:iron_pickaxe"},
  "shape": "square", "size": 1, "icon_scale": 1, "milestone": false,
  "requires": {"nodes": ["ingot"], "mode": "all", "min": null,
               "points": 0, "spent_points": 0, "exclusions": 1, "mods": []},
  "exclusive": {"group": "hammer", "max": 1},
  "cost": 0,
  "tasks": [{"type": "item", "item": {"id": "minecraft:iron_ingot"}, "count": 3, "consume": false}],
  "rewards": [{"type": "item", "item": {"id": "minecraft:iron_ingot", "count": 4}}],
  "optional": false,
  "tasks_required": null,
  "ranks": null,
  "toggleable": false,
  "repeat": {"cooldown": 0, "max": null},
  "sequential_tasks": false,
  "visibility": {"hidden_until": "deps_visible", "requires": null, "hide_details_until_startable": false, "hide_text_until_complete": false, "invisible_until_tasks": null},
  "lines": {"hide_to_deps": false, "hide_to_dependents": false},
  "toast": true,
  "links": [],
  "detail_width": null
}
```

- 題名を書かないノードは、最初のタスクの表示名を題名にする（FTB Quests と同じ。DarkRPG は1,892個中1,777個がこれに頼っている）。
- 題名・副題・説明は言語ファイルの `progression.<ns>.<chapter>.<node>.title / .subtitle / .desc`。説明はページの配列で、`&a` の色コード・画像 `{image:...}`・改ページ・クリックで開くリンクを使える（ATM10 で色コード1,552行、改ページ63件）。
- `shape` は FTB Quests の11種類（square・rsquare・circle・diamond・hexagon・octagon・pentagon・gear・heart・none・既定）を受け付ける。このMODではタイルの外形として描き、満ちる表示はその形の内側で行う。hexagon は 2026-10-15 に描画から外し、四角で描く（本人「どう調整してもかっこわるい」）。
- `shape` をノードにも章の `defaults` にも書かないと、ノードの役割で形が決まる。`milestone: true` は五角形、`exclusive`（または `exclusive_with`）のあるノードはひし形、`repeat` のあるクエストは円、それ以外は四角。複数当てはまる時は節目、選択、繰り返しの順に優先する。節目は `size` を書かなければ 1.5 倍で描く。FTB Quests から取り込んだ章には `defaults.shape` を必ず書き出し、元のパックの形を保つ（2026-10-16）。
- `mode` は `all`・`any`・`all_started`・`any_started`。
- `exclusive` は、同じ `group` のノードのうち `max` 個までしか済ませられない。
- `tasks_required` は「タスクのうち何個済ませれば開くか」。null なら全部。
- `repeat` があるクエストは、受け取るたびにやり直しになる。`cooldown` は再開までの秒数、`max` は合計で何回できるか（省略で無制限）。やり直す行動（討伐・チェック・納品）が無いクエストは `cooldown` が無いと繰り返さない。繰り返すクエストの品のタスクは、持っているだけでは済まず納品になる。一度でも済んだ繰り返しクエストは、後続の条件と排他では「済み」として扱う。
- `ranks` は同じノードを段階的に取る（`[{"cost": 1, "rewards": [...]}, ...]`）。
- `visibility.requires` は「見えるようになる条件」で、開く条件 `requires` とは別に持てる。
- 章にも `requires`（Pack Stages の段階など）を付けられる。条件を満たさない人には章ごと見えない。
- `shared: false` は、そのノードの完了・タスクの数え上げ・繰り返し・クールダウンをチームではなくプレイヤーごとに持つ。報酬の受け取りは元からプレイヤーごと（`team` を書いた報酬だけチームで1回）。省略すると従来どおりチーム共有。

## タスク（`tasks[].type`）

`item`（count・consume・match_components・only_from_crafting）、`checkmark`、`kill`、`advancement`、`structure`、`biome`、`dimension`、`location`、`observation`、`stat`、`xp`、`fluid`、`energy`、`break`、`stage`、`custom`、`craft`、`use_item`、`right_click_block`、`interact_entity`、`tame`、`breed`、`ride`、`pickup`、`interaction`。どれも `optional`・`icon`・`title`・`conditions` を持てる。

操作系は FTB Quests の Task Screen の代わりに、プレイヤーの行動そのものを数える。`craft` は取り出した完成品の数（`item` で結果、`recipe` でレシピを絞る）、`use_item` は使った回数、`right_click_block` はブロックを右クリックした回数、`interact_entity` はモブを右クリックした回数、`tame`・`breed`・`ride`・`pickup` はそれぞれ手なずけ・繁殖・騎乗・拾得の回数。`interaction` は `action`（`craft`・`use_item`・`right_click_block`・`interact_entity`・`tame`・`breed`・`ride`・`pickup`）を1つ書く汎用形で、FTB Quests の取り込みが使う。`use_item`・`right_click_block`・`interact_entity` は `item`、`right_click_block` は `block`、`interact_entity` は `entity` でも絞れる。

`kill` は `entity` に加えて `entity_tag`、`name`（表示名の完全一致）、`nbt`（SNBT の部分一致）で対象を絞れる。

`fluid` は `fluid`（id または `#tag`）と `amount`（mB、既定1000＝バケツ1杯）。手持ちのうち一致する液体の合計を数え、バケツは1000として数える。`energy` は `amount`（FE）で、手持ちのうち最も多く蓄えた1つの値で数える（合計ではない）。Fabric には基盤のエネルギーAPIが無いため、`energy` は Fabric では 0 のまま（NeoForge のみ対応）。

`energy` の数え方は FTB Quests と違う（2026-10-19 に FTB Quests 2101.1.36 の現物で確認）。FTB Quests の `forge_energy` は、専用のブロック（Task Screen）へ流し込んだ量を足していく。Quest Tree にはそのブロックが無いので、手持ちのうち最も多く蓄えた1つの量で数える（Tenet も同じ代わり方）。何百万 FE を求めるクエストは、それだけ蓄えられる電池を持てば済む。

`observation` は `observe_type`（`block`・`block_tag`・`block_state`・`entity_type`・`entity_type_tag`）と `target`、`ticks`（既定0＝即時）。十字線を target に合わせ続けた連続tickを数え、外すと0に戻る。`break` は `block`（id または `#tag`、空ならどのブロックでも）と `count`（既定1）で、サバイバルとクリエイティブの両方で壊した数を数える（`count` で頭打ち）。

`requires` は Pufferfish's Skills の `required_*` も受ける。`points` は解放時に残っていなければならない章のポイント（既定 0）、`spent_points` はその章で既に使っていなければならないポイント（既定 0）、`exclusions` は排他の相手を何個取られたら閉じるか（既定 1）、`mods` は全て入っていなければならない MOD ID（既定は空）。`nodes` は `mode`（`all`・`any`・`all_started`・`any_started`、既定 `all`）と `min`（既定 0）で読み、`required_skills` は `nodes`／`min` に対応し、0 は `root` になる。満たしていない間もデータは消さず、ノードを `LOCKED` にする。既に1段以上取ったノードには `points`・`spent_points` を再適用しない（Pufferfish's Skills の `getSkillState` が取得済みを先に UNLOCKED として返すのと同じ。自分の取得で次の段がロックしない）。

`item` の数え方（2026-10-16）: `consume` が無ければ、今の手持ちの数をそのまま出す（減れば下がる）。`count` に一度届いたらそのタスクは済みで戻らない（FTB Quests と同じ）。「一番多く持った数」で止める FTB Quests の表示はやめた（本人が、止まった 8/16 を「拾った分の合計」と読んだ）。「新しく手に入れた数」や「手持ち＋クラフトで使った数」で数える案は採らない: 捨てて拾う・チェスト・機械・行き来できるレシピなど、想定しきれない抜け道で数を増やせる（本人「こっちが想定できない抜け道ができてしまう仕組みはまずい」）。何度かに分けて集めさせたい時は `consume`（納品）を使う。

## 報酬（`rewards[].type`）

`item`（random_bonus）、`xp`、`xp_levels`、`table`（mode: `random`・`choice`・`all`・`loot`）、`command`（permission_level・silent・elevate）、`advancement`、`stage`、`toast`、`attribute`、`points`、`tag`、`scoreboard`、`custom`、`dummy`、`currency`。共通の項目は `team`・`auto`・`exclude_from_claim_all`・`ignore_reward_blocking`（別名 `ignore_blocking`）・`conditions`・`icon`・`title`。

`stage` は Pack Stages（mod-100）へ段階を渡す。`"team": true` を付けると段階はチームの記録に残り、オンラインの全員へ Pack Stages のコマンドでも渡す（`"remove": true` で取り消す）。`/quest_tree team_stage add|remove <players> <stage>` でも操作できる。

`dummy` は何も渡さない。報酬欄にアイコンだけを出したいとき（FTB Quests の dummy 報酬）に使う。取り込み時に未知の任意報酬だった場合は、元の種類を `original_type` に残す。

`points` の `amount` は章のポイントに足す（省略時 1）。数値でない・0 以下なら、付与でも取り消しでも 0 として扱う（片方だけ負をクランプしない）。`chapter` を書かなければそのノードの章、書けばその章の ID（`<ns>:<chapter>`）に入る。画面とコマンドの振り直しは、取った段の `points` 報酬のうち、払った記録にあるものだけを払い先の章のボーナスから一度だけ引く。取得時に条件を満たさず払わなかった `points` は記録が無いので、振り直しの判定にも取り消しにも数えない。`xp`・`xp_levels`・`item`・`table`・`advancement`・`command`・`currency`・`custom` は、同じ人には同じ段を一度しか渡さない（振り直して取り直しても二重にならない。`custom` はアドオンのイベントも一度だけ）。ただし `lock_command` を持つ `command` は例外で、取り直すたびに `command` を実行する（振り直しで `lock_command` が走った後、もう一度解錠する必要があるため）。払った記録はチームに1つ、報酬ごとのキー `<node>#<rank>#<index>`（`paid_skill_rewards`）で持ち、チームのだれが取っても1つ。払えた時だけ記録し、条件未達・支払い失敗・受け取りを断った選択式テーブルでは記録しないので、次の取得で払える。振り直しで取り消せる報酬（`points`・`tag`・`attribute`・`lock_command` 付き `command`）は、この記録にあるものだけを1回ずつ取り消して記録を消す。一回限りの報酬は取り消さず記録も残す。旧セーブ（`paid_skill_rewards` キーが無いチーム）は初回の読み込みで一度だけ移行し、移行後は空でもキーを書くので、新しい版のセーブを再び「全部払った」扱いにしない。移行では旧形式の印（`<node>#skill<rank>`・`<node>#skill<rank>#<index>`）を記録に変えたうえで、取得済みのスキルノードのその段までの取り消せる報酬（`points`・`tag`・`attribute`・`lock_command` 付き `command`）を全部記録する（印が1つだけでもノード全体を対象にする）。記録が1つも無いノードは、その段までの報酬を全部「払った」として記録する（この版より前は条件が無く、当時は全部払われていた）。章ロック（`chapterOpen` を false）は効果だけを取り消し、`points`・`attribute`・`tag`・`lock_command` 付き `command` の記録は残す。アンロックは、その記録にある `attribute`・`tag`・`lock_command` 付き `command` を全メンバーへ戻す（記録の無い報酬は戻さない）。

`currency` は `amount` を取り、FTB Library の通貨フックに登録された経済 MOD から支払う。FTB Library か経済 MOD が無ければログに1度だけ出して何も渡さない（クエストは完了する）。

`trophy`（`{"type": "trophy", "trophy": "<ns>:<id>"}`）は Kuronami's Trophies（mod-108）のトロフィーを渡す（`trophy award @s <id>` を実行。同じ人には1回だけ・刻印は受け取った時）。画面では受け取る前から実物のトロフィーを報酬欄に出す。Kuronami's Trophies が入っていなければ何もしない。どの報酬も `icon` を書けば報酬欄にそのアイコンを出す（`command` 報酬など、他に出すものが無い報酬向け）。2026-10-15 追加。

## 条件（`conditions`）

タスクにも報酬にも `"conditions": [...]` を書ける。全部満たしたときだけ、タスクは数えられ、報酬は渡される。条件を満たさない報酬は未受取のまま残り、あとで満たせば受け取れる。

判定は1つの入口だけを通る。組み込みの5種類と、他 MOD・KubeJS が登録した種類を同じ配列に混ぜて書ける。

条件は取得時に1回だけ評価する。報酬の取り消し（振り直し・リセット）とログイン時の再付与は、その時に払った記録（`paid_skill_rewards`）に従い、条件を評価し直さない。取得時に満たさず渡していない報酬は記録が無いので、取り消しでも再付与でも触らない。スキルの `points` 報酬も、記録にある分だけを振り直しの判定と取り消しに数える（2026-10-22）。スキル報酬の条件は取った人で判定し、効果（`attribute`・`tag` など）はチームの全員に付く。記録はチーム共有（2026-10-22）。

```json
{"type": "stage", "stage": "chapter/one"}
{"type": "score", "objective": "points", "min": 10}
{"type": "item", "item": {"id": "minecraft:diamond", "count": 3}}
{"type": "advancement", "advancement": "minecraft:story/root", "criterion": null}
{"type": "party_size", "min": 2}
{"type": "example:enabled"}
```

`stage` は Pack Stages の段階（`team_stages` も含む）、`score` はスコアボードの値、`item` は手持ち、`advancement` は進捗、`party_size` はオンラインのパーティ人数。`score` は `scoreboard` でも書ける。組み込みの5種類は名前空間なし（`item`）かこの MOD の名前空間（`kuronami_quest_tree:item`）で書け、どちらも組み込みとして読む。他の名前空間（`example:item`）は id が組み込みと同じでも組み込みにはせず、必ず登録表を引く。

登録した種類は `<namespace>:<id>` で書き、`QuestTreeApi.registerConditionType` か KubeJS の `QuestTree.registerCondition` で登録する（[_docs/API.md](API.md)）。登録した種類は名前空間込みの id で照合し、その項目の判定はハンドラに任せる。どちらにも無い種類は条件を満たさない扱い（書いたゲートを開けない側に倒す）。検証では未登録の種類を `WARNING` にし、ノードは落とさない。

## ブロック

`quest_task_block`（クエストボード）は右クリックで、ブロックに記録したクエストを開く。`quest_barrier`（クエストの関所）は、記録したクエストが完了しているか段階を持っているプレイヤーだけ通す（他のエンティティは通さない）。どちらもキーは設置したアイテムのカスタムデータからブロックエンティティへ移る。管理者は次で配れる。

```
/quest_tree block task <players> <node>
/quest_tree block barrier <players> node <node>
/quest_tree block barrier <players> stage <stage>
```

## 報酬テーブル

```json
{"rolls": 1, "empty_weight": 0, "entries": [{"weight": 10, "reward": {"type": "item", "item": {"id": "minecraft:diamond"}}}],
 "loot_crate": null}
```

## 他MODからの拡張

タスク・報酬・経験値源の種類は、他MODが登録できる（Pufferfish's Skills の `passive_skill` のように、追加MODが種類を足している実例がある）。

## 未決

- エディタ（ゲーム内でノードを置く画面）の保存先。データパックに書き戻すか、`config/` に書くか。
- `custom` タスク・報酬の API（KubeJS などから進み具合を動かす入口）。

## 検査

データパックの読み込み時（`ProgressData.reload`）に、全ファイルを一度検査する。検査はゲームに依存しない（レジストリ照会だけを差し替え可能な入口にしてあり、JUnit で実行できる）。見つかった問題は `ERROR` か `WARNING` のどちらかで、`file:line path - message` の形でログに出る。件数の要約は 1 行、`/quest_tree validate` で先頭 20 件を op（permission level 2）が見られる。op が参加した時は、エラーがあればその場で 1 行だけ知らせる。エラーがあってもパック全体は止めない。検査そのものが例外で落ちた場合も、その 1 件を `ERROR` として記録し、読み込みは続ける。検査の後に `ProgressModel.of` が例外を投げた場合（検査が見落とした形）は、その 1 件を `ERROR` として記録し、前回の内容をそのまま使う: サーバーがモデルにできないものを公開すると、クライアントにサーバーが持たない進行を渡すことになるため。

- `ERROR` は「書かれた通りに動かない」もの。読み手が例外を投げる（サーバーの tick が止まる）、読み手がタスクを捨てる（そのノードが読まれた瞬間に完了扱いになる）、または解放条件が変わって書いたゲートが消える。そのノードだけをモデルから外し、他は動かし続ける。
- `WARNING` は「動くが書かれた通りではない」もの。報酬が黙って飛ばされる（`Rewards.give` が読み手の例外を捕まえる）、既定値に落ちる、開く条件が書いたより緩い、など。ノードは残す。
- 報酬側の問題は原則 `WARNING`（`Rewards.give` が全体を捕まえるため）。唯一の例外は報酬テーブルが自分自身を含む場合で、渡すと再帰が止まらず `StackOverflowError`（`Error` なので捕まらない）になるため `ERROR`。テーブルは外せないので、それを渡すノードを外す。
- 参照の重大度は、書いたゲートがどうなるかで決める。`ProgressModel` は解決できない名前を落として読むので、`requires` の名前が解決しないとノードは書いたより早く開き得る。`mode` が `all`（既定）で `min` が無い（または 0 以下）なら、ゲートごと消えるので `ERROR`。`any`・`one`・`min` がある場合は同じ数を要求し続けるので `WARNING`。`exclusive_with` の名前が解決しないのは、競合の表示が消えるだけでノードは書いた通りに開いて完了するため（`ProgressRules.excluded` しか見ない）常に `WARNING`。
- 存在しない `requires` と、外したノードを指す `requires` は同じ規則・同じ重大度にする。実行時はどちらも「名前が解決しない」同じ状態になるため。前者では `ERROR` のノードを外し、後者の `ERROR` は外さない（無関係なタイポ1つのために、書いたノードまで消さない）。`unreachable` で外れたノードを指す場合も同じ。
- 到達性: `ProgressRules.unlocked` と同じ規則を不動点反復で調べる（最初に開くノードから始めて、条件の満たされたノードを開き続け、それ以上変わらなくなった時点で閉じたままのノード）。プレイヤーがどうプレイしても開かないノードは `ERROR` で外す。`root` は何を書いていても開く（`ProgressRules.unlocked` と同じ）。ループ探索だけでは誤検知する: Puffish 取り込みは双方向リンクの両側を `mode: "any"` の条件にするので、`A → B → A` の片側が `any` のループは正常（もう片方の枝から開く）。
- 型: タスク・報酬の `type` が既知か。未知は、タスクは `ERROR`（そのタスクは永遠に数えられない）、報酬は `WARNING`（何も渡らずに飛ばされる）。`observation` の `observe_type` が未知なのは `ERROR`。
- 必須の項目（`item` の `item`、`fluid` の `fluid`、`location` の `position` 3 要素、`observation` の `target` など）。タスク側の欠落は `ERROR`、報酬側の欠落は `WARNING`。
- 数の型。`count`・`amount`・`ticks`・`item.count` と `location` の `position`・`size` はサーバーの tick で `getAsLong`・`getAsInt` に渡る。`count`・`ticks` は `ProgressRules.target` が `isJsonPrimitive` で囲んでいて読めない形を読み飛ばすので `WARNING`（既定の 1 になる）、それ以外（`amount`・`item.count`）と、読める形の非整数（`"many"` や `1.5`）は捕まえる場所が無いので `ERROR`（Gson は数値を表す文字列 `"16"` を通すので、それは `ERROR` にしない）。
- tick で `getAsBoolean` に渡る `optional`・`consume` は、オブジェクト・配列・null だと例外になるので `ERROR`、数値や語は false として読まれるので `WARNING`。
- `rolls`・`weight`・`empty_weight`・`min`・`max`・`cost`・`tasks_required`・attribute の `value`・xp の `amount` は既定値に落ちるか `Rewards.give` に捕まるので `WARNING`。
- 0 以下: `count`・`amount` は `WARNING`（`ProgressRules.target` が `Math.max(1, …)` で 1 にする）。`weight` が 0 以下なのは `WARNING`（その `entries` は抽選で決して選ばれない）。`break` の `block` が空なのは「どのブロックでも」という意味なので警告しない。
- `repeat`: `repeatAfterClaim` は `done + 1 >= max` で判定するので `max` が 1 以下だと一度も繰り返さない（`WARNING`）。`cooldown` は claim の確定時に `getAsLong` に渡るので非整数は `ERROR`、0 以下は「クールダウン無し」として読まれる（`WARNING`）。`max` に数値でない形を書くと上限無しとして読まれる（`WARNING`）。
- 報酬テーブル: `entries` の要素がオブジェクトでない場合と、`weight` が読めない場合は、どちらも `Rewards.table` が渡す物を組む途中で落ちるので、そのテーブルは何も渡さない（`WARNING` の本文にそう書く）。
- 形: 章がオブジェクトか、章の `nodes` がオブジェクトか（違えば `ERROR`。読み手は `obj(...)` で空のオブジェクトとして読むので、その章は空になる）、`nodes` の各値がオブジェクトか（違えば `ERROR`、読み手は読み飛ばす）。`tasks` が配列か、`rewards`・`ranks`・報酬テーブルの `entries` が配列か。`requires` がオブジェクトか、`repeat` が真偽値かオブジェクトか。`requires.nodes`・`exclusive_with` が配列か、その要素が名前として読めるか（数値・真偽値は読み手が `getAsString` で文字列に直すので名前として解決し、オブジェクト・配列・null は例外になるので `ERROR`）。`root`・`optional`・`hidden` が真偽値か、`mode`・`group` が文字列か、`shape`・`milestone` が文字列か。報酬テーブルの `mode` も `Rewards.give` が `getAsString` で読むので文字列か（違えばその報酬が飛ばされる `WARNING`）。既定値に落ちるものは `WARNING`、例外になるものは `ERROR`。
- 同じ名前を二度宣言していないか（`WARNING`。Gson は後勝ちで黙って読むので、テキストを見て検出する）。
- JSON の構文。読めなかったファイルは `ERROR`。その章への参照は存在を確かめられないので `WARNING` に留める。
- id の照会は、実在するアイテム・ブロック・流体・エンティティ・attribute だけを見る（`GameRegistry`）。無ければ `WARNING`、名前空間が無ければ `WARNING`。タグ（`#`）と動的レジストリ（biome・structure・dimension・stat）は読み込み中に内容を確かめられないので照会しない。タグの内容は `TagManager.updateRegistryTags` が全 reload listener の完了後に初めて確定するため、listener の実行中に引くと初回起動では空・`/reload` では前回の内容になり、どちらも偽の警告になる。
- 知っているがまだ動かない種類（報酬テーブルの `loot_crate`）は `WARNING`。
- 章をまたいだノード ID の重複は検査しない: ノードのキーは章の ID で修飾されるので衝突せず、同じ ID を別の章に置くのは正常（GameTest のパックが実際にそうしている）。

## 章の背景（`background`、2026-10-19）

章の地図の後ろに絵を何枚でも重ねる。下から順に描く。1枚なら止まった背景、2枚以上を違う速さにすると、地図を動かした時に奥行きが出る（Thaumaturge の研究画面の型）。絵はリソースなので、リソースパックで差し替えられる。

```json
"background": {"layers": [
  {"texture": "mypack:textures/gui/backgrounds/nebula.png", "speed": 0.3},
  {"texture": "mypack:textures/gui/backgrounds/stars.png", "speed": 0.6, "alpha": 0.8}
]}
```

- `texture`（必須）: 絵のフルパス。
- `speed`: 地図と一緒に動く割合。1 = 地図と同じ、0 = 動かない（既定 0.5）。拡大縮小も同じ割合で追う。
- `tile`: 敷き詰める（既定 true）。false なら1枚を画面いっぱいに引き伸ばす。
- `size`: 拡大1倍の時の絵の幅（GUI ピクセル、既定は絵のピクセル幅）。
- `alpha`: 0〜1（既定 1）。
- 短縮形: `"background": "mypack:textures/..."` は、動かない1枚。
- Quest Tree 自体は背景の絵を持たない。無ければ、パレットの `ground` の色のまま。

## Task and reward decisions (2026-10-21 review)

Decisions taken in the 2026-10-21 task/reward review. They override older notes where the two differ.

### Pickup tasks (review item 4)

`pickup` counts an item entity taken by a player. An item whose `owner` is the picking player does not count, so a
player cannot finish a pickup task by dropping and re-taking the same stack. This is the only anti-farming rule:
crafting an item and uncrafting it in a loop still counts, because the tree does not keep a per-player item history.
Pack authors who need more should use a `craft` or `consume` task instead.

### Reward blocking (review item 6)

`/quest_tree rewards block` stops the **pay-out of quest rewards**, not the quest or skill itself.

- Tasks, node completion, quest claims and taking a skill continue while rewards are blocked.
- Only quest rewards are affected. Skill node rewards (the take, a respec, a rank, and a member's own command or tag)
  are never blocked and never enter the pending list; taking a skill is never stopped (2026-10-22).
- A blocked quest reward is recorded as taken for its node and kept in the player's pending list. When the block is
  lifted, the pending rewards are paid in their original order.
- A repeatable quest starting its next round keeps the pending rewards of the round it just claimed, so a blocked
  pay-out is still owed afterwards. A reset of the node forgets them, together with the node's saved reward rolls.
- The block is judged per quest reward. `attribute` rewards are part of the skill effect and are always applied, even
  while quest pay-outs are blocked.
- `ignore_reward_blocking` / `ignore_blocking` still exempt one quest reward.
- Old saves may still hold skill pending keys (a rank of 0 or more). Loading drops them and logs the count instead of
  paying them: a skill taken while blocked already had its effect, and login re-applies its attributes.

### Blocking rewards (review item 8)

`/quest_tree rewards block|unblock` accepts a player who is offline (a name from the server's profile cache). The flag
and the pending quest rewards live in that player's own record. Lifting the block pays immediately for an online
player; for an offline player the pending quest rewards are paid at the next login, where a still-blocked flag keeps
them waiting.

### Stages (review item 6)

`teamStagesApplied` is the record of the team stages this player currently holds; the team's `teamStages` is the
wanted set. On login the difference is removed or added, and then every stage in the player's own `stages` list is
added again. That re-add is deliberate: `p.stages` records rewards this player was granted directly, and the stage mod
may not report them back after a relog. Removing a team stage therefore skips keys that are still in `p.stages`, so a
rewarded stage is not taken away with the team's copy.

### Personal-node routing (review item 5)

The saved record keeps the set of node keys that were routed as personal. The caller checks whether the loaded model
has any node at all: a model that reads as empty leaves that set alone, so an unreadable pack file cannot move saved
personal progress into the shared record. An empty set with a loaded model is a real change — the pack made every
node shared — and its saved personal values are moved to the shared record.

### Currency rewards (review item 3)

A `currency` reward is paid through FTB Library's currency hook. Without an economy mod that registers a provider,
the reward is recorded as taken and pays nothing; the log says so once, and the validator warns pack authors on load.
Only a provider that is present but fails to pay keeps the reward unclaimed, so the node can be claimed again.

### Completion with unmet reward conditions (review item 9)

A reward whose `conditions` this player does not meet is skipped. It does not keep `allRewardsClaimed` false and does
not hold the node in `CLAIM`: the node finishes and, if repeatable, can repeat. The old note that such a reward stays
unclaimed forever is withdrawn; the reward is simply not offered to that player for that completion.

### Personal stages and team stages (review items 3 and 10)

`stages` is the player's copy of the stages the quest tree granted to them. Stage barriers open when the key is in
`stages` or in the shared `teamStages`. If it is in neither, both sides ask Pack Stages: the server checks the player's
saved stages, and the client reads the copy Pack Stages syncs to it. A stage granted by another mod therefore opens the
gate on both sides. A team stage this player was given is tracked in `teamStagesApplied`; on login only the stages the
team no longer holds are removed, so an offline removal is followed without stripping stages another mod granted.

### Reward tables with a failing entry (2026-10-22 review)

A table pays its entries in order. An entry that was paid is marked in the player's saved `rewardRolls`, so a retry
after a later entry failed hands out only the entries still missing. When the whole table is through, the marks and
the recorded roll are removed together. A `choice` table pays one entry, so it has nothing to mark.

# ゲーム内エディタの上書き層（Phase 1）

サーバー側の保存形式と操作。UI はまだ無く、クライアントは `EditorOpPayload` を送り `EditorReplyPayload` を受け取る。

## 保存先と優先順

```
<world>/kuronami_quest_tree/progression/
  chapters/<namespace>/<chapter path>.json
  revisions.json
```

読み込みは低い順に、data pack の章・報酬テーブル・グループ → data pack の `patches/*.json` → この上書き層。上書き層は章単位で、pack の章を消すこともできる。`/reload` は要らない。保存のたびに `ProgressData` が層を適用し、検証してから generation を上げて全員へ送り直す（1 tick に最大1回）。

## 章ファイル

```json
{
  "revision": 3,
  "base": true,
  "replace": false,
  "remove": false,
  "edited": false,
  "chapter": { "title": "..." },
  "removed": ["subtitle"],
  "nodes": {
    "a": { "action": "replace", "node": { "x": 1, "y": 2 } },
    "b": { "action": "remove", "node": { "...": "削除時の内容" }, "edited": true },
    "c": { "action": "add", "node": { "...": "新しいノード" } }
  }
}
```

- `revision`: 章ごとの版番号。クライアントは最後に見た版を送り、古ければ拒否される。
- `base`: 最初に編集した時、pack に章があったか。
- `replace`: `chapter` を pack の章に重ねず、そのまま使う。`create_chapter` は true。
- `remove`: 章の墓石。`chapter` に削除時の内容を残す。
- `edited`: 墓石にした時、それ以前に上書き層で変更していたか。復元時に「pack の元の章」と「作者の版」を選ぶために使う。
- `chapter`: 編集で変わったトップレベルのキーだけを、**変更後の値ごと**持つ。`replace` が false なら pack の章へキー単位で重なり、`nodes` は下のノード指定だけが効く。入れ子の一部だけを変えても兄弟は消えない（例: `display.title` を変えても pack の `display.icon` は残る）。
- `removed`: RFC 7396 の `null` で pack の項目を消した時の、トップレベルのキー名。`replace` が false の時、`chapter` を重ねる前にこのキーを pack から外す。`create_chapter` のように `replace` が true の章では使わない。
- `nodes`: `action` は `add` / `replace` / `remove`。`remove` は内容を残すので復元できる。`edited` は削除前に変更があったか。
- `revisions.json`: 章ファイルを消した取り消しでも版番号を巻き戻さないための小さな控え（`{"<chapter id>": 版}`）。章ファイルの `revision` と控えの大きい方を使う。クライアントは同期バンドルの `revisions`（章 id → 版）で現在の版を知る。

手書きする場合、`action` を省くと `add` として読む。読めないファイルは ERROR として報告し、その章への書き込みは拒否する。

## 操作（`EditorOpPayload.op`）

`chapterId` は `<namespace>:<chapter path>`。`create_chapter` だけは章がまだ無いので空文字を送り、`args.namespace` で名前空間を指定する。新 ID はサーバーが発番し、返答の `createdId` に入る。

| op | args |
|---|---|
| `create_node` | `{"node": {...}}` |
| `update_node` | `{"id": "...", "patch": {...}}`（RFC 7386。`null` はキー削除） |
| `move_node` | `{"id": "...", "x": 0, "y": 0}` |
| `delete_node` / `restore_node` | `{"id": "..."}` |
| `create_chapter` | `{"namespace": "...", "chapter": {...}}`（初期ノードの id はサーバーが振り直し、初期ノード同士の `requires` / `exclusive_with` も書き換える） |
| `update_chapter` | `{"patch": {...}}`（`nodes` は不可。今の章全体に対する RFC 7396 で、`null` は pack の項目も消す） |
| `delete_chapter` / `restore_chapter` | `{}` |
| `link` / `unlink` | `{"from": "...", "to": "..."}`（`to` は同じ章ならノード名、別章なら `chapter/node`） |
| `undo` / `redo` | `{}` |

ノード id は `[a-z0-9_]` のみ。それ以外を含む id は受け付けない。ID の改名は無い（参照・排他・翻訳キー・プレイヤーの進行を一度に壊すため）。

## 検証と取り消し

- 書き込み前に候補の章を `ProgressValidator` に通す。本全体の ERROR 集合を前後で比べ、**新しい ERROR** が1つでも出たら書かない（他章の前提を壊す削除もここで止まる）。既存の ERROR は編集を妨げない。
- 拒否時は返答に検証メッセージが入り、ファイルは変わらない。
- 取り消しは章ごとのスナップショット、直近50手（同じ端から積み、同じ端から戻す）。サーバー再起動では消える。ディスクに残るのは墓石、`.bak`（書き込み直前の版）、`revisions.json`。
- 書き込みは同じフォルダの一時ファイルに書いてから置き換える（`ATOMIC_MOVE`、無い環境では `REPLACE_EXISTING`）。途中で落ちても半端な章ファイルは残らない。

## 通信の大きさ

バニラのサーバー行きカスタムペイロードは 32767 バイトまでで、Fabric は C2S を分割しない。超えたペイロードはクライアント側のエンコードで落ち、返答も残らない。そのため `EditorOpPayload` は 7000 文字ずつのチャンクに分けて送り、サーバーの `EditorOpAssembler` がプレイヤーごと・`requestId` ごとに組み立ててから操作を実行する。分割位置は 1 個の int `chunk` に `(parts << 8) | (part + 1)` として詰める（0 は分割なし。`part()` / `parts()` が読み出す）。1個で収まる要求は今まで通り `part=0, parts=1`。262144 文字を超える要求は送る前に拒否する。

## コマンド

- `/quest_tree edit on|off`（引数なしで切り替え）: OP レベル2 かつ編集モードがオンの時だけ操作を受け付ける。ログアウト・サーバー停止で解除。
- `/quest_tree export <namespace> [force]`: 検証済みの本を `datapacks/quest_tree_export_<namespace>` に通常の data pack として書き出す。有効化も `/reload` もしない。名前空間は `[a-z0-9_.-]` のまま章の照合に使い、フォルダ名だけを安全な文字に直す（`-` や `.` を含む名前空間でも 0 章にならない）。その名前空間の翻訳（`progression.<ns>.` で始まるキー）も `lang/` に入れる。検証で落としたノードの数はチャットに出す。既にあるフォルダは消さずに拒否し、`force` を付けた時だけ書き換える（`ImportOutput.writeTo` は `data/<ns>/progression` を先に消すため）。

## Phase 2: マップ上の編集（2026-10-20）

- サーバーが `EditorStatePayload` で「編集してよいか」（権限2 かつ `/quest_tree edit on`）を参加時と切替時に送る。許可があるとタイトル行のピン横に歯車が出て、E でも切り替わる。
- 選択（クリック・Ctrl+クリック・Shift+ドラッグの矩形・Ctrl+A）、ドラッグ移動（半グリッド、Alt で無効）、矢印キー（Shift で5）、右クリックのメニュー、Ctrl+C/V/D、Delete、L を押しながらのドラッグかメニューの「ここからリンク」、Ctrl+Z / Ctrl+Shift+Z / Ctrl+Y。EditBox にフォーカスがある間はキーを奪わない。
- 操作は `EditorSession` が1件ずつ送り、前の返答の revision を次に使う。移動は返答前から仮の位置で描き、拒否されたら元に戻す。拒否の文面は検証メッセージをそのまま短く出す。古い revision はサーバーが bundle を再送する。
- 貼り付けはサーバーが新しい id を付ける。作成の返答で旧 id と新 id を対応させ、コピー内のリンクを後から張る。
- ネットワークのプロトコル版は 3（編集状態の payload が増えたため）。

## Phase 3: 中身の編集画面（2026-10-21）

- 右クリックの「編集」、ノードのダブルクリック、選択が1件の時の Enter で `NodeEditScreen` が開く。ノードの題名・説明・形・大きさ・アイコン・可視条件・前提条件・繰り返しを1枚に並べ、タスクと報酬はカードで追加・並べ替え・削除する。カードを開くと `ContentEditScreen` が型ごとの欄を出す。
- 型は `ContentSchema` が唯一の定義で、画面と往復テストが同じ表を読む。型を変えると、その型だけのキーを消し、共通の欄と未知のキーは残す（`retypeTask` / `retypeReward`）。
- アイテム・エンティティ・進捗・テーブルなどは `EditPickerScreen` で検索して選ぶ。一覧に無い id も入力できる（タグや、クライアントが持たない MOD の id のため）。
- 画面は JSON の作業コピーを持ち、閉じる時に `update_node` / `update_chapter` の RFC 7396 patch を1回だけ送る。打鍵ごとの書き込みはしない。拒否された時は検証メッセージを該当の欄の下に出し、画面は閉じない。古い revision はサーバーが bundle を送り直し、画面は地図の短い通知に文面を残して閉じる。
- 章の設定は `ChapterEditScreen`（題名・アイコン・既定値・可視性・スキルポイント・章の削除）、章の新規作成は `NewChapterScreen`（名前空間と任意の題名。id はサーバーが発番）。
- 報酬テーブルは `TableEditScreen` で丸ごと編集する（`update_table`、テーブル専用の revision）。存在しない id を報酬の `table` に書いて開くと、保存時に `create: true` で作る。
- 削除した章・ノードは、同期バンドルの `deleted` に入る。`deletedJson()` は墓石の章 id と、生きている章ごとの削除ノード id を返す。`RestoreScreen` はこの一覧から `restore_chapter` / `restore_node` を送る。
- 画面の見た目は `Ui` / `Tiles` / `EditForm` の既存の面と行だけを使い、色を直書きしない。文字はラベル・値・状態・失敗理由だけで、説明文は置かない。

## Phase 4: 操作ボタン（2026-10-21）

- 地図の右クリックメニューに「章」「新しい章」「削除済み」「書き出し」「プレイヤーとして試す」を足した。どれも `EditorSession` の1件ずつの列に乗る。
- `export`（`args.namespace`、無ければ章の名前空間）は `/quest_tree export <namespace>` を実行し、書き出し先のフォルダを返答の行に返す。地図の通知にそのパスが出る。
- `test_chapter`（`chapterId`）は `/quest_tree reset @s <chapter>` を実行してから編集モードを切る。章が本に無ければ何もせず拒否する。返答は要求の revision をそのまま返す（章を書かないため、クライアントの版を動かさない）。
- どちらも名前空間・章 id を `ProgressExport.validNamespace` / `ProgressionEditor.validChapterId` で確認してからコマンド文字列にする（`EditorOps`）。

## まだ無いもの

- 実機（runClient）での確認。
- 章をまたぐリンクの作成 UI。
- 翻訳ファイルそのものの編集（表示文字の上書きまで）。
- 章の `revision` を跨いだ自動併合（古い版は拒否して再取得）。

# ゲーム内エディタの上書き層（Phase 1）

サーバー側の保存形式と操作。UI はまだ無く、クライアントは `EditorOpPayload` を送り `EditorReplyPayload` を受け取る。

## 保存先と優先順

```
<world>/kuronami_quest_tree/progression/
  chapters/<namespace>/<chapter path>.json
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
- `chapter`: `replace` が false なら pack の章へキー単位で重なり、`nodes` は下のノード指定だけが効く。
- `nodes`: `action` は `add` / `replace` / `remove`。`remove` は内容を残すので復元できる。`edited` は削除前に変更があったか。

手書きする場合、`action` を省くと `add` として読む。読めないファイルは ERROR として報告し、その章への書き込みは拒否する。

## 操作（`EditorOpPayload.op`）

`chapterId` は `<namespace>:<chapter path>`。`create_chapter` だけは章がまだ無いので空文字を送り、`args.namespace` で名前空間を指定する。新 ID はサーバーが発番し、返答の `createdId` に入る。

| op | args |
|---|---|
| `create_node` | `{"node": {...}}` |
| `update_node` | `{"id": "...", "patch": {...}}`（RFC 7386。`null` はキー削除） |
| `move_node` | `{"id": "...", "x": 0, "y": 0}` |
| `delete_node` / `restore_node` | `{"id": "..."}` |
| `create_chapter` | `{"namespace": "...", "chapter": {...}}` |
| `update_chapter` | `{"patch": {...}}`（`nodes` は不可） |
| `delete_chapter` / `restore_chapter` | `{}` |
| `link` / `unlink` | `{"from": "...", "to": "..."}`（`to` は同じ章ならノード名、別章なら `chapter/node`） |
| `undo` / `redo` | `{}` |

ID の改名は無い。参照・排他・翻訳キー・プレイヤーの進行を一度に壊すため。

## 検証と取り消し

- 書き込み前に候補の章を `ProgressValidator` に通す。本全体の ERROR 集合を前後で比べ、**新しい ERROR** が1つでも出たら書かない（他章の前提を壊す削除もここで止まる）。既存の ERROR は編集を妨げない。
- 拒否時は返答に検証メッセージが入り、ファイルは変わらない。
- 取り消しは章ごとのスナップショット、直近50手。サーバー再起動では消える。ディスクに残るのは墓石と `.bak`（書き込み直前の版）。

## コマンド

- `/quest_tree edit on|off`（引数なしで切り替え）: OP レベル2 かつ編集モードがオンの時だけ操作を受け付ける。ログアウト・サーバー停止で解除。
- `/quest_tree export <namespace>`: 検証済みの本を `datapacks/quest_tree_export_<namespace>` に通常の data pack として書き出す。有効化も `/reload` もしない。

## Phase 1 でまだ無いもの

- クライアントの編集画面（返答の受信は no-op）。
- 章をまたぐリンクの作成 UI、複数選択、コピー＆貼り付け。
- 翻訳ファイルそのものの編集（表示文字の上書きまで）。
- 章の `revision` を跨いだ自動併合（古い版は拒否して再取得）。

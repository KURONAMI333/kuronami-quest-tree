# 説明文の書き方

クエストの説明文は、ノード JSON の `description` か、言語ファイルの `progression.<namespace>.<chapter>.<node>.desc` に書く。画面の幅に合わせて自動で折り返すので、改行位置を自分で合わせる必要はない。

## 行と色

- `\n` で行を分ける。FTB Quests の説明文（行の配列）を取り込んだ場合も、行ごとに改行として扱う。
- `{@pagebreak}` は段落の切れ目。前後に余白が入る。
- `&0`〜`&f` で色、`&l` `&o` `&n` `&m` `&k` `&r` で書式。`&` 自体を出したい時は `\&`。
- 折り返した行にも色と書式は引き継がれる。幅より長い語は文字の途中で折る。

```json
"description": "&6旅の始まり\n&7村を出る前に、&e鉄の剣&7を用意しよう。"
```

## リンク

`{種類:対象 表示文字}` の形。表示文字を省くと対象がそのまま出る。リンクはアクセント色で描かれ、クリックで開く。

| 書き方 | クリックした時 |
|---|---|
| `{open_url:https://example.com 配布ページ}` | 確認画面を出してからブラウザで開く（`http` / `https` のみ） |
| `{quest:two/iron 鉄の時代}` | そのクエストを開く。ノード名だけなら同じ章のノードとして探す |
| `{recipe:minecraft:iron_ingot 鉄インゴット}` | レシピビューアでそのアイテムのレシピを開く（JEI / REI / EMI のどれかが入っている時だけ） |

対象に空白は使えない。表示文字には空白を含められる。

```json
"description": "材料は{recipe:minecraft:iron_ingot 鉄インゴット}。次は{quest:two/iron 鉄の時代}へ。"
```

## 画像

```json
"description": "{image:example:textures/gui/map.png width:180 height:120}"
```

- 対象は `<namespace>:<path>` のテクスチャパスだけ。`https://...` やファイルパス、先頭が `/` のパス、大文字を含むパスは画像にならず、そのまま文字として出る。
- `width` と `height` は省略可（既定 64）。描画時に画面幅へ収める。
- `width` / `height` 以外の属性は無視される。

## JSON テキストコンポーネント

FTB Quests や Pufferfish's Skills が書く JSON の説明文も読める。**1 行がまるごと** `{...}` または `[...]` の時だけテキストコンポーネントとして解釈し、その行の `clickEvent`（`open_url` / `change_page`）は上のリンクとして扱う。平文と JSON が同じ説明文に混ざっていてもよい。行の一部だけが JSON の時は、その行は書かれたまま表示する。

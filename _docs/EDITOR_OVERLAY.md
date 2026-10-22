# In-game editor overlay

The server-side storage format and operations. The client sends `EditorOpPayload` and receives `EditorReplyPayload`; the editor screens are described under Phase 2–4 below.

## Location and precedence

```
<world>/kuronami_quest_tree/progression/
  chapters/<namespace>/<chapter path>.json
  tables/<namespace>/<table>.json
  restrictions.json
  revisions.json
```

Loading applies, from lowest to highest precedence: data pack chapters, reward tables and groups → data pack `patches/*.json` → this overlay. The overlay is per chapter and can also remove a pack chapter. `/reload` is not needed. Each save applies the layers through `ProgressData`, validates the result, then raises the generation and resends it to everyone (at most once per ten ticks).

## Chapter file

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
    "b": { "action": "remove", "node": { "...": "contents at deletion" }, "edited": true },
    "c": { "action": "add", "node": { "...": "a new node" } }
  }
}
```

- `revision`: the chapter's version number. Clients send the last version they saw; an older one is refused.
- `base`: whether the pack had this chapter when it was first edited.
- `replace`: use `chapter` as-is instead of layering it over the pack chapter. `create_chapter` sets this to true.
- `remove`: the chapter's tombstone. `chapter` keeps the contents at deletion.
- `edited`: whether the overlay had changed this chapter before it became a tombstone. Used when restoring, to choose between "the pack's original chapter" and "the author's version".
- `chapter`: only the top-level keys the edit changed, each with its **new value**. When `replace` is false the keys are layered over the pack chapter one by one, and `nodes` only applies the node entries below. Changing one nested value does not remove its siblings (for example, changing `display.title` keeps the pack's `display.icon`).
- `removed`: the top-level key names whose pack entries were deleted with RFC 7396 `null`. When `replace` is false these keys are removed from the pack chapter before `chapter` is layered on. Not used for a chapter with `replace` true, such as `create_chapter`.
- `nodes`: `action` is `add` / `replace` / `remove`. `remove` keeps the contents so the node can be restored. `edited` says whether the node had been changed before it was deleted.
- `revisions.json`: a small record so an undo that deletes the chapter file does not roll the version number back (`{"<chapter id>": version}`). The larger of the chapter file's `revision` and this record is used. Clients learn the current versions from the sync bundle's `revisions` (chapter id → version).

When writing a file by hand, an omitted `action` is read as `add`. An unreadable file is reported as an ERROR and writes to that chapter are refused.

## Operations (`EditorOpPayload.op`)

`chapterId` is `<namespace>:<chapter path>`. Only `create_chapter` sends an empty chapter id, because the chapter does not exist yet; `args.namespace` names the namespace. The server mints the new id and returns it as `createdId`.

| op | args |
|---|---|
| `create_node` | `{"node": {...}}` |
| `update_node` | `{"id": "...", "patch": {...}}` (RFC 7386; `null` deletes the key) |
| `move_node` | `{"id": "...", "x": 0, "y": 0}` |
| `move_nodes` | `{"nodes": [{"id": "...", "x": 0, "y": 0}, ...]}` (one revision and one undo step) |
| `delete_node` / `restore_node` | `{"id": "..."}` |
| `delete_nodes` | `{"ids": ["...", ...]}` (one revision and one undo step) |
| `create_chapter` | `{"namespace": "...", "chapter": {...}}` (the server reassigns the initial node ids and rewrites `requires` / `exclusive_with` between them) |
| `update_chapter` | `{"patch": {...}}` (not `nodes`; an RFC 7396 patch on the whole current chapter, where `null` also deletes pack entries) |
| `delete_chapter` / `restore_chapter` | `{}` |
| `update_table` | `{"id": "...", "create": true?, "table": {...}}` (whole table; `patch` is also accepted) |
| `delete_table` / `restore_table` | `{"id": "..."}` |
| `update_restrictions` | `{"rules": [...]}` (the whole array; `chapterId` is `restrictions`) |
| `link` / `unlink` | `{"from": "...", "to": "..."}` (`to` is a node name in the same chapter, or `chapter/node` for another chapter) |
| `undo` / `redo` | `{}` |

Node ids use `[a-z0-9_]` only; an id containing anything else is refused. There is no rename (it would break references, exclusions, translation keys and player progress at once).

## Validation and undo

- Before writing, the candidate chapter is run through `ProgressValidator`. The whole book's set of ERRORs is compared before and after, and the write is refused if even one **new ERROR** appears (this also stops a deletion that breaks another chapter's prerequisite). Existing ERRORs do not block an edit.
- On refusal, the reply carries the validation messages and no file changes.
- Undo uses per-chapter and per-table snapshots, for the last 50 steps (pushed and popped on the same end). Table history is separate from chapter history, so the same id cannot mix them. It is lost when the server restarts. What stays on disk is the tombstone, `.bak` (the version just before a write), and `revisions.json` / `table_revisions.json`.
- Writes go to a temporary file in the same folder and are then moved into place (`ATOMIC_MOVE`, or `REPLACE_EXISTING` where that is unavailable). A crash halfway leaves no partial chapter file.

## Payload size

A vanilla custom payload to the server is at most 32767 bytes, and Fabric does not split C2S packets. A larger payload fails during client-side encoding and no reply follows. `EditorOpPayload` therefore splits writes into chunks of 7000 characters, and the server's `EditorOpAssembler` rebuilds them per player and per `requestId` before running the operation. The split position is packed into one int `chunk` as `(parts << 8) | (part + 1)` (0 means unsplit; `part()` / `parts()` read it back). A request that fits in one chunk stays `part=0, parts=1`. A request larger than 262144 characters is refused before sending.

## Commands

- `/quest_tree edit on|off` (no argument toggles): operations are accepted only with permission level 2 and edit mode on. Logout and server shutdown turn it off.
- `/quest_tree export <namespace> [force]`: writes the validated book to `datapacks/quest_tree_export_<namespace>` as an ordinary data pack. It does not enable it or run `/reload`. The namespace keeps `[a-z0-9_.-]` for chapter matching, and only the folder name is made safe (a namespace containing `-` or `.` does not produce zero chapters). Translations for that namespace (keys starting with `progression.<ns>.`) go into `lang/` as well. The number of nodes dropped by validation is reported in chat. An existing folder is refused rather than deleted; `force` rewrites it (`ImportOutput.writeTo` deletes `data/<ns>/progression` first).

## Phase 2: editing on the map (2026-10-20)

- The server sends `EditorStatePayload` with "may edit" (permission 2 and `/quest_tree edit on`) on join and on every change. When allowed, a gear appears next to the pin in the title row, and E also toggles it.
- Selection (click, Ctrl+click, Shift+drag box, Ctrl+A), drag to move (half grid, disabled with Alt), arrow keys (Shift for 5), right-click menu, Ctrl+C/V/D, Delete, drag while holding L or the menu's "Link from here", Ctrl+Z / Ctrl+Shift+Z / Ctrl+Y. Keys are not captured while an EditBox has focus.
- `EditorSession` sends operations one at a time and uses the previous reply's revision for the next. A move is drawn at its provisional position before the reply and is put back if refused. A refusal shows the validation message, shortened as-is. For an old revision the server resends the bundle.
- Paste is given new ids by the server. The create replies pair the old and new ids, and the links inside the copy are made afterwards.
- The network protocol version is 3 (the editor state payload was added).

## Phase 3: content edit screens (2026-10-21)

- The right-click "Edit", a double-click on a node, or Enter with a single selection opens `NodeEditScreen`. It puts the node's title, description, shape, size, icon, visibility conditions, prerequisites and repeat on one screen, and tasks and rewards are added, reordered and deleted as cards. Opening a card shows that type's fields in `ContentEditScreen`.
- `ContentSchema` is the single definition of the types; the screens and the round-trip tests read the same table. Changing the type deletes the keys belonging only to the old type and keeps common fields and unknown keys (`retypeTask` / `retypeReward`).
- Items, entities, advancements, tables and similar are searched and picked in `EditPickerScreen`. An id not in the list can still be typed (for tags, or for a mod id the client does not have).
- A screen holds a working copy of the JSON and, when it closes, sends one `update_node` / `update_chapter` RFC 7396 patch. Save and delete use the revision from when the screen opened, so another operation in between causes a refusal. Nothing is written per keystroke. A refusal shows the validation message under the field and does not close the screen (a second Esc or × discards and closes). For an old revision the server resends the bundle, and the screen closes after leaving the message in the map's short notices.
- Chapter settings are in `ChapterEditScreen` (title, icon, defaults, visibility, skill points, delete chapter); a new chapter is made in `NewChapterScreen` (namespace and an optional title; the server mints the id).
- Reward tables are edited whole in `TableEditScreen` (`update_table`, with a table-only revision). Opening a reward whose `table` names a missing id creates it with `create: true` on save. Delete writes a tombstone (`remove: true` and the last contents), and `RestoreScreen` brings it back with `restore_table` (when the pack copy is unchanged, the overlay is removed and the pack version returns). Undo restores the previous table file.
- Deleted chapters, tables and nodes appear in the sync bundle's `deleted`. `deletedJson()` returns the tombstone chapter ids, table ids, and deleted node ids for each live chapter. `RestoreScreen` sends `restore_chapter` / `restore_table` / `restore_node` from that list.
- Screens use only the existing `Ui` / `Tiles` / `EditForm` surfaces and rows, and never hard-code colours. Text is limited to labels, values, states and failure reasons; there is no explanatory copy.

## Phase 4: action buttons (2026-10-21)

- The map's right-click menu gained "Chapters", "New chapter", "Deleted", "Export" and "Test as player". All of them ride `EditorSession`'s one-at-a-time queue.
- `export` (`args.namespace`, or the chapter's namespace when absent) runs `/quest_tree export <namespace>` and returns only the output folder name (`quest_tree_export_<namespace>`) in the reply lines. It never exposes a server absolute path. A failed command does not report success; it returns the reason.
- `test_chapter` (`chapterId`) runs `/quest_tree reset @s <chapter>` and only on success turns edit mode off. It refuses without doing anything when the chapter is not in the book, when the player is in a party (progress is shared, so other members' chapter progress would be erased too), or when the command fails. The reply repeats the request's revision, since no chapter is written and the client's version must not move.
- Both check the namespace / chapter id with `ProgressExport.validNamespace` / `ProgressionEditor.validChapterId` before building the command string (`EditorOps`).

## Phase 5: restrictions (2026-10-22)

- The right-click menu's "Restrictions" opens `RestrictionsEditScreen`. It edits one file, `restrictions.json`, whose `{revision, rules}` replaces the pack's rule array whole when it exists. The revision is a single number for the file (`EditorSession.RESTRICTIONS`, `restrictions_revision` in the bundle); saving sends `update_restrictions`, and a stale one is refused like a chapter's.
- Rows are the rule's id, items, item tags, blocks, block tags, actions and message as one line each, and `conditions` as compact JSON text (the same kinds as tasks and rewards). An unreadable conditions text leaves the last readable value in place.
- The reply carries the same rule warnings `/quest_tree validate` prints (a missing target, an unknown action, no conditions). There is no undo for this file: the array is small and its history is not worth 50 copies.

## Still missing

- Confirmation in the real client (runClient).
- A UI for creating cross-chapter links.
- Editing translation files themselves (beyond overriding the displayed text).
- Automatic merging across a chapter's `revision` (an old version is refused and fetched again).

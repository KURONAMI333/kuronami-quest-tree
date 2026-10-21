# Release Check — Kuronami's Quest Tree 0.1.0 alpha

Checked against `4e772d6` (working tree also contains the release documents below). No store API, GitHub API or upload was called. URLs were not opened.

## Verdict

**Not ready to publish.** The store body, store summary, license text and first-alpha changelog are drafted. The jar metadata and the release gates below still block a CurseForge / Modrinth upload.

## Identity and links

| Item | Current | Required | Status |
|---|---|---|---|
| NeoForge `authors` | `KURONAMI` (from `mod_author`) | `KURONAMI333` only | **Blocker** |
| Fabric `authors` | `["KURONAMI"]` | `KURONAMI333` only | **Blocker** |
| NeoForge `displayURL` | `.../mc-mods/pack-progress` | This mod's CurseForge page | **Blocker** (wrong project) |
| Fabric `contact.homepage` | `.../mc-mods/pack-progress` | This mod's CurseForge page | **Blocker** (wrong project) |
| Issue tracker | Absent | CurseForge comments URL; X DM stated in text | **Blocker** |
| Source link in store body | `https://github.com/KURONAMI333/quest-tree` | Repo must exist before publishing | **Blocker** (not created/verified) |
| GitHub issues URL | None | Must stay absent | Pass |
| Forbidden handle / identity leak | No match in source or either jar | None | Pass |
| Personal name / e-mail | None in the new documents | None | Pass |
| AI attribution | None found in source | None | Pass |

The wrong `pack-progress` URL is in both loader metadata files; fixing only one leaves the other wrong.

## Logo and store images

- `neoforge.mods.toml` sets `logoFile="kuronami_quest_tree.png"`, and `fabric.mod.json` sets `"icon": "kuronami_quest_tree.png"`.
- No `kuronami_quest_tree.png` exists in the repository, and no `.png` with that name is present in either built jar. **Blocker.**
- The logo has not been chosen, so it cannot be added by this task. `verify-mod` (MineTexture asset gate) has not been run because the required asset is absent.
- Gallery images and the CurseForge featured image are not prepared; the alpha has visible features, so the gallery rule applies. **Blocker.**

## Dependencies

NeoForge metadata:

| Mod | Current | Required |
|---|---|---|
| `ftbteams` | `optional`, `AFTER`, `[2101.1,)` | `optional`, `[0,)` |
| `ftblibrary` | Missing | `optional`, `[0,)` |
| `roughlyenoughitems` | Missing | `optional`, `[0,)` |
| `emi` | Missing | `optional`, `[0,)` |
| `jei` | Missing | `optional`, `[0,)` |
| `kubejs` | Missing (NeoForge only) | `optional`, `[0,)` |
| `pack_stages` | Missing (NeoForge only) | `optional`, `[0,)` |
| `kuronami_trophies` | Missing | `optional`, `[0,)` |

Fabric metadata:

| Mod | Current | Required |
|---|---|---|
| `ftbteams` | `suggests: "*"` | Keep, optional |
| `ftblibrary` | Missing | Suggest, optional |
| `roughlyenoughitems` | Missing | Suggest, optional |
| `emi` | Missing | Suggest, optional |
| `jei` | Missing | Suggest, optional |
| `kuronami_trophies` | Missing | Suggest, optional |
| `kubejs`, `pack_stages` | Not declared | Correct — NeoForge-only integrations |

`ftbteams` is the only optional dependency declared today. All other soft integrations are missing from metadata, which makes the advertised compatibility invisible to launchers and modpack tools. **Blocker** for the release metadata contract, even though the mod still loads without them.

The current `[2101.1,)` range should become `[0,)` under the project rule for optional dependency ranges; it does not block loading, but it can hide the optional integration.

## Build and jar

Command:

```bash
~/dev/projects/minecraft-mod-dev/_tools/gw <mod-worktree> build
```

Result: `BUILD SUCCESSFUL in 18s` (35 tasks executed; Javadoc warnings only, no compilation or test failure).

Artifacts:

| Loader | Jar | SHA256 |
|---|---|---|
| NeoForge 1.21.1 | `kuronami_quest_tree-neoforge-1.21.1-0.1.0.jar` (1,163,730 bytes) | `b8066b41303f54ad44440f5312395211b0f3f3816af8b7bf6d78a0a948797861` |
| Fabric 1.21.1 | `kuronami_quest_tree-fabric-1.21.1-0.1.0.jar` (1,164,942 bytes) | `fb1b5655695ba59b543db2e8c293aa2fbdc698420f7914eaae9c8521332d8c70` |

Jar contents:

- No internal documentation, handoff or research directory, and no agent-instruction file, in either shipped jar. Pass.
- The built metadata still contains the wrong author, URL and logo values listed above. That is expected because this task did not change code or metadata.
- Release builds must be repeated with `--no-build-cache` after the metadata fix. **Not done.**

## Store text checks

- `STORE_BODY_EN.md`: `store_description_lint.py --strict` → 0 errors, 0 warnings.
- Summary: 122 characters, 21 words.
- Alpha warning is a separate marked block (`ALPHA RELEASE ONLY`) before the hook, to delete at 1.0.
- No commission e-mail section and no personal name/e-mail.
- License closing paragraph states: ARR; free modpack use including monetised packs; addons/public API allowed without bundling the jar; data/resource packs, quest books, translations, configs and KubeJS scripts allowed, including paid packs.
- `LICENSE`: addon/API and data-pack permissions added; the ARR grant and warranty disclaimer are unchanged.
- `CHANGELOG.md`: `## 0.1.0 (alpha)`, player-facing, no internal config detail.
- `gradle.properties` was not changed: `version=0.1.0`, license fields remain `All Rights Reserved`.

## Remaining release gates

- [ ] **Blocker** — independent review of the release commit with zero crash, data-loss and permission findings.
- [ ] **Blocker** — dev-client pass on both NeoForge and Fabric at this commit. The known Fabric pass predates the latest screen additions.
- [ ] **Blocker** — owner's real-play pass on the release build, with no crash or lost progression.
- [ ] **Blocker** — CurseForge project and slug created; Modrinth project created.
- [ ] **Blocker** — GitHub repository created and source URL verified.
- [ ] **Blocker** — logo chosen, added at `common/src/main/resources/kuronami_quest_tree.png`, and `verify-mod` passes.
- [ ] **Blocker** — NeoForge `authors`, `displayURL`, optional dependencies and Fabric `authors`, `homepage`, `suggests` fixed.
- [ ] **Blocker** — gallery images and featured image prepared.
- [ ] **Blocker** — final `--no-build-cache` build after metadata changes; record the SHA256 of the exact jars to upload.
- [ ] CurseForge relations: FTB Teams, FTB Library, REI, EMI, JEI, Pack Stages, KubeJS, Kuronami's Trophies as optional where supported. Do not add Fabric API as a relation.
- [ ] Paste `STORE_BODY_EN.md` into both stores, use the summary and categories from `STORE_SUMMARY.md`, upload the alpha changelog section.
- [ ] After publishing: remove the alpha block at 1.0 and revisit the summary, since `0.1.0` is an alpha contract.

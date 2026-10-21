# Release Check — Kuronami's Quest Tree 0.1.0 alpha

Checked against `2efea06` (metadata fixes) plus this document. No store API, GitHub API or upload was called. URLs were not opened.

## Verdict

**Not ready to publish.** The store body, store summary, license text and first-alpha changelog are drafted. The jar metadata is now fixed and verified in both built jars, but the remaining release gates below (project creation, logo, gallery and the test passes) still block a CurseForge / Modrinth upload.

## Identity and links

| Item | Current | Required | Status |
|---|---|---|---|
| NeoForge `authors` | `KURONAMI` (from `mod_author`) | `KURONAMI` | Pass — portfolio-wide convention across 80+ KURONAMI mods; the `KURONAMI333` commit identity is separate |
| Fabric `authors` | `["KURONAMI"]` | `KURONAMI` | Pass — same convention |
| NeoForge `displayURL` | `.../mc-mods/kuronamis-quest-tree` | This mod's CurseForge page | Pass — confirm the slug when the CurseForge project is created |
| Fabric `contact.homepage` | `.../mc-mods/kuronamis-quest-tree` | This mod's CurseForge page | Pass — same slug confirmation |
| Issue tracker | CurseForge comments URL in both loaders | CurseForge comments; never GitHub issues | Pass |
| Source link in store body | `https://github.com/KURONAMI333/quest-tree` | Repo must exist before publishing | **Blocker** — must exist before publishing (not created/verified) |
| GitHub issues URL | None | Must stay absent | Pass |
| Forbidden handle / identity leak | No match in source or either jar | None | Pass |
| Personal name / e-mail | None in the new documents | None | Pass |
| AI attribution | None found in source | None | Pass |

The wrong `pack-progress` URL was in both loader metadata files; both now point at `kuronamis-quest-tree`.

## Logo and store images

- `neoforge.mods.toml` sets `logoFile="kuronami_quest_tree.png"`, and `fabric.mod.json` sets `"icon": "kuronami_quest_tree.png"`.
- No `kuronami_quest_tree.png` exists in the repository, and no `.png` with that name is present in either built jar. **Blocker.**
- The logo has not been chosen, so it cannot be added by this task. `verify-mod` (MineTexture asset gate) has not been run because the required asset is absent.
- Gallery images and the CurseForge featured image are not prepared; the alpha has visible features, so the gallery rule applies. **Blocker.**

## Dependencies

NeoForge metadata:

| Mod | Declared now | Side | Source of the mod id |
|---|---|---|---|
| `ftbteams` | `optional`, `AFTER`, `[0,)` | BOTH | `ftb-teams-neoforge-2101.1.11.jar` |
| `ftblibrary` | `optional`, `AFTER`, `[0,)` | BOTH | `ftb-library-neoforge-2101.1.37.jar` |
| `roughlyenoughitems` | `optional`, `AFTER`, `[0,)` | CLIENT | `RoughlyEnoughItems-neoforge-16.0.799.jar` |
| `emi` | `optional`, `AFTER`, `[0,)` | CLIENT | `emi-neoforge-1.1.22+1.21.1-api.jar` |
| `jei` | `optional`, `AFTER`, `[0,)` | CLIENT | `jei-1.21.1-neoforge-19.57.0.447.jar` |
| `kubejs` | `optional`, `AFTER`, `[0,)` | BOTH | `kubejs-neoforge-2101.7.2-build.377.jar` |
| `pack_stages` | `optional`, `AFTER`, `[0,)` | BOTH | `pack_stages-0.1.1.jar` (mod-100) |
| `kuronami_trophies` | `optional`, `AFTER`, `[0,)` | BOTH | `kuronami_trophies-fabric-1.21.1-0.1.1.jar` (mod-108) |

Fabric metadata:

| Mod | Declared now | Note |
|---|---|---|
| `ftbteams` | `suggests: "*"` | `ftb-teams-fabric-2101.1.11.jar` |
| `ftblibrary` | `suggests: "*"` | `ftb-library-fabric-2101.1.37.jar` |
| `roughlyenoughitems` | `suggests: "*"` | REI ships on Fabric |
| `emi` | `suggests: "*"` | EMI ships on Fabric |
| `jei` | `suggests: "*"` | JEI ships on Fabric |
| `kuronami_trophies` | `suggests: "*"` | `kuronami_trophies-fabric-1.21.1-0.1.1.jar` |
| `kubejs`, `pack_stages` | Not declared | Correct — NeoForge-only integrations on 1.21.1 |

All eight soft integrations are declared with the `[0,)` optional range, so launchers and modpack tools see them and none of them blocks loading. Pass.

## Build and jar

Command:

```bash
~/dev/projects/minecraft-mod-dev/_tools/gw <mod-worktree> build
```

Result: `BUILD SUCCESSFUL in 16s` (38 tasks; Javadoc warnings only, no compilation or test failure) after `clean build --no-build-cache`. The preceding plain `build` also passed.

Artifacts:

| Loader | Jar | SHA256 |
|---|---|---|
| NeoForge 1.21.1 | `kuronami_quest_tree-neoforge-1.21.1-0.1.0.jar` (1,164,672 bytes) | `2e68e5a5ab0ae9f39dc67527f3224d54fdd31149ca214fac43209cfbb7cd1158` |
| Fabric 1.21.1 | `kuronami_quest_tree-fabric-1.21.1-0.1.0.jar` (1,165,886 bytes) | `b3269cecdb4115c9103f93d322c764d1875cb1b61f813cb643b4c0689f67bf91` |

Jar contents (checked with `unzip -p`):

- No internal documentation, handoff or research directory, and no agent-instruction file, in either shipped jar. Pass.
- NeoForge: `displayURL` and `issueTrackerURL` point at the CurseForge slug above, `authors = "KURONAMI"`, and the eight optional dependencies carry `[0,)`. Pass.
- Fabric: `contact.homepage` and `contact.issues` point at the CurseForge slug above, `authors` is `["KURONAMI"]`, and `suggests` lists the six Fabric-compatible integrations. Pass.
- `logoFile` / `icon` still name a PNG that does not exist; that stays a blocker (see above).

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
- [ ] **Blocker** — CurseForge project and slug created (confirm `kuronamis-quest-tree`); Modrinth project created.
- [ ] **Blocker** — GitHub repository created and source URL verified; the source link must exist before publishing.
- [ ] **Blocker** — logo chosen, added at `common/src/main/resources/kuronami_quest_tree.png`, and `verify-mod` passes.
- [x] NeoForge `displayURL` / `issueTrackerURL`, optional dependencies and Fabric `homepage` / `issues` / `suggests` fixed and checked in the built jars. `authors` stays `KURONAMI` by the portfolio convention.
- [ ] **Blocker** — gallery images and featured image prepared.
- [x] `clean build --no-build-cache` after the metadata changes; the SHA256 above is the verified pair. Repeat if any file changes before upload.
- [ ] CurseForge relations: FTB Teams, FTB Library, REI, EMI, JEI, Pack Stages, KubeJS, Kuronami's Trophies as optional where supported. Do not add Fabric API as a relation.
- [ ] Paste `STORE_BODY_EN.md` into both stores, use the summary and categories from `STORE_SUMMARY.md`, upload the alpha changelog section.
- [ ] After publishing: remove the alpha block at 1.0 and revisit the summary, since `0.1.0` is an alpha contract.

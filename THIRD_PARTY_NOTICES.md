# Third-Party Notices

Project author: **wbero**. QQ discussion group: **1065226139**.

The root CC BY-NC-SA 4.0 license applies to this project's own contributions,
not to third-party works under different terms. Attribution alone does not
establish permission or license compatibility for a combined distribution.

## Selected new-guild support from SatenRuiko10032/limingjie-assistant

The test.12 new-guild catalog, initial-role choices, opening-relic mapping and
enhancement-node parsing are adapted from contributions by SatenRuiko10032,
quickgk and the existing project contributors:

- <https://github.com/SatenRuiko10032/limingjie-assistant>
- New guilds/roster: `a9ac22e67d3b6dc4ff4a90cd1d40089bdaef90a6`
- Opening relics/localized names: `2846b25451993cce66f760757b7185cdb8b4e6b0`
- Reference tree: `55bd21f3d65213e8bbc29893d25bf51ae6e4ad04`

That tree uses CC BY-NC-SA 4.0. The selected implementation has been modified
to fit this branch's saved-route JSON and idempotent run ledger; its other
UI, battle-strategy and execution-engine changes are not incorporated.
The supplied third-party v11.3 APK is not redistributed, and these credits do
not establish its exact build provenance. Existing third-party asset terms
below remain applicable.

## AutoPCR

The upstream AutoPCR project (Python) is a behavioral, protocol, and
Labyrinth opening-reroll reference for the native Kotlin implementation.
Its Python runtime and source files are not bundled as an application runtime.
This runtime distinction is not a claim that translated or adapted code is
exempt from the upstream license.

Upstream project: <https://github.com/cc004/autopcr>

Upstream license checked on 2026-09-12: [Creative Commons
Attribution-NonCommercial-ShareAlike 4.0 International](https://github.com/cc004/autopcr/blob/main/LICENSE).

## 花阳亲的米团子

The Labyrinth opening-reroll approach also references 花阳亲的米团子 (Go),
as credited by the project author. The locally retained `go-autopcr-core`
reference describes itself as an AutoPCR Go port and carries CC BY-NC-SA 4.0.
The locally retained Go reference checkout is excluded from this Git
repository. No unverified project URL or additional relicensing permission
is asserted here.

## Community character scores and strategy data

Player ratings are aggregated from scores submitted by some members of the
discussion group. They are subjective contributions, not official ratings,
not a representative survey, and not a guarantee of battle performance.
Public attribution of individual contributors requires their agreement;
raw submissions and personal information should not be uploaded.

The imported Labyrinth workbook and derived relic/event data have separate
source and redistribution considerations. Keep their provenance and confirm
permission before publishing; the root license does not grant rights held
by workbook contributors or game rights holders.

## LandosolRoster character names

The optional character-name catalog is derived from the `CHARA_NAME` and
`UnavailableChara` tables in Ice9Coffee/LandosolRoster:
<https://github.com/Ice9Coffee/LandosolRoster>

The repository license was confirmed as **GNU General Public License v3.0
(GPL-3.0)** on 2026-07-22. The local source snapshot is kept outside Git; it
is not copied into the APK as Python. A deterministic
offline importer converts only IDs, names, aliases, and availability into
the app's `CharacterResource` JSON schema. Keep this notice and the GPL-3.0
license text with any release that ships generated catalog data. The generated
catalog is currently bundled in the app; it is not optional for the current
character-template loaders. The GPL-3.0 and CC BY-NC-SA combination has not
been cleared by this notice: confirm the applicable distribution obligations,
obtain compatible permission, or replace the derived catalog before release.

## priconne-database

The current Chinese-service unit and skill database is used as an ID/skill
schema reference during resource validation; the SQLite database itself is
not bundled in the APK:
<https://github.com/SonderXiaoming/priconne-database>

## redive.estertion.win unit images

Character icon files are cached from the unit-image index at
<https://redive.estertion.win/icon/unit/>. The site identifies the images as
Princess Connect! Re:Dive game imagery; rights remain with the original
rights holders.

## Kokkoro Clan Battle Assistant

Project: <https://github.com/wbero/kokkoro-clan-battle-assistant>

The existing Kokkoro clan battle assistant is owned by this project's owner. Its Kotlin code, visual resources, tests, and axis formats may be migrated into this application. General capture and accessibility code will be refactored into the shared automation runtime.

It is also a reference for contributors adapting capture and input behavior
to physical phones. Phone support has not been validated for this project.

## Current CN database delivery

Current database releases are supplied through
<https://github.com/wbero/autopcr-db-builder>. The app downloads and validates
the full database at runtime; it is not embedded in the APK. Hosting,
conversion, or downloading does not transfer the game data's ownership.

## Android and Kotlin libraries

AndroidX, Kotlin, Gradle, and other build/runtime dependencies remain subject to their own licenses. A generated dependency notice will be added before release.

Current native networking and serialization dependencies include OkHttp and kotlinx.serialization, subject to their respective licenses.

The game protocol MessagePack and AES-CBC compatibility layer is a clean Kotlin implementation in this repository and does not embed a Python protocol library.

## Geetest

When Bilibili requests secondary verification, the app loads the provider's official web component for the user to complete verification manually. The app does not bundle a solver, bypass, or third-party captcha service.

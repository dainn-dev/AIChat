# Upstream: FlorisBoard

The Android keyboard (`mobile/android/`) is a fork of FlorisBoard. It is imported as plain files, with no
submodule and no upstream git history.

| Item | Value |
|------|-------|
| Upstream repository | https://github.com/florisboard/florisboard |
| Imported tag | `v0.5.2` (latest stable release, published 2025-11-28; `v0.6.0-alpha*` are pre-releases and were skipped) |
| Imported commit | `2e82060251897226c0739b9f52d1d051b02305fb` |
| License | Apache License 2.0 (`LICENSE`, verified at the tag) |
| Import method | `git archive v0.5.2` copied into `mobile/android/`. The staged tree was checked to be blob-identical to upstream (896 files). |
| Import commit in this repo | `chore(android): import FlorisBoard v0.5.2 as keyboard baseline` |

## Why FlorisBoard

AIChat Keyboard ships as a closed-source commercial app. FlorisBoard is Apache-2.0, which allows that if we keep
the license and notices and mark the files we change. The GPL keyboards (AOSP LatinIME forks such as
HeliBoard/OpenBoard, and AnySoftKeyboard) would not allow it.

## Rules for modifying upstream code

1. **Keep the pristine import commit separate.** Every AIChat change goes into later commits, so
   `git diff <import-commit> -- mobile/android` always shows our full delta from upstream.
2. **Mark modified upstream files.** Add an `AIChat MODIFIED:` comment at each change, and list the file in
   `NOTICE` under "Files modified by AIChat". Apache-2.0 §4(b) requires this.
3. **Put new code in new files.** Prefer new packages such as `com.aichat.keyboard.*` or new Gradle modules
   over editing upstream files. Every upstream file we leave alone makes a future rebase cheaper.
4. **Do not remove upstream copyright headers** from files that came from upstream.

## Rebrand decisions (and trade-offs)

| What | Upstream | AIChat | Notes |
|------|----------|--------|-------|
| `applicationId` | `dev.patrickgold.florisboard` | `online.dainn.aichat.keyboard` (final, confirmed 2026-11-20) | Debug builds keep upstream's `.debug` suffix, so they install as `online.dainn.aichat.keyboard.debug`. Beta builds use `.beta`. |
| Launcher / IME label (`floris_app_name`) | `FlorisBoard` | `DBoard` (Debug/Beta suffix per build type; renamed from `AIChat Keyboard` on 2026-11-26, the application ID stays) | Set through `resValue` in `app/build.gradle.kts`. |
| Android `namespace` (R / BuildConfig package) | `dev.patrickgold.florisboard` | **unchanged** | See below. |
| Kotlin packages (`dev.patrickgold.florisboard.*`, `org.florisboard.lib.*`) | — | **unchanged** | See below. |
| Backup/restore package check | hard-coded `dev.patrickgold.florisboard` | `BuildConfig.BASE_APPLICATION_ID` | Without this, restore would reject backups made by this fork. |

**Why we keep the namespace and packages.** Renaming `namespace` changes the generated `R` and `BuildConfig`
classes, so every import across the ~325 Kotlin files would need rewriting. A package rename would move every
source file. Either one turns each future upstream merge into one huge conflict and adds regression risk for no
user-visible gain. Users never see the package name; Android identifies the app by `applicationId`, which is
already changed. Class names in the manifest are fully qualified, so they keep working under the new
`applicationId`, and the provider authorities derive from `${applicationId}`.

**What is still visibly "FlorisBoard":**
- `app_name` in every `values-*/strings.xml` locale. It is used internally only, for activity theming and
  task-description labels; the launcher and IME picker use `floris_app_name`.
- Strings in settings screens: About, crash dialog text, the "FlorisBoard is not enabled" hints, the addons
  store wording.
- Launcher icons.

Clean these up when the v1 settings UI is reworked. `docs/integration-points.md` lists the features to disable.

**Final application ID.** `online.dainn.aichat.keyboard` (confirmed 2026-11-20, replacing the earlier placeholder
`com.aichat.keyboard`). Play does not allow changing the ID after the first upload, so treat it as fixed. It is set by
`aichatBaseApplicationId` in `app/build.gradle.kts`. The Kotlin package `com.aichat.keyboard.*` used for AIChat code
is unrelated to the application ID and stays as is.

## Build fixes required on top of upstream

- **jetpref snapshot vanished.** Upstream v0.5.2 pins `patrickgold-jetpref = "20251119T222500Z-SNAPSHOT"`.
  That artifact has expired from the Sonatype snapshots repository, so a pristine v0.5.2 checkout no longer
  resolves dependencies. We pin `0.3.0-rc01` (Maven Central) instead. The snapshot was built from jetpref commit
  `d6e12dd`, and `0.3.0-rc01` is the next release, 3 commits later: a KSP model-processor codegen rewrite to
  kotlinpoet, a dependency bump, and a Material3 `menuAnchor` API rename. Upstream FlorisBoard adopted the same
  version in v0.6.0-alpha02. The debug build compiles, and the `:app` and `:lib:kotlin` unit tests pass with it (29/29). `:lib:snygg` unit tests
  do not compile at upstream v0.5.2 itself: `SnyggThemeTest` imports `org.florisboard.lib.color.schemes.*`, which
  does not exist at that tag. That is a pre-existing upstream bug, unrelated to this fork. Still smoke-test
  the settings screens on a device. The fallback is to build jetpref `d6e12dd` from source as an included build.

- **Snapshots repository disabled.** The Sonatype snapshots `maven(...)` repository in `settings.gradle.kts` is
  commented out, because nothing resolves from it any more.

## Backup policy (v1)

`android:allowBackup="false"` and `android:fullBackupContent="false"` are set, and `android:dataExtractionRules`
points to `res/xml/data_extraction_rules.xml`. That file excludes every domain from both cloud backup and
device-to-device transfer, which `allowBackup="false"` alone does not cover on API 31+. The reason is that the
keyboard will store auth tokens and chat-derived data, which must not leave the device through Android backup.

Upstream wired its `<full-backup-content>` file (`backup_rules.xml`) into both attributes. That was invalid for
`dataExtractionRules`, which needs a `<data-extraction-rules>` document, so the file was removed. The manual
in-app backup/restore screen (Settings > Advanced) still works.

## Network and security (Phase 2)

Upstream has no networking. AIChat adds the `:core:network` module (`core/network/`), described in
`docs/integration-points.md` section (e). Changes to upstream files for it:

- **Manifest.** `INTERNET` permission and `android:networkSecurityConfig="@xml/network_security_config"`.
  `res/xml/network_security_config.xml` forbids cleartext and trusts system CAs only (beta and release). The debug
  source set replaces it (`app/src/debug/res/xml/network_security_config.xml`) to allow cleartext only to
  `10.0.2.2` (emulator to host) and `localhost` (device with `adb reverse`). The root `.gitignore` re-includes
  `app/src/debug/`, which the upstream Rust `debug/` rule would otherwise hide.
- **Extension import intents (baseline review finding 5).** The exported "Import Extension" intent-filters on
  `FlorisAppActivity` (`VIEW` with a `content:` URI and `SEND`, for `application/vnd.florisboard.extension+zip` and
  the catch-all `application/octet-stream`) are removed. `FlorisAppActivity.onNewIntent` also ignores such intents,
  because the activity stays exported (launcher alias) and would still receive explicit intents. Importing extensions still works from inside the app (Addons > Import, system file picker);
  opening a `.flex` file from a file manager or browser no longer offers AIChat Keyboard.
- **`ui://florisboard` deep links (security review).** The upstream BROWSABLE `ui://florisboard/...` intent-filter is
  removed from the manifest, `Routes.composableWithDeepLink` no longer registers the deep links with the
  NavController, and `FlorisAppActivity` no longer turns launch/new intents into navigation. Any web page or app
  could otherwise open arbitrary settings screens. Internal navigation never used them.
- **Settings.** `HomeScreen.kt` shows the "Tài khoản AIChat" entry first; `Routes.kt` registers the AIChat screens
  (without deep links).
- **Memory disclosure and opt-out.** The "Cho phép AIChat ghi nhớ" disclosure (`aichat_strings.xml`, `values` and
  `values-vi`) states that the other person's messages are processed by an external AI provider, that sensitive-data
  filtering is automatic and not perfect, that data is kept until the user deletes it and can be deleted at any time,
  and that deleting a conversation removes only the memories learned from that conversation alone. The wording was
  approved by the product owner on 2026-11-20. Turning the toggle **off** asks for confirmation first, because the
  backend then deletes every keyboard-captured conversation and the memories learned only from them
  (screenshot/share data is untouched). The optional `deleted: { conversations, memories }` counts in the
  `PATCH /me/settings` response are shown in a short confirmation.
- **Base URL.** Debug builds use `http://10.0.2.2:3000/` (override: `./gradlew assembleDebug
  -Paichat.debugApiBaseUrl=http://<host>:3000/`; a host other than `10.0.2.2`/`localhost` must also be added to the
  debug network security config). Beta and release use the production API `https://chat-api.dainn.online/`
  (override with `-Paichat.apiBaseUrl=https://<host>/`, e.g. staging; a non-https value fails the beta/release
  build because those builds forbid cleartext).

## Follow-ups

- **Gradle dependency verification** (`gradle/verification-metadata.xml` via
  `./gradlew --write-verification-metadata sha256 help`) is not set up yet. Add it in CI or on a machine with more
  memory, then keep it updated with every dependency bump.

## Local build prerequisites

| Requirement | Version | Notes |
|-------------|---------|-------|
| JDK | 17+ (21 works) | `JAVA_HOME` must point at a valid JDK. |
| Android SDK platform / build-tools | 36 / 35.0.0 | |
| NDK | 26.1.10909125 | AGP auto-installs it when the SDK licenses are accepted. |
| CMake | **4.0.2 exactly** (`gradle/tools.versions.toml`) | AGP does not auto-install it. Install it with SDK Manager > SDK Tools > CMake 4.0.2, or `sdkmanager "cmake;4.0.2"`. |
| Rust (rustup + cargo) | stable; targets are added automatically by CMake (`rustup target add`) | Needed by `lib/native`. |
| `local.properties` | `sdk.dir=...` (git-ignored) | |
| Network access to the Gradle Plugin Portal | | `vi-input/` is a composite build (`includeBuild("vi-input")`) with its own Kotlin Gradle plugin (2.4.21), resolved on first build. |

Build with `./gradlew assembleDebug`. The output is `app/build/outputs/apk/debug/app-debug.apk`, with the
application ID `online.dainn.aichat.keyboard.debug`. On machines with limited RAM, add `--no-parallel --max-workers=2`.
The parallel cargo, CMake, and Kotlin daemons can exhaust native memory.

## minSdk

Upstream `projectMinSdk=26` (in `gradle.properties`) already matches the AIChat requirement (Android 8.0).
No change was needed. `targetSdk`/`compileSdk` are 36.

## Updating from upstream

1. Clone upstream at the new tag into a temp dir and `git archive` it into a scratch branch, as a fresh copy
   of `mobile/android/`.
2. Diff the old tag against the new tag upstream, and apply that patch onto `mobile/android/` with
   `git apply --3way --directory=mobile/android`.
3. Resolve conflicts in the files listed in `NOTICE`, then update the tag and SHA in this file.

# Android Automatic Stickers Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Automatically create transparent user stickers by extracting the main subject from either a gallery image or an image shared from a chat, entirely on device.

**Architecture:** Reuse the existing user sticker repository and atomic PNG storage. Introduce a subject-segmentation boundary in the importer so segmentation can be verified independently and failures preserve the current image-import fallback. Add an explicit sticker-import action to the existing image share flow; do not silently turn every shared image into a sticker or send image bytes to the backend.

**Tech Stack:** Kotlin, Android Activity Result Photo Picker, existing Android bitmap/Exif importer and repository, on-device subject segmentation library (select and pin a compatible Android library/version during the dependency research step), JUnit 5 Kotlin tests.

**Spec:** Approved in-chat design (2026-11-26): support both gallery images and chat-shared images; extract the subject on-device; save to the existing personal sticker collection; retain original-image import as fallback; do not send image bytes to the backend. No separate design spec was created because this was a bounded change.

## Global Constraints

- Process image data locally; do not upload image bytes or add a backend endpoint.
- Preserve existing sticker file format, user sticker repository, deletion behavior, and image size/pixel limits.
- Gallery and shared-chat images must use the same segmentation/import path.
- If segmentation is unavailable or fails for an image, save the existing normalized image as the fallback instead of losing the import.
- Accept only readable image `content:` URIs from external providers for the chat-share path; preserve the existing rejection of the app's own providers.
- Keep decoding and segmentation off the main thread; keep memory bounded by sampled bitmap dimensions.

## Review Focus

- Oversized or malformed image → rejected safely without crashing or allocating an unbounded bitmap (test importer boundary).
- Segmentation model/library failure or no usable mask → original normalized image is still imported (test importer fallback).
- EXIF-rotated source and mask → output subject orientation/alignment remains correct (test mask/compositing pipeline).
- Shared non-image, file URI, missing URI, or app-owned provider URI → not imported (test share intent validation).
- User cancels the picker or share-import activity → no sticker is created and no crash occurs (test activity decision logic where practical; verify manually on device).

---

### Task 1: Choose and isolate on-device segmentation

**Files:**
- Modify: `mobile/android/gradle/libs.versions.toml`
- Modify: `mobile/android/app/build.gradle.kts`
- Create: `mobile/android/app/src/main/kotlin/com/aichat/keyboard/sticker/SubjectSegmenter.kt`
- Test: `mobile/android/app/src/test/kotlin/com/aichat/keyboard/sticker/SubjectSegmenterTest.kt`

**Interfaces:**
- Produces: `fun interface SubjectSegmenter { suspend fun foregroundMask(bitmap: Bitmap): Bitmap? }`, where a non-null mask is an ARGB_8888 alpha mask in the same dimensions as the input; null means segmentation could not produce a usable result.
- Select an actively supported on-device Android segmentation library that supports the app's minSdk and local inference. Record the exact dependency/version and model delivery/bundling behavior in the gradle catalog comment; do not choose a cloud-backed service.

- [ ] **Step 1: Write tests for the segmenter adapter contract**: mask dimensions match input; unsupported/failed inference yields null; cancellation propagates rather than being swallowed.
- [ ] **Step 2: Run the focused test**: `cd mobile/android && ./gradlew :app:testDebugUnitTest --tests '*SubjectSegmenterTest'`; expected initial failure because the adapter is not implemented.
- [ ] **Step 3: Add and pin the selected segmentation dependency and implement the adapter** with inference on `Dispatchers.Default` or the library-required executor; bound input to the existing sticker render size before inference.
- [ ] **Step 4: Run the focused unit test and `./gradlew :app:compileDebugKotlin`**; expect tests and compilation to pass.

### Task 2: Composite subject masks and preserve importer fallback

**Files:**
- Modify: `mobile/android/app/src/main/kotlin/com/aichat/keyboard/sticker/UserStickerImporter.kt`
- Create: `mobile/android/app/src/main/kotlin/com/aichat/keyboard/sticker/StickerForegroundComposer.kt`
- Test: `mobile/android/app/src/test/kotlin/com/aichat/keyboard/sticker/StickerForegroundComposerTest.kt`
- Test: `mobile/android/app/src/test/kotlin/com/aichat/keyboard/sticker/UserStickerImporterTest.kt`

**Interfaces:**
- Consumes: `SubjectSegmenter.foregroundMask(bitmap): Bitmap?` from Task 1.
- Produces: `StickerForegroundComposer.compose(source: Bitmap, mask: Bitmap): Bitmap`; preserves source RGB and sets output alpha from mask, producing transparent background.
- Modify import API to `import(context: Context, uri: Uri, files: UserStickerFiles, segmenter: SubjectSegmenter): Outcome`; existing callers pass the shared on-device implementation.

- [ ] **Step 1: Write failing composer tests** for fully opaque mask, fully transparent mask, and a partial-alpha mask; assert source RGB is preserved and output alpha follows the mask.
- [ ] **Step 2: Write importer behavior tests**: valid mask stores transparent PNG; null mask or segmentation exception stores normalized source fallback; malformed/oversized image remains `INVALID`; cancellation is propagated.
- [ ] **Step 3: Run focused tests and confirm failure** using the two test class filters.
- [ ] **Step 4: Implement composition and wire segmentation into import after existing decode/EXIF normalization and before final fit/write**; keep source-size guard, atomic file write, and bitmap recycling. Catch ordinary segmentation failures only for fallback; rethrow `CancellationException`.
- [ ] **Step 5: Run both focused tests and all sticker unit tests**; expect pass with no bitmap leaks in tested branches.

### Task 3: Route gallery imports through the shared automatic pipeline

**Files:**
- Modify: `mobile/android/app/src/main/kotlin/com/aichat/keyboard/sticker/StickerRepository.kt`
- Modify: `mobile/android/app/src/main/kotlin/com/aichat/keyboard/sticker/StickerImportActivity.kt`
- Test: `mobile/android/app/src/test/kotlin/com/aichat/keyboard/sticker/StickerRepositoryTest.kt`

**Interfaces:**
- Consumes: importer signature and segmenter from Tasks 1–2.
- Produces: repository `importInBackground(uris, onDone)` uses one shared segmenter for each URI, refreshes once after imports, and preserves existing outcome reporting.

- [ ] **Step 1: Add tests** proving gallery URI imports invoke the injected/shared segmenter, refresh the repository, and report outcomes; cancellation/failure of an individual segmentation still completes with fallback outcome.
- [ ] **Step 2: Run the focused repository test and confirm failure.**
- [ ] **Step 3: Wire the shared segmenter into repository imports without changing picker behavior, multi-select limit, or existing toasts.**
- [ ] **Step 4: Run repository/importer tests and compile the app.**

### Task 4: Add explicit import of chat-shared images as stickers

**Files:**
- Modify: `mobile/android/app/src/main/kotlin/com/aichat/keyboard/share/ShareReplyActivity.kt`
- Modify: `mobile/android/app/src/main/kotlin/com/aichat/keyboard/share/SharedImageUriGuard.kt` only if sharing validation needs a reusable pure decision function.
- Modify: relevant share UI/action files located by following `ShareReplyScreen` and its action interface.
- Test: share intent validation tests alongside existing `SharedImageUriGuard` or `ShareReplyActivity` tests.

**Interfaces:**
- Consumes: `StickerRepository.importInBackground(listOf(uri), onDone)` from Task 3.
- Produces: explicit “Tạo sticker” action in the image-share UI. Tapping it imports that validated URI via the same on-device pipeline, shows success/failure through existing user-facing feedback, and does not send image bytes to the reply endpoint.

- [ ] **Step 1: Trace current share-screen UI/actions and write tests** for accepted image content URI and rejection of non-image, absent URI, non-content URI, and app-owned provider.
- [ ] **Step 2: Run focused share tests and confirm failure for the new action/decision behavior.**
- [ ] **Step 3: Add an explicit sticker action and route only the already-validated `ACTION_SEND image/*` URI to the repository; keep normal reply flow unchanged.**
- [ ] **Step 4: Run share-flow tests and app compilation.**

### Task 5: End-to-end verification and device checks

**Files:**
- Modify: relevant Android tests from prior tasks only if integration wiring needs coverage.

- [ ] **Step 1: Run the complete Android unit-test suite**: `cd mobile/android && ./gradlew :app:testDebugUnitTest`; expected all tests pass.
- [ ] **Step 2: Run Android lint and compile checks**: `./gradlew :app:lintDebug :app:assembleDebug`; expected BUILD SUCCESSFUL.
- [ ] **Step 3: Manually verify on a device/emulator**: (a) select a gallery photo and confirm transparent subject sticker appears in “Của tôi”; (b) share a chat image to AIChat, explicitly choose “Tạo sticker”, confirm same result; (c) use unsupported/no-subject image and verify fallback import; (d) cancel picker and verify no sticker is created.
- [ ] **Step 4: Review diff for privacy and memory safety**: no network image upload, no image content in logs, all streams/bitmaps closed/recycled, and own-provider URIs remain rejected.

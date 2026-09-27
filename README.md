# Duplicate File Remover for Android

A native Android adaptation of [Akash's Python/Tkinter duplicate-file-remover](https://github.com/AKASH991833/duplicate-file-remover). The desktop app groups by file size and content hash, then lets you select copies to remove. This Android version keeps that idea, uses SHA-256 instead of MD5, adds perceptual photo matching, a dark modern UI with thumbnails, and a recycle-bin style **Deleted Photos** folder instead of instant permanent deletion.

## What it does

1. Tap **Choose folder** and grant the Android system picker access to a local folder.
2. Tap **Scan**. It walks that folder and subfolders and shows results in two clearly separated sections:
   - **EXACT DUPLICATES** - files with the same size **and** the same SHA-256. Byte-identical copies; removing extras is safe.
   - **SIMILAR PHOTOS - NOT DUPLICATES** - photos whose 64-bit dHash (perceptual hash) is within a small Hamming distance. These are **real, different photos that only look alike** (resized, compressed, or near-identical shots). The app never bulk-selects them and labels every similar group so you do not mistake them for duplicates.
3. Every file row shows a **thumbnail preview** (tap it for a large preview), its name and size. The first entry in each group is marked **KEEP** and cannot be selected. Tick the copies you want to remove, or use **Select all duplicates** (exact groups only).
4. Tap **Delete selected**, read the breakdown (exact vs similar counts), and confirm. Before moving anything, the app rechecks each selected file (hash/size, and photo hash for similar items) and skips anything that changed.
5. Selected files are **moved into a "Deleted Photos" folder the app creates inside your chosen folder** - not erased. Open **Deleted Photos** in the app to **Restore** items back to their original folder or **Delete forever** / **Empty folder** when you are sure. Restore/delete decisions stay with you.

Extra conveniences: scan stats (files scanned, group counts, reclaimable space), All / Exact / Similar filter chips, per-group "select extra copies" button, progress indicator during scans, and dark theme.

## Safety notes

- The app never auto-selects or auto-deletes. Permanent erasure happens only inside Deleted Photos, on your explicit action.
- Moves to Deleted Photos copy the file, verify the copy's size, and only then remove the original. Skipped/failed items are reported separately.
- The bin folder is excluded from future scans. A small `deleted_photos_manifest.json` inside it remembers each item's original folder so **Restore** can put it back.
- The app requests no storage or network permission; it can access only the folder you pick. Some document providers refuse delete/create operations; those files are reported as skipped/failed.
- Large trees are capped at 20,000 files. Photo comparison covers up to 6,000 photos per scan. Photos already listed as exact duplicates are not repeated in the similar section.

**Google Photos cloud library is not supported.** Photos stored only in the Google Photos app/cloud and not saved as local files in the chosen folder will not appear here.

## Build / install

Open the project in Android Studio with JDK 17 and Android SDK 35, then choose **Build > Build APK(s)**. Or run `gradle :app:assembleDebug` using Gradle 8.11.1, JDK 17 and SDK 35. A GitHub Actions workflow builds a debug APK on pushes; after a successful run, open **Actions > Android debug APK > latest successful run > Artifacts**, download `duplicate-file-remover-debug-apk`, unzip it, and install `app-debug.apk` on Android 8+ (you may need to allow installs from your browser/file manager). This is a debug build, not a signed Play Store release.

## Design notes

The Tkinter GUI cannot run as an ordinary Android UI, so this project ports the scanner's **size-first, hash-second** algorithm to Java/Android and adds a second pass for photos: a 9x8 grayscale **dHash**; two photos whose hashes differ by at most 10 of 64 bits are grouped as *similar*, with the match percentage shown on the group. Android document URIs replace filesystem paths (Storage Access Framework). "Deletion" during review is a verified copy into the Deleted Photos folder plus removal of the original; restore copies it back. Everything runs on-device; no network is used. The UI is built programmatically in a single activity with no external dependencies, so the CI build stays reproducible.

SAF reference: [Android storage guide](https://developer.android.com/training/data-storage/shared/documents-files). AGP 8.9.2 requires Gradle 8.11.1 and JDK 17: [Android release notes](https://developer.android.com/build/releases/agp-8-9-0-release-notes).

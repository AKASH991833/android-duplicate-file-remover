# Duplicate File Remover for Android

A native Android adaptation of [Akash's Python/Tkinter duplicate-file-remover](https://github.com/AKASH991833/duplicate-file-remover). The desktop app groups by file size and content hash, then lets you select copies to remove. This version uses the same two-stage idea, but SHA-256 instead of MD5, and Android's Storage Access Framework (SAF) instead of direct filesystem paths.

## What it does

1. Tap **Choose folder** and grant the Android system picker access to a local folder.
2. Tap **Scan**. It walks that folder and subfolders, groups equal-size files, hashes only candidate groups, and displays exact duplicate groups.
3. The first file in each group is marked **KEEP** and cannot be selected; tick the other copies you want to delete.
4. Tap **Delete selected**, read the count and warning, then confirm. Before each delete it rechecks the kept file and selected copy's size and SHA-256; it skips changed/unreadable files. It reports successes and failures separately.

**Deletion is not a reversible move:** Android's document provider may delete immediately. Back up important files first. Do not assume a Trash/Recycle Bin exists. The app never auto-deletes or chooses checkboxes for you. It requests no broad storage or network permission. It can access only the folder the user picks. Some document providers refuse deletion; those files are reported as skipped/failed. Large trees may take a while and are capped at 20,000 files to avoid running out of memory.

**Google Photos cloud library is not supported.** A photo visible only in the Google Photos app and not saved as a local file in the chosen folder will not appear here. Picking a cloud-backed documents provider is not a promise that its files or delete operations work.

## Build / install

Open the project in Android Studio with JDK 17 and Android SDK 35, then choose **Build > Build APK(s)**. Or run `gradle :app:assembleDebug` using Gradle 8.11.1, JDK 17 and SDK 35. A GitHub Actions workflow builds a debug APK on pushes; after a successful run, open **Actions > Android debug APK > latest successful run > Artifacts**, download `duplicate-file-remover-debug-apk`, unzip it, and install `app-debug.apk` on Android 8+ (you may need to allow installs from your browser/file manager). This is a debug build, not a signed Play Store release. Check the run status before treating the artifact as available. No APK or on-device behavior is claimed verified until a successful build and device test.

## Design notes

The Tkinter GUI cannot run as an ordinary Android UI. This project ports the scanner's **size-first, hash-second** algorithm and manual review workflow to Java/Android. Android document URIs replace paths; deletion uses `DocumentsContract.deleteDocument` after an explicit confirmation. SHA-256 is used in place of the source project's MD5. For very large files the scan reads the entire content and may be slow. It does not find visually similar, compressed, or resized photos.

SAF reference: [Android storage guide](https://developer.android.com/training/data-storage/shared/documents-files). AGP 8.9.2 requires Gradle 8.11.1 and JDK 17: [Android release notes](https://developer.android.com/build/releases/agp-8-9-0-release-notes).

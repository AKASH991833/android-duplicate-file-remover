# Duplicate File Remover for Android (v1.0)

An on-device Android app for exact file duplicates and look-alike photos. Download the latest successful debug APK from [Android debug APK Actions](https://github.com/AKASH991833/android-duplicate-file-remover/actions/workflows/android.yml). It is a debug build, not a Play Store release.

## Scanning

- **Full phone**: grant All files access in Android settings, then scan shared internal storage and any mounted SD card the app can access. Private app data, protected Android/data, system partitions, cloud-only media, and inaccessible providers cannot be scanned. On a device that blocks SD card direct access, use Choose folder.
- **Choose folder**: use Android's system picker to scan one selected folder recursively, with or without full-storage permission.
- Choose **All files**, **Photos**, **Videos**, or **Documents** as a type filter before a scan. Unknown file types are included under All files.
- The report counts extra exact copies by type and folder and shows the exact file paths. Exact matches pass size, 64-KiB fingerprint, then full SHA-256. Three bounded workers process hash candidates. There is no persistent hash cache yet, so later scans still verify files afresh.
- Look-alike photos have a separate **SIMILAR PHOTOS - NOT DUPLICATES** heading. A 64-bit visual hash groups at most the first 6,000 eligible photos; the app reports any omitted count. Similar photos are never bulk-selected.

## Reviewing and deleting

Images have thumbnail and tap-to-preview views. One copy in each group is marked KEEP and cannot be selected; **Select all exact** selects only extra exact copies. You can manually select similar photos if you deliberately choose to remove them. The app warns separately about these real, different photos.

**Delete selected** removes the files directly from their original folders after a permanent-deletion confirmation. There is **no app recycle bin, restore, or undo**. Each selected exact file and keeper are re-hashed just before deletion, and changed files are skipped. The selected photo's visual hash is rechecked for similar groups. Some Android file providers may apply their own trash policy. Check previews and keep a backup of important files.

## Limitations

Android 8+ (API 26), target API 35. Runtime permissions and OEM storage policies vary. A CI-green build proves compilation, not correct behavior on every phone or version. Large result sets may use considerable memory; results are paged in groups but scanning collects file metadata in memory. This release has no background scan, video similarity, persistent hash cache or automatic bin cleanup. Scan performance depends on storage throughput and number of same-size files.

Build with Gradle 8.11.1, JDK 17 and SDK 35: `gradle :app:assembleDebug`.

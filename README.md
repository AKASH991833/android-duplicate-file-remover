# Duplicate File Remover for Android (v1.1)

An on-device Android app for **byte-identical** files and folders. No visual similarity detection. Download the debug APK from the latest successful [Android build](https://github.com/AKASH991833/android-duplicate-file-remover/actions/workflows/android.yml).

## Scanning

- Full phone scans accessible shared internal storage and mounted SD storage after the required All files permission. Choose folder uses Android's system document picker without full-storage access. Private app data, protected Android/data, system partitions, cloud-only media, and inaccessible providers are outside scope.
- File types: All files, Photos, Videos, Documents. Exact file matches are grouped by size, then first-chunk SHA-256, then a full-file SHA-256. Names and extensions need not match if bytes match.
- **Only All files scans can identify whole-folder duplicates.** Folder names can differ, but their complete relative file names, nested directory names and file contents must match. Every file is SHA-256 hashed; a folder signature includes its child structure, relative names, sizes and content hashes. Empty folders count. Hidden/protected/unreadable children make the containing folder ineligible for a match. A folder whose structure matches but whose file bytes differ is not reported. Folder groups are **review only**: this version does not delete entire folders.
- Selected file deletion is permanent with no app recycle bin or undo. A keeper remains for every exact file group. The selected file and its keeper are re-hashed just before deletion, and changed/unreadable files are skipped. Some providers may apply their own trash policy. Back up important files.

Android 8+ (API 26), target API 35. The debug build and emulator tests do not prove behavior on every real device/OEM. Scans collect file metadata in memory; full-folder verification can take longer because all files must be read and hashed. No persistent hash cache. Folder matches represent a point-in-time scan and are not automatically rechecked for deletion (no folder deletion is offered).

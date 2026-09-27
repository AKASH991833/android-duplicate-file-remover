package com.akash.duperemover;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.InputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private static final int PICK_TREE = 42;
    private static final int MAX_FILES = 20000;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final List<Group> groups = new ArrayList<>();
    private final Map<String, CheckBox> selections = new HashMap<>();
    private Uri tree;
    private TextView status;
    private LinearLayout results;
    private Button scanButton, deleteButton;
    private boolean busy = false;

    static class Entry {
        final Uri uri;
        final String name;
        final long size;
        final int flags;
        Entry(Uri uri, String name, long size, int flags) {
            this.uri = uri; this.name = name; this.size = size; this.flags = flags;
        }
    }
    static class Group {
        final String hash;
        final List<Entry> files;
        Group(String hash, List<Entry> files) { this.hash = hash; this.files = files; }
    }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        page.setPadding(pad, pad, pad, pad);
        TextView title = new TextView(this);
        title.setText("Duplicate File Remover"); title.setTextSize(22);
        page.addView(title);
        TextView hint = new TextView(this);
        hint.setText("Local folder only. Exact duplicate files. Nothing is deleted during a scan.");
        page.addView(hint);
        Button choose = new Button(this); choose.setText("Choose folder");
        choose.setOnClickListener(v -> {
            if (busy) return;
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION |
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
            startActivityForResult(intent, PICK_TREE);
        });
        page.addView(choose);
        scanButton = new Button(this); scanButton.setText("Scan"); scanButton.setEnabled(false);
        scanButton.setOnClickListener(v -> scan()); page.addView(scanButton);
        deleteButton = new Button(this); deleteButton.setText("Delete selected"); deleteButton.setEnabled(false);
        deleteButton.setOnClickListener(v -> confirmDelete()); page.addView(deleteButton);
        status = new TextView(this); status.setText("Choose a folder to start."); page.addView(status);
        ScrollView scroll = new ScrollView(this);
        results = new LinearLayout(this); results.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(results); page.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(page);
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != PICK_TREE || resultCode != RESULT_OK || data == null || data.getData() == null) return;
        Uri picked = data.getData();
        try {
            int flags = data.getFlags() & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            getContentResolver().takePersistableUriPermission(picked, flags);
            tree = picked;
            groups.clear(); selections.clear(); results.removeAllViews();
            scanButton.setEnabled(true); deleteButton.setEnabled(false);
            status.setText("Folder selected. Tap Scan.");
        } catch (Exception ex) {
            status.setText("Could not keep folder access: " + ex.getMessage());
        }
    }

    private void setBusy(boolean value) {
        busy = value;
        scanButton.setEnabled(!value && tree != null);
        deleteButton.setEnabled(!value && !groups.isEmpty());
    }

    private void scan() {
        if (tree == null || busy) return;
        final Uri source = tree;
        groups.clear(); selections.clear(); results.removeAllViews();
        setBusy(true); status.setText("Scanning. Please wait...");
        worker.execute(() -> {
            try {
                List<Entry> files = new ArrayList<>();
                String rootId = DocumentsContract.getTreeDocumentId(source);
                walk(source, rootId, files);
                Map<Long, List<Entry>> bySize = new HashMap<>();
                for (Entry file : files) bySize.computeIfAbsent(file.size, ignored -> new ArrayList<>()).add(file);
                Map<String, List<Entry>> byHash = new HashMap<>();
                int skipped = 0;
                for (List<Entry> candidates : bySize.values()) {
                    if (candidates.size() < 2) continue;
                    for (Entry entry : candidates) {
                        try {
                            String digest = hash(entry.uri);
                            byHash.computeIfAbsent(entry.size + ":" + digest, ignored -> new ArrayList<>()).add(entry);
                        } catch (Exception ex) { skipped++; }
                    }
                }
                List<Group> found = new ArrayList<>();
                for (Map.Entry<String, List<Entry>> item : byHash.entrySet()) {
                    if (item.getValue().size() < 2) continue;
                    List<Entry> matches = item.getValue();
                    matches.sort(Comparator.comparing(a -> a.uri.toString()));
                    found.add(new Group(item.getKey().substring(item.getKey().indexOf(':') + 1), matches));
                }
                found.sort(Comparator.comparing(g -> g.files.get(0).name));
                final int unreadable = skipped;
                runOnUiThread(() -> {
                    if (!source.equals(tree)) { setBusy(false); return; }
                    groups.addAll(found); showGroups(); setBusy(false);
                    status.setText(files.size() + " files scanned; " + found.size() + " exact duplicate groups. " +
                            unreadable + " candidate files unreadable.");
                });
            } catch (Exception ex) {
                runOnUiThread(() -> { setBusy(false); status.setText("Scan stopped: " + ex.getMessage()); });
            }
        });
    }

    private void walk(Uri root, String parentId, List<Entry> files) throws Exception {
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(root, parentId);
        String[] columns = { DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_SIZE,
                DocumentsContract.Document.COLUMN_FLAGS };
        try (Cursor cursor = getContentResolver().query(children, columns, null, null, null)) {
            if (cursor == null) throw new Exception("Folder cannot be read");
            while (cursor.moveToNext()) {
                String id = cursor.getString(0);
                String name = cursor.getString(1);
                String mime = cursor.getString(2);
                long size = cursor.isNull(3) ? -1 : cursor.getLong(3);
                int flags = cursor.isNull(4) ? 0 : cursor.getInt(4);
                if (DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)) {
                    walk(root, id, files);
                } else if (size >= 0) {
                    if (files.size() >= MAX_FILES) throw new Exception("More than " + MAX_FILES + " files; choose a smaller folder");
                    files.add(new Entry(DocumentsContract.buildDocumentUriUsingTree(root, id), name, size, flags));
                }
            }
        }
    }

    private String hash(Uri uri) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            if (in == null) throw new Exception("Cannot open file");
            byte[] buf = new byte[65536]; int n;
            while ((n = in.read(buf)) != -1) digest.update(buf, 0, n);
        }
        StringBuilder result = new StringBuilder();
        for (byte b : digest.digest()) result.append(String.format(java.util.Locale.ROOT, "%02x", b & 0xff));
        return result.toString();
    }

    private long currentSize(Uri uri) throws Exception {
        String[] projection = { DocumentsContract.Document.COLUMN_SIZE };
        try (Cursor c = getContentResolver().query(uri, projection, null, null, null)) {
            if (c == null || !c.moveToFirst() || c.isNull(0)) throw new Exception("Size unavailable");
            return c.getLong(0);
        }
    }

    private void showGroups() {
        results.removeAllViews(); selections.clear();
        for (Group group : groups) {
            TextView label = new TextView(this);
            label.setText("\nGroup: " + group.files.size() + " copies · " + group.files.get(0).size + " bytes");
            label.setTextSize(17); results.addView(label);
            Entry keeper = group.files.get(0);
            TextView keep = new TextView(this); keep.setText("KEEP: " + keeper.name + "\n" + keeper.uri);
            results.addView(keep);
            for (int i = 1; i < group.files.size(); i++) {
                Entry file = group.files.get(i);
                CheckBox checkbox = new CheckBox(this);
                checkbox.setText(file.name + "\n" + file.uri);
                boolean canDelete = (file.flags & DocumentsContract.Document.FLAG_SUPPORTS_DELETE) != 0;
                checkbox.setEnabled(canDelete);
                if (!canDelete) checkbox.append("\nProvider does not offer delete");
                results.addView(checkbox);
                selections.put(file.uri.toString(), checkbox);
            }
        }
    }

    private void confirmDelete() {
        if (busy) return;
        List<Entry> chosen = new ArrayList<>();
        for (Group group : groups) for (int i = 1; i < group.files.size(); i++) {
            Entry entry = group.files.get(i);
            CheckBox box = selections.get(entry.uri.toString());
            if (box != null && box.isChecked()) chosen.add(entry);
        }
        if (chosen.isEmpty()) { status.setText("Select at least one copy first."); return; }
        new AlertDialog.Builder(this).setTitle("Delete " + chosen.size() + " files?")
                .setMessage("These are the selected duplicate copies, not the kept originals. The provider may delete immediately with no Trash. Back up anything important. Continue?")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Delete", (dialog, which) -> deleteChecked(chosen)).show();
    }

    private void deleteChecked(List<Entry> chosen) {
        if (busy) return;
        setBusy(true); status.setText("Rechecking and deleting selected copies...");
        List<Group> snapshot = new ArrayList<>(groups);
        worker.execute(() -> {
            int deleted = 0, skipped = 0;
            for (Group group : snapshot) {
                Entry keeper = group.files.get(0);
                for (int i = 1; i < group.files.size(); i++) {
                    Entry entry = group.files.get(i);
                    if (!chosen.contains(entry)) continue;
                    try {
                        if (currentSize(keeper.uri) != keeper.size || currentSize(entry.uri) != entry.size ||
                                !group.hash.equals(hash(keeper.uri)) || !group.hash.equals(hash(entry.uri))) {
                            skipped++; continue;
                        }
                        if (DocumentsContract.deleteDocument(getContentResolver(), entry.uri)) deleted++;
                        else skipped++;
                    } catch (Exception ex) { skipped++; }
                }
            }
            final int done = deleted, failed = skipped;
            runOnUiThread(() -> {
                groups.clear(); selections.clear(); results.removeAllViews(); setBusy(false);
                status.setText(done + " deleted; " + failed + " skipped/failed. Scan again to refresh. Deletion may be permanent.");
                deleteButton.setEnabled(false);
            });
        });
    }

    @Override protected void onDestroy() {
        worker.shutdown(); super.onDestroy();
    }
}

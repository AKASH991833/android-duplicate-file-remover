package com.akash.duperemover;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Outline;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.util.LruCache;
import android.view.Gravity;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Duplicate File Remover - native Android port of the desktop duplicate-file-remover.
 *
 * Detection:
 *   EXACT  - same size + same SHA-256. Byte-identical copies; extras are safe to move out.
 *   SIMILAR - photos whose 64-bit dHash (perceptual hash) is within a small Hamming
 *             distance. These are real, different photos that merely look alike; the app
 *             keeps them in a clearly separated section and never bulk-selects them.
 *
 * Deletion is recycle-bin style: selected files are moved into a "Deleted Photos" folder
 * the app creates inside the chosen folder (copy, verify, then remove original). From
 * there the user can restore items or permanently delete them. Nothing is erased
 * permanently during a scan or a move.
 */
public class MainActivity extends Activity {
    private static final int PICK_TREE = 42;
    private static final int MAX_FILES = 20000;
    private static final int MAX_PHOTOS_FOR_SIMILAR = 6000;
    private static final int SIM_THRESHOLD = 10;      // Hamming distance out of 64 bits
    private static final int THUMB_AUTO_LIMIT = 240;  // auto-decoded thumbnails per render
    private static final String BIN_NAME = "Deleted Photos";
    private static final String MANIFEST_NAME = "deleted_photos_manifest.json";

    private static final int BG = 0xFF0F1420;
    private static final int CARD = 0xFF1B2333;
    private static final int CARD_SOFT = 0xFF26314A;
    private static final int ACCENT = 0xFF4F8CFF;
    private static final int EXACT_COLOR = 0xFF3DDC84;
    private static final int SIMILAR_COLOR = 0xFFFFB74D;
    private static final int TXT = 0xFFE8ECF4;
    private static final int DIM = 0xFF9AA7BD;
    private static final int DANGER = 0xFFE05252;

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final ExecutorService thumbs = Executors.newFixedThreadPool(2);
    private final List<Group> groups = new ArrayList<>();
    private final List<BinEntry> binItems = new ArrayList<>();
    private final Map<String, Boolean> selected = new HashMap<>();
    private final LruCache<String, Bitmap> thumbCache = new LruCache<String, Bitmap>(16 * 1024 * 1024) {
        @Override protected int sizeOf(String key, Bitmap value) { return value.getByteCount(); }
    };

    private Uri tree;
    private String binId;               // document id of the "Deleted Photos" folder, once known
    private boolean binLoaded = false;  // manifest read into binItems
    private boolean binMode = false;    // showing the Deleted Photos view
    private boolean busy = false;
    private int filter = 0;             // 0 = all, 1 = exact, 2 = similar
    private int thumbAutoLoaded = 0;
    private int lastExactGroups = 0, lastSimilarGroups = 0;

    private TextView status, statsLine, heading;
    private LinearLayout results, filterRow;
    private Button scanButton, deleteButton, binButton;
    private TextView chipAll, chipExact, chipSimilar;
    private ProgressBar progress;

    static class Entry {
        final Uri uri;
        final String name;
        final long size;
        final int flags;
        final String mime;
        final boolean image;
        final String parentId;          // document id of the folder containing this file
        String sha;                     // set for files that went through the exact pass
        Long dhash;                     // set for photos that went through the similar pass
        Entry(Uri uri, String name, long size, int flags, String mime, String parentId) {
            this.uri = uri; this.name = name; this.size = size; this.flags = flags;
            this.mime = mime; this.parentId = parentId;
            this.image = mime != null && mime.startsWith("image/");
        }
    }

    static class Group {
        final boolean exact;
        final List<Entry> files;
        final int matchPct;             // 100 for exact groups
        Group(boolean exact, List<Entry> files, int matchPct) {
            this.exact = exact; this.files = files; this.matchPct = matchPct;
        }
        long reclaimable() {
            if (exact) return files.get(0).size * (long) (files.size() - 1);
            long sum = 0, max = 0;
            for (Entry e : files) { sum += e.size; if (e.size > max) max = e.size; }
            return sum - max;
        }
    }

    static class BinEntry {
        String name;
        String mime;
        String originalParentId;
        BinEntry(String name, String mime, String originalParentId) {
            this.name = name; this.mime = mime; this.originalParentId = originalParentId;
        }
    }

    // ---------- UI construction ----------

    private int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density + 0.5f); }

    private GradientDrawable rounded(int color, int radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(radiusDp));
        return d;
    }

    private void styleButton(Button b, int bgColor, int textColor) {
        b.setBackground(rounded(bgColor, 12));
        b.setTextColor(textColor);
        b.setAllCaps(false);
        b.setTextSize(14);
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setPadding(dp(14), dp(10), dp(14), dp(10));
    }

    private TextView pill(String text, int bgColor, int textColor) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(textColor);
        t.setTextSize(11);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setBackground(rounded(bgColor, 20));
        t.setPadding(dp(10), dp(4), dp(10), dp(4));
        return t;
    }

    private LinearLayout card() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackground(rounded(CARD, 16));
        int p = dp(14);
        c.setPadding(p, p, p, p);
        return c;
    }

    private void addWithMargin(LinearLayout parent, View child, int topDp, int bottomDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(0, dp(topDp), 0, dp(bottomDp));
        parent.addView(child, lp);
    }

    private TextView label(String text, int sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setBackgroundColor(BG);
        int pad = dp(16);
        page.setPadding(pad, pad, pad, dp(10));

        heading = label("Duplicate File Remover", 22, TXT, true);
        page.addView(heading);
        TextView sub = label("Exact copies + look-alike photos - local folder only - nothing is erased permanently", 13, DIM, false);
        addWithMargin(page, sub, 2, 10);

        status = label("Choose a folder to start.", 13, DIM, false);
        addWithMargin(page, status, 0, 8);

        LinearLayout topRow = new LinearLayout(this);
        topRow.setOrientation(LinearLayout.HORIZONTAL);
        Button choose = new Button(this);
        choose.setText("Choose folder");
        styleButton(choose, CARD_SOFT, TXT);
        choose.setOnClickListener(v -> {
            if (busy) return;
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION |
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
            startActivityForResult(intent, PICK_TREE);
        });
        topRow.addView(choose, new LinearLayout.LayoutParams(-2, -2));

        scanButton = new Button(this);
        scanButton.setText("Scan");
        scanButton.setEnabled(false);
        styleButton(scanButton, ACCENT, 0xFFFFFFFF);
        scanButton.setOnClickListener(v -> scan());
        LinearLayout.LayoutParams scanLp = new LinearLayout.LayoutParams(-2, -2);
        scanLp.setMargins(dp(8), 0, 0, 0);
        topRow.addView(scanButton, scanLp);

        binButton = new Button(this);
        binButton.setText("Deleted Photos");
        styleButton(binButton, CARD_SOFT, SIMILAR_COLOR);
        binButton.setOnClickListener(v -> toggleBinMode());
        LinearLayout.LayoutParams binLp = new LinearLayout.LayoutParams(-2, -2);
        binLp.setMargins(dp(8), 0, 0, 0);
        topRow.addView(binButton, binLp);
        page.addView(topRow);

        filterRow = new LinearLayout(this);
        filterRow.setOrientation(LinearLayout.HORIZONTAL);
        filterRow.setGravity(Gravity.CENTER_VERTICAL);
        chipAll = chip("All", 0);
        chipExact = chip("Exact duplicates", 1);
        chipSimilar = chip("Similar photos", 2);
        filterRow.setVisibility(View.GONE);
        addWithMargin(page, filterRow, 10, 0);

        statsLine = label("", 13, TXT, true);
        statsLine.setVisibility(View.GONE);
        addWithMargin(page, statsLine, 8, 0);

        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setIndeterminate(true);
        progress.setVisibility(View.GONE);
        addWithMargin(page, progress, 8, 0);

        ScrollView scroll = new ScrollView(this);
        results = new LinearLayout(this);
        results.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(results);
        page.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));

        LinearLayout bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.HORIZONTAL);
        bottom.setGravity(Gravity.CENTER_VERTICAL);
        Button selectAll = new Button(this);
        selectAll.setText("Select all duplicates");
        styleButton(selectAll, CARD_SOFT, TXT);
        selectAll.setOnClickListener(v -> selectAllExact());
        bottom.addView(selectAll, new LinearLayout.LayoutParams(-2, -2));
        Button clear = new Button(this);
        clear.setText("Clear");
        styleButton(clear, CARD_SOFT, TXT);
        clear.setOnClickListener(v -> { selected.clear(); render(); });
        LinearLayout.LayoutParams clearLp = new LinearLayout.LayoutParams(-2, -2);
        clearLp.setMargins(dp(8), 0, 0, 0);
        bottom.addView(clear, clearLp);
        deleteButton = new Button(this);
        deleteButton.setText("Delete selected");
        deleteButton.setEnabled(false);
        styleButton(deleteButton, DANGER, 0xFFFFFFFF);
        deleteButton.setOnClickListener(v -> confirmMove());
        LinearLayout.LayoutParams delLp = new LinearLayout.LayoutParams(-2, -2);
        delLp.setMargins(dp(8), 0, 0, 0);
        bottom.addView(deleteButton, delLp);
        LinearLayout.LayoutParams bottomLp = new LinearLayout.LayoutParams(-1, -2);
        bottomLp.setMargins(0, dp(8), 0, 0);
        page.addView(bottom, bottomLp);

        setContentView(page);
    }

    private TextView chip(String text, int mode) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(13);
        t.setPadding(dp(12), dp(6), dp(12), dp(6));
        t.setOnClickListener(v -> { filter = mode; updateChips(); render(); });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.setMargins(0, 0, dp(8), 0);
        t.setLayoutParams(lp);
        filterRow.addView(t);
        refreshChip(t, mode);
        return t;
    }

    private void refreshChip(TextView t, int mode) {
        boolean on = filter == mode;
        t.setBackground(rounded(on ? ACCENT : CARD_SOFT, 20));
        t.setTextColor(on ? 0xFFFFFFFF : DIM);
        t.setTypeface(on ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
    }

    private void updateChips() {
        refreshChip(chipAll, 0);
        refreshChip(chipExact, 1);
        refreshChip(chipSimilar, 2);
    }

    // ---------- folder picking ----------

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != PICK_TREE || resultCode != RESULT_OK || data == null || data.getData() == null) return;
        Uri picked = data.getData();
        try {
            int flags = data.getFlags() & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            getContentResolver().takePersistableUriPermission(picked, flags);
            tree = picked;
            binId = null;
            binLoaded = false;
            binItems.clear();
            groups.clear();
            selected.clear();
            thumbCache.evictAll();
            binMode = false;
            results.removeAllViews();
            filterRow.setVisibility(View.GONE);
            statsLine.setVisibility(View.GONE);
            scanButton.setEnabled(true);
            deleteButton.setEnabled(false);
            status.setText("Folder selected. Tap Scan.");
            refreshBinCount();
        } catch (Exception ex) {
            status.setText("Could not keep folder access: " + ex.getMessage());
        }
    }

    private void setBusy(boolean value) {
        busy = value;
        progress.setVisibility(value ? View.VISIBLE : View.GONE);
        scanButton.setEnabled(!value && tree != null);
        updateDeleteLabel();
    }

    // ---------- scanning ----------

    private void postStatus(final String text) {
        runOnUiThread(() -> status.setText(text));
    }

    private void scan() {
        if (tree == null || busy) return;
        final Uri source = tree;
        groups.clear();
        selected.clear();
        thumbCache.evictAll();
        results.removeAllViews();
        setBusy(true);
        status.setText("Scanning folder...");
        worker.execute(() -> {
            try {
                ensureBinQuiet(source);
                List<Entry> files = new ArrayList<>();
                String rootId = DocumentsContract.getTreeDocumentId(source);
                walk(source, rootId, rootId, files, binId);
                final int totalFiles = files.size();

                // Pass 1: exact duplicates (same size, then same SHA-256).
                Map<Long, List<Entry>> bySize = new HashMap<>();
                for (Entry f : files) bySize.computeIfAbsent(f.size, k -> new ArrayList<>()).add(f);
                List<Entry> candidates = new ArrayList<>();
                for (List<Entry> c : bySize.values()) if (c.size() >= 2) candidates.addAll(c);
                Map<String, List<Entry>> byHash = new HashMap<>();
                int unreadable = 0, hashed = 0;
                for (Entry e : candidates) {
                    try {
                        e.sha = sha256(e.uri);
                        byHash.computeIfAbsent(e.size + ":" + e.sha, k -> new ArrayList<>()).add(e);
                    } catch (Exception ex) { unreadable++; }
                    hashed++;
                    if (hashed % 10 == 0) postStatus("Checking exact copies: " + hashed + "/" + candidates.size());
                }
                List<Group> exact = new ArrayList<>();
                final Set<String> inExact = new HashSet<>();
                for (Map.Entry<String, List<Entry>> item : byHash.entrySet()) {
                    if (item.getValue().size() < 2) continue;
                    List<Entry> matches = item.getValue();
                    matches.sort((a, b) -> a.uri.toString().compareTo(b.uri.toString()));
                    for (Entry e : matches) inExact.add(e.uri.toString());
                    exact.add(new Group(true, matches, 100));
                }

                // Pass 2: visually similar photos (dHash), excluding anything already
                // reported as an exact duplicate.
                List<Entry> photos = new ArrayList<>();
                for (Entry f : files) {
                    if (!f.image || inExact.contains(f.uri.toString())) continue;
                    photos.add(f);
                    if (photos.size() >= MAX_PHOTOS_FOR_SIMILAR) break;
                }
                int done = 0, dfail = 0;
                for (Entry p : photos) {
                    p.dhash = dhash(p.uri);
                    if (p.dhash == null) dfail++;
                    done++;
                    if (done % 25 == 0) postStatus("Comparing photos: " + done + "/" + photos.size());
                }
                List<List<Entry>> buckets = new ArrayList<>();
                List<Long> leaders = new ArrayList<>();
                for (Entry p : photos) {
                    if (p.dhash == null) continue;
                    int best = -1, bestDist = Integer.MAX_VALUE;
                    for (int gi = 0; gi < leaders.size(); gi++) {
                        int d = Long.bitCount(p.dhash ^ leaders.get(gi));
                        if (d <= SIM_THRESHOLD && d < bestDist) { best = gi; bestDist = d; }
                    }
                    if (best >= 0) buckets.get(best).add(p);
                    else {
                        List<Entry> ng = new ArrayList<>();
                        ng.add(p);
                        buckets.add(ng);
                        leaders.add(p.dhash);
                    }
                }
                List<Group> similar = new ArrayList<>();
                for (List<Entry> g : buckets) {
                    if (g.size() < 2) continue;
                    g.sort((a, b) -> Long.compare(b.size, a.size)); // largest first = keeper
                    int maxDist = 0;
                    for (int i = 0; i < g.size(); i++)
                        for (int j = i + 1; j < g.size(); j++) {
                            int d = Long.bitCount(g.get(i).dhash ^ g.get(j).dhash);
                            if (d > maxDist) maxDist = d;
                        }
                    int pct = (int) Math.round((64 - maxDist) * 100.0 / 64);
                    similar.add(new Group(false, g, pct));
                }

                List<Group> found = new ArrayList<>();
                found.addAll(exact);
                found.addAll(similar);
                found.sort((a, b) -> Long.compare(b.reclaimable(), a.reclaimable()));
                final int ex = exact.size(), sim = similar.size();
                final int skipTotal = unreadable + dfail;
                runOnUiThread(() -> {
                    if (!source.equals(tree)) { setBusy(false); return; }
                    groups.addAll(found);
                    lastExactGroups = ex;
                    lastSimilarGroups = sim;
                    setBusy(false);
                    binMode = false;
                    filter = 0;
                    updateChips();
                    render();
                    status.setText(totalFiles + " files scanned - " + ex + " exact groups, " + sim +
                            " similar groups" + (skipTotal > 0 ? " - " + skipTotal + " files unreadable" : ""));
                });
            } catch (Exception ex) {
                runOnUiThread(() -> { setBusy(false); status.setText("Scan stopped: " + ex.getMessage()); });
            }
        });
    }

    private void walk(Uri root, String parentId, String currentId, List<Entry> files, String skipId) throws Exception {
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(root, currentId);
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
                    if (skipId != null && skipId.equals(id)) continue; // never rescan the bin
                    walk(root, id, id, files, skipId);
                } else if (size >= 0) {
                    if (files.size() >= MAX_FILES) throw new Exception("More than " + MAX_FILES + " files; choose a smaller folder");
                    files.add(new Entry(DocumentsContract.buildDocumentUriUsingTree(root, id), name, size, flags, mime, currentId));
                    if (files.size() % 300 == 0) postStatus("Found " + files.size() + " files...");
                }
            }
        }
    }

    private String sha256(Uri uri) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            if (in == null) throw new Exception("Cannot open file");
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) != -1) digest.update(buf, 0, n);
        }
        StringBuilder result = new StringBuilder();
        for (byte b : digest.digest()) result.append(String.format(Locale.ROOT, "%02x", b & 0xff));
        return result.toString();
    }

    private long currentSize(Uri uri) throws Exception {
        String[] projection = { DocumentsContract.Document.COLUMN_SIZE };
        try (Cursor c = getContentResolver().query(uri, projection, null, null, null)) {
            if (c == null || !c.moveToFirst() || c.isNull(0)) throw new Exception("Size unavailable");
            return c.getLong(0);
        }
    }

    // ---------- perceptual hash ----------

    private Bitmap decodeSampled(Uri uri, int targetMax) {
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            try (InputStream in = getContentResolver().openInputStream(uri)) {
                if (in == null) return null;
                BitmapFactory.decodeStream(in, null, bounds);
            }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;
            int sample = 1;
            int maxDim = Math.max(bounds.outWidth, bounds.outHeight);
            while (maxDim / (sample * 2) >= targetMax) sample *= 2;
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = sample;
            try (InputStream in = getContentResolver().openInputStream(uri)) {
                if (in == null) return null;
                return BitmapFactory.decodeStream(in, null, opts);
            }
        } catch (Exception ex) {
            return null;
        }
    }

    private int gray(int color) {
        int r = (color >> 16) & 0xff, g = (color >> 8) & 0xff, b = color & 0xff;
        return (r * 299 + g * 587 + b * 114) / 1000;
    }

    private Long dhash(Uri uri) {
        try {
            Bitmap bmp = decodeSampled(uri, 72);
            if (bmp == null) return null;
            Bitmap small = Bitmap.createScaledBitmap(bmp, 9, 8, true);
            long h = 0;
            for (int y = 0; y < 8; y++) {
                for (int x = 0; x < 8; x++) {
                    h = (h << 1) | (gray(small.getPixel(x, y)) > gray(small.getPixel(x + 1, y)) ? 1L : 0L);
                }
            }
            if (small != bmp) small.recycle();
            bmp.recycle();
            return h;
        } catch (Exception ex) {
            return null;
        }
    }

    // ---------- results rendering ----------

    private void render() {
        if (binMode) { renderBin(); return; }
        results.removeAllViews();
        thumbAutoLoaded = 0;
        boolean anyExact = false, anySimilar = false;
        for (Group g : groups) { if (g.exact) anyExact = true; else anySimilar = true; }
        if (groups.isEmpty()) {
            filterRow.setVisibility(View.GONE);
            statsLine.setVisibility(View.GONE);
            updateDeleteLabel();
            return;
        }
        filterRow.setVisibility(View.VISIBLE);
        statsLine.setVisibility(View.VISIBLE);
        long reclaimExact = 0;
        int exactCopies = 0;
        for (Group g : groups) if (g.exact) { reclaimExact += g.reclaimable(); exactCopies += g.files.size() - 1; }
        statsLine.setText(lastExactGroups + " exact groups (" + exactCopies + " extra copies, ~" +
                fmtBytes(reclaimExact) + ") - " + lastSimilarGroups + " similar-photo groups");

        if (anyExact && filter != 2) {
            addWithMargin(results, sectionHeader("EXACT DUPLICATES",
                    "Byte-identical copies (same size + same SHA-256). Extra copies are safe to remove.", EXACT_COLOR), 12, 6);
            for (Group g : groups) if (g.exact) addWithMargin(results, groupCard(g), 0, 10);
        }
        if (anySimilar && filter != 1) {
            addWithMargin(results, sectionHeader("SIMILAR PHOTOS - NOT DUPLICATES",
                    "Real, different photos that only look alike. Nothing here is an exact copy - review each one yourself before removing anything.", SIMILAR_COLOR), 12, 6);
            for (Group g : groups) if (!g.exact) addWithMargin(results, groupCard(g), 0, 10);
        }
        updateDeleteLabel();
    }

    private View sectionHeader(String title, String subtitle, int color) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        TextView t = label(title, 15, color, true);
        box.addView(t);
        TextView s = label(subtitle, 12, DIM, false);
        addWithMargin(box, s, 2, 0);
        return box;
    }

    private View groupCard(Group g) {
        LinearLayout card = card();
        LinearLayout headerRow = new LinearLayout(this);
        headerRow.setOrientation(LinearLayout.HORIZONTAL);
        headerRow.setGravity(Gravity.CENTER_VERTICAL);

        TextView badge = g.exact
                ? pill("EXACT COPY", 0x223DDC84, EXACT_COLOR)
                : pill("SIMILAR - REAL PHOTO", 0x33FFB74D, SIMILAR_COLOR);
        headerRow.addView(badge);

        String head = g.exact
                ? g.files.size() + " identical copies - " + fmtBytes(g.files.get(0).size) + " each"
                : g.files.size() + " look-alike photos - about " + g.matchPct + "% alike";
        TextView headText = label(head, 14, TXT, true);
        LinearLayout.LayoutParams headLp = new LinearLayout.LayoutParams(0, -2, 1);
        headLp.setMargins(dp(10), 0, 0, 0);
        headerRow.addView(headText, headLp);
        card.addView(headerRow);

        TextView subText = label(g.exact
                ? "Free ~" + fmtBytes(g.reclaimable()) + " by removing " + (g.files.size() - 1) + " extra copies"
                : "These are NOT exact duplicates. Keeping the largest; remove only ones you checked.",
                12, DIM, false);
        addWithMargin(card, subText, 4, 8);

        for (int i = 0; i < g.files.size(); i++) {
            addWithMargin(card, fileRow(g, i), 0, 6);
        }
        if (g.exact) {
            Button groupSelect = new Button(this);
            groupSelect.setText("Select extra copies in this group");
            styleButton(groupSelect, CARD_SOFT, TXT);
            groupSelect.setOnClickListener(v -> {
                for (int i = 1; i < g.files.size(); i++) {
                    Entry e = g.files.get(i);
                    if (deletable(e)) selected.put(e.uri.toString(), true);
                }
                render();
            });
            addWithMargin(card, groupSelect, 4, 0);
        }
        return card;
    }

    private boolean deletable(Entry e) {
        return (e.flags & DocumentsContract.Document.FLAG_SUPPORTS_DELETE) != 0;
    }

    private View fileRow(Group g, int index) {
        Entry e = g.files.get(index);
        boolean keeper = index == 0;
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackground(rounded(CARD_SOFT, 12));
        row.setPadding(dp(8), dp(8), dp(8), dp(8));

        View slot = thumbSlot(e);
        LinearLayout.LayoutParams slotLp = new LinearLayout.LayoutParams(dp(64), dp(64));
        row.addView(slot, slotLp);

        LinearLayout mid = new LinearLayout(this);
        mid.setOrientation(LinearLayout.VERTICAL);
        TextView name = label(e.name, 13, TXT, true);
        mid.addView(name);
        String detail = fmtBytes(e.size);
        if (!g.exact) detail += " - " + (e.dhash == null ? "no photo hash" : "photo hash " + Long.toHexString(e.dhash));
        TextView det = label(detail, 11, DIM, false);
        addWithMargin(mid, det, 2, 0);
        LinearLayout.LayoutParams midLp = new LinearLayout.LayoutParams(0, -2, 1);
        midLp.setMargins(dp(10), 0, dp(6), 0);
        row.addView(mid, midLp);

        if (keeper) {
            row.addView(pill("KEEP", 0x223DDC84, EXACT_COLOR));
        } else {
            CheckBox box = new CheckBox(this);
            String key = e.uri.toString();
            box.setChecked(Boolean.TRUE.equals(selected.get(key)));
            box.setOnCheckedChangeListener((buttonView, isChecked) -> {
                selected.put(key, isChecked);
                updateDeleteLabel();
            });
            if (!deletable(e)) {
                box.setEnabled(false);
            }
            row.addView(box);
        }
        return row;
    }

    private View thumbSlot(Entry e) {
        if (!e.image) {
            String ext = "FILE";
            int dot = e.name.lastIndexOf('.');
            if (dot >= 0 && dot < e.name.length() - 1) {
                ext = e.name.substring(dot + 1).toUpperCase(Locale.ROOT);
                if (ext.length() > 4) ext = ext.substring(0, 4);
            }
            TextView t = label(ext, 11, DIM, true);
            t.setGravity(Gravity.CENTER);
            t.setBackground(rounded(0xFF141A29, 12));
            return t;
        }
        ImageView iv = new ImageView(this);
        iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
        iv.setBackground(rounded(0xFF141A29, 12));
        iv.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), dp(12));
            }
        });
        iv.setClipToOutline(true);
        iv.setOnClickListener(v -> preview(e));
        String key = e.uri.toString();
        Bitmap cached = thumbCache.get(key);
        if (cached != null) {
            iv.setImageBitmap(cached);
        } else if (thumbAutoLoaded < THUMB_AUTO_LIMIT) {
            thumbAutoLoaded++;
            loadThumb(iv, e, 320);
        }
        return iv;
    }

    private void loadThumb(ImageView iv, Entry e, int target) {
        String key = e.uri.toString();
        iv.setTag(key);
        thumbs.execute(() -> {
            Bitmap bmp = decodeSampled(e.uri, target);
            if (bmp == null) return;
            thumbCache.put(key, bmp);
            runOnUiThread(() -> {
                if (key.equals(iv.getTag())) iv.setImageBitmap(bmp);
            });
        });
    }

    private void preview(Entry e) {
        ImageView iv = new ImageView(this);
        iv.setAdjustViewBounds(true);
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        int p = dp(8);
        iv.setPadding(p, p, p, p);
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(e.name)
                .setMessage(fmtBytes(e.size) + (e.image ? "" : " (not an image)"))
                .setView(iv)
                .setPositiveButton("Close", null)
                .show();
        if (!e.image) return;
        String key = e.uri.toString();
        thumbs.execute(() -> {
            Bitmap bmp = thumbCache.get(key);
            if (bmp == null) bmp = decodeSampled(e.uri, 1280);
            if (bmp == null) return;
            final Bitmap show = bmp;
            runOnUiThread(() -> { if (dialog.isShowing()) iv.setImageBitmap(show); });
        });
    }

    // ---------- selection + move to bin ----------

    private void selectAllExact() {
        if (busy || binMode) return;
        for (Group g : groups) {
            if (!g.exact) continue; // similar photos are never bulk-selected
            for (int i = 1; i < g.files.size(); i++) {
                Entry e = g.files.get(i);
                if (deletable(e)) selected.put(e.uri.toString(), true);
            }
        }
        render();
    }

    private int selectedCount() {
        int n = 0;
        for (Boolean b : selected.values()) if (Boolean.TRUE.equals(b)) n++;
        return n;
    }

    private void updateDeleteLabel() {
        int n = selectedCount();
        deleteButton.setText(n > 0 ? "Delete selected (" + n + ")" : "Delete selected");
        deleteButton.setEnabled(!busy && !binMode && n > 0);
    }

    private void confirmMove() {
        if (busy || binMode) return;
        List<Entry> chosen = new ArrayList<>();
        int exactSel = 0, similarSel = 0;
        long bytes = 0;
        for (Group g : groups) {
            for (int i = 1; i < g.files.size(); i++) {
                Entry e = g.files.get(i);
                if (Boolean.TRUE.equals(selected.get(e.uri.toString()))) {
                    chosen.add(e);
                    bytes += e.size;
                    if (g.exact) exactSel++; else similarSel++;
                }
            }
        }
        if (chosen.isEmpty()) {
            status.setText("Select at least one copy first.");
            return;
        }
        String msg = "Moving " + chosen.size() + " files (" + fmtBytes(bytes) + ") to the \"" + BIN_NAME +
                "\" folder inside your chosen folder. The kept copy in each group stays untouched.\n\n" +
                exactSel + " exact duplicate copies" +
                (similarSel > 0 ? "\n" + similarSel + " SIMILAR photos - these are NOT exact duplicates, they are real photos that only look alike" : "") +
                "\n\nNothing is erased permanently: you can restore or finally delete items from " + BIN_NAME + ".";
        new AlertDialog.Builder(this)
                .setTitle("Move " + chosen.size() + " files to " + BIN_NAME + "?")
                .setMessage(msg)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Move to " + BIN_NAME, (dialog, which) -> moveToBin(chosen))
                .show();
    }

    private void moveToBin(List<Entry> chosen) {
        if (busy) return;
        setBusy(true);
        status.setText("Rechecking and moving selected files...");
        List<Group> snapshot = new ArrayList<>(groups);
        Set<String> chosenKeys = new HashSet<>();
        for (Entry e : chosen) chosenKeys.add(e.uri.toString());
        worker.execute(() -> {
            int moved = 0, skipped = 0, step = 0;
            String error = null;
            for (Group group : snapshot) {
                Entry keeper = group.files.get(0);
                for (int i = 1; i < group.files.size(); i++) {
                    Entry entry = group.files.get(i);
                    if (!chosenKeys.contains(entry.uri.toString())) continue;
                    step++;
                    postStatus("Moving " + step + "/" + chosen.size() + "...");
                    try {
                        if (currentSize(keeper.uri) != keeper.size || currentSize(entry.uri) != entry.size) {
                            skipped++;
                            continue;
                        }
                        if (group.exact) {
                            if (entry.sha == null || !entry.sha.equals(sha256(keeper.uri)) ||
                                    !entry.sha.equals(sha256(entry.uri))) {
                                skipped++;
                                continue;
                            }
                        } else {
                            Long h = dhash(entry.uri);
                            if (h == null || entry.dhash == null ||
                                    Long.bitCount(h ^ entry.dhash) > SIM_THRESHOLD) {
                                skipped++;
                                continue;
                            }
                        }
                        if (addToBin(entry)) moved++;
                        else skipped++;
                    } catch (Exception ex) {
                        skipped++;
                        if (error == null) error = ex.getMessage();
                    }
                }
            }
            final int done = moved, failed = skipped;
            final String err = error;
            runOnUiThread(() -> {
                groups.clear();
                selected.clear();
                thumbCache.evictAll();
                results.removeAllViews();
                filterRow.setVisibility(View.GONE);
                statsLine.setVisibility(View.GONE);
                setBusy(false);
                status.setText(done + " moved to " + BIN_NAME + "; " + failed + " skipped/failed" +
                        (err != null ? " (" + err + ")" : "") + ". Scan again to refresh.");
                refreshBinCount();
            });
        });
    }

    // ---------- Deleted Photos (recycle bin) ----------

    private void toggleBinMode() {
        if (busy) return;
        if (tree == null) {
            status.setText("Choose a folder first - " + BIN_NAME + " lives inside it.");
            return;
        }
        binMode = !binMode;
        if (binMode) {
            status.setText(BIN_NAME + " - restore items or delete them forever.");
            heading.setText(BIN_NAME);
        } else {
            heading.setText("Duplicate File Remover");
            status.setText("Back to results.");
        }
        render();
    }

    private void refreshBinCount() {
        if (tree == null) return;
        worker.execute(() -> {
            int count = 0;
            try {
                ensureBinQuiet(tree);
                ensureManifestLoaded();
                count = binItems.size();
            } catch (Exception ignored) { }
            final int n = count;
            runOnUiThread(() -> binButton.setText(n > 0 ? BIN_NAME + " (" + n + ")" : BIN_NAME));
        });
    }

    /** Finds or creates the bin folder. Never throws; leaves binId null on failure. */
    private void ensureBinQuiet(Uri source) {
        if (binId != null || source == null) return;
        try {
            String rootId = DocumentsContract.getTreeDocumentId(source);
            String found = findChildId(source, rootId, BIN_NAME);
            if (found != null) {
                binId = found;
                return;
            }
            Uri rootDoc = DocumentsContract.buildDocumentUriUsingTree(source, rootId);
            Uri created = DocumentsContract.createDocument(getContentResolver(), rootDoc,
                    DocumentsContract.Document.MIME_TYPE_DIR, BIN_NAME);
            if (created != null) binId = DocumentsContract.getDocumentId(created);
        } catch (Exception ignored) { }
    }

    private String findChildId(Uri root, String parentId, String childName) {
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(root, parentId);
        String[] columns = { DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME };
        try (Cursor c = getContentResolver().query(children, columns, null, null, null)) {
            if (c == null) return null;
            while (c.moveToNext()) {
                if (childName.equals(c.getString(1))) return c.getString(0);
            }
        } catch (Exception ignored) { }
        return null;
    }

    private Uri binDocUri(String docId) {
        return DocumentsContract.buildDocumentUriUsingTree(tree, docId);
    }

    private void ensureManifestLoaded() {
        if (binLoaded || tree == null || binId == null) { binLoaded = true; return; }
        binLoaded = true;
        binItems.clear();
        String manifestId = findChildId(tree, binId, MANIFEST_NAME);
        if (manifestId == null) return;
        try (InputStream in = getContentResolver().openInputStream(binDocUri(manifestId))) {
            if (in == null) return;
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
            JSONObject json = new JSONObject(new String(out.toByteArray(), "UTF-8"));
            JSONArray items = json.optJSONArray("items");
            if (items == null) return;
            for (int i = 0; i < items.length(); i++) {
                JSONObject o = items.getJSONObject(i);
                binItems.add(new BinEntry(o.getString("name"), o.optString("mime", "application/octet-stream"),
                        o.optString("parent", "")));
            }
        } catch (Exception ignored) { }
    }

    private void saveManifest() {
        if (tree == null || binId == null) return;
        try {
            String manifestId = findChildId(tree, binId, MANIFEST_NAME);
            Uri manifestUri;
            if (manifestId == null) {
                manifestUri = DocumentsContract.createDocument(getContentResolver(), binDocUri(binId),
                        "application/json", MANIFEST_NAME);
                if (manifestUri == null) return;
            } else {
                manifestUri = binDocUri(manifestId);
            }
            JSONObject json = new JSONObject();
            JSONArray items = new JSONArray();
            for (BinEntry b : binItems) {
                JSONObject o = new JSONObject();
                o.put("name", b.name);
                o.put("mime", b.mime);
                o.put("parent", b.originalParentId);
                items.put(o);
            }
            json.put("items", items);
            try (OutputStream out = getContentResolver().openOutputStream(manifestUri, "wt")) {
                if (out == null) return;
                out.write(json.toString().getBytes("UTF-8"));
            }
        } catch (Exception ignored) { }
    }

    /** Copies the entry into the bin, verifies the copy, then deletes the original. */
    private boolean addToBin(Entry e) {
        ensureBinQuiet(tree);
        if (binId == null) return false;
        ensureManifestLoaded();
        try {
            String mime = e.mime != null ? e.mime : "application/octet-stream";
            Uri created = DocumentsContract.createDocument(getContentResolver(), binDocUri(binId), mime, e.name);
            if (created == null) return false;
            try (InputStream in = getContentResolver().openInputStream(e.uri);
                 OutputStream out = getContentResolver().openOutputStream(created)) {
                if (in == null || out == null) return false;
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
            }
            if (currentSize(created) != e.size) {
                try { DocumentsContract.deleteDocument(getContentResolver(), created); } catch (Exception ignored) { }
                return false;
            }
            if (!DocumentsContract.deleteDocument(getContentResolver(), e.uri)) {
                try { DocumentsContract.deleteDocument(getContentResolver(), created); } catch (Exception ignored) { }
                return false;
            }
            String actualName = displayNameOf(created);
            binItems.add(new BinEntry(actualName != null ? actualName : e.name, mime, e.parentId));
            saveManifest();
            return true;
        } catch (Exception ex) {
            return false;
        }
    }

    private String displayNameOf(Uri doc) {
        String[] columns = { DocumentsContract.Document.COLUMN_DISPLAY_NAME };
        try (Cursor c = getContentResolver().query(doc, columns, null, null, null)) {
            if (c != null && c.moveToFirst() && !c.isNull(0)) return c.getString(0);
        } catch (Exception ignored) { }
        return null;
    }

    private boolean restoreFromBin(BinEntry b) {
        if (tree == null || binId == null) return false;
        String sourceId = findChildId(tree, binId, b.name);
        if (sourceId == null) return false;
        Uri source = binDocUri(sourceId);
        try {
            long size = currentSize(source);
            Uri target = null;
            if (b.originalParentId != null && !b.originalParentId.isEmpty()) {
                try {
                    target = DocumentsContract.createDocument(getContentResolver(),
                            binDocUri(b.originalParentId), b.mime, b.name);
                } catch (Exception ignored) { }
            }
            if (target == null) {
                String rootId = DocumentsContract.getTreeDocumentId(tree);
                target = DocumentsContract.createDocument(getContentResolver(), binDocUri(rootId), b.mime, b.name);
            }
            if (target == null) return false;
            try (InputStream in = getContentResolver().openInputStream(source);
                 OutputStream out = getContentResolver().openOutputStream(target)) {
                if (in == null || out == null) return false;
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
            }
            if (currentSize(target) != size) {
                try { DocumentsContract.deleteDocument(getContentResolver(), target); } catch (Exception ignored) { }
                return false;
            }
            if (!DocumentsContract.deleteDocument(getContentResolver(), source)) return false;
            binItems.remove(b);
            saveManifest();
            return true;
        } catch (Exception ex) {
            return false;
        }
    }

    private boolean deleteForever(BinEntry b) {
        if (tree == null || binId == null) return false;
        String sourceId = findChildId(tree, binId, b.name);
        try {
            if (sourceId != null) DocumentsContract.deleteDocument(getContentResolver(), binDocUri(sourceId));
            binItems.remove(b);
            saveManifest();
            return true;
        } catch (Exception ex) {
            return false;
        }
    }

    private void renderBin() {
        results.removeAllViews();
        thumbAutoLoaded = 0;
        filterRow.setVisibility(View.GONE);
        statsLine.setVisibility(View.GONE);
        updateDeleteLabel();

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        Button back = new Button(this);
        back.setText("< Back to results");
        styleButton(back, CARD_SOFT, TXT);
        back.setOnClickListener(v -> toggleBinMode());
        bar.addView(back);
        Button restoreAll = new Button(this);
        restoreAll.setText("Restore all");
        styleButton(restoreAll, CARD_SOFT, EXACT_COLOR);
        restoreAll.setOnClickListener(v -> bulkBinOp(true));
        LinearLayout.LayoutParams raLp = new LinearLayout.LayoutParams(-2, -2);
        raLp.setMargins(dp(8), 0, 0, 0);
        bar.addView(restoreAll, raLp);
        Button empty = new Button(this);
        empty.setText("Empty folder");
        styleButton(empty, DANGER, 0xFFFFFFFF);
        empty.setOnClickListener(v -> confirmEmptyBin());
        LinearLayout.LayoutParams eLp = new LinearLayout.LayoutParams(-2, -2);
        eLp.setMargins(dp(8), 0, 0, 0);
        bar.addView(empty, eLp);
        addWithMargin(results, bar, 4, 8);

        setBusy(true);
        status.setText("Loading " + BIN_NAME + "...");
        worker.execute(() -> {
            try {
                ensureBinQuiet(tree);
                ensureManifestLoaded();
            } catch (Exception ignored) { }
            runOnUiThread(() -> {
                setBusy(false);
                if (!binMode) return;
                if (binItems.isEmpty()) {
                    TextView emptyNote = label("Nothing here yet. Files you delete land in this folder first, so you can restore them or remove them for good.", 13, DIM, false);
                    addWithMargin(results, emptyNote, 8, 0);
                    status.setText(BIN_NAME + " is empty.");
                    refreshBinCount();
                    return;
                }
                status.setText(binItems.size() + " items in " + BIN_NAME + ".");
                List<BinEntry> copy = new ArrayList<>(binItems);
                for (BinEntry b : copy) addWithMargin(results, binRow(b), 0, 8);
                refreshBinCount();
            });
        });
    }

    private View binRow(BinEntry b) {
        LinearLayout rowCard = card();
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        boolean isImage = b.mime != null && b.mime.startsWith("image/");
        View slot;
        if (isImage) {
            ImageView iv = new ImageView(this);
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            iv.setBackground(rounded(0xFF141A29, 12));
            iv.setOutlineProvider(new ViewOutlineProvider() {
                @Override public void getOutline(View view, Outline outline) {
                    outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), dp(12));
                }
            });
            iv.setClipToOutline(true);
            slot = iv;
            if (thumbAutoLoaded < THUMB_AUTO_LIMIT) {
                thumbAutoLoaded++;
                String key = "bin:" + b.name;
                Bitmap cached = thumbCache.get(key);
                if (cached != null) iv.setImageBitmap(cached);
                else {
                    iv.setTag(key);
                    thumbs.execute(() -> {
                        try {
                            String id = findChildId(tree, binId, b.name);
                            if (id == null) return;
                            Bitmap bmp = decodeSampled(binDocUri(id), 320);
                            if (bmp == null) return;
                            thumbCache.put(key, bmp);
                            runOnUiThread(() -> { if (key.equals(iv.getTag())) iv.setImageBitmap(bmp); });
                        } catch (Exception ignored) { }
                    });
                }
            }
        } else {
            TextView t = label("FILE", 11, DIM, true);
            t.setGravity(Gravity.CENTER);
            t.setBackground(rounded(0xFF141A29, 12));
            slot = t;
        }
        row.addView(slot, new LinearLayout.LayoutParams(dp(64), dp(64)));

        LinearLayout mid = new LinearLayout(this);
        mid.setOrientation(LinearLayout.VERTICAL);
        mid.addView(label(b.name, 13, TXT, true));
        String origin = b.originalParentId != null && b.originalParentId.contains(":")
                ? b.originalParentId.substring(b.originalParentId.indexOf(':') + 1) : "chosen folder";
        addWithMargin(mid, label("Was in: " + origin, 11, DIM, false), 2, 0);
        LinearLayout.LayoutParams midLp = new LinearLayout.LayoutParams(0, -2, 1);
        midLp.setMargins(dp(10), 0, dp(6), 0);
        row.addView(mid, midLp);
        rowCard.addView(row);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        Button restore = new Button(this);
        restore.setText("Restore");
        styleButton(restore, CARD_SOFT, EXACT_COLOR);
        restore.setOnClickListener(v -> singleBinOp(b, true));
        actions.addView(restore);
        Button del = new Button(this);
        del.setText("Delete forever");
        styleButton(del, DANGER, 0xFFFFFFFF);
        del.setOnClickListener(v -> confirmDeleteForever(b));
        LinearLayout.LayoutParams dLp = new LinearLayout.LayoutParams(-2, -2);
        dLp.setMargins(dp(8), 0, 0, 0);
        actions.addView(del, dLp);
        addWithMargin(rowCard, actions, 8, 0);
        return rowCard;
    }

    private void confirmDeleteForever(BinEntry b) {
        new AlertDialog.Builder(this)
                .setTitle("Delete forever?")
                .setMessage("\"" + b.name + "\" will be erased permanently. This cannot be undone.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Delete forever", (d, w) -> singleBinOp(b, false))
                .show();
    }

    private void confirmEmptyBin() {
        if (binItems.isEmpty()) {
            status.setText(BIN_NAME + " is already empty.");
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("Empty " + BIN_NAME + "?")
                .setMessage("All " + binItems.size() + " items will be erased permanently. This cannot be undone.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Erase all", (d, w) -> bulkBinOp(false))
                .show();
    }

    private void singleBinOp(BinEntry b, boolean restore) {
        if (busy) return;
        setBusy(true);
        status.setText((restore ? "Restoring " : "Erasing ") + b.name + "...");
        worker.execute(() -> {
            boolean ok = restore ? restoreFromBin(b) : deleteForever(b);
            runOnUiThread(() -> {
                setBusy(false);
                thumbCache.evictAll();
                status.setText(ok ? (restore ? "Restored " : "Erased ") + b.name + "."
                        : "Could not " + (restore ? "restore " : "erase ") + b.name + ".");
                if (binMode) renderBin();
                refreshBinCount();
            });
        });
    }

    /** restore=true restores everything; restore=false erases everything permanently. */
    private void bulkBinOp(boolean restore) {
        if (busy || tree == null) return;
        setBusy(true);
        status.setText(restore ? "Restoring everything..." : "Erasing everything...");
        worker.execute(() -> {
            ensureBinQuiet(tree);
            ensureManifestLoaded();
            int ok = 0, fail = 0;
            List<BinEntry> copy = new ArrayList<>(binItems);
            for (BinEntry b : copy) {
                boolean done = restore ? restoreFromBin(b) : deleteForever(b);
                if (done) ok++; else fail++;
            }
            final int doneCount = ok, failCount = fail;
            runOnUiThread(() -> {
                setBusy(false);
                thumbCache.evictAll();
                status.setText((restore ? "Restored " : "Erased ") + doneCount + " items" +
                        (failCount > 0 ? "; " + failCount + " failed" : "") + ".");
                if (binMode) renderBin();
                refreshBinCount();
            });
        });
    }

    // ---------- formatting ----------

    private String fmtBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        double kb = bytes / 1024.0;
        if (kb < 1024) return String.format(Locale.ROOT, "%.1f KB", kb);
        double mb = kb / 1024.0;
        if (mb < 1024) return String.format(Locale.ROOT, "%.1f MB", mb);
        return String.format(Locale.ROOT, "%.2f GB", mb / 1024.0);
    }

    @Override protected void onDestroy() {
        worker.shutdown();
        thumbs.shutdown();
        super.onDestroy();
    }
    }

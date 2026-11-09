package id.taz.tazplay;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.GridView;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * TazPlay — launcher game retro satu pintu.
 * Memindai folder ROM pilihan pengguna, mengelompokkan per sistem
 * (PS1/PS2/PSP/N64/NDS), lalu membuka game di emulator yang terpasang
 * lewat intent Android standar. Tidak ada core emulator di dalam app ini.
 */
public class MainActivity extends Activity {

    static final String[] SYSTEMS = {"PS1", "PS2", "PSP", "N64", "NDS", "Lainnya"};
    static final Map<String, String> EMOJI = new HashMap<>();
    static final Map<String, Integer> BADGE = new HashMap<>();
    static final Map<String, String[]> EMULATORS = new HashMap<>();
    static {
        EMOJI.put("PS1", "\uD83D\uDCBF"); EMOJI.put("PS2", "\uD83C\uDFAE");
        EMOJI.put("PSP", "\uD83D\uDD79️"); EMOJI.put("N64", "\uD83E\uDDE9");
        EMOJI.put("NDS", "✏️"); EMOJI.put("Lainnya", "❓");
        BADGE.put("PS1", 0xFF58A6FF); BADGE.put("PS2", 0xFF7EE787);
        BADGE.put("PSP", 0xFFFFB454); BADGE.put("N64", 0xFFBC8CFF);
        BADGE.put("NDS", 0xFFEF6461); BADGE.put("Lainnya", 0xFF9AA4B2);
        EMULATORS.put("PS1", new String[]{"com.github.stenzek.duckstation", "com.retroarch.aarch64", "com.retroarch"});
        EMULATORS.put("PS2", new String[]{"xyz.aethersx2.android"});
        EMULATORS.put("PSP", new String[]{"org.ppsspp.ppsspp", "com.retroarch.aarch64", "com.retroarch"});
        EMULATORS.put("N64", new String[]{"org.mupen64plusae.v3.fzurita", "com.retroarch.aarch64", "com.retroarch"});
        EMULATORS.put("NDS", new String[]{"me.magnum.melonds", "com.dsemu.drastic", "com.retroarch.aarch64", "com.retroarch"});
        EMULATORS.put("Lainnya", new String[]{});
    }

    static class Game {
        String docId, name, system;
        Game(String d, String n, String s) { docId = d; name = n; system = s; }
    }

    private static final int REQ_PICK = 42;
    private static final String PREFS = "tazplay";
    private static final String KEY_TREE = "treeUri";

    private SharedPreferences prefs;
    private Uri treeUri;
    private final List<Game> allGames = new ArrayList<>();
    private String filter = "Semua";
    private TextView statusTv;
    private GridView grid;
    private GameAdapter adapter;
    private LinearLayout chipsRow;

    private int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density + 0.5f); }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(18), dp(16), dp(12));
        root.setBackgroundColor(0xFFF4F6FB);

        TextView title = new TextView(this);
        title.setText("\uD83C\uDFAE TazPlay");
        title.setTextSize(26); title.setTextColor(0xFF232946);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        root.addView(title);

        TextView sub = new TextView(this);
        sub.setText("Launcher retro satu pintu — tap game-nya, langsung main di emulator favoritmu.");
        sub.setTextSize(13.5f); sub.setTextColor(0xFF5A6272);
        sub.setPadding(0, dp(2), 0, dp(10));
        root.addView(sub);

        LinearLayout btnRow = new LinearLayout(this);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        Button btnFolder = new Button(this);
        btnFolder.setText("\uD83D\uDCC1 Pilih Folder Game");
        btnFolder.setOnClickListener(v -> pickFolder());
        btnRow.addView(btnFolder, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        Button btnScan = new Button(this);
        btnScan.setText("↻ Pindai");
        btnScan.setOnClickListener(v -> { if (treeUri != null) scan(); else toast("Pilih folder game dulu, ya."); });
        btnRow.addView(btnScan, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        root.addView(btnRow);

        statusTv = new TextView(this);
        statusTv.setTextSize(12.5f); statusTv.setTextColor(0xFF8A93A3);
        statusTv.setPadding(0, dp(8), 0, dp(4));
        root.addView(statusTv);

        chipsRow = new LinearLayout(this);
        chipsRow.setOrientation(LinearLayout.HORIZONTAL);
        HorizontalScrollView hscroll = new HorizontalScrollView(this);
        hscroll.setHorizontalScrollBarEnabled(false);
        hscroll.addView(chipsRow);
        root.addView(hscroll);
        buildChips();

        adapter = new GameAdapter();
        grid = new GridView(this);
        grid.setNumColumns(2);
        grid.setHorizontalSpacing(dp(10)); grid.setVerticalSpacing(dp(10));
        grid.setPadding(0, dp(10), 0, dp(6));
        grid.setAdapter(adapter);
        grid.setOnItemClickListener((p, v, pos, id) -> {
            Game g = adapter.getItem(pos);
            if ("PS1".equals(g.system)) {
                Intent pi = new Intent(this, PlayerActivity.class);
                pi.putExtra("treeUri", treeUri.toString());
                pi.putExtra("docId", g.docId);
                pi.putExtra("name", g.name);
                startActivity(pi);
            } else {
                launch(g);
            }
        });
        grid.setOnItemLongClickListener((p, v, pos, id) -> { assignSystem(adapter.getItem(pos)); return true; });
        root.addView(grid, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        setContentView(root);

        String saved = prefs.getString(KEY_TREE, null);
        if (saved != null) {
            treeUri = Uri.parse(saved);
            scan();
        } else {
            statusTv.setText("Belum ada folder game. Tap \u201CPilih Folder Game\u201D, lalu arahkan ke folder berisi ROM-mu " +
                    "(paling rapi: subfolder ps1 / ps2 / psp / n64 / nds). Tekan-tahan kartu game buat mengganti sistemnya.");
        }
    }

    private void buildChips() {
        chipsRow.removeAllViews();
        List<String> labels = new ArrayList<>();
        labels.add("Semua");
        for (String s : SYSTEMS) labels.add(s);
        for (String label : labels) {
            TextView chip = new TextView(this);
            chip.setText(label);
            chip.setTextSize(13.5f);
            chip.setPadding(dp(14), dp(7), dp(14), dp(7));
            boolean active = filter.equals(label);
            chip.setTextColor(active ? Color.WHITE : 0xFF232946);
            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(dp(16));
            bg.setColor(active ? 0xFF232946 : Color.WHITE);
            if (!active) bg.setStroke(dp(1), 0xFFD5DBE7);
            chip.setBackground(bg);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMargins(0, dp(4), dp(8), dp(4));
            chip.setLayoutParams(lp);
            chip.setOnClickListener(v -> { filter = label; buildChips(); adapter.refresh(); });
            chipsRow.addView(chip);
        }
    }

    private void pickFolder() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        try { startActivityForResult(i, REQ_PICK); }
        catch (ActivityNotFoundException e) { toast("Pemilih folder tidak tersedia di perangkat ini."); }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_PICK && res == RESULT_OK && data != null && data.getData() != null) {
            treeUri = data.getData();
            try {
                getContentResolver().takePersistableUriPermission(treeUri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (SecurityException ignored) { }
            prefs.edit().putString(KEY_TREE, treeUri.toString()).apply();
            scan();
        }
    }

    /** Pindai pohon folder secara rekursif lewat DocumentsContract. */
    private void scan() {
        statusTv.setText("Memindai folder…");
        new Thread(() -> {
            List<Game> found = new ArrayList<>();
            try {
                String rootId = DocumentsContract.getTreeDocumentId(treeUri);
                walk(rootId, "", found, 0);
            } catch (Exception e) {
                runOnUiThread(() -> statusTv.setText("Gagal membaca folder (" + e.getClass().getSimpleName() +
                        "). Coba pilih ulang foldernya."));
                return;
            }
            found.sort((a, b) -> a.name.compareToIgnoreCase(b.name));
            runOnUiThread(() -> {
                allGames.clear(); allGames.addAll(found);
                adapter.refresh();
                statusTv.setText(found.isEmpty()
                        ? "Tidak ada file game yang dikenali di folder ini. Format yang didukung: .iso .chd .cso .pbp .cue .bin .img .z64 .n64 .v64 .nds"
                        : found.size() + " game ditemukan. Tap buat main, tekan-tahan buat mengganti sistem / membuka dengan pemilih.");
            });
        }).start();
    }

    private void walk(String parentDocId, String folderPath, List<Game> out, int depth) {
        if (depth > 6 || out.size() > 5000) return;
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocId);
        String[] cols = {DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE};
        try (Cursor c = getContentResolver().query(children, cols, null, null, null)) {
            if (c == null) return;
            while (c.moveToNext()) {
                String id = c.getString(0), name = c.getString(1), mime = c.getString(2);
                if (name == null || mime == null) continue;
                if (DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)) {
                    walk(id, folderPath + "/" + name.toLowerCase(Locale.ROOT), out, depth + 1);
                } else {
                    String sys = classify(name, folderPath, id);
                    if (sys != null) out.add(new Game(id, stripExt(name), sys));
                }
            }
        } catch (Exception ignored) { }
    }

    private String classify(String fileName, String folderPath, String docId) {
        String ovr = prefs.getString("ovr_" + docId, null);
        if (ovr != null) return ovr;
        String f = folderPath.toLowerCase(Locale.ROOT);
        if (hasTok(f, "ps2") || f.contains("playstation 2") || f.contains("playstation2")) return "PS2";
        if (hasTok(f, "ps1") || hasTok(f, "psx") || f.contains("playstation 1")) return "PS1";
        if (hasTok(f, "psp")) return "PSP";
        if (hasTok(f, "n64") || f.contains("nintendo 64")) return "N64";
        if (hasTok(f, "nds") || f.contains("nintendo ds")) return "NDS";
        String n = fileName.toLowerCase(Locale.ROOT);
        if (n.endsWith(".z64") || n.endsWith(".n64") || n.endsWith(".v64")) return "N64";
        if (n.endsWith(".nds")) return "NDS";
        if (n.endsWith(".cso") || n.endsWith(".pbp")) return "PSP";
        if (n.endsWith(".cue") || n.endsWith(".bin") || n.endsWith(".img")) return "PS1";
        if (n.endsWith(".iso") || n.endsWith(".chd")) return "Lainnya";
        return null;
    }

    private boolean hasTok(String path, String tok) {
        for (String part : path.split("/")) if (part.equals(tok)) return true;
        return false;
    }

    private String stripExt(String name) {
        int i = name.lastIndexOf('.');
        return i > 0 ? name.substring(0, i) : name;
    }

    private void launch(Game g) {
        Uri docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, g.docId);
        String[] pkgs = EMULATORS.getOrDefault(g.system, new String[]{});
        for (String pkg : pkgs) {
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setData(docUri);
            i.setPackage(pkg);
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            try { grantUriPermission(pkg, docUri, Intent.FLAG_GRANT_READ_URI_PERMISSION); } catch (Exception ignored) { }
            try { startActivity(i); return; }
            catch (ActivityNotFoundException ignored) { }
        }
        // fallback: pemilih bawaan Android
        Intent any = new Intent(Intent.ACTION_VIEW);
        any.setData(docUri);
        any.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try { startActivity(Intent.createChooser(any, "Buka dengan emulator")); return; }
        catch (ActivityNotFoundException ignored) { }
        toast("Emulator untuk " + g.system + " belum terpasang.");
        Intent store = new Intent(Intent.ACTION_VIEW,
                Uri.parse("https://play.google.com/store/search?q=emulator%20" + g.system + "&c=apps"));
        try { startActivity(store); } catch (ActivityNotFoundException ignored) { }
    }

    private void assignSystem(Game g) {
        new AlertDialog.Builder(this)
                .setTitle(g.name + "\nSistem saat ini: " + g.system)
                .setItems(new CharSequence[]{"Buka di emulator eksternal…", "PS1", "PS2", "PSP", "N64", "NDS", "Lainnya", "Buka dengan pemilih…"},
                        (d, which) -> {
                            if (which == 0) {
                                launch(g);
                            } else if (which <= SYSTEMS.length) {
                                prefs.edit().putString("ovr_" + g.docId, SYSTEMS[which - 1]).apply();
                                g.system = SYSTEMS[which - 1];
                                adapter.refresh();
                                toast("Dipindah ke " + g.system + ".");
                            } else {
                                Uri docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, g.docId);
                                Intent any = new Intent(Intent.ACTION_VIEW);
                                any.setData(docUri);
                                any.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                                try { startActivity(Intent.createChooser(any, "Buka dengan…")); }
                                catch (ActivityNotFoundException e) { toast("Tidak ada app yang bisa membuka file ini."); }
                            }
                        })
                .show();
    }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_LONG).show(); }

    class GameAdapter extends BaseAdapter {
        private final List<Game> shown = new ArrayList<>();
        void refresh() {
            shown.clear();
            for (Game g : allGames) if (filter.equals("Semua") || filter.equals(g.system)) shown.add(g);
            notifyDataSetChanged();
        }
        @Override public int getCount() { return shown.size(); }
        @Override public Game getItem(int i) { return shown.get(i); }
        @Override public long getItemId(int i) { return i; }
        @Override public View getView(int pos, View convert, ViewGroup parent) {
            Game g = shown.get(pos);
            LinearLayout card = new LinearLayout(MainActivity.this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setGravity(Gravity.CENTER_HORIZONTAL);
            card.setPadding(dp(10), dp(14), dp(10), dp(12));
            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(dp(14)); bg.setColor(Color.WHITE);
            bg.setStroke(dp(1), 0xFFE1E6EF);
            card.setBackground(bg);

            TextView emoji = new TextView(MainActivity.this);
            emoji.setText(EMOJI.getOrDefault(g.system, "❓"));
            emoji.setTextSize(30); emoji.setGravity(Gravity.CENTER);
            card.addView(emoji);

            TextView name = new TextView(MainActivity.this);
            name.setText(g.name);
            name.setTextSize(13.5f); name.setTextColor(0xFF232946);
            name.setGravity(Gravity.CENTER);
            name.setMaxLines(2);
            name.setEllipsize(android.text.TextUtils.TruncateAt.END);
            name.setPadding(0, dp(6), 0, dp(6));
            card.addView(name);

            TextView badge = new TextView(MainActivity.this);
            badge.setText(g.system);
            badge.setTextSize(11); badge.setTextColor(Color.WHITE);
            badge.setPadding(dp(10), dp(3), dp(10), dp(3));
            GradientDrawable bb = new GradientDrawable();
            bb.setCornerRadius(dp(10));
            bb.setColor(BADGE.getOrDefault(g.system, 0xFF9AA4B2));
            badge.setBackground(bb);
            card.addView(badge);
            return card;
        }
    }
}

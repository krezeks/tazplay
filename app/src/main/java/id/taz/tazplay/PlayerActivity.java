package id.taz.tazplay;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.provider.DocumentsContract;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * PlayerActivity — memainkan game PS1 langsung di dalam TazPlay
 * memakai core PCSX-ReARMed (libretro) lewat NativeCore.
 * ROM disalin dulu dari folder SAF ke penyimpanan internal app
 * (termasuk file pendamping satu nama, mis. pasangan .cue/.bin).
 */
public class PlayerActivity extends Activity implements SurfaceHolder.Callback {

    // bit libretro joypad
    static final int B_B = 0, B_Y = 1, B_SELECT = 2, B_START = 3,
            B_UP = 4, B_DOWN = 5, B_LEFT = 6, B_RIGHT = 7,
            B_A = 8, B_X = 9, B_L = 10, B_R = 11;

    private Uri treeUri;
    private String docId, gameName;
    private TextView statusTv;
    private int mask = 0;
    private boolean coreReady = false;
    private boolean started = false;

    /* setelan (disimpan di SharedPreferences "tazplay") */
    private SharedPreferences prefs;
    private int aspectMode = 0;          /* 0 Asli, 1 Stretch, 2 Zoom */
    private int btnSizeDp = 56;
    private boolean haptic = true;
    private boolean showFps = false;
    private boolean smooth = false;
    private boolean orientSensor = false;
    private boolean audioOn = true;
    private int audioMode = 0;           /* 0 Latensi rendah, 1 Stabil */
    private int padType = 1;             /* 1 Digital (JOYPAD), 5 Analog (ANALOG/DualShock) */
    private int deadzoneIdx = 1;         /* 0 Rendah, 1 Sedang, 2 Tinggi */
    private int padBits = 0;             /* bit dari gamepad fisik (hat) */
    private TextView gearBtn;
    private FrameLayout controlsLayer;
    private LinearLayout settingsPanel;
    private android.widget.ScrollView settingsScroll;
    private String romBase = "game";
    private int stateSlot = 1;
    private TextView fpsTv;
    private final Handler fpsHandler = new Handler();
    private int lastFrameCount = 0;
    private final Runnable fpsTick = new Runnable() {
        @Override public void run() {
            if (showFps && coreReady) {
                int now = NativeCore.getFrameCount();
                fpsTv.setText(((now - lastFrameCount) * 2) + " FPS");
                lastFrameCount = now;
            }
            fpsHandler.postDelayed(this, 500);
        }
    };

    private int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density + 0.5f); }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);

        Intent it = getIntent();
        treeUri = Uri.parse(it.getStringExtra("treeUri"));
        docId = it.getStringExtra("docId");
        gameName = it.getStringExtra("name");

        prefs = getSharedPreferences("tazplay", MODE_PRIVATE);
        aspectMode = prefs.getInt("set_aspect", 0);
        btnSizeDp = prefs.getInt("set_btnsize", 56);
        haptic = prefs.getBoolean("set_haptic", true);
        showFps = prefs.getBoolean("set_fps", false);
        smooth = prefs.getBoolean("set_smooth", false);
        orientSensor = prefs.getBoolean("set_orient", false);
        audioOn = prefs.getBoolean("set_audio_on", true);
        audioMode = prefs.getInt("set_audio_mode", 0);
        padType = prefs.getInt("set_padtype", 1);
        deadzoneIdx = prefs.getInt("set_deadzone", 1);
        NativeCore.setAspectMode(aspectMode);
        NativeCore.setSmooth(smooth);
        NativeCore.setAudioEnabled(audioOn);
        NativeCore.setAudioMode(audioMode);
        NativeCore.setPadType(padType);
        setRequestedOrientation(orientSensor
                ? android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                : android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        SurfaceView surface = new SurfaceView(this);
        surface.getHolder().addCallback(this);
        root.addView(surface, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        TextView label = new TextView(this);
        label.setText("▶ " + gameName + " · PS1 (core bawaan)");
        label.setTextColor(0xCCFFFFFF);
        label.setTextSize(12.5f);
        label.setPadding(dp(10), dp(5), dp(10), dp(5));
        GradientDrawable lb = new GradientDrawable();
        lb.setCornerRadius(dp(10)); lb.setColor(0x55000000);
        label.setBackground(lb);
        FrameLayout.LayoutParams llp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        llp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        llp.topMargin = dp(8);
        root.addView(label, llp);

        statusTv = new TextView(this);
        statusTv.setText("Menyiapkan game…");
        statusTv.setTextColor(Color.WHITE);
        statusTv.setTextSize(15);
        statusTv.setGravity(Gravity.CENTER);
        root.addView(statusTv, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        controlsLayer = new FrameLayout(this);
        root.addView(controlsLayer, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        buildControls(controlsLayer);

        fpsTv = new TextView(this);
        fpsTv.setTextColor(0xFF7EE787);
        fpsTv.setTextSize(13);
        fpsTv.setPadding(dp(10), dp(4), dp(10), dp(4));
        GradientDrawable fb = new GradientDrawable();
        fb.setCornerRadius(dp(8)); fb.setColor(0x55000000);
        fpsTv.setBackground(fb);
        fpsTv.setVisibility(showFps ? View.VISIBLE : View.GONE);
        FrameLayout.LayoutParams flp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        flp.gravity = Gravity.TOP | Gravity.LEFT;
        flp.leftMargin = dp(10); flp.topMargin = dp(8);
        root.addView(fpsTv, flp);

        gearBtn = new TextView(this);
        gearBtn.setText("⚙ Setelan");
        gearBtn.setTextColor(0xEEFFFFFF);
        gearBtn.setTextSize(14);
        gearBtn.setPadding(dp(16), dp(11), dp(16), dp(11));
        GradientDrawable gb = new GradientDrawable();
        gb.setCornerRadius(dp(14)); gb.setColor(0x88000000);
        gearBtn.setBackground(gb);
        FrameLayout.LayoutParams glp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        glp.gravity = Gravity.TOP | Gravity.RIGHT;
        glp.rightMargin = dp(10); glp.topMargin = dp(8);
        gearBtn.setOnClickListener(v -> toggleSettings());
        root.addView(gearBtn, glp);

        buildSettingsPanel(root);
        gearBtn.bringToFront();
        setContentView(root);
        fpsHandler.postDelayed(fpsTick, 500);

        new Thread(this::prepareAndLoad).start();
    }

    /* ---------- kontrol sentuh ---------- */
    private TextView padButton(String text, final int bit, int sizeDp) {
        TextView b = new TextView(this);
        b.setText(text);
        b.setTextColor(0xEEFFFFFF);
        b.setTextSize(17);
        b.setGravity(Gravity.CENTER);
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(0x3DFFFFFF);
        bg.setStroke(dp(1), 0x66FFFFFF);
        b.setBackground(bg);
        GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
        lp.width = dp(btnSizeDp); lp.height = dp(btnSizeDp);
        lp.setMargins(dp(3), dp(3), dp(3), dp(3));
        b.setLayoutParams(lp);
        b.setOnTouchListener((v, e) -> {
            if (e.getAction() == MotionEvent.ACTION_DOWN) {
                mask |= (1 << bit); pushButtons(); v.setAlpha(0.55f);
                if (haptic) v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
            }
            else if (e.getAction() == MotionEvent.ACTION_UP || e.getAction() == MotionEvent.ACTION_CANCEL) {
                mask &= ~(1 << bit); pushButtons(); v.setAlpha(1f);
            }
            return true;
        });
        return b;
    }

    private void pushButtons() { NativeCore.setButtons(mask | padBits); }

    private void spacer(GridLayout g) {
        View v = new View(this);
        GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
        lp.width = dp(btnSizeDp); lp.height = dp(btnSizeDp);
        v.setLayoutParams(lp);
        g.addView(v);
    }

    private void buildControls(FrameLayout root) {
        // D-pad kiri bawah
        GridLayout dpad = new GridLayout(this);
        dpad.setColumnCount(3); dpad.setRowCount(3);
        spacer(dpad); dpad.addView(padButton("▲", B_UP, 56)); spacer(dpad);
        dpad.addView(padButton("◀", B_LEFT, 56)); spacer(dpad); dpad.addView(padButton("▶", B_RIGHT, 56));
        spacer(dpad); dpad.addView(padButton("▼", B_DOWN, 56)); spacer(dpad);
        FrameLayout.LayoutParams dlp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        dlp.gravity = Gravity.BOTTOM | Gravity.LEFT;
        dlp.leftMargin = dp(14); dlp.bottomMargin = dp(14);
        root.addView(dpad, dlp);

        // tombol aksi kanan bawah (△ ○ ✕ □)
        GridLayout act = new GridLayout(this);
        act.setColumnCount(3); act.setRowCount(3);
        spacer(act); act.addView(padButton("△", B_X, 56)); spacer(act);
        act.addView(padButton("□", B_Y, 56)); spacer(act); act.addView(padButton("○", B_A, 56));
        spacer(act); act.addView(padButton("✕", B_B, 56)); spacer(act);
        FrameLayout.LayoutParams alp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        alp.gravity = Gravity.BOTTOM | Gravity.RIGHT;
        alp.rightMargin = dp(14); alp.bottomMargin = dp(14);
        root.addView(act, alp);

        // Select / Start tengah bawah
        GridLayout mid = new GridLayout(this);
        mid.setColumnCount(2);
        mid.addView(padButton("SELECT", B_SELECT, 56));
        mid.addView(padButton("START", B_START, 56));
        FrameLayout.LayoutParams mlp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        mlp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        mlp.bottomMargin = dp(10);
        root.addView(mid, mlp);

        // L / R di bahu atas
        GridLayout tl = new GridLayout(this); tl.setColumnCount(1);
        tl.addView(padButton("L1", B_L, 56));
        FrameLayout.LayoutParams tllp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tllp.gravity = Gravity.TOP | Gravity.LEFT; tllp.leftMargin = dp(14); tllp.topMargin = dp(54);
        root.addView(tl, tllp);
        GridLayout tr = new GridLayout(this); tr.setColumnCount(1);
        tr.addView(padButton("R1", B_R, 56));
        FrameLayout.LayoutParams trlp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        trlp.gravity = Gravity.TOP | Gravity.RIGHT; trlp.rightMargin = dp(14); trlp.topMargin = dp(54);
        root.addView(tr, trlp);
    }

    /* ---------- panel setelan ---------- */
    private TextView chip(String text, boolean active, View.OnClickListener onClick) {
        TextView c = new TextView(this);
        c.setText(text);
        c.setTextSize(13.5f);
        c.setPadding(dp(14), dp(7), dp(14), dp(7));
        c.setTextColor(active ? Color.WHITE : 0xFFDDE3EE);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(14));
        bg.setColor(active ? 0xFFFF8C42 : 0x33FFFFFF);
        c.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, dp(8), 0);
        c.setLayoutParams(lp);
        c.setOnClickListener(onClick);
        return c;
    }

    private void rebuildSettingsPanel() {
        settingsPanel.removeAllViews();
        TextView title = new TextView(this);
        title.setText("⚙ Setelan");
        title.setTextColor(Color.WHITE); title.setTextSize(17);
        title.setPadding(0, 0, 0, dp(10));
        settingsPanel.addView(title);

        addSection("📺 Video");
        addRow("Rasio layar", new String[]{"Asli", "Stretch", "Zoom"}, aspectMode, idx -> {
            aspectMode = idx;
            prefs.edit().putInt("set_aspect", idx).apply();
            NativeCore.setAspectMode(idx);
            rebuildSettingsPanel();
        });
        addRow("Filter gambar", new String[]{"Tajam", "Halus"}, smooth ? 1 : 0, idx -> {
            smooth = idx == 1;
            prefs.edit().putBoolean("set_smooth", smooth).apply();
            NativeCore.setSmooth(smooth);
            rebuildSettingsPanel();
        });
        addRow("Orientasi layar", new String[]{"Kunci Landscape", "Ikuti Sensor"}, orientSensor ? 1 : 0, idx -> {
            orientSensor = idx == 1;
            prefs.edit().putBoolean("set_orient", orientSensor).apply();
            setRequestedOrientation(orientSensor
                    ? android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                    : android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
            rebuildSettingsPanel();
        });
        addRow("Tampil FPS", new String[]{"Sembunyi", "Tampil"}, showFps ? 1 : 0, idx -> {
            showFps = idx == 1;
            prefs.edit().putBoolean("set_fps", showFps).apply();
            fpsTv.setVisibility(showFps ? View.VISIBLE : View.GONE);
            rebuildSettingsPanel();
        });

        addSection("🔊 Audio");
        addRow("Suara", new String[]{"Mati", "Nyala"}, audioOn ? 1 : 0, idx -> {
            audioOn = idx == 1;
            prefs.edit().putBoolean("set_audio_on", audioOn).apply();
            NativeCore.setAudioEnabled(audioOn);
            rebuildSettingsPanel();
        });
        addRow("Mode audio", new String[]{"Latensi Rendah", "Stabil"}, audioMode, idx -> {
            audioMode = idx;
            prefs.edit().putInt("set_audio_mode", idx).apply();
            NativeCore.setAudioMode(idx);
            rebuildSettingsPanel();
        });

        addSection("🎮 Gamepad");
        addRow("Jenis stik", new String[]{"Digital", "Analog (DualShock)"}, padType == 5 ? 1 : 0, idx -> {
            padType = idx == 1 ? 5 : 1;
            prefs.edit().putInt("set_padtype", padType).apply();
            NativeCore.setPadType(padType);
            rebuildSettingsPanel();
        });
        addRow("Deadzone stik analog", new String[]{"Rendah", "Sedang", "Tinggi"}, deadzoneIdx, idx -> {
            deadzoneIdx = idx;
            prefs.edit().putInt("set_deadzone", idx).apply();
            rebuildSettingsPanel();
        });

        addSection("👆 Kontrol Sentuh");
        addRow("Ukuran tombol", new String[]{"Kecil", "Sedang", "Besar"},
                btnSizeDp == 48 ? 0 : btnSizeDp == 68 ? 2 : 1, idx -> {
            btnSizeDp = idx == 0 ? 48 : idx == 2 ? 68 : 56;
            prefs.edit().putInt("set_btnsize", btnSizeDp).apply();
            controlsLayer.removeAllViews();
            buildControls(controlsLayer);
            rebuildSettingsPanel();
        });
        addRow("Getar tombol", new String[]{"Mati", "Nyala"}, haptic ? 1 : 0, idx -> {
            haptic = idx == 1;
            prefs.edit().putBoolean("set_haptic", haptic).apply();
            rebuildSettingsPanel();
        });

        /* --- Save State --- */
        addSection("💾 Save State");
        addRow("Slot", new String[]{"Slot 1", "Slot 2", "Slot 3"}, stateSlot - 1, idx -> {
            stateSlot = idx + 1;
            rebuildSettingsPanel();
        });
        LinearLayout stateRow = new LinearLayout(this);
        stateRow.setOrientation(LinearLayout.HORIZONTAL);
        stateRow.addView(actionButton("💾 Simpan State", v -> {
            File f = stateFile();
            boolean ok = NativeCore.saveState(f.getAbsolutePath());
            Toast.makeText(this, ok ? "State tersimpan di Slot " + stateSlot + " 💾"
                    : "Gagal menyimpan state.", Toast.LENGTH_SHORT).show();
            if (ok) toggleSettings();
        }));
        stateRow.addView(actionButton("📂 Muat State", v -> {
            File f = stateFile();
            if (!f.exists()) {
                Toast.makeText(this, "Slot " + stateSlot + " masih kosong.", Toast.LENGTH_SHORT).show();
                return;
            }
            boolean ok = NativeCore.loadState(f.getAbsolutePath());
            Toast.makeText(this, ok ? "State Slot " + stateSlot + " dimuat 📂"
                    : "Gagal memuat state.", Toast.LENGTH_SHORT).show();
            if (ok) toggleSettings();
        }));
        settingsPanel.addView(stateRow);

        /* --- BIOS --- */
        addSection("🧬 BIOS PS1");
        String biosName = prefs.getString("bios_name", null);
        TextView biosStatus = new TextView(this);
        biosStatus.setText(biosName == null
                ? "Mode: HLE (bawaan core, tanpa file BIOS)"
                : "Mode: BIOS asli — " + biosName + "\n(berlaku saat game dibuka lagi)");
        biosStatus.setTextColor(0xFFDDE3EE); biosStatus.setTextSize(12.5f);
        biosStatus.setPadding(0, 0, 0, dp(6));
        settingsPanel.addView(biosStatus);
        LinearLayout biosRow = new LinearLayout(this);
        biosRow.setOrientation(LinearLayout.HORIZONTAL);
        biosRow.addView(actionButton("📁 Pilih file BIOS…", v -> {
            Intent pick = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            pick.addCategory(Intent.CATEGORY_OPENABLE);
            pick.setType("*/*");
            startActivityForResult(pick, 4211);
        }));
        if (biosName != null) {
            biosRow.addView(actionButton("Pakai HLE lagi", v -> {
                File sysDir = new File(getFilesDir(), "system");
                File bf = new File(sysDir, prefs.getString("bios_name", ""));
                if (bf.exists()) bf.delete();
                prefs.edit().remove("bios_name").apply();
                Toast.makeText(this, "BIOS dihapus — balik ke HLE saat game dibuka lagi.", Toast.LENGTH_SHORT).show();
                rebuildSettingsPanel();
            }));
        }
        settingsPanel.addView(biosRow);

        TextView close = new TextView(this);
        close.setText("Tutup");
        close.setTextColor(0xFF232946); close.setTextSize(14.5f);
        close.setGravity(Gravity.CENTER);
        close.setPadding(0, dp(10), 0, dp(10));
        GradientDrawable cb = new GradientDrawable();
        cb.setCornerRadius(dp(12)); cb.setColor(0xFFFFB454);
        close.setBackground(cb);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.topMargin = dp(12);
        close.setLayoutParams(clp);
        close.setOnClickListener(v -> toggleSettings());
        settingsPanel.addView(close);
    }

    private interface RowPick { void pick(int idx); }

    private void addSection(String title) {
        TextView s = new TextView(this);
        s.setText(title);
        s.setTextColor(0xFFFFB454); s.setTextSize(14.5f);
        s.setPadding(0, dp(14), 0, dp(2));
        settingsPanel.addView(s);
    }

    private void addRow(String label, String[] options, int current, RowPick onPick) {
        TextView l = new TextView(this);
        l.setText(label);
        l.setTextColor(0xFFB9C2D0); l.setTextSize(12.5f);
        l.setPadding(0, dp(8), 0, dp(5));
        settingsPanel.addView(l);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        for (int i = 0; i < options.length; i++) {
            final int idx = i;
            row.addView(chip(options[i], i == current, v -> onPick.pick(idx)));
        }
        settingsPanel.addView(row);
    }

    private void buildSettingsPanel(FrameLayout root) {
        settingsPanel = new LinearLayout(this);
        settingsPanel.setOrientation(LinearLayout.VERTICAL);
        settingsPanel.setPadding(dp(20), dp(16), dp(20), dp(16));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(18)); bg.setColor(0xE6232946);
        settingsPanel.setBackground(bg);
        /* panel TIDAK di-GONE: visibilitas diatur settingsScroll saja */
        settingsScroll = new android.widget.ScrollView(this);
        settingsScroll.setVisibility(View.GONE);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                dp(430), ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.CENTER;
        settingsScroll.setLayoutParams(lp);
        settingsScroll.addView(settingsPanel);
        root.addView(settingsScroll);
        rebuildSettingsPanel();
    }

    private void toggleSettings() {
        boolean open = settingsScroll.getVisibility() != View.VISIBLE;
        settingsScroll.setVisibility(open ? View.VISIBLE : View.GONE);
        if (open) { if (coreReady) NativeCore.stop(); }
        else if (coreReady && started) NativeCore.start();
    }

    private TextView actionButton(String text, View.OnClickListener onClick) {
        TextView b = new TextView(this);
        b.setText(text);
        b.setTextSize(13.5f);
        b.setTextColor(0xFF232946);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(10), dp(9), dp(10), dp(9));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(12)); bg.setColor(0xFFFFB454);
        b.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.setMargins(0, 0, dp(8), 0);
        b.setLayoutParams(lp);
        b.setOnClickListener(onClick);
        return b;
    }

    private File stateFile() {
        File dir = new File(getFilesDir(), "states");
        dir.mkdirs();
        return new File(dir, romBase + ".slot" + stateSlot + ".state");
    }

    /* ---------- pemilih BIOS ---------- */
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != 4211 || resultCode != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        String name = "bios.bin";
        try (Cursor c = getContentResolver().query(uri,
                new String[]{android.provider.OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst() && c.getString(0) != null) name = c.getString(0);
        } catch (Exception ignored) { }
        try {
            File sysDir = new File(getFilesDir(), "system");
            sysDir.mkdirs();
            File out = new File(sysDir, name);
            try (InputStream in = getContentResolver().openInputStream(uri);
                 OutputStream os = new FileOutputStream(out)) {
                byte[] buf = new byte[1 << 16];
                int n;
                while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
            }
            prefs.edit().putString("bios_name", name).apply();
            Toast.makeText(this, "BIOS \"" + name + "\" terpasang ✓ — berlaku saat game dibuka lagi.",
                    Toast.LENGTH_LONG).show();
            rebuildSettingsPanel();
        } catch (Exception e) {
            Toast.makeText(this, "Gagal menyalin BIOS: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    /* ---------- salin ROM dari SAF ---------- */
    private String displayNameOf(String documentId) {
        Uri u = DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId);
        try (Cursor c = getContentResolver().query(u,
                new String[]{DocumentsContract.Document.COLUMN_DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) return c.getString(0);
        } catch (Exception ignored) { }
        return documentId;
    }

    private File copyDoc(String documentId, File dir) throws Exception {
        String name = displayNameOf(documentId);
        File out = new File(dir, name);
        Uri u = DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId);
        try (InputStream in = getContentResolver().openInputStream(u);
             OutputStream os = new FileOutputStream(out)) {
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
        }
        return out;
    }

    private void prepareAndLoad() {
        try {
            String targetName = displayNameOf(docId);
            String base = targetName;
            int dot = base.lastIndexOf('.');
            if (dot > 0) base = base.substring(0, dot);
            romBase = base.replaceAll("[^a-zA-Z0-9 _-]", "_");

            File dir = new File(getFilesDir(), "roms/" + base.replaceAll("[^a-zA-Z0-9 _-]", "_"));
            dir.mkdirs();
            File target = new File(dir, targetName);
            if (!target.exists() || target.length() == 0) copyDoc(docId, dir);

            // salin file pendamping satu nama (mis. .bin untuk .cue)
            String parent = docId.contains("/")
                    ? docId.substring(0, docId.lastIndexOf('/'))
                    : DocumentsContract.getTreeDocumentId(treeUri);
            Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parent);
            try (Cursor c = getContentResolver().query(children,
                    new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                            DocumentsContract.Document.COLUMN_DISPLAY_NAME}, null, null, null)) {
                if (c != null) while (c.moveToNext()) {
                    String sibId = c.getString(0), sibName = c.getString(1);
                    if (sibName == null || sibId.equals(docId)) continue;
                    String sibBase = sibName;
                    int d2 = sibBase.lastIndexOf('.');
                    if (d2 > 0) sibBase = sibBase.substring(0, d2);
                    if (sibBase.equals(base)) {
                        File f = new File(dir, sibName);
                        if (!f.exists() || f.length() == 0) copyDoc(sibId, dir);
                    }
                }
            }

            File sysDir = new File(getFilesDir(), "system"); sysDir.mkdirs();
            File saveDir = new File(getFilesDir(), "saves"); saveDir.mkdirs();
            String corePath = getApplicationInfo().nativeLibraryDir + "/libpcsx_rearmed_libretro.so";

            runOnUiThread(() -> statusTv.setText("Memuat core PS1…"));
            if (!NativeCore.init(corePath, sysDir.getAbsolutePath(), saveDir.getAbsolutePath())) {
                runOnUiThread(() -> statusTv.setText("Core gagal dimuat. Cek logcat (TazCore)."));
                return;
            }
            if (!NativeCore.loadGame(target.getAbsolutePath())) {
                runOnUiThread(() -> statusTv.setText("Game gagal dimuat core. Format yang didukung: .iso .bin .cue .pbp .chd"));
                return;
            }
            coreReady = true;
            runOnUiThread(() -> {
                statusTv.setVisibility(View.GONE);
                if (started) NativeCore.start();
            });
        } catch (Exception e) {
            runOnUiThread(() -> statusTv.setText("Gagal menyiapkan game: " + e.getMessage()));
        }
    }

    /* ---------- surface & lifecycle ---------- */
    @Override public void surfaceCreated(SurfaceHolder h) { NativeCore.setSurface(h.getSurface()); }
    @Override public void surfaceChanged(SurfaceHolder h, int f, int w, int hh) { NativeCore.setSurface(h.getSurface()); }
    @Override public void surfaceDestroyed(SurfaceHolder h) { NativeCore.setSurface(null); }

    @Override protected void onResume() {
        super.onResume();
        started = true;
        if (coreReady && settingsScroll.getVisibility() != View.VISIBLE) NativeCore.start();
    }

    @Override protected void onPause() {
        started = false;
        if (coreReady) NativeCore.stop();
        super.onPause();
    }

    @Override protected void onDestroy() {
        if (coreReady) NativeCore.destroy();
        super.onDestroy();
    }

    /* ---------- keyboard / gamepad ---------- */
    private int keyBit(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_UP: case KeyEvent.KEYCODE_W: return B_UP;
            case KeyEvent.KEYCODE_DPAD_DOWN: case KeyEvent.KEYCODE_S: return B_DOWN;
            case KeyEvent.KEYCODE_DPAD_LEFT: case KeyEvent.KEYCODE_A: return B_LEFT;
            case KeyEvent.KEYCODE_DPAD_RIGHT: case KeyEvent.KEYCODE_D: return B_RIGHT;
            case KeyEvent.KEYCODE_Z: case KeyEvent.KEYCODE_BUTTON_B: return B_B;
            case KeyEvent.KEYCODE_X: case KeyEvent.KEYCODE_BUTTON_A: return B_A;
            case KeyEvent.KEYCODE_BUTTON_X: return B_Y;
            case KeyEvent.KEYCODE_BUTTON_Y: return B_X;
            case KeyEvent.KEYCODE_BUTTON_L1: case KeyEvent.KEYCODE_Q: return B_L;
            case KeyEvent.KEYCODE_BUTTON_R1: case KeyEvent.KEYCODE_E: return B_R;
            case KeyEvent.KEYCODE_BUTTON_START: case KeyEvent.KEYCODE_ENTER: return B_START;
            case KeyEvent.KEYCODE_BUTTON_SELECT: case KeyEvent.KEYCODE_SHIFT_LEFT: return B_SELECT;
            default: return -1;
        }
    }

    @Override public boolean onKeyDown(int keyCode, KeyEvent e) {
        int bit = keyBit(keyCode);
        if (bit >= 0) { mask |= (1 << bit); pushButtons(); return true; }
        return super.onKeyDown(keyCode, e);
    }

    @Override public boolean onKeyUp(int keyCode, KeyEvent e) {
        int bit = keyBit(keyCode);
        if (bit >= 0) { mask &= ~(1 << bit); pushButtons(); return true; }
        return super.onKeyUp(keyCode, e);
    }

    /* ---------- gamepad fisik: stik analog + hat (D-pad) ---------- */
    private float dz(float v) {
        float t = deadzoneIdx == 0 ? 0.12f : deadzoneIdx == 2 ? 0.32f : 0.22f;
        if (Math.abs(v) < t) return 0f;
        return Math.signum(v) * (Math.abs(v) - t) / (1f - t);
    }

    @Override
    public boolean onGenericMotionEvent(MotionEvent e) {
        if ((e.getSource() & android.view.InputDevice.SOURCE_JOYSTICK)
                == android.view.InputDevice.SOURCE_JOYSTICK
                && e.getAction() == MotionEvent.ACTION_MOVE) {
            NativeCore.setAxes(
                    (int) (dz(e.getAxisValue(MotionEvent.AXIS_X)) * 32767),
                    (int) (dz(e.getAxisValue(MotionEvent.AXIS_Y)) * 32767),
                    (int) (dz(e.getAxisValue(MotionEvent.AXIS_Z)) * 32767),
                    (int) (dz(e.getAxisValue(MotionEvent.AXIS_RZ)) * 32767));
            float hx = e.getAxisValue(MotionEvent.AXIS_HAT_X);
            float hy = e.getAxisValue(MotionEvent.AXIS_HAT_Y);
            int nb = 0;
            if (hx < -0.5f) nb |= (1 << B_LEFT);
            if (hx > 0.5f) nb |= (1 << B_RIGHT);
            if (hy < -0.5f) nb |= (1 << B_UP);
            if (hy > 0.5f) nb |= (1 << B_DOWN);
            if (nb != padBits) { padBits = nb; pushButtons(); }
            return true;
        }
        return super.onGenericMotionEvent(e);
    }

    /* Back = buka/tutup setelan (bukan langsung keluar) */
    @Override
    public void onBackPressed() {
        toggleSettings();
    }
}

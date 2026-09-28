package com.efremandrei.amadeus;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.net.Uri;
import android.os.Bundle;
import android.os.Build;
import android.os.SystemClock;
import android.text.InputType;
import android.text.method.LinkMovementMethod;
import android.text.util.Linkify;
import android.view.WindowInsets;
import android.view.MotionEvent;
import android.view.View;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;

public class MainActivity extends Activity {
    private static final int MIC_REQUEST = 42;
    private static final int EXPORT_REQUEST = 90;
    private static final int IMPORT_PAD_REQUEST = 91;
    private LoopStationView loopView;
    private int pendingExportFormat = ExportManager.FORMAT_WAV;
    private ExportManager.Settings pendingExportSettings = ExportManager.Settings.defaults(ExportManager.FORMAT_WAV);
    private int pendingImportPad = -1;

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(Color.rgb(16, 16, 22)); getWindow().setNavigationBarColor(Color.rgb(16, 16, 22));
        loopView = new LoopStationView(this);
        loopView.setOnApplyWindowInsetsListener((view, insets) -> {
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
                loopView.setSystemBarInsets(bars.top, bars.bottom);
            } else {
                loopView.setSystemBarInsets(insets.getSystemWindowInsetTop(), insets.getSystemWindowInsetBottom());
            }
            return insets;
        });
        setContentView(loopView); loopView.requestApplyInsets();
    }

    void ensureMicPermission() {
        if (android.os.Build.VERSION.SDK_INT >= 23 && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, MIC_REQUEST);
        else loopView.toggleRecording();
    }

    void showPadChooser(final int pad) {
        String[] options = new String[SoundPadEngine.LIBRARY.length + 1];
        System.arraycopy(SoundPadEngine.LIBRARY, 0, options, 0, SoundPadEngine.LIBRARY.length);
        options[options.length - 1] = "Import audio file…";
        new AlertDialog.Builder(this).setTitle("Assign pad " + (pad + 1)).setItems(options, (dialog, which) -> { if (which == SoundPadEngine.LIBRARY.length) beginImportPad(pad); else loopView.assignPad(pad, SoundPadEngine.LIBRARY[which]); }).show();
    }

    private void beginImportPad(int pad) {
        pendingImportPad = pad;
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT); intent.addCategory(Intent.CATEGORY_OPENABLE); intent.setType("audio/*"); intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION); startActivityForResult(intent, IMPORT_PAD_REQUEST);
    }

    void showAbout() {
        TextView message = new TextView(this);
        message.setText("Loop-first music creation\n\nVersion 0.1.18 (build 19)\n\nCreated by Andrei Efremuahkin\nandrei.efr@gmail.com\n\nhttps://github.com/efremandrei/Amadeus");
        message.setAutoLinkMask(Linkify.WEB_URLS | Linkify.EMAIL_ADDRESSES);
        Linkify.addLinks(message, Linkify.WEB_URLS | Linkify.EMAIL_ADDRESSES);
        message.setLinksClickable(true);
        message.setMovementMethod(LinkMovementMethod.getInstance());
        message.setLinkTextColor(Color.rgb(167, 139, 250));
        message.setPadding(24, 0, 24, 8);
        new AlertDialog.Builder(this)
                .setTitle("About Amadeus")
                .setView(message)
                .setPositiveButton("Close", null)
                .show();
    }

    void showHelp() {
        new AlertDialog.Builder(this)
                .setTitle("Amadeus Help")
                .setMessage("LOOPS\nTap Record, make your first sound, then tap again to close the loop. The first loop is snapped to the nearest beat using the selected BPM. Tap a track to mute it. Long press a track for Delete, Mute, Solo, Volume, Reverb, and Echo controls.\n\nTIMELINE\nOpen Timeline to see the arrangement. Drag a colored block to move it, or drag its left and right handles to trim the active range. Long press a block for Duplicate, Move up/down, Reset trim, and track controls. Playback and export use the edited ranges.\n\nTEMPO\nTap the BPM pill to set the tempo from 40–220 BPM and optionally enable a metronome click.\n\nPROJECTS\nTap the project name under Amadeus to rename it. Tap Save in the bottom action bar to save the current project inside Amadeus without creating or exporting a music file.\n\nSOUND PADS\nTap a pad to play its sound instantly. Tap the preset name to switch banks. Tap Configure, then tap a pad to assign a built-in sound or import a WAV/MP3/audio file.\n\nEXPORT\nTap Export to choose format and advanced quality controls: sample rate, codec bitrate, WAV bit depth, normalization, and fades.\n\nTIPS\nUse headphones while recording to avoid feedback. Microphone permission is needed for loops. Your loop session and pad assignments are saved automatically.")
                .setNeutralButton("Tutorials", (dialog, which) -> showTutorialChooser())
                .setPositiveButton("Got it", null)
                .show();
    }

    void showTutorialChooser() {
        new AlertDialog.Builder(this).setTitle("Choose a tutorial").setItems(new String[]{"Make your first loop", "Build with Sound Pads"}, (dialog, which) -> showTutorialStep(which, 0)).show();
    }

    private void showTutorialStep(final int tutorial, final int step) {
        final String[][] titles = {{"1  Make a sound", "2  Close the loop", "3  Build and share"}, {"1  Open Sound Pads", "2  Play a preset", "3  Make it yours"}};
        final String[][] messages = {{"Open Loops and tap the big purple Record button. Allow microphone access, then clap, hum, or play an instrument.", "Tap Record again when your phrase ends. Amadeus repeats it automatically. Use Add Loop to layer another sound, Undo to remove the last layer, and tap a track to mute it.", "When your idea is ready, tap Export and choose WAV, M4A, or MP3. Headphones help prevent the microphone from hearing the speaker."}, {"Tap Sound Pads at the top. Each pad is a playable one-shot sound, designed for quick ideas and live accents.", "Tap a pad to hear it. Tap the preset bank name to switch between Starter Kit, Percussion, and Synth Sketch.", "Tap Configure to assign a different sound and choose Icons, Text, or Both. Your choices are saved automatically."}};
        AlertDialog.Builder builder = new AlertDialog.Builder(this).setTitle(titles[tutorial][step]).setMessage(messages[tutorial][step]);
        if (step > 0) builder.setNegativeButton("Back", (dialog, which) -> showTutorialStep(tutorial, step - 1));
        if (step < titles[tutorial].length - 1) builder.setPositiveButton("Next", (dialog, which) -> showTutorialStep(tutorial, step + 1));
        else builder.setPositiveButton("Done", null);
        builder.show();
    }

    void showDemoChooser() {
        new AlertDialog.Builder(this).setTitle("Demo tracks").setMessage("Load a ready-made session to explore layers, mute controls, and export.").setItems(DemoLibrary.names(), (dialog, which) -> { loopView.loadDemo(which); Toast.makeText(this, DemoLibrary.get(which).description, Toast.LENGTH_LONG).show(); }).show();
    }

    void showTempoDialog() {
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (24 * getResources().getDisplayMetrics().density);
        content.setPadding(pad, 0, pad, 0);
        TextView value = new TextView(this);
        value.setTextSize(22);
        value.setText(loopView.getTempoBpm() + " BPM");
        SeekBar tempo = new SeekBar(this);
        tempo.setMin(40); tempo.setMax(220); tempo.setProgress(loopView.getTempoBpm());
        tempo.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) { value.setText(progress + " BPM"); }
            @Override public void onStartTrackingTouch(SeekBar bar) { }
            @Override public void onStopTrackingTouch(SeekBar bar) { }
        });
        CheckBox metronome = new CheckBox(this);
        metronome.setText("Enable metronome click");
        metronome.setChecked(loopView.isMetronomeEnabled());
        content.addView(value); content.addView(tempo); content.addView(metronome);
        new AlertDialog.Builder(this)
                .setTitle("Tempo & metronome")
                .setMessage("Use the click to keep a steady pulse while recording.")
                .setView(content)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Apply", (dialog, which) -> loopView.setTempo(tempo.getProgress(), metronome.isChecked()))
                .show();
    }

    void showRenameProject() {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        input.setText(loopView.projectName());
        input.setSelection(input.length());
        input.setHint("Project name");
        new AlertDialog.Builder(this)
                .setTitle("Name this project")
                .setView(input)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Save", (dialog, which) -> { loopView.setProjectName(input.getText().toString()); Toast.makeText(this, "Project name saved", Toast.LENGTH_SHORT).show(); })
                .show();
    }

    void showTrackMenu(int index) {
        String name = loopView.trackName(index);
        if (name == null) return;
        String mute = loopView.trackMuted(index) ? "Unmute" : "Mute";
        String solo = loopView.trackSolo(index) ? "Unsolo" : "Solo";
        new AlertDialog.Builder(this)
                .setTitle(name)
                .setItems(new String[]{"Delete", mute, solo, "Volume", "Effects", "Duplicate", "Move up", "Move down", "Reset trim"}, (dialog, which) -> {
                    if (which == 0) loopView.deleteTrack(index);
                    else if (which == 1) loopView.toggleMute(index);
                    else if (which == 2) loopView.toggleSolo(index);
                    else if (which == 3) showTrackVolume(index);
                    else if (which == 4) showTrackEffects(index);
                    else if (which == 5) loopView.duplicateTrack(index);
                    else if (which == 6) loopView.moveTrack(index, -1);
                    else if (which == 7) loopView.moveTrack(index, 1);
                    else loopView.resetTrackRange(index);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    void showTrackEffects(int index) {
        String name = loopView.trackName(index);
        if (name == null) return;
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (24 * getResources().getDisplayMetrics().density);
        content.setPadding(pad, 0, pad, 0);
        TextView reverbValue = new TextView(this); reverbValue.setText("Reverb " + Math.round(loopView.trackReverb(index) * 100) + "%");
        SeekBar reverb = new SeekBar(this); reverb.setMax(100); reverb.setProgress(Math.round(loopView.trackReverb(index) * 100));
        reverb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) { reverbValue.setText("Reverb " + progress + "%"); }
            @Override public void onStartTrackingTouch(SeekBar bar) { }
            @Override public void onStopTrackingTouch(SeekBar bar) { }
        });
        TextView delayValue = new TextView(this); delayValue.setText("Echo " + Math.round(loopView.trackDelay(index) * 100) + "%");
        SeekBar delay = new SeekBar(this); delay.setMax(100); delay.setProgress(Math.round(loopView.trackDelay(index) * 100));
        delay.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) { delayValue.setText("Echo " + progress + "%"); }
            @Override public void onStartTrackingTouch(SeekBar bar) { }
            @Override public void onStopTrackingTouch(SeekBar bar) { }
        });
        content.addView(reverbValue); content.addView(reverb); content.addView(delayValue); content.addView(delay);
        new AlertDialog.Builder(this)
                .setTitle(name + " effects")
                .setMessage("Simple live-safe effects applied to playback and export.")
                .setView(content)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Apply", (dialog, which) -> loopView.setTrackEffects(index, reverb.getProgress() / 100f, delay.getProgress() / 100f))
                .show();
    }

    void showTrackVolume(int index) {
        String name = loopView.trackName(index);
        if (name == null) return;
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (24 * getResources().getDisplayMetrics().density);
        content.setPadding(pad, 0, pad, 0);
        TextView value = new TextView(this);
        SeekBar volume = new SeekBar(this);
        volume.setMax(100); volume.setProgress(Math.round(loopView.trackVolume(index) * 100));
        value.setText("Volume " + volume.getProgress() + "%");
        volume.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) { value.setText("Volume " + progress + "%"); }
            @Override public void onStartTrackingTouch(SeekBar bar) { }
            @Override public void onStopTrackingTouch(SeekBar bar) { }
        });
        content.addView(value); content.addView(volume);
        new AlertDialog.Builder(this)
                .setTitle(name + " volume")
                .setView(content)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Apply", (dialog, which) -> loopView.setTrackVolume(index, volume.getProgress() / 100f))
                .show();
    }

    void showExportChooser() {
        if (!loopView.hasTracks()) { Toast.makeText(this, "Record a loop before exporting", Toast.LENGTH_SHORT).show(); return; }
        String[] formats = {"WAV — uncompressed", "M4A — compact AAC", "MP3 — compatible"};
        new AlertDialog.Builder(this).setTitle("Export creation").setMessage("Choose a format, then tune the output quality.").setItems(formats, (dialog, which) -> showExportQuality(which)).show();
    }

    private void showExportQuality(final int format) {
        final ExportManager.Settings defaults = ExportManager.Settings.defaults(format);
        final LinearLayout content = new LinearLayout(this); content.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (24 * getResources().getDisplayMetrics().density); content.setPadding(pad, 0, pad, 0);
        TextView rateValue = new TextView(this); rateValue.setText("Sample rate 44.1 kHz");
        SeekBar rate = new SeekBar(this); rate.setMax(1); rate.setProgress(defaults.sampleRate == 48000 ? 1 : 0);
        rate.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() { @Override public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) { rateValue.setText("Sample rate " + (progress == 1 ? "48 kHz" : "44.1 kHz")); } @Override public void onStartTrackingTouch(SeekBar bar) { } @Override public void onStopTrackingTouch(SeekBar bar) { } });
        content.addView(rateValue); content.addView(rate);

        final int[] bitrates = format == ExportManager.FORMAT_M4A ? new int[]{64, 96, 128, 192, 256} : new int[]{96, 128, 192, 256, 320};
        final int defaultBitrateIndex = nearestIndex(bitrates, defaults.bitRateKbps);
        TextView bitrateValue = new TextView(this); bitrateValue.setText("Bitrate " + bitrates[defaultBitrateIndex] + " kbps");
        SeekBar bitrate = new SeekBar(this); bitrate.setMax(bitrates.length - 1); bitrate.setProgress(defaultBitrateIndex);
        bitrate.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() { @Override public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) { bitrateValue.setText("Bitrate " + bitrates[progress] + " kbps"); } @Override public void onStartTrackingTouch(SeekBar bar) { } @Override public void onStopTrackingTouch(SeekBar bar) { } });
        if (format == ExportManager.FORMAT_WAV) bitrateValue.setText("WAV uses lossless PCM");
        content.addView(bitrateValue); bitrate.setVisibility(format == ExportManager.FORMAT_WAV ? View.GONE : View.VISIBLE); content.addView(bitrate);

        final CheckBox bitDepth = new CheckBox(this); bitDepth.setText("24-bit WAV container (larger file)"); bitDepth.setChecked(defaults.bitDepth == 24); bitDepth.setVisibility(format == ExportManager.FORMAT_WAV ? View.VISIBLE : View.GONE); content.addView(bitDepth);
        final CheckBox normalize = new CheckBox(this); normalize.setText("Normalize safely to prevent clipping"); normalize.setChecked(defaults.normalize); content.addView(normalize);

        final int[] fades = new int[]{0, 10, 50, 100, 250, 500};
        TextView fadeInValue = new TextView(this); fadeInValue.setText("Fade in 0 ms"); SeekBar fadeIn = new SeekBar(this); fadeIn.setMax(fades.length - 1); fadeIn.setProgress(0); fadeIn.setOnSeekBarChangeListener(fadeListener(fadeInValue, "Fade in ", fades)); content.addView(fadeInValue); content.addView(fadeIn);
        TextView fadeOutValue = new TextView(this); fadeOutValue.setText("Fade out 0 ms"); SeekBar fadeOut = new SeekBar(this); fadeOut.setMax(fades.length - 1); fadeOut.setProgress(0); fadeOut.setOnSeekBarChangeListener(fadeListener(fadeOutValue, "Fade out ", fades)); content.addView(fadeOutValue); content.addView(fadeOut);

        ScrollView scroll = new ScrollView(this); scroll.addView(content);
        new AlertDialog.Builder(this).setTitle("Advanced " + exportFormatName(format) + " quality").setMessage(format == ExportManager.FORMAT_WAV ? "Lossless export with optional 24-bit depth, resampling, normalization, and fades." : "Tune the codec bitrate, resampling, normalization, and fades before choosing a save location.").setView(scroll).setNegativeButton("Cancel", null).setPositiveButton("Choose file", (dialog, which) -> {
            int selectedRate = rate.getProgress() == 1 ? 48000 : 44100;
            int selectedBitrate = bitrates[bitrate.getProgress()];
            pendingExportSettings = new ExportManager.Settings(selectedRate, selectedBitrate, bitDepth.isChecked() ? 24 : 16, normalize.isChecked(), fades[fadeIn.getProgress()], fades[fadeOut.getProgress()]);
            beginExport(format, pendingExportSettings);
        }).show();
    }

    private SeekBar.OnSeekBarChangeListener fadeListener(final TextView value, final String prefix, final int[] options) {
        return new SeekBar.OnSeekBarChangeListener() { @Override public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) { value.setText(prefix + options[progress] + " ms"); } @Override public void onStartTrackingTouch(SeekBar bar) { } @Override public void onStopTrackingTouch(SeekBar bar) { } };
    }

    private int nearestIndex(int[] values, int target) { int best = 0; for (int i = 1; i < values.length; i++) if (Math.abs(values[i] - target) < Math.abs(values[best] - target)) best = i; return best; }
    private String exportFormatName(int format) { return format == ExportManager.FORMAT_WAV ? "WAV" : format == ExportManager.FORMAT_M4A ? "M4A" : "MP3"; }

    void saveProject() {
        loopView.saveProject();
        Toast.makeText(this, "Project saved on this device — no music file created", Toast.LENGTH_LONG).show();
    }

    void exitApp() {
        new AlertDialog.Builder(this)
                .setTitle("Exit Amadeus?")
                .setMessage("Your project is saved automatically. Exit the app now?")
                .setNegativeButton("Stay", null)
                .setPositiveButton("Exit", (dialog, which) -> finish())
                .show();
    }

    private void beginExport(int format, ExportManager.Settings settings) {
        pendingExportFormat = format; pendingExportSettings = settings;
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT); intent.addCategory(Intent.CATEGORY_OPENABLE); intent.setType(ExportManager.mimeType(format)); intent.putExtra(Intent.EXTRA_TITLE, "amadeus-loop." + ExportManager.extension(format)); startActivityForResult(intent, EXPORT_REQUEST);
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == MIC_REQUEST && grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) loopView.toggleRecording();
        else Toast.makeText(this, "Microphone access is needed to record loops", Toast.LENGTH_SHORT).show();
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == EXPORT_REQUEST && resultCode == RESULT_OK && data != null && data.getData() != null) loopView.exportToUri(data.getData(), pendingExportFormat, pendingExportSettings);
        if (requestCode == IMPORT_PAD_REQUEST && resultCode == RESULT_OK && data != null && data.getData() != null && pendingImportPad >= 0) {
            Uri uri = data.getData();
            try { if ((data.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0) getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION); } catch (Exception ignored) { }
            loopView.importPad(pendingImportPad, uri, audioLabel(uri));
            Toast.makeText(this, "Audio assigned to Pad " + (pendingImportPad + 1), Toast.LENGTH_LONG).show();
            pendingImportPad = -1;
        }
    }

    private String audioLabel(Uri uri) {
        String raw = uri.getLastPathSegment();
        if (raw == null || raw.length() == 0) return "IMPORTED AUDIO";
        int slash = raw.lastIndexOf('/'); if (slash >= 0) raw = raw.substring(slash + 1);
        int dot = raw.lastIndexOf('.'); if (dot > 0) raw = raw.substring(0, dot);
        raw = raw.replace('_', ' ').replace('-', ' ').trim();
        if (raw.length() == 0) raw = "IMPORTED AUDIO";
        return raw.length() > 18 ? raw.substring(0, 18) + "…" : raw.toUpperCase();
    }

    @Override protected void onDestroy() { if (loopView != null) loopView.release(); super.onDestroy(); }

    private static final class LoopStationView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final LoopEngine engine;
        private final SoundPadEngine pads;
        private final MetronomeEngine metronome;
        private final SharedPreferences prefs;
        private boolean light, padsMode, timelineMode, configuring;
        private int tempoBpm;
        private boolean metronomeEnabled;
        private int padDisplayMode;
        private float density;
        private int topInset, bottomInset;
        private int lastPad = -1;
        private long lastPadAt;
        private float trackScrollOffset, maxTrackScroll, touchDownX, touchDownY, lastTouchY;
        private long touchDownAt;
        private int touchDownTrack = -1;
        private boolean scrolling, timelineDragging;
        private int timelineDragMode;
        private final int[] trackColors = { Color.rgb(167, 139, 250), Color.rgb(45, 212, 191), Color.rgb(251, 146, 60), Color.rgb(244, 114, 182), Color.rgb(96, 165, 250), Color.rgb(163, 230, 53) };
        private final int[] padColors = { Color.rgb(167, 139, 250), Color.rgb(45, 212, 191), Color.rgb(251, 146, 60), Color.rgb(244, 114, 182), Color.rgb(96, 165, 250), Color.rgb(163, 230, 53), Color.rgb(251, 191, 36), Color.rgb(129, 140, 248) };

        LoopStationView(Context context) {
            super(context); density = getResources().getDisplayMetrics().density; engine = new LoopEngine(context); pads = new SoundPadEngine(context); metronome = new MetronomeEngine();
            prefs = context.getSharedPreferences("amadeus", Context.MODE_PRIVATE); light = prefs.getBoolean("light_theme", false); padDisplayMode = prefs.getInt("pad_display_mode", 2); tempoBpm = Math.max(40, Math.min(220, prefs.getInt("tempo_bpm", 96))); metronomeEnabled = prefs.getBoolean("metronome_enabled", false); metronome.setTempo(tempoBpm); metronome.setEnabled(metronomeEnabled); engine.setTempoBpm(tempoBpm); setFocusable(true);
        }

        void toggleRecording() { if (engine.getState() == LoopEngine.State.RECORDING) engine.stopRecording(); else engine.startRecording(); invalidate(); }
        void assignPad(int pad, String sound) { pads.assignPad(pad, sound); configuring = false; invalidate(); }
        boolean hasTracks() { return engine.hasTracks(); }
        String trackName(int index) { return engine.trackName(index); }
        boolean trackMuted(int index) { return engine.isTrackMuted(index); }
        boolean trackSolo(int index) { return engine.isTrackSolo(index); }
        float trackVolume(int index) { return engine.trackVolume(index); }
        float trackReverb(int index) { return engine.trackReverb(index); }
        float trackDelay(int index) { return engine.trackDelay(index); }
        int trackStart(int index) { return engine.trackStart(index); }
        int trackEnd(int index) { return engine.trackEnd(index); }
        void toggleMute(int index) { engine.toggleMute(index); invalidate(); }
        void toggleSolo(int index) { engine.toggleSolo(index); invalidate(); }
        void setTrackVolume(int index, float volume) { engine.setTrackVolume(index, volume); invalidate(); }
        void setTrackEffects(int index, float reverb, float delay) { engine.setTrackEffects(index, reverb, delay); invalidate(); }
        void deleteTrack(int index) { engine.deleteTrack(index); invalidate(); }
        void setTrackRange(int index, int start, int end) { engine.setTrackRange(index, start, end); invalidate(); }
        void resetTrackRange(int index) { engine.resetTrackRange(index); invalidate(); }
        void duplicateTrack(int index) { engine.duplicateTrack(index); invalidate(); }
        void moveTrack(int index, int direction) { engine.moveTrack(index, direction); invalidate(); }
        void saveProject() { engine.saveProject(); invalidate(); }
        int getTempoBpm() { return tempoBpm; }
        boolean isMetronomeEnabled() { return metronomeEnabled; }
        void setTempo(int bpm, boolean enabled) { tempoBpm = Math.max(40, Math.min(220, bpm)); metronomeEnabled = enabled; prefs.edit().putInt("tempo_bpm", tempoBpm).putBoolean("metronome_enabled", enabled).apply(); metronome.setTempo(tempoBpm); metronome.setEnabled(enabled); engine.setTempoBpm(tempoBpm); invalidate(); }
        String projectName() { return engine.getProjectName(); }
        void setProjectName(String name) { engine.setProjectName(name); invalidate(); }
        void importPad(int pad, Uri uri, String displayName) { pads.importPad(pad, uri, displayName); invalidate(); }
        void loadDemo(int index) { engine.loadDemo(DemoLibrary.get(index)); trackScrollOffset = 0; padsMode = false; timelineMode = false; configuring = false; invalidate(); }
        void setSystemBarInsets(int top, int bottom) { topInset = Math.max(0, top); bottomInset = Math.max(0, bottom); invalidate(); }
        void exportToUri(final Uri destination, final int format, final ExportManager.Settings settings) {
            final short[] audio = engine.mixedLoop();
            new Thread(() -> {
                try { ExportManager.export(getContext(), destination, format, audio, settings); ((Activity) getContext()).runOnUiThread(() -> Toast.makeText(getContext(), "Exported ." + ExportManager.extension(format) + " with selected quality", Toast.LENGTH_LONG).show()); }
                catch (Exception error) { ((Activity) getContext()).runOnUiThread(() -> Toast.makeText(getContext(), "Export failed: " + error.getMessage(), Toast.LENGTH_LONG).show()); }
            }, "Amadeus-export").start();
        }
        void release() { metronome.release(); engine.release(); }

        @Override protected void onDraw(Canvas c) {
            super.onDraw(c); int bg = light ? Color.rgb(247, 246, 251) : Color.rgb(16, 16, 22), surface = light ? Color.WHITE : Color.rgb(26, 26, 36), text = light ? Color.rgb(35, 35, 45) : Color.rgb(245, 243, 250), secondary = light ? Color.rgb(104, 101, 116) : Color.rgb(165, 160, 179);
            c.drawColor(bg); c.save(); c.translate(0, topInset); float w = getWidth(), h = Math.max(dp(1), getHeight() - topInset - bottomInset);
            drawAmbientBackground(c, w, h);
            paint.setColor(text); paint.setTextSize(dp(26)); paint.setTypeface(android.graphics.Typeface.DEFAULT_BOLD); c.drawText("Amadeus", dp(24), dp(42), paint);
            paint.setTypeface(android.graphics.Typeface.DEFAULT); paint.setTextSize(dp(12)); paint.setColor(secondary); String projectLabel = engine.getProjectName(); if (projectLabel.length() > 24) projectLabel = projectLabel.substring(0, 24) + "…"; c.drawText(padsMode ? "SOUND PADS" : timelineMode ? "ARRANGEMENT" : projectLabel, dp(25), dp(64), paint);
            drawStatusBadge(c, dp(24), dp(76), surface, text, secondary); drawModeTabs(c, w, surface, text); drawTopUtilityButtons(c, w, surface, text); paint.setColor(text); paint.setTextSize(dp(22)); c.drawText(light ? "☀" : "☾", w - dp(55), dp(42), paint);
            if (padsMode) drawPads(c, w, h, surface, text, secondary); else if (timelineMode) drawTimeline(c, w, h, surface, text, secondary); else drawLoops(c, w, h, surface, text, secondary);
            drawUtilityButtons(c, w, h, surface, text);
            c.restore();
            postInvalidateDelayed(engine.getState() == LoopEngine.State.RECORDING || SystemClock.uptimeMillis() - lastPadAt < 600 ? 16 : 80);
        }

        private void drawAmbientBackground(Canvas c, float w, float h) {
            float time = SystemClock.uptimeMillis() / 1000f; paint.setStyle(Paint.Style.FILL); paint.setColor(Color.argb(light ? 22 : 32, 167, 139, 250)); c.drawCircle(w - dp(18) + (float) Math.sin(time * .45) * dp(8), dp(68), dp(104), paint); paint.setColor(Color.argb(light ? 12 : 20, 45, 212, 191)); c.drawCircle(dp(18), h - dp(120) + (float) Math.cos(time * .35) * dp(10), dp(130), paint); paint.setColor(Color.argb(light ? 16 : 24, 251, 146, 60)); c.drawCircle(w - dp(20), h - dp(210), dp(82), paint);
        }

        private void drawStatusBadge(Canvas c, float x, float y, int surface, int text, int secondary) {
            boolean recording = engine.getState() == LoopEngine.State.RECORDING, active = padsMode || engine.getState() == LoopEngine.State.PLAYING; String label = recording ? "RECORDING" : engine.isDemoMode() ? "DEMO" : active ? "LIVE" : "READY"; int color = recording ? Color.rgb(248, 113, 113) : engine.isDemoMode() ? Color.rgb(251, 146, 60) : active ? Color.rgb(45, 212, 191) : Color.rgb(167, 139, 250);
            paint.setColor(surface); c.drawRoundRect(new RectF(x, y, x + dp(86), y + dp(26)), dp(13), dp(13), paint); paint.setColor(color); c.drawCircle(x + dp(13), y + dp(13), dp(4), paint); paint.setColor(secondary); paint.setTextSize(dp(10)); paint.setTypeface(android.graphics.Typeface.DEFAULT_BOLD); c.drawText(label, x + dp(23), y + dp(17), paint);
        }

        private void drawTopUtilityButtons(Canvas c, float w, int surface, int text) {
            float y = dp(76); drawSmallButton(c, w - dp(220), y, w - dp(126), y + dp(32), surface, text, "HELP"); drawSmallButton(c, w - dp(118), y, w - dp(20), y + dp(32), surface, text, "ABOUT");
        }

        private void drawModeTabs(Canvas c, float w, int surface, int text) { float y = dp(116), gap = dp(7), left = dp(20), tabW = (w - dp(40) - gap * 2) / 3f; drawTab(c, left, y, left + tabW, y + dp(32), !padsMode && !timelineMode, surface, text, "LOOPS"); drawTab(c, left + tabW + gap, y, left + tabW * 2 + gap, y + dp(32), timelineMode, surface, text, "TIMELINE"); drawTab(c, left + (tabW + gap) * 2, y, w - dp(20), y + dp(32), padsMode, surface, text, "PADS"); }

        private void drawLoops(Canvas c, float w, float h, int surface, int text, int secondary) {
            drawPill(c, w - dp(164), dp(22), w - dp(80), dp(54), surface, tempoBpm + " BPM"); List<LoopEngine.Track> tracks = engine.snapshotTracks(); float top = dp(168), rowHeight = dp(94), viewportBottom = h - dp(190);
            maxTrackScroll = Math.max(0, top + tracks.size() * rowHeight - viewportBottom); trackScrollOffset = Math.max(0, Math.min(trackScrollOffset, maxTrackScroll));
            c.save(); c.clipRect(0, top - dp(18), w, Math.max(top, viewportBottom));
            if (tracks.isEmpty()) { paint.setColor(surface); c.drawRoundRect(new RectF(dp(20), top, w - dp(20), top + dp(174)), dp(22), dp(22), paint); paint.setColor(Color.rgb(167, 139, 250)); paint.setTextSize(dp(44)); paint.setTypeface(android.graphics.Typeface.DEFAULT_BOLD); c.drawText("◉", dp(42), top + dp(73), paint); paint.setColor(text); paint.setTextSize(dp(21)); c.drawText("Start with a sound", dp(100), top + dp(55), paint); paint.setColor(secondary); paint.setTextSize(dp(14)); c.drawText("Tap Record, make a loop, then layer it.", dp(100), top + dp(83), paint); c.drawText("Your first loop sets the musical grid.", dp(100), top + dp(106), paint); }
            else for (int i = 0; i < tracks.size(); i++) drawTrack(c, tracks.get(i), i, top + i * rowHeight - trackScrollOffset, w, surface, text, secondary);
            c.restore();
            if (maxTrackScroll > 0) drawTrackScrollbar(c, w, top, viewportBottom, secondary);
            float controlsTop = h - dp(190); paint.setColor(secondary); paint.setTextSize(dp(13)); c.drawText(engine.getState() == LoopEngine.State.RECORDING ? "RECORDING — tap to close loop" : tracks.isEmpty() ? "READY TO RECORD" : engine.isDemoMode() ? "DEMO — " + engine.getDemoName() + " • tap to add a layer" : engine.getState() == LoopEngine.State.PLAYING ? "PLAYING — tap to add a layer" : "READY — tap to add a layer", dp(24), controlsTop - dp(18), paint);
            drawButton(c, dp(22), controlsTop, dp(92), controlsTop + dp(54), surface, "↶", "UNDO", text); drawRecord(c, w / 2, controlsTop + dp(29), engine.getState() == LoopEngine.State.RECORDING); drawButton(c, w - dp(114), controlsTop, w - dp(22), controlsTop + dp(54), surface, "+", "ADD LOOP", text);
            paint.setColor(secondary); paint.setTextSize(dp(13)); c.drawText("MIC INPUT", dp(24), h - dp(48), paint); paint.setColor(Color.rgb(65, 60, 80)); c.drawRoundRect(new RectF(dp(24), h - dp(36), w - dp(24), h - dp(28)), dp(4), dp(4), paint); paint.setColor(Color.rgb(167, 139, 250)); float levelWidth = (w - dp(48)) * Math.min(1f, engine.getInputLevel() * 2.5f); c.drawRoundRect(new RectF(dp(24), h - dp(36), dp(24) + levelWidth, h - dp(28)), dp(4), dp(4), paint);
        }

        private void drawTimeline(Canvas c, float w, float h, int surface, int text, int secondary) {
            drawPill(c, dp(20), dp(156), dp(152), dp(190), surface, "ARRANGE");
            drawPill(c, w - dp(152), dp(156), w - dp(20), dp(190), surface, "DRAG TO EDIT");
            paint.setColor(secondary); paint.setTextSize(dp(12)); c.drawText("Move blocks, or drag either edge to trim", dp(24), dp(214), paint);
            List<LoopEngine.Track> tracks = engine.snapshotTracks();
            float top = dp(230), rowHeight = dp(82), viewportBottom = h - dp(190), rulerLeft = dp(48), rulerWidth = Math.max(dp(1), w - dp(68));
            maxTrackScroll = Math.max(0, top + tracks.size() * rowHeight - viewportBottom); trackScrollOffset = Math.max(0, Math.min(trackScrollOffset, maxTrackScroll));
            c.save(); c.clipRect(0, top - dp(18), w, Math.max(top, viewportBottom));
            if (tracks.isEmpty()) {
                paint.setColor(surface); c.drawRoundRect(new RectF(dp(20), top, w - dp(20), top + dp(132)), dp(22), dp(22), paint);
                paint.setColor(Color.rgb(167, 139, 250)); paint.setTextSize(dp(38)); paint.setTypeface(android.graphics.Typeface.DEFAULT_BOLD); c.drawText("✦", dp(40), top + dp(63), paint);
                paint.setColor(text); paint.setTextSize(dp(19)); c.drawText("Your arrangement is empty", dp(88), top + dp(48), paint);
                paint.setColor(secondary); paint.setTextSize(dp(13)); c.drawText("Record a loop first, then shape it here.", dp(88), top + dp(76), paint);
            } else {
                paint.setColor(Color.argb(light ? 45 : 65, 167, 139, 250)); paint.setStrokeWidth(dp(1));
                for (int i = 0; i <= 16; i++) { float x = rulerLeft + rulerWidth * i / 16f; c.drawLine(x, top, x, viewportBottom, paint); }
                paint.setColor(secondary); paint.setTextSize(dp(10)); paint.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
                for (int i = 0; i < 4; i++) c.drawText("" + (i + 1), rulerLeft + rulerWidth * i / 4f + dp(3), top - dp(8), paint);
                paint.setTypeface(android.graphics.Typeface.DEFAULT);
                for (int i = 0; i < tracks.size(); i++) {
                    float y = top + i * rowHeight - trackScrollOffset;
                    paint.setColor(surface); c.drawRoundRect(new RectF(dp(20), y + dp(4), w - dp(20), y + rowHeight - dp(5)), dp(16), dp(16), paint);
                    int accent = trackColors[tracks.get(i).colorIndex % trackColors.length];
                    paint.setColor(accent); c.drawRoundRect(new RectF(dp(20), y + dp(4), dp(25), y + rowHeight - dp(5)), dp(3), dp(3), paint);
                    paint.setColor(text); paint.setTypeface(android.graphics.Typeface.DEFAULT_BOLD); paint.setTextSize(dp(11)); c.drawText(tracks.get(i).name, dp(29), y + dp(27), paint);
                    paint.setColor(secondary); paint.setTypeface(android.graphics.Typeface.DEFAULT); paint.setTextSize(dp(9)); c.drawText(tracks.get(i).muted ? "MUTED" : "TRACK " + (i + 1), dp(29), y + dp(44), paint);
                    float blockLeft = timelineBlockLeft(i, w), blockRight = timelineBlockRight(i, w);
                    RectF block = new RectF(blockLeft, y + dp(17), Math.max(blockLeft + dp(16), blockRight), y + dp(63));
                    paint.setColor(tracks.get(i).muted ? Color.rgb(75, 72, 87) : Color.argb(light ? 225 : 230, Color.red(accent), Color.green(accent), Color.blue(accent))); c.drawRoundRect(block, dp(12), dp(12), paint);
                    drawButtonBorder(c, block, dp(12));
                    paint.setColor(tracks.get(i).muted ? secondary : Color.WHITE); paint.setStrokeWidth(dp(2));
                    int bars = Math.max(4, Math.min(28, (int) ((block.width() - dp(20)) / dp(7))));
                    for (int bar = 0; bar < bars; bar++) { float bx = block.left + dp(10) + (block.width() - dp(20)) * bar / Math.max(1, bars - 1); float amp = dp(6 + ((bar * 13 + i * 7) % 12)); c.drawRoundRect(new RectF(bx, block.centerY() - amp, bx + dp(2), block.centerY() + amp), dp(1), dp(1), paint); }
                    paint.setColor(Color.WHITE); c.drawRoundRect(new RectF(block.left, block.top, block.left + dp(4), block.bottom), dp(2), dp(2), paint); c.drawRoundRect(new RectF(block.right - dp(4), block.top, block.right, block.bottom), dp(2), dp(2), paint);
                }
            }
            c.restore();
            if (maxTrackScroll > 0) drawTrackScrollbar(c, w, top, viewportBottom, secondary);
            float controlsTop = h - dp(190); paint.setColor(secondary); paint.setTextSize(dp(13)); c.drawText(engine.getState() == LoopEngine.State.RECORDING ? "RECORDING — tap to close loop" : tracks.isEmpty() ? "READY TO RECORD" : "ARRANGEMENT READY — tap a block for actions", dp(24), controlsTop - dp(18), paint);
            drawButton(c, dp(22), controlsTop, dp(92), controlsTop + dp(54), surface, "↶", "UNDO", text); drawRecord(c, w / 2, controlsTop + dp(29), engine.getState() == LoopEngine.State.RECORDING); drawButton(c, w - dp(114), controlsTop, w - dp(22), controlsTop + dp(54), surface, "+", "ADD LOOP", text);
            paint.setColor(secondary); paint.setTextSize(dp(13)); c.drawText("MIC INPUT", dp(24), h - dp(48), paint); paint.setColor(Color.rgb(65, 60, 80)); c.drawRoundRect(new RectF(dp(24), h - dp(36), w - dp(24), h - dp(28)), dp(4), dp(4), paint); paint.setColor(Color.rgb(167, 139, 250)); float levelWidth = (w - dp(48)) * Math.min(1f, engine.getInputLevel() * 2.5f); c.drawRoundRect(new RectF(dp(24), h - dp(36), dp(24) + levelWidth, h - dp(28)), dp(4), dp(4), paint);
        }

        private float timelineBlockLeft(int index, float w) { int length = Math.max(1, engine.getLoopLength()); return dp(48) + Math.max(0, Math.min(length - 1, engine.trackStart(index))) * timelineWidth(w) / length; }
        private float timelineBlockRight(int index, float w) { int length = Math.max(1, engine.getLoopLength()); return dp(48) + Math.max(1, Math.min(length, engine.trackEnd(index))) * timelineWidth(w) / length; }
        private float timelineWidth(float w) { return Math.max(dp(1), w - dp(68)); }

        private void drawTrackScrollbar(Canvas c, float w, float top, float bottom, int secondary) {
            float area = Math.max(dp(1), bottom - top), thumb = Math.max(dp(28), area * area / (area + maxTrackScroll)), travel = Math.max(0, area - thumb), y = top + (maxTrackScroll == 0 ? 0 : travel * trackScrollOffset / maxTrackScroll); paint.setColor(Color.argb(80, Color.red(secondary), Color.green(secondary), Color.blue(secondary))); c.drawRoundRect(new RectF(w - dp(11), top, w - dp(7), bottom), dp(2), dp(2), paint); paint.setColor(Color.argb(210, Color.red(secondary), Color.green(secondary), Color.blue(secondary))); c.drawRoundRect(new RectF(w - dp(12), y, w - dp(6), y + thumb), dp(3), dp(3), paint);
        }

        private void drawPads(Canvas c, float w, float h, int surface, int text, int secondary) {
            float top = dp(166); drawPill(c, dp(20), dp(156), dp(178), dp(190), surface, pads.getActivePresetName()); drawPill(c, w - dp(136), dp(156), w - dp(20), dp(190), configuring ? Color.rgb(167, 139, 250) : surface, configuring ? "TAP A PAD" : "CONFIGURE");
            paint.setColor(secondary); paint.setTextSize(dp(12)); c.drawText(configuring ? "PAD DISPLAY" : "Tap a sound to play it instantly", dp(24), dp(214), paint);
            if (configuring) { drawDisplayChoice(c, dp(20), dp(220), dp(96), dp(252), padDisplayMode == 0, surface, text, "ICONS"); drawDisplayChoice(c, dp(102), dp(220), dp(178), dp(252), padDisplayMode == 1, surface, text, "TEXT"); drawDisplayChoice(c, dp(184), dp(220), dp(260), dp(252), padDisplayMode == 2, surface, text, "BOTH"); }
            float gap = dp(12), left = dp(20), cellW = (w - dp(40) - gap) / 2f, gridTop = configuring ? dp(264) : dp(228), cellH = padCellHeight(h, gridTop, gap);
            for (int i = 0; i < SoundPadEngine.PAD_COUNT; i++) { int col = i % 2, row = i / 2; float x = left + col * (cellW + gap), y = gridTop + row * (cellH + gap); boolean active = i == lastPad && SystemClock.uptimeMillis() - lastPadAt < 600; int padSurface = active ? (light ? Color.rgb(245, 240, 255) : Color.rgb(45, 39, 67)) : surface; drawPad(c, x, y, cellW, cellH, i, pads.getSoundForPad(i), padSurface, text, secondary, active); }
            paint.setColor(secondary); paint.setTextSize(dp(12)); c.drawText("PRESET BANK  " + (pads.getActivePreset() + 1) + " / " + SoundPadEngine.PRESETS.length + "  •  tap the bank name to switch", dp(24), h - dp(34), paint);
        }

        private float padCellHeight(float h, float gridTop, float gap) { float available = h - dp(58) - gridTop - gap * 3; return Math.min(dp(86), Math.max(dp(52), available / 4f)); }

        private void drawUtilityButtons(Canvas c, float w, float h, int surface, int text) {
            float y = h - dp(98);
            if (!padsMode) {
                float gap = dp(8), left = dp(20), buttonW = (w - dp(40) - gap * 3) / 4f;
                drawSmallButton(c, left, y, left + buttonW, y + dp(32), surface, text, "DEMOS");
                drawSmallButton(c, left + buttonW + gap, y, left + buttonW * 2 + gap, y + dp(32), surface, text, "SAVE");
                drawSmallButton(c, left + buttonW * 2 + gap * 2, y, left + buttonW * 3 + gap * 2, y + dp(32), surface, text, "EXPORT");
                drawSmallButton(c, left + buttonW * 3 + gap * 3, y, w - dp(20), y + dp(32), surface, text, "EXIT");
            } else {
                drawSmallButton(c, w - dp(236), y, w - dp(132), y + dp(32), surface, text, "SAVE");
                drawSmallButton(c, w - dp(124), y, w - dp(20), y + dp(32), surface, text, "EXIT");
            }
        }

        private void drawSmallButton(Canvas c, float l, float t, float r, float b, int surface, int text, String label) {
            RectF rect = new RectF(l, t, r, b); paint.setColor(surface); c.drawRoundRect(rect, dp(15), dp(15), paint); drawButtonBorder(c, rect, dp(15));
            paint.setColor(text); paint.setTypeface(android.graphics.Typeface.DEFAULT_BOLD); paint.setTextSize(dp(11)); float labelWidth = paint.measureText(label); c.drawText(label, l + (r - l - labelWidth) / 2f, t + dp(22), paint); paint.setTypeface(android.graphics.Typeface.DEFAULT);
        }

        private void drawDisplayChoice(Canvas c, float l, float t, float r, float b, boolean selected, int surface, int text, String label) { RectF rect = new RectF(l, t, r, b); paint.setColor(selected ? Color.rgb(167, 139, 250) : surface); c.drawRoundRect(rect, dp(15), dp(15), paint); drawButtonBorder(c, rect, dp(15)); paint.setColor(selected ? Color.WHITE : text); paint.setTextSize(dp(10)); c.drawText(label, l + dp(13), t + dp(20), paint); }

        private void drawPad(Canvas c, float x, float y, float width, float height, int index, String label, int surface, int text, int secondary, boolean active) {
            int accent = padColors[index]; if (active) { paint.setColor(Color.argb(light ? 35 : 55, Color.red(accent), Color.green(accent), Color.blue(accent))); c.drawRoundRect(new RectF(x - dp(5), y - dp(5), x + width + dp(5), y + height + dp(5)), dp(24), dp(24), paint); } RectF rect = new RectF(x, y, x + width, y + height); paint.setColor(surface); c.drawRoundRect(rect, dp(20), dp(20), paint); drawButtonBorder(c, rect, dp(20)); paint.setColor(accent); c.drawRoundRect(new RectF(x, y, x + dp(6), y + height), dp(3), dp(3), paint);
            if (padDisplayMode == 0) {
                paint.setColor(secondary); paint.setTextSize(dp(10)); paint.setTypeface(android.graphics.Typeface.DEFAULT_BOLD); c.drawText("" + (index + 1), x + dp(14), y + dp(20), paint); drawSoundIcon(c, x + width / 2f, y + height / 2f + dp(4), label, accent);
            } else if (padDisplayMode == 1) {
                paint.setColor(secondary); paint.setTextSize(dp(10)); paint.setTypeface(android.graphics.Typeface.DEFAULT_BOLD); c.drawText("PAD " + (index + 1), x + width / 2f - dp(20), y + dp(24), paint); paint.setColor(text); paint.setTextSize(dp(18)); paint.setTypeface(android.graphics.Typeface.DEFAULT_BOLD); float labelWidth = paint.measureText(label); c.drawText(label, x + (width - labelWidth) / 2f, y + dp(53), paint);
            } else {
                drawSoundIcon(c, x + dp(30), y + dp(40), label, accent); paint.setColor(Color.WHITE); paint.setTextSize(dp(10)); paint.setTypeface(android.graphics.Typeface.DEFAULT_BOLD); c.drawText("" + (index + 1), x + dp(26), y + dp(44), paint); paint.setColor(text); paint.setTextSize(dp(17)); c.drawText(label, x + dp(54), y + dp(35), paint); paint.setColor(secondary); paint.setTypeface(android.graphics.Typeface.DEFAULT); paint.setTextSize(dp(10)); c.drawText(configuring ? "TAP TO ASSIGN" : "PLAY ONE-SHOT", x + dp(54), y + dp(56), paint);
            }
        }

        private void drawSoundIcon(Canvas c, float cx, float cy, String label, int color) {
            paint.setColor(color); paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(dp(2.5f)); paint.setStrokeCap(Paint.Cap.ROUND);
            if (label.equals("Kick")) { c.drawCircle(cx, cy, dp(17), paint); c.drawCircle(cx, cy, dp(6), paint); }
            else if (label.equals("Snare")) { c.drawCircle(cx, cy, dp(16), paint); for (int i = -1; i <= 1; i++) c.drawLine(cx - dp(13), cy + dp(8 + i * 4), cx + dp(13), cy + dp(8 + i * 4), paint); }
            else if (label.equals("Hi-Hat")) { c.drawOval(new RectF(cx - dp(18), cy - dp(11), cx + dp(18), cy - dp(1)), paint); c.drawLine(cx, cy - dp(1), cx, cy + dp(16), paint); c.drawLine(cx - dp(12), cy + dp(16), cx + dp(12), cy + dp(16), paint); }
            else if (label.equals("Clap")) { for (int i = -1; i <= 1; i++) c.drawArc(new RectF(cx - dp(18), cy - dp(18 + i * 2), cx + dp(18), cy + dp(18 + i * 2)), 205, 130, false, paint); }
            else if (label.equals("Bass")) { c.drawLine(cx - dp(16), cy - dp(12), cx - dp(16), cy + dp(12), paint); c.drawLine(cx - dp(6), cy - dp(12), cx - dp(6), cy + dp(12), paint); c.drawLine(cx + dp(4), cy - dp(12), cx + dp(4), cy + dp(12), paint); c.drawLine(cx + dp(14), cy - dp(12), cx + dp(14), cy + dp(12), paint); }
            else if (label.equals("Tone")) { c.drawArc(new RectF(cx - dp(16), cy - dp(16), cx + dp(16), cy + dp(16)), 215, 250, false, paint); c.drawArc(new RectF(cx - dp(9), cy - dp(9), cx + dp(9), cy + dp(9)), 215, 250, false, paint); }
            else if (label.equals("Chime")) { c.drawLine(cx - dp(12), cy - dp(13), cx - dp(12), cy + dp(10), paint); c.drawLine(cx, cy - dp(16), cx, cy + dp(10), paint); c.drawLine(cx + dp(12), cy - dp(9), cx + dp(12), cy + dp(10), paint); c.drawArc(new RectF(cx - dp(16), cy + dp(4), cx - dp(8), cy + dp(14)), 0, 180, false, paint); c.drawArc(new RectF(cx - dp(4), cy + dp(4), cx + dp(4), cy + dp(14)), 0, 180, false, paint); c.drawArc(new RectF(cx + dp(8), cy + dp(4), cx + dp(16), cy + dp(14)), 0, 180, false, paint); }
            else if (label.equals("Zap")) { Path path = new Path(); path.moveTo(cx - dp(5), cy - dp(18)); path.lineTo(cx - dp(15), cy); path.lineTo(cx - dp(3), cy - dp(2)); path.lineTo(cx - dp(10), cy + dp(18)); path.lineTo(cx + dp(15), cy - dp(7)); path.lineTo(cx + dp(3), cy - dp(6)); path.lineTo(cx + dp(11), cy - dp(18)); c.drawPath(path, paint); }
            else if (label.equals("Click")) { c.drawCircle(cx, cy, dp(4), paint); c.drawCircle(cx, cy, dp(12), paint); }
            else if (label.equals("Pop")) { c.drawCircle(cx, cy, dp(9), paint); c.drawLine(cx, cy - dp(16), cx, cy - dp(23), paint); c.drawLine(cx - dp(13), cy - dp(11), cx - dp(18), cy - dp(16), paint); c.drawLine(cx + dp(13), cy - dp(11), cx + dp(18), cy - dp(16), paint); }
            else if (label.equals("Vox")) { c.drawOval(new RectF(cx - dp(11), cy - dp(17), cx + dp(11), cy + dp(17)), paint); c.drawArc(new RectF(cx - dp(18), cy - dp(12), cx + dp(18), cy + dp(12)), 250, 140, false, paint); }
            else c.drawCircle(cx, cy, dp(15), paint);
            paint.setStyle(Paint.Style.FILL); paint.setStrokeCap(Paint.Cap.BUTT);
        }

        private void drawTrack(Canvas c, LoopEngine.Track track, int index, float y, float w, int surface, int text, int secondary) { paint.setColor(surface); c.drawRoundRect(new RectF(dp(20), y, w - dp(20), y + dp(80)), dp(18), dp(18), paint); int accent = trackColors[track.colorIndex % trackColors.length]; paint.setColor(accent); c.drawRoundRect(new RectF(dp(20), y, dp(26), y + dp(80)), dp(3), dp(3), paint); paint.setColor(text); paint.setTypeface(android.graphics.Typeface.DEFAULT_BOLD); paint.setTextSize(dp(14)); c.drawText(track.name, dp(38), y + dp(27), paint); paint.setTypeface(android.graphics.Typeface.DEFAULT); paint.setColor(secondary); paint.setTextSize(dp(11)); String stateLabel = track.muted ? "MUTED" : engine.isTrackSolo(index) ? "SOLO" : "LOOPING"; if (track.volume < .99f && !track.muted) stateLabel += " • " + Math.round(track.volume * 100) + "%"; if (track.reverb > .01f || track.delay > .01f) stateLabel += " • FX"; c.drawText(stateLabel, dp(38), y + dp(49), paint); paint.setColor(track.muted ? Color.rgb(80, 77, 92) : accent); float waveStart = dp(124), waveWidth = w - dp(230), center = y + dp(40); for (int i = 0; i < 22; i++) { float x = waveStart + waveWidth * i / 22f, amp = dp(8 + ((i * 17 + index * 11) % 18)); c.drawRoundRect(new RectF(x, center - amp, x + dp(3), center + amp), dp(2), dp(2), paint); } paint.setColor(track.muted ? Color.rgb(70, 68, 80) : Color.rgb(57, 53, 70)); c.drawRoundRect(new RectF(w - dp(90), y + dp(25), w - dp(38), y + dp(34)), dp(4), dp(4), paint); paint.setColor(track.muted ? secondary : accent); c.drawCircle(w - dp(90) + dp(52) * track.volume, y + dp(29), dp(8), paint); }
        private void drawTab(Canvas c, float l, float t, float r, float b, boolean selected, int surface, int text, String label) { RectF rect = new RectF(l, t, r, b); paint.setColor(selected ? Color.rgb(167, 139, 250) : surface); c.drawRoundRect(rect, dp(16), dp(16), paint); drawButtonBorder(c, rect, dp(16)); paint.setColor(selected ? Color.WHITE : text); paint.setTypeface(android.graphics.Typeface.DEFAULT_BOLD); paint.setTextSize(dp(11)); c.drawText(label, l + dp(13), t + dp(21), paint); paint.setTypeface(android.graphics.Typeface.DEFAULT); }
        private void drawPill(Canvas c, float l, float t, float r, float b, int color, String label) { RectF rect = new RectF(l, t, r, b); paint.setColor(color); c.drawRoundRect(rect, dp(18), dp(18), paint); drawButtonBorder(c, rect, dp(18)); paint.setColor(Color.rgb(188, 180, 210)); paint.setTextSize(dp(12)); c.drawText(label, l + dp(16), t + dp(21), paint); }
        private void drawButton(Canvas c, float l, float t, float r, float b, int color, String icon, String label, int text) { RectF rect = new RectF(l, t, r, b); paint.setColor(color); c.drawRoundRect(rect, dp(16), dp(16), paint); drawButtonBorder(c, rect, dp(16)); paint.setColor(text); paint.setTextSize(dp(21)); c.drawText(icon, l + (r-l)/2 - dp(8), t + dp(25), paint); paint.setTextSize(dp(10)); paint.setColor(Color.rgb(170, 164, 185)); c.drawText(label, l + (r - l - paint.measureText(label)) / 2f, t + dp(45), paint); }
        private void drawButtonBorder(Canvas c, RectF rect, float radius) { if (light) return; paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(Math.max(1f, dp(.8f))); paint.setColor(Color.argb(205, 167, 139, 250)); c.drawRoundRect(rect, radius, radius, paint); paint.setStyle(Paint.Style.FILL); }
        private void drawRecord(Canvas c, float x, float y, boolean recording) { float pulse = 1f + (float) Math.sin(SystemClock.uptimeMillis() / 180.0) * .06f; paint.setStyle(Paint.Style.FILL); paint.setColor(recording ? Color.argb(48, 248, 113, 113) : Color.argb(42, 167, 139, 250)); c.drawCircle(x, y, dp(51) * pulse, paint); paint.setColor(recording ? Color.rgb(248, 113, 113) : Color.rgb(167, 139, 250)); c.drawCircle(x, y, dp(37), paint); paint.setColor(Color.WHITE); if (recording) c.drawRoundRect(new RectF(x-dp(11), y-dp(11), x+dp(11), y+dp(11)), dp(4), dp(4), paint); else c.drawCircle(x, y, dp(12), paint); }
        private float dp(float v) { return v * density; }

        @Override public boolean onTouchEvent(MotionEvent event) {
            if (event.getAction() == MotionEvent.ACTION_DOWN) {
                touchDownX = event.getX(); touchDownY = event.getY() - topInset; lastTouchY = touchDownY; touchDownAt = SystemClock.uptimeMillis(); touchDownTrack = -1; scrolling = false; timelineDragging = false; timelineDragMode = 0;
                if (!padsMode) {
                    float contentH = Math.max(dp(1), getHeight() - topInset - bottomInset);
                    if (timelineMode) {
                        float top = dp(230), rowHeight = dp(82);
                        if (touchDownY >= top && touchDownY < Math.max(top, contentH - dp(190))) {
                            touchDownTrack = (int) ((touchDownY - top + trackScrollOffset) / rowHeight);
                            if (touchDownTrack >= 0 && touchDownTrack < engine.snapshotTracks().size()) {
                                float left = timelineBlockLeft(touchDownTrack, getWidth()), right = timelineBlockRight(touchDownTrack, getWidth());
                                if (event.getX() >= left - dp(12) && event.getX() <= right + dp(12)) {
                                    timelineDragMode = event.getX() - left <= dp(14) ? 2 : event.getX() - right >= -dp(14) ? 3 : 1;
                                } else { touchDownTrack = -1; timelineDragMode = 0; }
                            }
                        }
                    } else if (touchDownY >= dp(168) && touchDownY < Math.max(dp(168), contentH - dp(190))) {
                        touchDownTrack = (int) ((touchDownY - dp(168) + trackScrollOffset) / dp(94));
                    }
                }
                return true;
            }
            if (event.getAction() == MotionEvent.ACTION_MOVE) {
                float currentX = event.getX(), currentY = event.getY() - topInset, deltaX = currentX - touchDownX, deltaY = currentY - touchDownY;
                if (timelineMode && touchDownTrack >= 0 && timelineDragMode > 0 && Math.abs(deltaX) > dp(8) && Math.abs(deltaX) >= Math.abs(deltaY)) {
                    timelineDragging = true;
                    int length = Math.max(1, engine.getLoopLength()), start = engine.trackStart(touchDownTrack), end = engine.trackEnd(touchDownTrack), delta = Math.round(deltaX * length / timelineWidth(getWidth()));
                    if (timelineDragMode == 1) { int range = end - start; int moved = Math.max(0, Math.min(length - range, start + delta)); setTrackRange(touchDownTrack, moved, moved + range); }
                    else if (timelineDragMode == 2) setTrackRange(touchDownTrack, Math.max(0, Math.min(end - 1, start + delta)), end);
                    else setTrackRange(touchDownTrack, start, Math.max(start + 1, Math.min(length, end + delta)));
                    invalidate(); return true;
                }
                if (!padsMode && Math.abs(currentY - touchDownY) > dp(8)) { scrolling = true; trackScrollOffset = Math.max(0, Math.min(maxTrackScroll, trackScrollOffset - (currentY - lastTouchY))); lastTouchY = currentY; invalidate(); }
                return true;
            }
            if (event.getAction() != MotionEvent.ACTION_UP) return true;
            float x = event.getX(), y = event.getY() - topInset, w = getWidth(), h = Math.max(dp(1), getHeight() - topInset - bottomInset);
            if (scrolling) { scrolling = false; return true; }
            if (timelineDragging) { timelineDragging = false; timelineDragMode = 0; touchDownTrack = -1; invalidate(); return true; }
            if (!padsMode && touchDownTrack >= 0 && SystemClock.uptimeMillis() - touchDownAt >= 550 && Math.abs(y - touchDownY) < dp(16)) { ((MainActivity) getContext()).showTrackMenu(touchDownTrack); touchDownTrack = -1; timelineDragMode = 0; return true; }
            if (!padsMode && y < dp(70) && x >= w - dp(164) && x < w - dp(80)) { ((MainActivity) getContext()).showTempoDialog(); return true; }
            if (y < dp(70) && x > w - dp(80)) { light = !light; prefs.edit().putBoolean("light_theme", light).apply(); invalidate(); return true; }
            if (!padsMode && y >= dp(40) && y < dp(70) && x < dp(245)) { ((MainActivity) getContext()).showRenameProject(); return true; }
            if (y >= dp(70) && y < dp(110)) { if (x >= w - dp(220) && x < w - dp(120)) ((MainActivity) getContext()).showHelp(); else if (x >= w - dp(120)) ((MainActivity) getContext()).showAbout(); return true; }
            if (y >= dp(110) && y < dp(154)) {
                float gap = dp(7), left = dp(20), tabW = (w - dp(40) - gap * 2) / 3f;
                if (x < left + tabW) { padsMode = false; timelineMode = false; }
                else if (x < left + tabW * 2 + gap) { padsMode = false; timelineMode = true; }
                else { padsMode = true; timelineMode = false; }
                configuring = false; trackScrollOffset = 0; invalidate(); return true;
            }
            if (y >= h - dp(110) && y < h - dp(66)) {
                if (!padsMode) {
                    float gap = dp(8), left = dp(20), buttonW = (w - dp(40) - gap * 3) / 4f;
                    if (x >= left && x < left + buttonW) ((MainActivity) getContext()).showDemoChooser();
                    else if (x < left + buttonW + gap + buttonW) ((MainActivity) getContext()).saveProject();
                    else if (x < left + buttonW * 3 + gap * 2) ((MainActivity) getContext()).showExportChooser();
                    else ((MainActivity) getContext()).exitApp();
                } else if (x >= w - dp(236) && x < w - dp(132)) ((MainActivity) getContext()).saveProject();
                else if (x >= w - dp(124)) ((MainActivity) getContext()).exitApp();
                return true;
            }
            if (padsMode) {
                if (y >= dp(150) && y < dp(200) && x < dp(190)) { pads.nextPreset(); configuring = false; invalidate(); return true; }
                if (y >= dp(150) && y < dp(200) && x > w - dp(155)) { configuring = !configuring; invalidate(); return true; }
                if (configuring && y >= dp(210) && y < dp(260)) { if (x < dp(100)) padDisplayMode = 0; else if (x < dp(182)) padDisplayMode = 1; else if (x < dp(270)) padDisplayMode = 2; prefs.edit().putInt("pad_display_mode", padDisplayMode).apply(); invalidate(); return true; }
                float gap = dp(12), left = dp(20), cellW = (w - dp(40) - gap) / 2f, gridTop = configuring ? dp(264) : dp(228), cellH = padCellHeight(h, gridTop, gap);
                if (y >= gridTop && y < gridTop + 4 * (cellH + gap)) { int col = (int) ((x - left) / (cellW + gap)), row = (int) ((y - gridTop) / (cellH + gap)); if (col >= 0 && col < 2 && row >= 0 && row < 4) { int pad = row * 2 + col; if (configuring) ((MainActivity) getContext()).showPadChooser(pad); else { pads.playPad(pad); lastPad = pad; lastPadAt = System.currentTimeMillis(); } invalidate(); } }
                return true;
            }
            if (timelineMode) return true;
            if (y > h - dp(190) && y < h - dp(70)) { if (Math.abs(x - w / 2) < dp(70) || x > w - dp(130)) { ((MainActivity) getContext()).ensureMicPermission(); return true; } if (x < dp(125)) { engine.clearLastTrack(); invalidate(); return true; } }
            if (y >= dp(168) && y < h - dp(190)) { int index = (int) ((y - dp(168) + trackScrollOffset) / dp(94)); engine.toggleMute(index); invalidate(); }
            return true;
        }
    }
}

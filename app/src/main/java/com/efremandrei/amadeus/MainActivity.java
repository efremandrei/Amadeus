package com.efremandrei.amadeus;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Bundle;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Toast;

import java.util.List;

public class MainActivity extends Activity {
    private static final int MIC_REQUEST = 42;
    private LoopStationView loopView;

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(Color.rgb(16, 16, 22)); getWindow().setNavigationBarColor(Color.rgb(16, 16, 22));
        loopView = new LoopStationView(this); setContentView(loopView);
    }

    void ensureMicPermission() {
        if (android.os.Build.VERSION.SDK_INT >= 23 && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, MIC_REQUEST);
        else loopView.toggleRecording();
    }

    void showPadChooser(final int pad) {
        new AlertDialog.Builder(this).setTitle("Assign pad " + (pad + 1)).setItems(SoundPadEngine.LIBRARY, (dialog, which) -> loopView.assignPad(pad, SoundPadEngine.LIBRARY[which])).show();
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == MIC_REQUEST && grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) loopView.toggleRecording();
        else Toast.makeText(this, "Microphone access is needed to record loops", Toast.LENGTH_SHORT).show();
    }

    @Override protected void onDestroy() { if (loopView != null) loopView.release(); super.onDestroy(); }

    private static final class LoopStationView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final LoopEngine engine;
        private final SoundPadEngine pads;
        private final SharedPreferences prefs;
        private boolean light, padsMode, configuring;
        private float density;
        private int lastPad = -1;
        private long lastPadAt;
        private final int[] trackColors = { Color.rgb(167, 139, 250), Color.rgb(45, 212, 191), Color.rgb(251, 146, 60), Color.rgb(244, 114, 182), Color.rgb(96, 165, 250), Color.rgb(163, 230, 53) };
        private final int[] padColors = { Color.rgb(167, 139, 250), Color.rgb(45, 212, 191), Color.rgb(251, 146, 60), Color.rgb(244, 114, 182), Color.rgb(96, 165, 250), Color.rgb(163, 230, 53), Color.rgb(251, 191, 36), Color.rgb(129, 140, 248) };

        LoopStationView(Context context) {
            super(context); density = getResources().getDisplayMetrics().density; engine = new LoopEngine(context); pads = new SoundPadEngine(context);
            prefs = context.getSharedPreferences("amadeus", Context.MODE_PRIVATE); light = prefs.getBoolean("light_theme", false); setFocusable(true);
        }

        void toggleRecording() { if (engine.getState() == LoopEngine.State.RECORDING) engine.stopRecording(); else engine.startRecording(); invalidate(); }
        void assignPad(int pad, String sound) { pads.assignPad(pad, sound); configuring = false; invalidate(); }
        void release() { engine.release(); }

        @Override protected void onDraw(Canvas c) {
            super.onDraw(c); int bg = light ? Color.rgb(247, 246, 251) : Color.rgb(16, 16, 22), surface = light ? Color.WHITE : Color.rgb(26, 26, 36), text = light ? Color.rgb(35, 35, 45) : Color.rgb(245, 243, 250), secondary = light ? Color.rgb(104, 101, 116) : Color.rgb(165, 160, 179);
            c.drawColor(bg); float w = getWidth(), h = getHeight();
            paint.setColor(text); paint.setTextSize(dp(26)); paint.setTypeface(android.graphics.Typeface.DEFAULT_BOLD); c.drawText("Amadeus", dp(24), dp(42), paint);
            paint.setTypeface(android.graphics.Typeface.DEFAULT); paint.setTextSize(dp(12)); paint.setColor(secondary); c.drawText(padsMode ? "SOUND PADS" : "LOOP SESSION 01", dp(25), dp(64), paint);
            drawModeTabs(c, surface, text); paint.setColor(text); paint.setTextSize(dp(22)); c.drawText(light ? "☀" : "☾", w - dp(55), dp(42), paint);
            if (padsMode) drawPads(c, w, h, surface, text, secondary); else drawLoops(c, w, h, surface, text, secondary);
            postInvalidateDelayed(80);
        }

        private void drawModeTabs(Canvas c, int surface, int text) { float y = dp(76); drawTab(c, dp(20), y, dp(111), y + dp(32), !padsMode, surface, text, "LOOPS"); drawTab(c, dp(119), y, dp(230), y + dp(32), padsMode, surface, text, "SOUND PADS"); }

        private void drawLoops(Canvas c, float w, float h, int surface, int text, int secondary) {
            drawPill(c, w - dp(164), dp(22), w - dp(80), dp(54), surface, "96 BPM"); List<LoopEngine.Track> tracks = engine.snapshotTracks(); float top = dp(128), rowHeight = dp(94);
            if (tracks.isEmpty()) { paint.setColor(surface); c.drawRoundRect(new RectF(dp(20), top, w - dp(20), top + dp(174)), dp(22), dp(22), paint); paint.setColor(Color.rgb(167, 139, 250)); paint.setTextSize(dp(44)); paint.setTypeface(android.graphics.Typeface.DEFAULT_BOLD); c.drawText("◉", dp(42), top + dp(73), paint); paint.setColor(text); paint.setTextSize(dp(21)); c.drawText("Start with a sound", dp(100), top + dp(55), paint); paint.setColor(secondary); paint.setTextSize(dp(14)); c.drawText("Tap Record, make a loop, then layer it.", dp(100), top + dp(83), paint); c.drawText("Your first loop sets the musical grid.", dp(100), top + dp(106), paint); }
            else for (int i = 0; i < tracks.size(); i++) drawTrack(c, tracks.get(i), i, top + i * rowHeight, w, surface, text, secondary);
            float controlsTop = h - dp(150); paint.setColor(secondary); paint.setTextSize(dp(12)); c.drawText(engine.getState() == LoopEngine.State.RECORDING ? "RECORDING — tap to close loop" : tracks.isEmpty() ? "READY TO RECORD" : engine.getState() == LoopEngine.State.PLAYING ? "PLAYING — tap to add a layer" : "READY — tap to add a layer", dp(24), controlsTop - dp(18), paint);
            drawButton(c, dp(22), controlsTop, dp(92), controlsTop + dp(54), surface, "↶", "UNDO", text); drawRecord(c, w / 2, controlsTop + dp(29), engine.getState() == LoopEngine.State.RECORDING); drawButton(c, w - dp(114), controlsTop, w - dp(22), controlsTop + dp(54), surface, "+", "ADD LOOP", text);
            paint.setColor(secondary); paint.setTextSize(dp(12)); c.drawText("MIC INPUT", dp(24), h - dp(48), paint); paint.setColor(Color.rgb(65, 60, 80)); c.drawRoundRect(new RectF(dp(24), h - dp(36), w - dp(24), h - dp(28)), dp(4), dp(4), paint); paint.setColor(Color.rgb(167, 139, 250)); float levelWidth = (w - dp(48)) * Math.min(1f, engine.getInputLevel() * 2.5f); c.drawRoundRect(new RectF(dp(24), h - dp(36), dp(24) + levelWidth, h - dp(28)), dp(4), dp(4), paint);
        }

        private void drawPads(Canvas c, float w, float h, int surface, int text, int secondary) {
            float top = dp(126); drawPill(c, dp(20), dp(116), dp(178), dp(150), surface, pads.getActivePresetName()); drawPill(c, w - dp(136), dp(116), w - dp(20), dp(150), configuring ? Color.rgb(167, 139, 250) : surface, configuring ? "TAP A PAD" : "CONFIGURE");
            paint.setColor(secondary); paint.setTextSize(dp(12)); c.drawText(configuring ? "Choose a pad to assign a different sound" : "Tap a sound to play it instantly", dp(24), dp(174), paint);
            float gap = dp(12), left = dp(20), cellW = (w - dp(40) - gap) / 2f, cellH = dp(86);
            for (int i = 0; i < SoundPadEngine.PAD_COUNT; i++) { int col = i % 2, row = i / 2; float x = left + col * (cellW + gap), y = top + dp(62) + row * (cellH + gap); boolean active = i == lastPad && System.currentTimeMillis() - lastPadAt < 220; drawPad(c, x, y, cellW, cellH, i, pads.getSoundForPad(i), active ? Color.WHITE : surface, text, secondary); }
            paint.setColor(secondary); paint.setTextSize(dp(12)); c.drawText("PRESET BANK  " + (pads.getActivePreset() + 1) + " / " + SoundPadEngine.PRESETS.length + "  •  tap the bank name to switch", dp(24), h - dp(34), paint);
        }

        private void drawPad(Canvas c, float x, float y, float width, float height, int index, String label, int surface, int text, int secondary) { paint.setColor(surface); c.drawRoundRect(new RectF(x, y, x + width, y + height), dp(20), dp(20), paint); paint.setColor(padColors[index]); c.drawCircle(x + dp(29), y + dp(30), dp(13), paint); paint.setColor(Color.WHITE); paint.setTextSize(dp(11)); paint.setTypeface(android.graphics.Typeface.DEFAULT_BOLD); c.drawText("" + (index + 1), x + dp(25), y + dp(34), paint); paint.setColor(text); paint.setTextSize(dp(17)); c.drawText(label, x + dp(54), y + dp(35), paint); paint.setColor(secondary); paint.setTypeface(android.graphics.Typeface.DEFAULT); paint.setTextSize(dp(10)); c.drawText(configuring ? "TAP TO ASSIGN" : "PLAY ONE-SHOT", x + dp(54), y + dp(56), paint); }

        private void drawTrack(Canvas c, LoopEngine.Track track, int index, float y, float w, int surface, int text, int secondary) { paint.setColor(surface); c.drawRoundRect(new RectF(dp(20), y, w - dp(20), y + dp(80)), dp(18), dp(18), paint); int accent = trackColors[track.colorIndex % trackColors.length]; paint.setColor(accent); c.drawRoundRect(new RectF(dp(20), y, dp(26), y + dp(80)), dp(3), dp(3), paint); paint.setColor(text); paint.setTypeface(android.graphics.Typeface.DEFAULT_BOLD); paint.setTextSize(dp(14)); c.drawText(track.name, dp(38), y + dp(27), paint); paint.setTypeface(android.graphics.Typeface.DEFAULT); paint.setColor(secondary); paint.setTextSize(dp(11)); c.drawText(track.muted ? "MUTED" : "LOOPING", dp(38), y + dp(49), paint); paint.setColor(track.muted ? Color.rgb(80, 77, 92) : accent); float waveStart = dp(124), waveWidth = w - dp(230), center = y + dp(40); for (int i = 0; i < 22; i++) { float x = waveStart + waveWidth * i / 22f, amp = dp(8 + ((i * 17 + index * 11) % 18)); c.drawRoundRect(new RectF(x, center - amp, x + dp(3), center + amp), dp(2), dp(2), paint); } paint.setColor(track.muted ? Color.rgb(70, 68, 80) : Color.rgb(57, 53, 70)); c.drawRoundRect(new RectF(w - dp(90), y + dp(25), w - dp(38), y + dp(34)), dp(4), dp(4), paint); paint.setColor(track.muted ? secondary : accent); c.drawCircle(w - dp(64), y + dp(29), dp(8), paint); }
        private void drawTab(Canvas c, float l, float t, float r, float b, boolean selected, int surface, int text, String label) { paint.setColor(selected ? Color.rgb(167, 139, 250) : surface); c.drawRoundRect(new RectF(l, t, r, b), dp(16), dp(16), paint); paint.setColor(selected ? Color.WHITE : text); paint.setTextSize(dp(10)); c.drawText(label, l + dp(13), t + dp(20), paint); }
        private void drawPill(Canvas c, float l, float t, float r, float b, int color, String label) { paint.setColor(color); c.drawRoundRect(new RectF(l, t, r, b), dp(18), dp(18), paint); paint.setColor(Color.rgb(188, 180, 210)); paint.setTextSize(dp(12)); c.drawText(label, l + dp(16), t + dp(21), paint); }
        private void drawButton(Canvas c, float l, float t, float r, float b, int color, String icon, String label, int text) { paint.setColor(color); c.drawRoundRect(new RectF(l, t, r, b), dp(16), dp(16), paint); paint.setColor(text); paint.setTextSize(dp(21)); c.drawText(icon, l + (r-l)/2 - dp(8), t + dp(25), paint); paint.setTextSize(dp(9)); paint.setColor(Color.rgb(170, 164, 185)); c.drawText(label, l + dp(13), t + dp(45), paint); }
        private void drawRecord(Canvas c, float x, float y, boolean recording) { paint.setColor(recording ? Color.rgb(248, 113, 113) : Color.rgb(167, 139, 250)); c.drawCircle(x, y, dp(37), paint); paint.setColor(Color.WHITE); if (recording) c.drawRoundRect(new RectF(x-dp(11), y-dp(11), x+dp(11), y+dp(11)), dp(4), dp(4), paint); else c.drawCircle(x, y, dp(12), paint); }
        private float dp(float v) { return v * density; }

        @Override public boolean onTouchEvent(MotionEvent event) {
            if (event.getAction() != MotionEvent.ACTION_UP) return true;
            float x = event.getX(), y = event.getY(), w = getWidth(), h = getHeight();
            if (y < dp(70) && x > w - dp(80)) { light = !light; prefs.edit().putBoolean("light_theme", light).apply(); invalidate(); return true; }
            if (y >= dp(70) && y < dp(114)) { if (x < dp(115)) padsMode = false; else if (x < dp(240)) padsMode = true; configuring = false; invalidate(); return true; }
            if (padsMode) {
                if (y >= dp(110) && y < dp(160) && x < dp(190)) { pads.nextPreset(); configuring = false; invalidate(); return true; }
                if (y >= dp(110) && y < dp(160) && x > w - dp(155)) { configuring = !configuring; invalidate(); return true; }
                float gap = dp(12), left = dp(20), cellW = (w - dp(40) - gap) / 2f, cellH = dp(86), gridTop = dp(188);
                if (y >= gridTop && y < gridTop + 4 * (cellH + gap)) { int col = (int) ((x - left) / (cellW + gap)), row = (int) ((y - gridTop) / (cellH + gap)); if (col >= 0 && col < 2 && row >= 0 && row < 4) { int pad = row * 2 + col; if (configuring) ((MainActivity) getContext()).showPadChooser(pad); else { pads.playPad(pad); lastPad = pad; lastPadAt = System.currentTimeMillis(); } invalidate(); } }
                return true;
            }
            if (y > h - dp(190) && y < h - dp(70)) { if (Math.abs(x - w / 2) < dp(70) || x > w - dp(130)) { ((MainActivity) getContext()).ensureMicPermission(); return true; } if (x < dp(125)) { engine.clearLastTrack(); invalidate(); return true; } }
            if (y >= dp(128) && y < h - dp(190)) { int index = (int) ((y - dp(128)) / dp(94)); engine.toggleMute(index); invalidate(); }
            return true;
        }
    }
}

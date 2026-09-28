package com.efremandrei.amadeus;

import android.media.AudioManager;
import android.media.ToneGenerator;
import android.os.Handler;
import android.os.Looper;

/** Lightweight beat click used by the tempo control without adding an audio dependency. */
public final class MetronomeEngine {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ToneGenerator tone;
    private final Runnable beatTask = this::playBeat;
    private int bpm = 96;
    private int beat;
    private boolean enabled;

    public MetronomeEngine() {
        ToneGenerator created;
        try { created = new ToneGenerator(AudioManager.STREAM_MUSIC, 72); }
        catch (Exception ignored) { created = null; }
        tone = created;
    }

    public void setTempo(int value) {
        bpm = Math.max(40, Math.min(220, value));
        if (enabled) { handler.removeCallbacks(beatTask); handler.post(beatTask); }
    }

    public void setEnabled(boolean value) {
        enabled = value;
        handler.removeCallbacks(beatTask);
        if (enabled) { beat = 0; handler.post(beatTask); }
    }

    private void playBeat() {
        if (!enabled) return;
        if (tone != null) tone.startTone(beat++ % 4 == 0 ? ToneGenerator.TONE_PROP_BEEP2 : ToneGenerator.TONE_PROP_BEEP, 55);
        handler.postDelayed(beatTask, Math.max(100, 60000L / bpm));
    }

    public void release() {
        enabled = false;
        handler.removeCallbacks(beatTask);
        if (tone != null) tone.release();
    }
}

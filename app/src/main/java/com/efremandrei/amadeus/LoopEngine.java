package com.efremandrei.amadeus;

import android.content.Context;
import android.content.SharedPreferences;
import android.Manifest;
import android.content.pm.PackageManager;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.os.Process;

import java.util.ArrayList;
import java.util.Collections;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.List;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.FileInputStream;
import java.io.FileOutputStream;

/** Small, dependency-free loop engine for the first Amadeus slice. */
public final class LoopEngine {
    public enum State { IDLE, RECORDING, PLAYING }

    public static final int SAMPLE_RATE = 44100;
    private static final int MAGIC = 0x414D4446;
    private static final int LOOPING_MAGIC = 0x414D4445;
    private static final int AUTOMATION_MAGIC = 0x414D4444;
    private static final int TIMELINE_MAGIC = 0x414D4443;
    private static final int EFFECTS_MAGIC = 0x414D4442;
    private static final int LEGACY_MAGIC = 0x414D4441;
    private static final int HISTORY_MAGIC = 0x414D484A;
    private static final int MAX_HISTORY_SNAPSHOTS = 10;
    private static final int MAX_HISTORY_PCM_SAMPLES = 12 * 1024 * 1024;
    private static final int MAX_TRACKS = 64;
    private final Object lock = new Object();
    private final List<Track> tracks = new ArrayList<>();
    private final Deque<EditorState> undoStack = new ArrayDeque<>();
    private final Deque<EditorState> redoStack = new ArrayDeque<>();
    private volatile State state = State.IDLE;
    private volatile float inputLevel;
    private volatile int playbackPosition;
    private volatile boolean playbackRequested;
    private volatile boolean recordingRequested;
    private AudioRecord recorder;
    private Thread recordThread;
    private Thread playbackThread;
    private final List<Short> capture = Collections.synchronizedList(new ArrayList<Short>());
    private int loopLength;
    private int arrangementLength;
    private int arrangementBars = 4;
    private final Context appContext;
    private final SharedPreferences prefs;
    private boolean demoMode;
    private String demoName = "";
    private String projectName = "LOOP SESSION 01";
    private int soloIndex = -1;
    private int tempoBpm = 96;

    public static final class Track {
        public final String name;
        public final int colorIndex;
        public final short[] pcm;
        public boolean muted;
        public boolean looping = true;
        public float volume = 1f;
        public float volumeEnd = 1f;
        public float pan;
        public float panEnd;
        public float reverb;
        public float reverbEnd;
        public float delay;
        public float delayEnd;
        public final float[] volumeCurve = {1f, 1f, 1f, 1f};
        public final float[] panCurve = {0f, 0f, 0f, 0f};
        public final float[] reverbCurve = {0f, 0f, 0f, 0f};
        public final float[] delayCurve = {0f, 0f, 0f, 0f};
        public int startSample;
        public int endSample;

        Track(String name, int colorIndex, short[] pcm) {
            this.name = name;
            this.colorIndex = colorIndex;
            this.pcm = pcm;
            this.endSample = pcm.length;
        }
    }

    private static final class EditorState {
        final int loopLength;
        final int arrangementLength;
        final int arrangementBars;
        final int soloIndex;
        final List<Track> tracks;
        EditorState(int loopLength, int arrangementLength, int arrangementBars, int soloIndex, List<Track> tracks) { this.loopLength = loopLength; this.arrangementLength = arrangementLength; this.arrangementBars = arrangementBars; this.soloIndex = soloIndex; this.tracks = tracks; }
    }

    public LoopEngine(Context context) {
        appContext = context.getApplicationContext();
        prefs = appContext.getSharedPreferences("amadeus", Context.MODE_PRIVATE);
        projectName = prefs.getString("project_name", "LOOP SESSION 01");
        loadSession();
        loadHistory();
    }

    public State getState() { return state; }
    public float getInputLevel() { return inputLevel; }
    public int getLoopLength() { return loopLength; }
    public int getArrangementLength() { synchronized (lock) { return arrangementLength > 0 ? arrangementLength : loopLength; } }
    public int getArrangementBars() { synchronized (lock) { return Math.max(1, arrangementBars); } }
    public int getPlaybackPosition() { return playbackPosition; }
    public boolean isDemoMode() { return demoMode; }
    public String getDemoName() { return demoName; }
    public void setTempoBpm(int bpm) { tempoBpm = Math.max(40, Math.min(220, bpm)); }
    public String getProjectName() { synchronized (lock) { return projectName; } }

    public void setProjectName(String name) {
        String clean = name == null ? "" : name.trim();
        if (clean.length() == 0) clean = "LOOP SESSION 01";
        if (clean.length() > 30) clean = clean.substring(0, 30);
        synchronized (lock) { projectName = clean; }
        prefs.edit().putString("project_name", clean).apply();
    }

    public List<Track> snapshotTracks() {
        synchronized (lock) { return new ArrayList<>(tracks); }
    }

    public boolean hasTracks() { synchronized (lock) { return !tracks.isEmpty(); } }

    public void loadDemo(DemoLibrary.Demo demo) {
        synchronized (lock) {
            tracks.clear(); undoStack.clear(); redoStack.clear(); loopLength = demo.loopLength; arrangementBars = 4; arrangementLength = loopLength * arrangementBars; soloIndex = -1; demoMode = true; demoName = demo.name;
            for (DemoLibrary.Layer layer : demo.layers) { Track track = new Track(layer.name, layer.colorIndex, layer.pcm); track.endSample = arrangementLength; tracks.add(track); }
            state = State.PLAYING;
        }
        ensurePlayback();
    }

    public void setArrangementBars(int bars) {
        synchronized (lock) { if (loopLength <= 0) return; pushUndoLocked(); int oldLength = arrangementLength; arrangementBars = Math.max(1, Math.min(16, bars)); arrangementLength = loopLength * arrangementBars; for (Track track : tracks) { if (track.looping && track.endSample >= oldLength) track.endSample = arrangementLength; track.startSample = Math.max(0, Math.min(arrangementLength - 1, track.startSample)); track.endSample = Math.max(track.startSample + 1, Math.min(arrangementLength, track.endSample)); } }
        saveSession();
    }

    public short[] mixedLoop() {
        synchronized (lock) {
            int length = arrangementLength > 0 ? arrangementLength : loopLength;
            if (length <= 0 || tracks.isEmpty()) return new short[0];
            short[] mix = new short[length];
            for (int i = 0; i < length; i++) { float sum = 0f; for (int trackIndex = 0; trackIndex < tracks.size(); trackIndex++) { Track track = tracks.get(trackIndex); if (!track.muted && (soloIndex < 0 || soloIndex == trackIndex)) sum += sampleWithEffects(track, i); } mix[i] = (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, (int) sum)); }
            return mix;
        }
    }

    public short[] mixedLoopStereo() {
        synchronized (lock) {
            int length = arrangementLength > 0 ? arrangementLength : loopLength;
            if (length <= 0 || tracks.isEmpty()) return new short[0];
            short[] mix = new short[length * 2];
            for (int i = 0; i < length; i++) {
                float left = 0f, right = 0f;
                for (int trackIndex = 0; trackIndex < tracks.size(); trackIndex++) {
                    Track track = tracks.get(trackIndex);
                    if (track.muted || (soloIndex >= 0 && soloIndex != trackIndex)) continue;
                    float value = sampleWithEffects(track, i), pan = interpolatedPan(track, i);
                    left += value * (pan > 0f ? 1f - pan : 1f);
                    right += value * (pan < 0f ? 1f + pan : 1f);
                }
                mix[i * 2] = (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, Math.round(left)));
                mix[i * 2 + 1] = (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, Math.round(right)));
            }
            return mix;
        }
    }

    public float mixPeak(boolean stereo) {
        synchronized (lock) {
            int length = arrangementLength > 0 ? arrangementLength : loopLength; float peak = 0f;
            for (int i = 0; i < length; i++) {
                float left = 0f, right = 0f;
                for (int trackIndex = 0; trackIndex < tracks.size(); trackIndex++) { Track track = tracks.get(trackIndex); if (track.muted || (soloIndex >= 0 && soloIndex != trackIndex)) continue; float value = sampleWithEffects(track, i), pan = interpolatedPan(track, i); if (!stereo) left += value; else { left += value * (pan > 0f ? 1f - pan : 1f); right += value * (pan < 0f ? 1f + pan : 1f); } }
                peak = Math.max(peak, Math.abs(left)); if (stereo) peak = Math.max(peak, Math.abs(right));
            }
            return peak;
        }
    }

    public void startRecording() {
        if (state == State.RECORDING || tracks.size() >= MAX_TRACKS) return;
        capture.clear();
        recordingRequested = true;
        state = State.RECORDING;
        recordThread = new Thread(this::recordLoop, "Amadeus-record");
        recordThread.start();
        ensurePlayback();
    }

    public void stopRecording() {
        if (state != State.RECORDING) return;
        recordingRequested = false;
        try { if (recordThread != null) recordThread.join(700); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
        ArrayList<Short> copy = new ArrayList<>(capture);
        if (copy.isEmpty()) { state = hasTracks() ? State.PLAYING : State.IDLE; return; }
        short[] take = new short[copy.size()];
        for (int i = 0; i < copy.size(); i++) take[i] = copy.get(i);
        synchronized (lock) {
            pushUndoLocked();
            if (tracks.isEmpty()) {
                demoMode = false; demoName = "";
                soloIndex = -1;
                loopLength = quantizedLoopLength(take.length);
                arrangementBars = 4; arrangementLength = loopLength * arrangementBars;
                Track track = new Track("LOOP 1", 0, fitToLoop(take, loopLength)); track.endSample = arrangementLength; tracks.add(track);
            } else if (tracks.size() < MAX_TRACKS) {
                demoMode = false; demoName = "";
                soloIndex = -1;
                Track track = new Track("LOOP " + (tracks.size() + 1), tracks.size() % 6, fitToLoop(take, loopLength)); track.endSample = arrangementLength; tracks.add(track);
            }
        }
        state = State.PLAYING;
        ensurePlayback();
        saveSession();
    }

    public void addPadHit(String name, short[] pcm, int startSample) {
        if (pcm == null || pcm.length == 0) return;
        synchronized (lock) {
            if (tracks.size() >= MAX_TRACKS) return;
            if (loopLength <= 0) { loopLength = Math.max(1, (int) (SAMPLE_RATE * 60f / tempoBpm * 4)); arrangementBars = 4; arrangementLength = loopLength * arrangementBars; }
            pushUndoLocked();
            int start = Math.max(0, Math.min(Math.max(0, arrangementLength - 1), startSample));
            Track track = new Track(name == null ? "PAD" : name, tracks.size() % 6, pcm.clone()); track.looping = false; track.startSample = start; track.endSample = Math.min(arrangementLength, start + pcm.length);
            if (track.endSample <= start) return;
            tracks.add(track); demoMode = false; demoName = "";
        }
        state = State.PLAYING; ensurePlayback(); saveSession();
    }

    public void clearLastTrack() {
        synchronized (lock) {
            int removed = tracks.size() - 1;
            if (removed >= 0) { pushUndoLocked(); tracks.remove(removed); }
            if (soloIndex == removed) soloIndex = -1;
            else if (soloIndex > removed) soloIndex--;
            if (tracks.isEmpty()) { demoMode = false; demoName = ""; }
            if (tracks.isEmpty()) { loopLength = 0; arrangementLength = 0; playbackRequested = false; state = State.IDLE; }
        }
        saveSession();
    }

    public void toggleMute(int index) {
        synchronized (lock) { if (index >= 0 && index < tracks.size()) { pushUndoLocked(); tracks.get(index).muted = !tracks.get(index).muted; } }
        saveSession();
    }

    public boolean isTrackMuted(int index) { synchronized (lock) { return index >= 0 && index < tracks.size() && tracks.get(index).muted; } }

    public float trackVolume(int index) { synchronized (lock) { return index >= 0 && index < tracks.size() ? tracks.get(index).volume : 1f; } }
    public float trackVolumeEnd(int index) { synchronized (lock) { return index >= 0 && index < tracks.size() ? tracks.get(index).volumeEnd : 1f; } }

    public void setTrackVolume(int index, float volume) {
        synchronized (lock) { if (index >= 0 && index < tracks.size()) { pushUndoLocked(); Track track = tracks.get(index); track.volume = clamp01(volume); track.volumeEnd = track.volume; fillCurve(track.volumeCurve, track.volume); } }
        saveSession();
    }

    public float trackPan(int index) { synchronized (lock) { return index >= 0 && index < tracks.size() ? tracks.get(index).pan : 0f; } }
    public float trackPanEnd(int index) { synchronized (lock) { return index >= 0 && index < tracks.size() ? tracks.get(index).panEnd : 0f; } }
    public void setTrackPan(int index, float pan) { synchronized (lock) { if (index >= 0 && index < tracks.size()) { pushUndoLocked(); Track track = tracks.get(index); track.pan = clampPan(pan); track.panEnd = track.pan; fillCurve(track.panCurve, track.pan); } } saveSession(); }

    public float trackReverb(int index) { synchronized (lock) { return index >= 0 && index < tracks.size() ? tracks.get(index).reverb : 0f; } }

    public float trackDelay(int index) { synchronized (lock) { return index >= 0 && index < tracks.size() ? tracks.get(index).delay : 0f; } }
    public float trackReverbEnd(int index) { synchronized (lock) { return index >= 0 && index < tracks.size() ? tracks.get(index).reverbEnd : 0f; } }
    public float trackDelayEnd(int index) { synchronized (lock) { return index >= 0 && index < tracks.size() ? tracks.get(index).delayEnd : 0f; } }

    public void setTrackEffects(int index, float reverb, float delay) {
        synchronized (lock) { if (index >= 0 && index < tracks.size()) { pushUndoLocked(); Track track = tracks.get(index); track.reverb = clamp01(reverb); track.reverbEnd = track.reverb; track.delay = clamp01(delay); track.delayEnd = track.delay; fillCurve(track.reverbCurve, track.reverb); fillCurve(track.delayCurve, track.delay); } }
        saveSession();
    }

    public void setTrackAutomation(int index, float volumeStart, float volumeEnd, float panStart, float panEnd, float reverbStart, float reverbEnd, float delayStart, float delayEnd) {
        synchronized (lock) {
            if (index < 0 || index >= tracks.size()) return;
            pushUndoLocked(); Track track = tracks.get(index);
            track.volume = clamp01(volumeStart); track.volumeEnd = clamp01(volumeEnd); track.pan = clampPan(panStart); track.panEnd = clampPan(panEnd); track.reverb = clamp01(reverbStart); track.reverbEnd = clamp01(reverbEnd); track.delay = clamp01(delayStart); track.delayEnd = clamp01(delayEnd);
            linearCurve(track.volumeCurve, track.volume, track.volumeEnd); linearCurve(track.panCurve, track.pan, track.panEnd); linearCurve(track.reverbCurve, track.reverb, track.reverbEnd); linearCurve(track.delayCurve, track.delay, track.delayEnd);
        }
        saveSession();
    }

    public void setAutomationPointTransient(int index, int lane, int point, float value) {
        synchronized (lock) {
            if (index < 0 || index >= tracks.size()) return;
            Track track = tracks.get(index); float safe = lane == 1 ? clampPan(value) : clamp01(value);
            if (point < 0 || point > 3) return;
            float[] curve = lane == 0 ? track.volumeCurve : lane == 1 ? track.panCurve : lane == 2 ? track.reverbCurve : lane == 3 ? track.delayCurve : null; if (curve == null) return;
            curve[point] = safe;
            if (lane == 0) { track.volume = curve[0]; track.volumeEnd = curve[3]; } else if (lane == 1) { track.pan = curve[0]; track.panEnd = curve[3]; } else if (lane == 2) { track.reverb = curve[0]; track.reverbEnd = curve[3]; } else { track.delay = curve[0]; track.delayEnd = curve[3]; }
        }
    }

    public int trackStart(int index) { synchronized (lock) { return index >= 0 && index < tracks.size() ? tracks.get(index).startSample : 0; } }

    public int trackEnd(int index) { synchronized (lock) { return index >= 0 && index < tracks.size() ? tracks.get(index).endSample : arrangementLength; } }

    public void setTrackRange(int index, int start, int end) {
        synchronized (lock) {
            if (index < 0 || index >= tracks.size()) return;
            pushUndoLocked();
            int max = Math.max(1, arrangementLength > 0 ? arrangementLength : loopLength);
            int safeStart = Math.max(0, Math.min(max - 1, start));
            int safeEnd = Math.max(safeStart + 1, Math.min(max, end));
            tracks.get(index).startSample = safeStart; tracks.get(index).endSample = safeEnd;
        }
        saveSession();
    }

    public void resetTrackRange(int index) { setTrackRange(index, 0, arrangementLength > 0 ? arrangementLength : loopLength); }

    public void beginTimelineEdit() { synchronized (lock) { pushUndoLocked(); } }
    public void setTrackRangeTransient(int index, int start, int end) {
        synchronized (lock) {
            if (index < 0 || index >= tracks.size()) return;
            int max = Math.max(1, arrangementLength > 0 ? arrangementLength : loopLength); int safeStart = Math.max(0, Math.min(max - 1, start)); int safeEnd = Math.max(safeStart + 1, Math.min(max, end));
            tracks.get(index).startSample = safeStart; tracks.get(index).endSample = safeEnd;
        }
    }
    public void finishTimelineEdit() { saveSession(); }

    public boolean duplicateTrack(int index) {
        synchronized (lock) {
            if (index < 0 || index >= tracks.size() || tracks.size() >= MAX_TRACKS) return false;
            pushUndoLocked();
            Track source = tracks.get(index); Track copy = new Track(source.name + " COPY", source.colorIndex + 1, source.pcm.clone());
            copy.muted = source.muted; copy.looping = source.looping; copy.volume = source.volume; copy.volumeEnd = source.volumeEnd; copy.pan = source.pan; copy.panEnd = source.panEnd; copy.reverb = source.reverb; copy.reverbEnd = source.reverbEnd; copy.delay = source.delay; copy.delayEnd = source.delayEnd; copy.startSample = source.startSample; copy.endSample = source.endSample; copyCurves(source, copy);
            tracks.add(index + 1, copy);
        }
        saveSession(); return true;
    }

    public void moveTrack(int index, int direction) {
        synchronized (lock) { int target = index + direction; if (index < 0 || index >= tracks.size() || target < 0 || target >= tracks.size()) return; pushUndoLocked(); Track track = tracks.remove(index); tracks.add(target, track); if (soloIndex == index) soloIndex = target; else if (soloIndex == target) soloIndex = index; }
        saveSession();
    }

    public boolean isTrackSolo(int index) { synchronized (lock) { return soloIndex == index; } }

    public void toggleSolo(int index) {
        synchronized (lock) { if (index >= 0 && index < tracks.size()) { pushUndoLocked(); soloIndex = soloIndex == index ? -1 : index; } }
        saveSession();
    }

    public String trackName(int index) {
        synchronized (lock) { return index >= 0 && index < tracks.size() ? tracks.get(index).name : null; }
    }

    public void deleteTrack(int index) {
        boolean wasDemo;
        synchronized (lock) {
            if (index < 0 || index >= tracks.size()) return;
            pushUndoLocked();
            wasDemo = demoMode;
            tracks.remove(index);
            if (soloIndex == index) soloIndex = -1;
            else if (soloIndex > index) soloIndex--;
            if (tracks.isEmpty()) {
                loopLength = 0;
                arrangementLength = 0;
                playbackRequested = false;
                state = State.IDLE;
                demoMode = false;
                demoName = "";
            }
        }
        if (!wasDemo) saveSession();
    }

    /** Persists the current project state inside the app without creating an audio export. */
    public void saveProject() {
        synchronized (lock) { demoMode = false; demoName = ""; }
        saveSession();
    }

    public boolean canUndo() { synchronized (lock) { return !undoStack.isEmpty(); } }
    public boolean canRedo() { synchronized (lock) { return !redoStack.isEmpty(); } }
    public void undo() { synchronized (lock) { if (undoStack.isEmpty()) return; redoStack.push(captureStateLocked()); restoreStateLocked(undoStack.pop()); state = tracks.isEmpty() ? State.IDLE : State.PLAYING; } saveSession(); ensurePlayback(); }
    public void redo() { synchronized (lock) { if (redoStack.isEmpty()) return; undoStack.push(captureStateLocked()); restoreStateLocked(redoStack.pop()); state = tracks.isEmpty() ? State.IDLE : State.PLAYING; } saveSession(); ensurePlayback(); }

    public void release() {
        recordingRequested = false;
        playbackRequested = false;
        try { if (recordThread != null) recordThread.join(400); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
        try { if (playbackThread != null) playbackThread.join(400); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
        if (recorder != null) { recorder.release(); recorder = null; }
    }

    private void recordLoop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO);
        if (appContext.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            recordingRequested = false; state = hasTracks() ? State.PLAYING : State.IDLE; return;
        }
        int min = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        if (min <= 0) min = 4096;
        recorder = new AudioRecord(MediaRecorder.AudioSource.MIC, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, min * 2);
        short[] buffer = new short[Math.max(512, min / 2)];
        try {
            recorder.startRecording();
            while (recordingRequested) {
                int read = recorder.read(buffer, 0, buffer.length);
                if (read > 0) {
                    float peak = 0f;
                    for (int i = 0; i < read; i++) { capture.add(buffer[i]); peak = Math.max(peak, Math.abs(buffer[i]) / 32768f); }
                    inputLevel = inputLevel * .75f + peak * .25f;
                }
            }
        } catch (Exception ignored) {
            recordingRequested = false;
        } finally {
            inputLevel = 0f;
            if (recorder != null) { try { recorder.stop(); } catch (Exception ignored) {} recorder.release(); recorder = null; }
        }
    }

    private void ensurePlayback() {
        synchronized (lock) { if (tracks.isEmpty()) return; }
        playbackRequested = true;
        if (playbackThread == null || !playbackThread.isAlive()) {
            playbackThread = new Thread(this::playbackLoop, "Amadeus-playback");
            playbackThread.start();
        }
    }

    private void playbackLoop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO);
        int min = AudioTrack.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
        if (min <= 0) min = 4096;
        AudioTrack output = new AudioTrack(android.media.AudioManager.STREAM_MUSIC, SAMPLE_RATE,
                AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT, min * 2, AudioTrack.MODE_STREAM);
        short[] mixed = new short[Math.max(512, min / 2)];
        int position = 0;
        output.play();
        try {
            while (playbackRequested) {
                int length;
                synchronized (lock) { length = arrangementLength > 0 ? arrangementLength : loopLength; }
                if (length <= 0) break;
                for (int i = 0; i < mixed.length; i++) {
                    int sampleIndex = (position + i) % length;
                    float sum = 0f;
                    synchronized (lock) {
                        for (int trackIndex = 0; trackIndex < tracks.size(); trackIndex++) { Track track = tracks.get(trackIndex); if (!track.muted && (soloIndex < 0 || soloIndex == trackIndex)) sum += sampleWithEffects(track, sampleIndex); }
                    }
                    mixed[i] = (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, (int) sum));
                }
                output.write(mixed, 0, mixed.length);
                position = (position + mixed.length) % length;
                playbackPosition = position;
            }
        } finally { try { output.stop(); } catch (Exception ignored) {} output.release(); playbackThread = null; }
    }

    private int quantizedLoopLength(int capturedLength) {
        double samplesPerBeat = SAMPLE_RATE * 60.0 / tempoBpm;
        int beats = Math.max(1, Math.min(128, (int) Math.round(capturedLength / samplesPerBeat)));
        return Math.max(1, (int) Math.round(beats * samplesPerBeat));
    }

    private float sampleWithEffects(Track track, int sampleIndex) {
        if (sampleIndex < track.startSample || sampleIndex >= track.endSample) return 0f;
        int localIndex = sampleIndex - track.startSample;
        if (track.pcm.length == 0) return 0f;
        if (track.looping) localIndex %= track.pcm.length;
        else if (localIndex < 0 || localIndex >= track.pcm.length) return 0f;
        float t = (track.endSample - track.startSample) <= 1 ? 0f : (sampleIndex - track.startSample) / (float) (track.endSample - track.startSample - 1);
        float volume = curveAt(track.volumeCurve, t), reverb = curveAt(track.reverbCurve, t), delay = curveAt(track.delayCurve, t);
        float value = track.pcm[localIndex] * volume;
        if (delay > 0f) {
            int delaySamples = Math.max(1, (int) (SAMPLE_RATE * .25f));
            value += delayedSample(track, localIndex, delaySamples, volume) * delay * .32f;
        }
        if (reverb > 0f) {
            value += delayedSample(track, localIndex, Math.max(1, (int) (SAMPLE_RATE * .07f)), volume) * reverb * .18f;
            value += delayedSample(track, localIndex, Math.max(1, (int) (SAMPLE_RATE * .13f)), volume) * reverb * .12f;
            value += delayedSample(track, localIndex, Math.max(1, (int) (SAMPLE_RATE * .21f)), volume) * reverb * .08f;
        }
        return value;
    }

    private float delayedSample(Track track, int sampleIndex, int delaySamples, float volume) {
        int index = sampleIndex - delaySamples;
        if (track.looping && track.pcm.length > 0) index = (index % track.pcm.length + track.pcm.length) % track.pcm.length;
        if (index < 0 || index >= track.pcm.length) return 0f;
        return track.pcm[index] * volume;
    }

    private float interpolatedPan(Track track, int sampleIndex) { if (sampleIndex < track.startSample || sampleIndex >= track.endSample) return 0f; float t = (track.endSample - track.startSample) <= 1 ? 0f : (sampleIndex - track.startSample) / (float) (track.endSample - track.startSample - 1); return curveAt(track.panCurve, t); }
    private static float lerp(float start, float end, float amount) { return start + (end - start) * Math.max(0f, Math.min(1f, amount)); }
    private static float clamp01(float value) { return Math.max(0f, Math.min(1f, value)); }
    private static float clampPan(float value) { return Math.max(-1f, Math.min(1f, value)); }
    private static void fillCurve(float[] curve, float value) { for (int i = 0; i < curve.length; i++) curve[i] = value; }
    private static void linearCurve(float[] curve, float start, float end) { for (int i = 0; i < curve.length; i++) curve[i] = lerp(start, end, i / (float) (curve.length - 1)); }
    private static float curveAt(float[] curve, float amount) { float position = Math.max(0f, Math.min(1f, amount)) * (curve.length - 1); int left = Math.min(curve.length - 1, (int) position); int right = Math.min(curve.length - 1, left + 1); return lerp(curve[left], curve[right], position - left); }
    private static void copyCurves(Track source, Track target) { System.arraycopy(source.volumeCurve, 0, target.volumeCurve, 0, 4); System.arraycopy(source.panCurve, 0, target.panCurve, 0, 4); System.arraycopy(source.reverbCurve, 0, target.reverbCurve, 0, 4); System.arraycopy(source.delayCurve, 0, target.delayCurve, 0, 4); }
    private static void initializeLinearCurves(Track track) { linearCurve(track.volumeCurve, track.volume, track.volumeEnd); linearCurve(track.panCurve, track.pan, track.panEnd); linearCurve(track.reverbCurve, track.reverb, track.reverbEnd); linearCurve(track.delayCurve, track.delay, track.delayEnd); }
    private static void writeCurves(DataOutputStream out, Track track) throws Exception { for (float value : track.volumeCurve) out.writeFloat(value); for (float value : track.panCurve) out.writeFloat(value); for (float value : track.reverbCurve) out.writeFloat(value); for (float value : track.delayCurve) out.writeFloat(value); }
    private static void readCurves(DataInputStream in, Track track) throws Exception { for (int i = 0; i < 4; i++) track.volumeCurve[i] = clamp01(in.readFloat()); for (int i = 0; i < 4; i++) track.panCurve[i] = clampPan(in.readFloat()); for (int i = 0; i < 4; i++) track.reverbCurve[i] = clamp01(in.readFloat()); for (int i = 0; i < 4; i++) track.delayCurve[i] = clamp01(in.readFloat()); track.volume = track.volumeCurve[0]; track.volumeEnd = track.volumeCurve[3]; track.pan = track.panCurve[0]; track.panEnd = track.panCurve[3]; track.reverb = track.reverbCurve[0]; track.reverbEnd = track.reverbCurve[3]; track.delay = track.delayCurve[0]; track.delayEnd = track.delayCurve[3]; }

    private static short[] fitToLoop(short[] source, int targetLength) {
        if (source.length == targetLength) return source;
        short[] result = new short[targetLength];
        if (source.length == 0) return result;
        for (int i = 0; i < targetLength; i++) {
            double sourcePosition = (double) i * (source.length - 1) / Math.max(1, targetLength - 1);
            int left = (int) sourcePosition;
            int right = Math.min(source.length - 1, left + 1);
            double fraction = sourcePosition - left;
            result[i] = (short) ((1 - fraction) * source[left] + fraction * source[right]);
        }
        return result;
    }

    private void saveSession() {
        synchronized (lock) {
            if (demoMode) return;
            try (DataOutputStream out = new DataOutputStream(new FileOutputStream(appContext.getFileStreamPath("session.bin")))) {
                out.writeInt(MAGIC);
                out.writeInt(loopLength); out.writeInt(arrangementLength); out.writeInt(arrangementBars);
                out.writeInt(tracks.size());
                for (Track track : tracks) {
                    out.writeUTF(track.name); out.writeInt(track.colorIndex); out.writeBoolean(track.muted); out.writeFloat(track.volume); out.writeFloat(track.reverb); out.writeFloat(track.delay);
                    out.writeInt(track.pcm.length); for (short sample : track.pcm) out.writeShort(sample);
                    out.writeInt(track.startSample); out.writeInt(track.endSample);
                    out.writeFloat(track.volumeEnd); out.writeFloat(track.pan); out.writeFloat(track.panEnd); out.writeFloat(track.reverbEnd); out.writeFloat(track.delayEnd); out.writeBoolean(track.looping); writeCurves(out, track);
                }
                saveHistoryLocked();
            } catch (Exception ignored) { }
        }
    }

    private void saveHistoryLocked() throws Exception {
        List<EditorState> undo = limitedHistory(undoStack), redo = limitedHistory(redoStack);
        IdentityHashMap<short[], Integer> ids = new IdentityHashMap<>(); List<short[]> audio = new ArrayList<>(); int samples = 0;
        List<EditorState> acceptedUndo = collectHistory(undo, ids, audio, samples); samples = countSamples(audio);
        List<EditorState> acceptedRedo = collectHistory(redo, ids, audio, samples);
        try (DataOutputStream out = new DataOutputStream(new FileOutputStream(appContext.getFileStreamPath("history.bin")))) {
            out.writeInt(HISTORY_MAGIC); out.writeInt(audio.size()); for (short[] pcm : audio) { out.writeInt(pcm.length); for (short sample : pcm) out.writeShort(sample); }
            out.writeInt(acceptedUndo.size()); for (EditorState state : acceptedUndo) writeHistoryState(out, state, ids);
            out.writeInt(acceptedRedo.size()); for (EditorState state : acceptedRedo) writeHistoryState(out, state, ids);
        }
    }

    private List<EditorState> limitedHistory(Deque<EditorState> source) { List<EditorState> result = new ArrayList<>(); int count = 0; for (EditorState state : source) { if (count++ >= MAX_HISTORY_SNAPSHOTS) break; result.add(state); } return result; }
    private int countSamples(List<short[]> audio) { int total = 0; for (short[] pcm : audio) total += pcm.length; return total; }
    private List<EditorState> collectHistory(List<EditorState> candidates, IdentityHashMap<short[], Integer> ids, List<short[]> audio, int usedSamples) {
        List<EditorState> accepted = new ArrayList<>();
        for (EditorState state : candidates) {
            int additional = 0; IdentityHashMap<short[], Boolean> unseen = new IdentityHashMap<>();
            for (Track track : state.tracks) if (!ids.containsKey(track.pcm) && unseen.put(track.pcm, Boolean.TRUE) == null) additional += track.pcm.length;
            if (usedSamples + additional > MAX_HISTORY_PCM_SAMPLES) continue;
            for (Track track : state.tracks) if (!ids.containsKey(track.pcm)) { ids.put(track.pcm, audio.size()); audio.add(track.pcm); usedSamples += track.pcm.length; }
            accepted.add(state);
        }
        return accepted;
    }
    private void writeHistoryState(DataOutputStream out, EditorState state, IdentityHashMap<short[], Integer> ids) throws Exception {
        out.writeInt(state.loopLength); out.writeInt(state.arrangementLength); out.writeInt(state.arrangementBars); out.writeInt(state.soloIndex); out.writeInt(state.tracks.size());
        for (Track track : state.tracks) { out.writeUTF(track.name); out.writeInt(track.colorIndex); out.writeBoolean(track.muted); out.writeBoolean(track.looping); out.writeFloat(track.volume); out.writeFloat(track.volumeEnd); out.writeFloat(track.pan); out.writeFloat(track.panEnd); out.writeFloat(track.reverb); out.writeFloat(track.reverbEnd); out.writeFloat(track.delay); out.writeFloat(track.delayEnd); out.writeInt(track.startSample); out.writeInt(track.endSample); out.writeInt(ids.get(track.pcm)); writeCurves(out, track); }
    }

    private void loadHistory() {
        synchronized (lock) {
            try (DataInputStream in = new DataInputStream(new FileInputStream(appContext.getFileStreamPath("history.bin")))) {
                if (in.readInt() != HISTORY_MAGIC) return;
                int poolCount = in.readInt(); if (poolCount < 0 || poolCount > MAX_TRACKS * MAX_HISTORY_SNAPSHOTS * 2) return;
                short[][] audio = new short[poolCount][]; int totalSamples = 0;
                for (int i = 0; i < poolCount; i++) { int length = in.readInt(); if (length < 1 || length > MAX_HISTORY_PCM_SAMPLES || totalSamples + length > MAX_HISTORY_PCM_SAMPLES) return; audio[i] = new short[length]; for (int j = 0; j < length; j++) audio[i][j] = in.readShort(); totalSamples += length; }
                int undoCount = Math.max(0, Math.min(MAX_HISTORY_SNAPSHOTS, in.readInt())); List<EditorState> undo = new ArrayList<>(); for (int i = 0; i < undoCount; i++) undo.add(readHistoryState(in, audio));
                int redoCount = Math.max(0, Math.min(MAX_HISTORY_SNAPSHOTS, in.readInt())); List<EditorState> redo = new ArrayList<>(); for (int i = 0; i < redoCount; i++) redo.add(readHistoryState(in, audio));
                undoStack.clear(); redoStack.clear(); for (int i = undo.size() - 1; i >= 0; i--) undoStack.push(undo.get(i)); for (int i = redo.size() - 1; i >= 0; i--) redoStack.push(redo.get(i));
            } catch (Exception ignored) { undoStack.clear(); redoStack.clear(); }
        }
    }

    private EditorState readHistoryState(DataInputStream in, short[][] audio) throws Exception {
        int savedLoopLength = in.readInt(), savedArrangementLength = in.readInt(), savedBars = in.readInt(), savedSolo = in.readInt(); int count = Math.max(0, Math.min(MAX_TRACKS, in.readInt())); List<Track> savedTracks = new ArrayList<>();
        for (int i = 0; i < count; i++) { String name = in.readUTF(); int color = in.readInt(); boolean muted = in.readBoolean(), looping = in.readBoolean(); float volume = in.readFloat(), volumeEnd = in.readFloat(), pan = in.readFloat(), panEnd = in.readFloat(), reverb = in.readFloat(), reverbEnd = in.readFloat(), delay = in.readFloat(), delayEnd = in.readFloat(); int start = in.readInt(), end = in.readInt(), audioId = in.readInt(); if (audioId < 0 || audioId >= audio.length) throw new IllegalArgumentException("Invalid history audio reference"); Track track = new Track(name, color, audio[audioId]); track.muted = muted; track.looping = looping; track.volume = volume; track.volumeEnd = volumeEnd; track.pan = pan; track.panEnd = panEnd; track.reverb = reverb; track.reverbEnd = reverbEnd; track.delay = delay; track.delayEnd = delayEnd; track.startSample = start; track.endSample = end; readCurves(in, track); savedTracks.add(track); }
        return new EditorState(savedLoopLength, savedArrangementLength, savedBars, savedSolo, savedTracks);
    }

    private void pushUndoLocked() { undoStack.push(captureStateLocked()); if (undoStack.size() > 30) undoStack.removeLast(); redoStack.clear(); }
    private EditorState captureStateLocked() { List<Track> copies = new ArrayList<>(); for (Track track : tracks) copies.add(copyTrack(track)); return new EditorState(loopLength, arrangementLength, arrangementBars, soloIndex, copies); }
    private void restoreStateLocked(EditorState saved) { tracks.clear(); for (Track track : saved.tracks) tracks.add(copyTrack(track)); loopLength = saved.loopLength; arrangementLength = saved.arrangementLength; arrangementBars = saved.arrangementBars; soloIndex = saved.soloIndex; if (tracks.isEmpty()) { loopLength = 0; arrangementLength = 0; } }
    private static Track copyTrack(Track source) { Track copy = new Track(source.name, source.colorIndex, source.pcm); copy.muted = source.muted; copy.looping = source.looping; copy.volume = source.volume; copy.volumeEnd = source.volumeEnd; copy.pan = source.pan; copy.panEnd = source.panEnd; copy.reverb = source.reverb; copy.reverbEnd = source.reverbEnd; copy.delay = source.delay; copy.delayEnd = source.delayEnd; copy.startSample = source.startSample; copy.endSample = source.endSample; copyCurves(source, copy); return copy; }

    private void loadSession() {
        try (DataInputStream in = new DataInputStream(new FileInputStream(appContext.getFileStreamPath("session.bin")))) {
            int magic = in.readInt();
            if (magic != MAGIC && magic != LOOPING_MAGIC && magic != AUTOMATION_MAGIC && magic != TIMELINE_MAGIC && magic != EFFECTS_MAGIC && magic != LEGACY_MAGIC) return;
            boolean hasEffects = magic == MAGIC || magic == LOOPING_MAGIC || magic == AUTOMATION_MAGIC || magic == TIMELINE_MAGIC || magic == EFFECTS_MAGIC;
            boolean hasTimeline = magic == MAGIC || magic == LOOPING_MAGIC || magic == AUTOMATION_MAGIC || magic == TIMELINE_MAGIC;
            boolean hasAutomation = magic == MAGIC || magic == LOOPING_MAGIC || magic == AUTOMATION_MAGIC;
            boolean hasLoopMode = magic == MAGIC || magic == LOOPING_MAGIC;
            boolean hasCurves = magic == MAGIC;
            int savedLength = in.readInt(); int savedArrangement = hasAutomation ? in.readInt() : savedLength; int savedBars = hasAutomation ? in.readInt() : 1; int count = Math.min(MAX_TRACKS, in.readInt());
            for (int i = 0; i < count; i++) {
                String name = in.readUTF(); int color = in.readInt(); boolean muted = in.readBoolean(); float volume = in.readFloat(); float reverb = hasEffects ? in.readFloat() : 0f; float delay = hasEffects ? in.readFloat() : 0f;
                int length = in.readInt(); if (length < 1 || length > SAMPLE_RATE * 60 * 10) return;
                short[] pcm = new short[length]; for (int j = 0; j < length; j++) pcm[j] = in.readShort();
                Track track = new Track(name, color, pcm); track.muted = muted; track.volume = volume; track.reverb = reverb; track.delay = delay;
                if (hasTimeline) {
                    int start = in.readInt(); int end = in.readInt();
                    int rangeLimit = Math.max(length, hasAutomation ? savedArrangement : length);
                    track.startSample = Math.max(0, Math.min(rangeLimit - 1, start));
                    track.endSample = Math.max(track.startSample + 1, Math.min(rangeLimit, end));
                }
                track.volumeEnd = track.volume; track.reverbEnd = track.reverb; track.delayEnd = track.delay;
                if (hasAutomation) { track.volumeEnd = clamp01(in.readFloat()); track.pan = clampPan(in.readFloat()); track.panEnd = clampPan(in.readFloat()); track.reverbEnd = clamp01(in.readFloat()); track.delayEnd = clamp01(in.readFloat()); }
                if (hasLoopMode) track.looping = in.readBoolean();
                else if (track.startSample == 0 && track.endSample == length && savedArrangement > length) track.endSample = savedArrangement;
                if (hasCurves) readCurves(in, track); else initializeLinearCurves(track);
                tracks.add(track);
            }
            loopLength = savedLength > 0 ? savedLength : (tracks.isEmpty() ? 0 : tracks.get(0).pcm.length); arrangementBars = Math.max(1, Math.min(16, savedBars)); arrangementLength = savedArrangement > 0 ? savedArrangement : loopLength;
        } catch (Exception ignored) { }
    }
}

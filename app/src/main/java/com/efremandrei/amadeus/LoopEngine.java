package com.efremandrei.amadeus;

import android.content.Context;
import android.Manifest;
import android.content.pm.PackageManager;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.os.Process;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.FileInputStream;
import java.io.FileOutputStream;

/** Small, dependency-free loop engine for the first Amadeus slice. */
public final class LoopEngine {
    public enum State { IDLE, RECORDING, PLAYING }

    public static final int SAMPLE_RATE = 44100;
    private static final int MAX_TRACKS = 8;
    private final Object lock = new Object();
    private final List<Track> tracks = new ArrayList<>();
    private volatile State state = State.IDLE;
    private volatile float inputLevel;
    private volatile boolean playbackRequested;
    private volatile boolean recordingRequested;
    private AudioRecord recorder;
    private Thread recordThread;
    private Thread playbackThread;
    private final List<Short> capture = Collections.synchronizedList(new ArrayList<Short>());
    private int loopLength;
    private final Context appContext;

    public static final class Track {
        public final String name;
        public final int colorIndex;
        public final short[] pcm;
        public boolean muted;
        public float volume = 1f;

        Track(String name, int colorIndex, short[] pcm) {
            this.name = name;
            this.colorIndex = colorIndex;
            this.pcm = pcm;
        }
    }

    public LoopEngine(Context context) {
        appContext = context.getApplicationContext();
        loadSession();
    }

    public State getState() { return state; }
    public float getInputLevel() { return inputLevel; }
    public int getLoopLength() { return loopLength; }

    public List<Track> snapshotTracks() {
        synchronized (lock) { return new ArrayList<>(tracks); }
    }

    public boolean hasTracks() { synchronized (lock) { return !tracks.isEmpty(); } }

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
            if (tracks.isEmpty()) {
                loopLength = take.length;
                tracks.add(new Track("LOOP 1", 0, take));
            } else if (tracks.size() < MAX_TRACKS) {
                tracks.add(new Track("LOOP " + (tracks.size() + 1), tracks.size() % 6, fitToLoop(take, loopLength)));
            }
        }
        state = State.PLAYING;
        ensurePlayback();
        saveSession();
    }

    public void clearLastTrack() {
        synchronized (lock) {
            if (!tracks.isEmpty()) tracks.remove(tracks.size() - 1);
            if (tracks.isEmpty()) { loopLength = 0; playbackRequested = false; state = State.IDLE; }
        }
        saveSession();
    }

    public void toggleMute(int index) {
        synchronized (lock) { if (index >= 0 && index < tracks.size()) tracks.get(index).muted = !tracks.get(index).muted; }
    }

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
                synchronized (lock) { length = loopLength; }
                if (length <= 0) break;
                for (int i = 0; i < mixed.length; i++) {
                    int sampleIndex = (position + i) % length;
                    float sum = 0f;
                    synchronized (lock) {
                        for (Track track : tracks) if (!track.muted && sampleIndex < track.pcm.length) sum += track.pcm[sampleIndex] * track.volume;
                    }
                    mixed[i] = (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, (int) sum));
                }
                output.write(mixed, 0, mixed.length);
                position = (position + mixed.length) % length;
            }
        } finally { try { output.stop(); } catch (Exception ignored) {} output.release(); playbackThread = null; }
    }

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
            try (DataOutputStream out = new DataOutputStream(new FileOutputStream(appContext.getFileStreamPath("session.bin")))) {
                out.writeInt(0x414D4441);
                out.writeInt(loopLength);
                out.writeInt(tracks.size());
                for (Track track : tracks) {
                    out.writeUTF(track.name); out.writeInt(track.colorIndex); out.writeBoolean(track.muted); out.writeFloat(track.volume);
                    out.writeInt(track.pcm.length); for (short sample : track.pcm) out.writeShort(sample);
                }
            } catch (Exception ignored) { }
        }
    }

    private void loadSession() {
        try (DataInputStream in = new DataInputStream(new FileInputStream(appContext.getFileStreamPath("session.bin")))) {
            if (in.readInt() != 0x414D4441) return;
            int savedLength = in.readInt(); int count = Math.min(MAX_TRACKS, in.readInt());
            for (int i = 0; i < count; i++) {
                String name = in.readUTF(); int color = in.readInt(); boolean muted = in.readBoolean(); float volume = in.readFloat();
                int length = in.readInt(); if (length < 1 || length > SAMPLE_RATE * 60 * 10) return;
                short[] pcm = new short[length]; for (int j = 0; j < length; j++) pcm[j] = in.readShort();
                Track track = new Track(name, color, pcm); track.muted = muted; track.volume = volume; tracks.add(track);
            }
            loopLength = savedLength > 0 ? savedLength : (tracks.isEmpty() ? 0 : tracks.get(0).pcm.length);
        } catch (Exception ignored) { }
    }
}

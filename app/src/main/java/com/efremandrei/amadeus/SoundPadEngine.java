package com.efremandrei.amadeus;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.MediaPlayer;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.net.Uri;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;

/** One-shot pad playback and persistent preset assignment. */
public final class SoundPadEngine {
    public static final int PAD_COUNT = 8;
    public static final String[] LIBRARY = {"Kick", "Snare", "Hi-Hat", "Clap", "Bass", "Tone", "Noise", "Chime", "Zap", "Click", "Pop", "Vox"};
    public static final String[] PRESETS = {"Starter Kit", "Percussion", "Synth Sketch"};
    private static final String[][] DEFAULTS = {
            {"Kick", "Snare", "Hi-Hat", "Clap", "Bass", "Tone", "Chime", "Zap"},
            {"Kick", "Kick", "Snare", "Snare", "Hi-Hat", "Hi-Hat", "Clap", "Click"},
            {"Bass", "Tone", "Chime", "Zap", "Pop", "Vox", "Noise", "Click"}
    };
    private static final int SAMPLE_RATE = 44100;
    private final SharedPreferences prefs;
    private final Context appContext;
    private final Map<String, short[]> samples = new HashMap<>();
    private int activePreset;

    public SoundPadEngine(Context context) {
        appContext = context.getApplicationContext();
        prefs = appContext.getSharedPreferences("amadeus", Context.MODE_PRIVATE);
        activePreset = Math.max(0, Math.min(PRESETS.length - 1, prefs.getInt("sound_preset", 0)));
    }

    public int getActivePreset() { return activePreset; }
    public String getActivePresetName() { return PRESETS[activePreset]; }
    public void nextPreset() { activePreset = (activePreset + 1) % PRESETS.length; prefs.edit().putInt("sound_preset", activePreset).apply(); }
    public String getSoundForPad(int pad) { return prefs.getString(nameKey(activePreset, pad), prefs.getString(key(activePreset, pad), DEFAULTS[activePreset][pad])); }
    public void assignPad(int pad, String sound) { if (pad >= 0 && pad < PAD_COUNT) prefs.edit().remove(uriKey(activePreset, pad)).remove(nameKey(activePreset, pad)).putString(key(activePreset, pad), sound).apply(); }
    public void importPad(int pad, Uri uri, String displayName) { if (pad >= 0 && pad < PAD_COUNT && uri != null) prefs.edit().remove(key(activePreset, pad)).putString(uriKey(activePreset, pad), uri.toString()).putString(nameKey(activePreset, pad), displayName).apply(); }
    public boolean isImported(int pad) { return pad >= 0 && pad < PAD_COUNT && prefs.contains(uriKey(activePreset, pad)); }

    public void playPad(int pad) {
        if (pad < 0 || pad >= PAD_COUNT) return;
        String importedUri = prefs.getString(uriKey(activePreset, pad), null);
        if (importedUri != null) { playImported(importedUri); return; }
        final short[] pcm = sampleFor(getSoundForPad(pad));
        new Thread(() -> {
            AudioTrack track = null;
            try {
                AudioFormat format = new AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(SAMPLE_RATE).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build();
                AudioAttributes attributes = new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build();
                track = new AudioTrack.Builder().setAudioAttributes(attributes).setAudioFormat(format).setTransferMode(AudioTrack.MODE_STATIC).setBufferSizeInBytes(pcm.length * 2).build();
                track.write(pcm, 0, pcm.length); track.play(); Thread.sleep(Math.max(50, pcm.length * 1000L / SAMPLE_RATE + 50));
            } catch (Exception ignored) { }
            finally { if (track != null) { try { track.stop(); } catch (Exception ignored) {} track.release(); } }
        }, "Amadeus-pad").start();
    }

    private void playImported(String uriString) {
        try {
            MediaPlayer player = new MediaPlayer();
            player.setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build());
            player.setDataSource(appContext, Uri.parse(uriString));
            player.setOnPreparedListener(MediaPlayer::start);
            player.setOnCompletionListener(MediaPlayer::release);
            player.setOnErrorListener((mp, what, extra) -> { mp.release(); return true; });
            player.prepareAsync();
        } catch (Exception ignored) { }
    }

    private String key(int preset, int pad) { return "sound_" + preset + "_" + pad; }
    private String uriKey(int preset, int pad) { return "pad_uri_" + preset + "_" + pad; }
    private String nameKey(int preset, int pad) { return "pad_name_" + preset + "_" + pad; }
    private short[] sampleFor(String name) { synchronized (samples) { if (!samples.containsKey(name)) samples.put(name, createSample(name)); return samples.get(name); } }

    private short[] createSample(String name) {
        int length = name.equals("Tone") || name.equals("Bass") ? SAMPLE_RATE / 2 : SAMPLE_RATE / 3;
        if (name.equals("Chime") || name.equals("Vox")) length = SAMPLE_RATE * 2 / 3;
        short[] pcm = new short[length]; Random random = new Random(name.hashCode());
        for (int i = 0; i < length; i++) {
            double t = i / (double) SAMPLE_RATE, n = i / (double) length, value, noise = random.nextDouble() * 2.0 - 1.0;
            if (name.equals("Kick")) value = Math.sin(2 * Math.PI * (130 - 75 * n) * t) * Math.exp(-7 * n);
            else if (name.equals("Snare")) value = (noise * .72 + Math.sin(2 * Math.PI * 185 * t) * .28) * Math.exp(-9 * n);
            else if (name.equals("Hi-Hat")) value = noise * Math.exp(-18 * n);
            else if (name.equals("Clap")) value = noise * (n < .12 ? 1 : .4) * Math.exp(-7 * n);
            else if (name.equals("Bass")) value = Math.sin(2 * Math.PI * 92 * t) * Math.exp(-4 * n);
            else if (name.equals("Tone")) value = Math.sin(2 * Math.PI * 440 * t) * Math.exp(-3 * n);
            else if (name.equals("Chime")) value = (Math.sin(2 * Math.PI * 660 * t) * .6 + Math.sin(2 * Math.PI * 990 * t) * .4) * Math.exp(-4 * n);
            else if (name.equals("Zap")) value = Math.sin(2 * Math.PI * (180 + 1300 * n) * t) * Math.exp(-6 * n);
            else if (name.equals("Click")) value = noise * Math.exp(-35 * n);
            else if (name.equals("Pop")) value = Math.sin(2 * Math.PI * (240 - 120 * n) * t) * Math.exp(-10 * n);
            else if (name.equals("Vox")) value = (Math.sin(2 * Math.PI * 220 * t) + .35 * Math.sin(2 * Math.PI * 440 * t)) * Math.exp(-4 * n);
            else value = noise * Math.exp(-5 * n);
            pcm[i] = (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, (int) (value * 20000)));
        }
        return pcm;
    }
}

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
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** One-shot pad playback and persistent preset assignment. */
public final class SoundPadEngine {
    public static final int PAD_COUNT = 8;
    public static final String[] LIBRARY = {"Kick", "Snare", "Hi-Hat", "Clap", "Bass", "Tone", "Noise", "Chime", "Zap", "Click", "Pop", "Vox", "Shaker", "Bell", "808", "Riser", "Marimba", "Drone"};
    public static final String[] PRESETS = {"Starter Kit", "Percussion", "Synth Sketch", "Ambient Bloom", "Lo-Fi Lab", "Organic Perc"};
    private static final String[][] DEFAULTS = {
            {"Kick", "Snare", "Hi-Hat", "Clap", "Bass", "Tone", "Chime", "Zap"},
            {"Kick", "Kick", "Snare", "Snare", "Hi-Hat", "Hi-Hat", "Clap", "Click"},
            {"Bass", "Tone", "Chime", "Zap", "Pop", "Vox", "Noise", "Click"},
            {"Drone", "Bell", "Chime", "Tone", "Vox", "Riser", "Marimba", "Pop"},
            {"808", "Noise", "Tone", "Bass", "Click", "Riser", "Vox", "Drone"},
            {"Shaker", "Clap", "Hi-Hat", "Marimba", "Bell", "Kick", "Snare", "Pop"}
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
    public String getSoundForPad(int pad) { return prefs.getString(nameKey(activePreset, pad), prefs.contains(recordedKey(activePreset, pad)) ? "RECORDED PAD" : prefs.getString(key(activePreset, pad), DEFAULTS[activePreset][pad])); }
    public void assignPad(int pad, String sound) { if (pad >= 0 && pad < PAD_COUNT) prefs.edit().remove(uriKey(activePreset, pad)).remove(recordedKey(activePreset, pad)).remove(nameKey(activePreset, pad)).putString(key(activePreset, pad), sound).apply(); }
    public void importPad(int pad, Uri uri, String displayName) { if (pad >= 0 && pad < PAD_COUNT && uri != null) prefs.edit().remove(key(activePreset, pad)).remove(recordedKey(activePreset, pad)).putString(uriKey(activePreset, pad), uri.toString()).putString(nameKey(activePreset, pad), displayName).apply(); }
    public boolean isImported(int pad) { return pad >= 0 && pad < PAD_COUNT && prefs.contains(uriKey(activePreset, pad)); }
    public boolean isRecorded(int pad) { return pad >= 0 && pad < PAD_COUNT && prefs.contains(recordedKey(activePreset, pad)); }

    public void recordPad(int pad, short[] pcm, String displayName) {
        if (pad < 0 || pad >= PAD_COUNT || pcm == null || pcm.length == 0) return;
        String file = "recorded_pad_" + activePreset + "_" + pad + ".pcm";
        try (DataOutputStream out = new DataOutputStream(new FileOutputStream(appContext.getFileStreamPath(file)))) { out.writeInt(SAMPLE_RATE); out.writeInt(pcm.length); for (short sample : pcm) out.writeShort(sample); prefs.edit().remove(key(activePreset, pad)).remove(uriKey(activePreset, pad)).putString(recordedKey(activePreset, pad), file).putString(nameKey(activePreset, pad), displayName).apply(); } catch (Exception ignored) { }
    }

    /** Imports a shareable manifest: AMAPACK 1, Name=..., Pads=a,b,c,d,e,f,g,h. */
    public String importSoundPack(String text) {
        if (text == null) return null;
        String name = "Sound Pack";
        String padsLine = null;
        Matcher jsonName = Pattern.compile("(?i)\\\"(?:name|pack)\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"").matcher(text);
        if (jsonName.find()) name = jsonName.group(1).trim();
        Matcher plainName = Pattern.compile("(?im)^\\s*(?:name|pack)\\s*=\\s*(.+)$").matcher(text);
        if (plainName.find()) name = plainName.group(1).trim();
        Matcher jsonPads = Pattern.compile("(?is)\\\"pads\\\"\\s*:\\s*\\[(.*?)\\]").matcher(text);
        if (jsonPads.find()) padsLine = jsonPads.group(1);
        Matcher plainPads = Pattern.compile("(?im)^\\s*pads\\s*=\\s*(.+)$").matcher(text);
        if (plainPads.find()) padsLine = plainPads.group(1);
        if (padsLine == null) return null;
        String[] values = padsLine.split(","); String[] assigned = new String[PAD_COUNT]; int count = 0;
        for (String raw : values) {
            String candidate = raw.trim().replace("\"", "").replace("'", "");
            if (candidate.length() == 0) continue;
            for (String libraryName : LIBRARY) if (libraryName.equalsIgnoreCase(candidate)) { assigned[count++] = libraryName; break; }
            if (count == PAD_COUNT) break;
        }
        if (count != PAD_COUNT) return null;
        SharedPreferences.Editor editor = prefs.edit().putString("sound_pack_name_" + activePreset, name.length() == 0 ? "Sound Pack" : name);
        for (int i = 0; i < PAD_COUNT; i++) editor.remove(uriKey(activePreset, i)).remove(recordedKey(activePreset, i)).remove(nameKey(activePreset, i)).putString(key(activePreset, i), assigned[i]);
        editor.apply();
        return name.length() == 0 ? "Sound Pack" : name;
    }

    public void playPad(int pad) {
        if (pad < 0 || pad >= PAD_COUNT) return;
        String importedUri = prefs.getString(uriKey(activePreset, pad), null);
        if (importedUri != null) { playImported(importedUri); return; }
        final short[] pcm = isRecorded(pad) ? readRecorded(pad) : sampleFor(getSoundForPad(pad));
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
    private String recordedKey(int preset, int pad) { return "pad_recorded_" + preset + "_" + pad; }
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
            else if (name.equals("Shaker")) value = noise * .65 * Math.exp(-8 * n);
            else if (name.equals("Bell")) value = (Math.sin(2 * Math.PI * 880 * t) * .65 + Math.sin(2 * Math.PI * 1320 * t) * .35) * Math.exp(-3 * n);
            else if (name.equals("808")) value = Math.sin(2 * Math.PI * (54 - 20 * n) * t) * Math.exp(-2.5 * n);
            else if (name.equals("Riser")) value = Math.sin(2 * Math.PI * (160 + 900 * n) * t) * n;
            else if (name.equals("Marimba")) value = (Math.sin(2 * Math.PI * 330 * t) + .4 * Math.sin(2 * Math.PI * 660 * t)) * Math.exp(-5 * n);
            else if (name.equals("Drone")) value = (.6 * Math.sin(2 * Math.PI * 110 * t) + .3 * Math.sin(2 * Math.PI * 165 * t)) * (.8 - .2 * n);
            else value = noise * Math.exp(-5 * n);
            pcm[i] = (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, (int) (value * 20000)));
        }
        return pcm;
    }

    private short[] readRecorded(int pad) {
        String file = prefs.getString(recordedKey(activePreset, pad), null); if (file == null) return new short[0];
        try (DataInputStream in = new DataInputStream(new FileInputStream(appContext.getFileStreamPath(file)))) { in.readInt(); int length = Math.min(SAMPLE_RATE * 8, Math.max(1, in.readInt())); short[] pcm = new short[length]; for (int i = 0; i < length; i++) pcm[i] = in.readShort(); return pcm; } catch (Exception ignored) { return new short[0]; }
    }
}

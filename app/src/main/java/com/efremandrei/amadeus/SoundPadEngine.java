package com.efremandrei.amadeus;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.MediaPlayer;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;

import java.util.HashMap;
import java.util.ArrayList;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
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
    public short[] getPcmForPad(int pad) {
        if (pad < 0 || pad >= PAD_COUNT) return new short[0];
        String imported = prefs.getString(uriKey(activePreset, pad), null);
        if (imported != null) { synchronized (samples) { String cacheKey = "URI:" + imported; if (!samples.containsKey(cacheKey)) samples.put(cacheKey, decodeAudio(imported)); return samples.get(cacheKey).clone(); } }
        return (isRecorded(pad) ? readRecorded(pad) : sampleFor(getSoundForPad(pad))).clone();
    }
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

    public String importSoundPack(Uri uri) {
        if (uri == null) return null;
        Map<String, byte[]> entries = new HashMap<>(); int total = 0; String manifest = null;
        try (InputStream raw = appContext.getContentResolver().openInputStream(uri); ZipInputStream zip = new ZipInputStream(raw)) {
            if (raw == null) return null;
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                String path = entry.getName().replace('\\', '/'); if (entry.isDirectory() || path.startsWith("/") || path.contains("../")) continue;
                ByteArrayOutputStream bytes = new ByteArrayOutputStream(); byte[] buffer = new byte[8192]; int read, size = 0;
                while ((read = zip.read(buffer)) >= 0) { size += read; total += read; if (size > 12 * 1024 * 1024 || total > 32 * 1024 * 1024) throw new IllegalArgumentException("Sound pack is too large"); bytes.write(buffer, 0, read); }
                if (path.equalsIgnoreCase("manifest.txt") || path.equalsIgnoreCase("amapack.txt")) manifest = bytes.toString("UTF-8"); else entries.put(path.toLowerCase(Locale.ROOT), bytes.toByteArray());
            }
        } catch (Exception ignored) { return null; }
        if (manifest == null) return null;
        String name = "Sound Pack"; String padsLine = null;
        Matcher n = Pattern.compile("(?im)^\\s*(?:name|pack)\\s*=\\s*(.+)$").matcher(manifest); if (n.find()) name = n.group(1).trim();
        Matcher p = Pattern.compile("(?im)^\\s*pads\\s*=\\s*(.+)$").matcher(manifest); if (p.find()) padsLine = p.group(1);
        if (padsLine == null) return null;
        String[] definitions = padsLine.split(","); if (definitions.length != PAD_COUNT) return null;
        String[] soundNames = new String[PAD_COUNT]; short[][] importedPcm = new short[PAD_COUNT][];
        for (int i = 0; i < PAD_COUNT; i++) {
            String[] pair = definitions[i].trim().split(":", 2); String label = pair[0].trim();
            if (pair.length == 1) { boolean known = false; for (String sound : LIBRARY) if (sound.equalsIgnoreCase(label)) { soundNames[i] = sound; known = true; break; } if (!known) return null; }
            else {
                String key = pair[1].trim().replace('\\', '/').toLowerCase(Locale.ROOT); byte[] data = entries.get(key); if (data == null || data.length == 0) return null;
                File temp = null;
                try { temp = File.createTempFile("amapack-", ".audio", appContext.getCacheDir()); try (FileOutputStream out = new FileOutputStream(temp)) { out.write(data); } importedPcm[i] = decodeAudio(Uri.fromFile(temp).toString()); }
                catch (Exception ignored) { return null; }
                finally { if (temp != null && !temp.delete()) temp.deleteOnExit(); }
                if (importedPcm[i] == null || importedPcm[i].length == 0) return null; soundNames[i] = label.length() == 0 ? "PACK PAD " + (i + 1) : label;
            }
        }
        for (int i = 0; i < PAD_COUNT; i++) if (importedPcm[i] != null) recordPad(i, importedPcm[i], soundNames[i]); else assignPad(i, soundNames[i]);
        prefs.edit().putString("sound_pack_name_" + activePreset, name).apply(); return name;
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

    private short[] decodeAudio(String uriString) {
        MediaExtractor extractor = new MediaExtractor(); MediaCodec decoder = null; ArrayList<Short> decoded = new ArrayList<>();
        int inputRate = SAMPLE_RATE, channels = 1, encoding = AudioFormat.ENCODING_PCM_16BIT; boolean inputDone = false, outputDone = false;
        try {
            extractor.setDataSource(appContext, Uri.parse(uriString), null); int audioTrack = -1; MediaFormat format = null;
            for (int i = 0; i < extractor.getTrackCount(); i++) { MediaFormat candidate = extractor.getTrackFormat(i); String mime = candidate.getString(MediaFormat.KEY_MIME); if (mime != null && mime.startsWith("audio/")) { audioTrack = i; format = candidate; break; } }
            if (audioTrack < 0 || format == null) return new short[0];
            inputRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE); channels = Math.max(1, format.getInteger(MediaFormat.KEY_CHANNEL_COUNT));
            decoder = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)); extractor.selectTrack(audioTrack); decoder.configure(format, null, null, 0); decoder.start();
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            while (!outputDone && decoded.size() < SAMPLE_RATE * 30) {
                if (!inputDone) { int inIndex = decoder.dequeueInputBuffer(10000); if (inIndex >= 0) { ByteBuffer in = decoder.getInputBuffer(inIndex); in.clear(); int size = extractor.readSampleData(in, 0); if (size < 0) { decoder.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputDone = true; } else { decoder.queueInputBuffer(inIndex, 0, size, extractor.getSampleTime(), 0); extractor.advance(); } } }
                int outIndex = decoder.dequeueOutputBuffer(info, 10000);
                if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) { MediaFormat output = decoder.getOutputFormat(); if (output.containsKey(MediaFormat.KEY_SAMPLE_RATE)) inputRate = output.getInteger(MediaFormat.KEY_SAMPLE_RATE); if (output.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) channels = Math.max(1, output.getInteger(MediaFormat.KEY_CHANNEL_COUNT)); if (output.containsKey(MediaFormat.KEY_PCM_ENCODING)) encoding = output.getInteger(MediaFormat.KEY_PCM_ENCODING); }
                else if (outIndex >= 0) {
                    ByteBuffer out = decoder.getOutputBuffer(outIndex); if (out != null && info.size > 0) { out.position(info.offset); out.limit(info.offset + info.size); out.order(ByteOrder.LITTLE_ENDIAN); int sampleBytes = encoding == AudioFormat.ENCODING_PCM_FLOAT ? 4 : encoding == AudioFormat.ENCODING_PCM_8BIT ? 1 : 2; int frameBytes = sampleBytes * channels;
                        while (out.remaining() >= frameBytes && decoded.size() < SAMPLE_RATE * 30) { float sum = 0; for (int ch = 0; ch < channels; ch++) { float value; if (sampleBytes == 4) value = out.getFloat(); else if (sampleBytes == 1) value = ((out.get() & 255) - 128) / 128f; else value = out.getShort() / 32768f; sum += value; } decoded.add((short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, Math.round(sum / channels * 32767f)))); }
                    }
                    decoder.releaseOutputBuffer(outIndex, false); if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outputDone = true;
                }
            }
        } catch (Exception ignored) { return new short[0]; }
        finally { if (decoder != null) { try { decoder.stop(); } catch (Exception ignored) {} decoder.release(); } extractor.release(); }
        if (decoded.isEmpty()) return new short[0];
        short[] source = new short[decoded.size()]; for (int i = 0; i < source.length; i++) source[i] = decoded.get(i);
        if (inputRate == SAMPLE_RATE) return source;
        int outLength = Math.max(1, Math.round(source.length * SAMPLE_RATE / (float) inputRate)); short[] result = new short[outLength];
        for (int i = 0; i < outLength; i++) { float position = i * (source.length - 1f) / Math.max(1, outLength - 1); int left = (int) position, right = Math.min(source.length - 1, left + 1); float f = position - left; result[i] = (short) Math.round(source[left] * (1 - f) + source[right] * f); }
        return result;
    }
}

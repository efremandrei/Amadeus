package com.efremandrei.amadeus;

import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.net.Uri;

import java.io.DataOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.CancellationException;

/** Writes the mixed loop to common audio formats. */
public final class ExportManager {
    public static final int FORMAT_WAV = 0;
    public static final int FORMAT_M4A = 1;
    public static final int FORMAT_MP3 = 2;
    private static final int SAMPLE_RATE = LoopEngine.SAMPLE_RATE;

    public static final class Settings {
        public final int sampleRate;
        public final int bitRateKbps;
        public final int bitDepth;
        public final boolean normalize;
        public final int fadeInMs;
        public final int fadeOutMs;
        public final float targetLufs;
        public final int channels;
        public final String title;
        public final String artist;
        public final String album;
        public final byte[] artwork;
        public final String artworkMime;

        public Settings(int sampleRate, int bitRateKbps, int bitDepth, boolean normalize, int fadeInMs, int fadeOutMs) {
            this(sampleRate, bitRateKbps, bitDepth, normalize, fadeInMs, fadeOutMs, -14f);
        }

        public Settings(int sampleRate, int bitRateKbps, int bitDepth, boolean normalize, int fadeInMs, int fadeOutMs, float targetLufs) {
            this(sampleRate, bitRateKbps, bitDepth, normalize, fadeInMs, fadeOutMs, targetLufs, false, "", "", "", null, "image/jpeg");
        }

        public Settings(int sampleRate, int bitRateKbps, int bitDepth, boolean normalize, int fadeInMs, int fadeOutMs, float targetLufs, boolean stereo, String title, String artist, String album, byte[] artwork, String artworkMime) {
            this.sampleRate = sampleRate == 48000 ? 48000 : SAMPLE_RATE;
            this.bitRateKbps = Math.max(64, Math.min(320, bitRateKbps));
            this.bitDepth = bitDepth == 24 ? 24 : 16;
            this.normalize = normalize;
            this.fadeInMs = Math.max(0, fadeInMs);
            this.fadeOutMs = Math.max(0, fadeOutMs);
            this.targetLufs = Math.max(-24f, Math.min(-8f, targetLufs));
            this.channels = stereo ? 2 : 1;
            this.title = title == null ? "" : title.trim(); this.artist = artist == null ? "" : artist.trim(); this.album = album == null ? "" : album.trim(); this.artwork = artwork; this.artworkMime = artworkMime == null ? "image/jpeg" : artworkMime;
        }

        public static Settings defaults(int format) {
            return new Settings(SAMPLE_RATE, format == FORMAT_M4A ? 128 : 192, 16, true, 0, 0);
        }
    }

    public interface ProgressListener {
        void onProgress(int percent);
        boolean isCancelled();
    }

    private ExportManager() { }

    public static String extension(int format) { return format == FORMAT_WAV ? "wav" : format == FORMAT_M4A ? "m4a" : "mp3"; }
    public static String mimeType(int format) { return format == FORMAT_WAV ? "audio/wav" : format == FORMAT_M4A ? "audio/mp4" : "audio/mpeg"; }

    public static void export(Context context, Uri destination, int format, short[] pcm) throws Exception {
        export(context, destination, format, pcm, Settings.defaults(format));
    }

    public static void export(Context context, Uri destination, int format, short[] pcm, Settings settings) throws Exception {
        export(context, destination, format, pcm, settings, null);
    }

    public static void export(Context context, Uri destination, int format, short[] pcm, Settings settings, ProgressListener listener) throws Exception {
        if (pcm == null || pcm.length == 0) throw new IllegalArgumentException("There is no recorded loop to export yet.");
        Settings safeSettings = settings == null ? Settings.defaults(format) : settings;
        short[] prepared = preparePcm(pcm, safeSettings, listener);
        checkCancelled(listener); report(listener, 30);
        if (format == FORMAT_WAV) writeWav(context, destination, prepared, safeSettings, listener);
        else if (format == FORMAT_M4A) writeM4a(context, destination, prepared, safeSettings, listener);
        else writeMp3(context, destination, prepared, safeSettings, listener);
        report(listener, 100);
    }

    private static short[] preparePcm(short[] source, Settings settings, ProgressListener listener) {
        int sourceFrames = Math.max(1, source.length / settings.channels), outputFrames = Math.max(1, Math.round(sourceFrames * settings.sampleRate / (float) SAMPLE_RATE));
        short[] result = new short[outputFrames * settings.channels];
        float peak = 0f; double energy = 0d;
        if (settings.normalize) {
            for (short sample : source) { float normalized = sample / 32768f; peak = Math.max(peak, Math.abs(normalized)); energy += normalized * normalized; }
        }
        float gain = 1f;
        if (settings.normalize && source.length > 0 && energy > 0d) {
            float rms = (float) Math.sqrt(energy / source.length);
            float currentLufs = 20f * (float) Math.log10(Math.max(0.000001f, rms));
            gain = (float) Math.pow(10d, (settings.targetLufs - currentLufs) / 20d);
            if (peak * gain > .98f) gain = .98f / peak;
        }
        int fadeInSamples = Math.min(outputFrames, Math.round(settings.fadeInMs * settings.sampleRate / 1000f));
        int fadeOutSamples = Math.min(outputFrames, Math.round(settings.fadeOutMs * settings.sampleRate / 1000f));
        for (int frame = 0; frame < outputFrames; frame++) {
            if ((frame & 2047) == 0) { checkCancelled(listener); report(listener, Math.round(30f * frame / outputFrames)); }
            float sourcePosition = frame * (sourceFrames - 1f) / Math.max(1, outputFrames - 1); int leftFrame = Math.max(0, Math.min(sourceFrames - 1, (int) sourcePosition)); int rightFrame = Math.min(sourceFrames - 1, leftFrame + 1); float fraction = sourcePosition - leftFrame;
            float fade = 1f; if (fadeInSamples > 0 && frame < fadeInSamples) fade *= frame / (float) fadeInSamples; if (fadeOutSamples > 0 && frame >= outputFrames - fadeOutSamples) fade *= (outputFrames - 1 - frame) / (float) fadeOutSamples;
            for (int channel = 0; channel < settings.channels; channel++) { float value = source[leftFrame * settings.channels + channel] + (source[rightFrame * settings.channels + channel] - source[leftFrame * settings.channels + channel]) * fraction; value *= gain * fade; result[frame * settings.channels + channel] = (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, Math.round(value))); }
        }
        return result;
    }

    private static void writeWav(Context context, Uri destination, short[] pcm, Settings settings, ProgressListener listener) throws Exception {
        OutputStream stream = context.getContentResolver().openOutputStream(destination);
        if (stream == null) throw new IllegalStateException("Could not open the selected destination.");
        try (DataOutputStream out = new DataOutputStream(stream)) {
            int bytesPerSample = settings.bitDepth / 8, dataSize = pcm.length * bytesPerSample;
            out.writeBytes("RIFF"); writeIntLE(out, 36 + dataSize); out.writeBytes("WAVE"); out.writeBytes("fmt "); writeIntLE(out, 16); writeShortLE(out, 1); writeShortLE(out, settings.channels); writeIntLE(out, settings.sampleRate); writeIntLE(out, settings.sampleRate * settings.channels * bytesPerSample); writeShortLE(out, settings.channels * bytesPerSample); writeShortLE(out, settings.bitDepth); out.writeBytes("data"); writeIntLE(out, dataSize);
            for (int i = 0; i < pcm.length; i++) { if ((i & 4095) == 0) { checkCancelled(listener); report(listener, 30 + Math.round(70f * i / pcm.length)); } short sample = pcm[i]; if (settings.bitDepth == 24) write24LE(out, sample); else writeShortLE(out, sample); }
        }
    }

    private static void writeM4a(Context context, Uri destination, short[] pcm, Settings settings, ProgressListener listener) throws Exception {
        File temp = new File(context.getCacheDir(), "amadeus-export-" + System.nanoTime() + ".m4a");
        MediaCodec codec = null; MediaMuxer muxer = null;
        FileOutputStream tempOutput = null;
        try {
            codec = createEncoder("audio/mp4a-latm");
            if (codec == null) throw new IllegalStateException("This device does not provide an AAC encoder. Export WAV instead.");
            MediaFormat format = MediaFormat.createAudioFormat("audio/mp4a-latm", settings.sampleRate, settings.channels); format.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC); format.setInteger(MediaFormat.KEY_BIT_RATE, settings.bitRateKbps * 1000); format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384);
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE); codec.start(); tempOutput = new FileOutputStream(temp); muxer = new MediaMuxer(tempOutput.getFD(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            drainAac(codec, muxer, pcm, settings.sampleRate, settings.channels, listener);
        } finally {
            if (codec != null) { try { codec.stop(); } catch (Exception ignored) {} codec.release(); }
            if (muxer != null) { try { muxer.stop(); } catch (Exception ignored) {} muxer.release(); }
            if (tempOutput != null) tempOutput.close();
        }
        byte[] bytes = readFile(temp); bytes = addM4aMetadata(bytes, settings); OutputStream destinationStream = context.getContentResolver().openOutputStream(destination); if (destinationStream == null) throw new IllegalStateException("Could not open the selected destination."); try (OutputStream out = destinationStream) { out.write(bytes); } finally { if (!temp.delete()) temp.deleteOnExit(); }
    }

    private static byte[] readFile(File file) throws Exception {
        try (FileInputStream input = new FileInputStream(file); ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192]; int read; while ((read = input.read(buffer)) >= 0) bytes.write(buffer, 0, read); return bytes.toByteArray();
        }
    }

    /** Adds standard iTunes-style M4A metadata and repairs media offsets if moov precedes mdat. */
    private static byte[] addM4aMetadata(byte[] source, Settings settings) {
        if (source == null || source.length < 16 || (settings.title.isEmpty() && settings.artist.isEmpty() && settings.album.isEmpty() && (settings.artwork == null || settings.artwork.length == 0))) return source;
        int moov = -1, moovSize = 0, mdat = -1, offset = 0;
        while (offset + 8 <= source.length) {
            int size = readIntBE(source, offset); if (size < 8 || offset + size > source.length) break;
            String type = new String(source, offset + 4, 4, java.nio.charset.StandardCharsets.ISO_8859_1);
            if ("moov".equals(type)) { moov = offset; moovSize = size; }
            if ("mdat".equals(type)) mdat = offset;
            offset += size;
        }
        if (moov < 0 || mdat < 0) return source;
        byte[] udta = m4aUdta(settings); if (udta.length == 0) return source;
        byte[] result = new byte[source.length + udta.length]; int moovEnd = moov + moovSize;
        System.arraycopy(source, 0, result, 0, moovEnd); writeIntBE(result, moov, moovSize + udta.length); System.arraycopy(udta, 0, result, moovEnd, udta.length); System.arraycopy(source, moovEnd, result, moovEnd + udta.length, source.length - moovEnd);
        if (mdat > moov) patchChunkOffsets(result, moov + 8, moovEnd, udta.length);
        return result;
    }

    private static void patchChunkOffsets(byte[] data, int start, int end, int delta) {
        int offset = start;
        while (offset + 8 <= end) {
            int size = readIntBE(data, offset); if (size < 8 || offset + size > end) return;
            String type = new String(data, offset + 4, 4, java.nio.charset.StandardCharsets.ISO_8859_1);
            if ("stco".equals(type) && size >= 16) { int count = readIntBE(data, offset + 12); for (int i = 0; i < count && offset + 16 + i * 4 + 4 <= offset + size; i++) writeIntBE(data, offset + 16 + i * 4, readIntBE(data, offset + 16 + i * 4) + delta); }
            else if ("co64".equals(type) && size >= 16) { int count = readIntBE(data, offset + 12); for (int i = 0; i < count && offset + 16 + i * 8 + 8 <= offset + size; i++) { int at = offset + 16 + i * 8; long value = readLongBE(data, at) + delta; writeLongBE(data, at, value); } }
            else if (isContainerAtom(type)) patchChunkOffsets(data, offset + 8 + ("meta".equals(type) ? 4 : 0), offset + size, delta);
            offset += size;
        }
    }

    private static boolean isContainerAtom(String type) { return "moov".equals(type) || "trak".equals(type) || "mdia".equals(type) || "minf".equals(type) || "stbl".equals(type) || "edts".equals(type) || "dinf".equals(type) || "mvex".equals(type) || "moof".equals(type) || "traf".equals(type) || "udta".equals(type) || "meta".equals(type); }

    private static byte[] m4aUdta(Settings settings) {
        ByteArrayOutputStream items = new ByteArrayOutputStream();
        try { if (!settings.title.isEmpty()) writeAtom(items, "©nam", m4aData(settings.title.getBytes("UTF-8"), 1)); if (!settings.artist.isEmpty()) writeAtom(items, "©ART", m4aData(settings.artist.getBytes("UTF-8"), 1)); if (!settings.album.isEmpty()) writeAtom(items, "©alb", m4aData(settings.album.getBytes("UTF-8"), 1)); if (settings.artwork != null && settings.artwork.length > 0) writeAtom(items, "covr", m4aData(settings.artwork, settings.artworkMime.toLowerCase().contains("png") ? 14 : 13));
            byte[] handlerBody = new byte[25]; System.arraycopy(new byte[]{'m','d','i','r'}, 0, handlerBody, 8, 4); ByteArrayOutputStream handler = new ByteArrayOutputStream(); writeAtom(handler, "hdlr", handlerBody); ByteArrayOutputStream ilst = new ByteArrayOutputStream(); writeAtom(ilst, "ilst", items.toByteArray()); ByteArrayOutputStream metaBody = new ByteArrayOutputStream(); metaBody.write(new byte[4]); metaBody.write(handler.toByteArray()); metaBody.write(ilst.toByteArray()); ByteArrayOutputStream meta = new ByteArrayOutputStream(); writeAtom(meta, "meta", metaBody.toByteArray()); ByteArrayOutputStream udta = new ByteArrayOutputStream(); writeAtom(udta, "udta", meta.toByteArray()); return udta.toByteArray();
        } catch (Exception ignored) { return new byte[0]; }
    }

    private static byte[] m4aData(byte[] value, int type) throws Exception { ByteArrayOutputStream body = new ByteArrayOutputStream(); body.write(new byte[]{0, 0, 0, (byte) type}); body.write(new byte[4]); body.write(value); ByteArrayOutputStream atom = new ByteArrayOutputStream(); writeAtom(atom, "data", body.toByteArray()); return atom.toByteArray(); }
    private static void writeAtom(ByteArrayOutputStream out, String type, byte[] body) throws Exception { writeIntBE(out, 8 + body.length); out.write(type.getBytes("ISO-8859-1")); out.write(body); }
    private static void writeIntBE(ByteArrayOutputStream out, int value) { out.write((value >> 24) & 0xff); out.write((value >> 16) & 0xff); out.write((value >> 8) & 0xff); out.write(value & 0xff); }
    private static void writeIntBE(byte[] bytes, int offset, int value) { bytes[offset] = (byte) (value >> 24); bytes[offset + 1] = (byte) (value >> 16); bytes[offset + 2] = (byte) (value >> 8); bytes[offset + 3] = (byte) value; }
    private static int readIntBE(byte[] bytes, int offset) { return ((bytes[offset] & 0xff) << 24) | ((bytes[offset + 1] & 0xff) << 16) | ((bytes[offset + 2] & 0xff) << 8) | (bytes[offset + 3] & 0xff); }
    private static long readLongBE(byte[] bytes, int offset) { return ((long) readIntBE(bytes, offset) << 32) | (readIntBE(bytes, offset + 4) & 0xffffffffL); }
    private static void writeLongBE(byte[] bytes, int offset, long value) { writeIntBE(bytes, offset, (int) (value >> 32)); writeIntBE(bytes, offset + 4, (int) value); }

    private static void drainAac(MediaCodec codec, MediaMuxer muxer, short[] pcm, int sampleRate, int channels, ProgressListener listener) throws Exception {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo(); int sampleOffset = 0, track = -1; boolean inputDone = false, outputDone = false, muxerStarted = false;
        while (!outputDone) {
            checkCancelled(listener);
            if (!inputDone) {
                int inputIndex = codec.dequeueInputBuffer(10000);
                if (inputIndex >= 0) {
                    ByteBuffer input = codec.getInputBuffer(inputIndex); input.clear(); input.order(ByteOrder.LITTLE_ENDIAN); int capacity = input.remaining() / 2; int count = Math.min(capacity, pcm.length - sampleOffset);
                    for (int i = 0; i < count; i++) input.putShort(pcm[sampleOffset + i]);
                    long pts = (sampleOffset / channels) * 1000000L / sampleRate;
                    if (count > 0) { sampleOffset += count; report(listener, 30 + Math.round(70f * sampleOffset / pcm.length)); codec.queueInputBuffer(inputIndex, 0, count * 2, pts, sampleOffset >= pcm.length ? MediaCodec.BUFFER_FLAG_END_OF_STREAM : 0); if (sampleOffset >= pcm.length) inputDone = true; }
                    else { codec.queueInputBuffer(inputIndex, 0, 0, pts, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputDone = true; }
                }
            }
            int outputIndex = codec.dequeueOutputBuffer(info, 10000);
            if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) { track = muxer.addTrack(codec.getOutputFormat()); muxer.start(); muxerStarted = true; }
            else if (outputIndex >= 0) { ByteBuffer output = codec.getOutputBuffer(outputIndex); if (output != null && info.size > 0 && muxerStarted && (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) { output.position(info.offset); output.limit(info.offset + info.size); muxer.writeSampleData(track, output, info); } codec.releaseOutputBuffer(outputIndex, false); if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outputDone = true; }
        }
    }

    private static void writeMp3(Context context, Uri destination, short[] pcm, Settings settings, ProgressListener listener) throws Exception {
        MediaCodec codec = createEncoder("audio/mpeg");
        if (codec == null) throw new IllegalStateException("This device does not provide an MP3 encoder. Export WAV or M4A instead.");
        try (OutputStream out = context.getContentResolver().openOutputStream(destination)) {
            if (out == null) throw new IllegalStateException("Could not open the selected destination.");
            writeId3(out, settings);
            MediaFormat format = MediaFormat.createAudioFormat("audio/mpeg", settings.sampleRate, settings.channels); format.setInteger(MediaFormat.KEY_BIT_RATE, settings.bitRateKbps * 1000); format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384); codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE); codec.start();
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo(); int sampleOffset = 0; boolean inputDone = false, outputDone = false;
            while (!outputDone) {
                checkCancelled(listener);
                if (!inputDone) { int inputIndex = codec.dequeueInputBuffer(10000); if (inputIndex >= 0) { ByteBuffer input = codec.getInputBuffer(inputIndex); input.clear(); input.order(ByteOrder.LITTLE_ENDIAN); int capacity = input.remaining() / 2; int count = Math.min(capacity, pcm.length - sampleOffset); for (int i = 0; i < count; i++) input.putShort(pcm[sampleOffset + i]); long pts = (sampleOffset / settings.channels) * 1000000L / settings.sampleRate; if (count > 0) { sampleOffset += count; report(listener, 30 + Math.round(70f * sampleOffset / pcm.length)); codec.queueInputBuffer(inputIndex, 0, count * 2, pts, sampleOffset >= pcm.length ? MediaCodec.BUFFER_FLAG_END_OF_STREAM : 0); if (sampleOffset >= pcm.length) inputDone = true; } else { codec.queueInputBuffer(inputIndex, 0, 0, pts, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputDone = true; } } }
                int outputIndex = codec.dequeueOutputBuffer(info, 10000); if (outputIndex >= 0) { ByteBuffer output = codec.getOutputBuffer(outputIndex); if (output != null && info.size > 0 && (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) { output.position(info.offset); output.limit(info.offset + info.size); byte[] bytes = new byte[info.size]; output.get(bytes); out.write(bytes); } codec.releaseOutputBuffer(outputIndex, false); if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outputDone = true; }
            }
        } finally { try { codec.stop(); } catch (Exception ignored) {} codec.release(); }
    }

    private static MediaCodec createEncoder(String mime) throws Exception {
        for (MediaCodecInfo info : new MediaCodecList(MediaCodecList.ALL_CODECS).getCodecInfos()) if (info.isEncoder()) for (String type : info.getSupportedTypes()) if (mime.equalsIgnoreCase(type)) return MediaCodec.createByCodecName(info.getName());
        return null;
    }

    private static void writeIntLE(DataOutputStream out, int value) throws Exception { out.writeByte(value & 0xff); out.writeByte((value >> 8) & 0xff); out.writeByte((value >> 16) & 0xff); out.writeByte((value >> 24) & 0xff); }
    private static void writeShortLE(DataOutputStream out, int value) throws Exception { out.writeByte(value & 0xff); out.writeByte((value >> 8) & 0xff); }
    private static void write24LE(DataOutputStream out, short value) throws Exception { int sample = value << 8; out.writeByte(sample & 0xff); out.writeByte((sample >> 8) & 0xff); out.writeByte((sample >> 16) & 0xff); }
    private static void writeId3(OutputStream out, Settings settings) throws Exception {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        writeId3Text(body, "TIT2", settings.title); writeId3Text(body, "TPE1", settings.artist); writeId3Text(body, "TALB", settings.album);
        if (settings.artwork != null && settings.artwork.length > 0) { ByteArrayOutputStream frame = new ByteArrayOutputStream(); frame.write(3); frame.write(settings.artworkMime.getBytes("ISO-8859-1")); frame.write(0); frame.write(3); frame.write(0); frame.write(settings.artwork); writeId3Frame(body, "APIC", frame.toByteArray()); }
        byte[] bytes = body.toByteArray(); out.write(new byte[]{'I', 'D', '3', 4, 0, 0}); out.write(syncsafe(bytes.length)); out.write(bytes);
    }
    private static void writeId3Text(ByteArrayOutputStream body, String id, String value) throws Exception { if (value == null || value.isEmpty()) return; ByteArrayOutputStream frame = new ByteArrayOutputStream(); frame.write(3); frame.write(value.getBytes("UTF-8")); writeId3Frame(body, id, frame.toByteArray()); }
    private static void writeId3Frame(ByteArrayOutputStream body, String id, byte[] payload) throws Exception { body.write(id.getBytes("ISO-8859-1")); body.write(syncsafe(payload.length)); body.write(new byte[]{0, 0}); body.write(payload); }
    private static byte[] syncsafe(int value) { return new byte[]{(byte) ((value >> 21) & 0x7f), (byte) ((value >> 14) & 0x7f), (byte) ((value >> 7) & 0x7f), (byte) (value & 0x7f)}; }
    private static void report(ProgressListener listener, int percent) { if (listener != null) listener.onProgress(Math.max(0, Math.min(100, percent))); }
    private static void checkCancelled(ProgressListener listener) { if (listener != null && listener.isCancelled()) throw new CancellationException("Export canceled"); }
}

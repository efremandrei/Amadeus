package com.efremandrei.amadeus;

import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.net.Uri;

import java.io.DataOutputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.CancellationException;
import android.os.ParcelFileDescriptor;

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

        public Settings(int sampleRate, int bitRateKbps, int bitDepth, boolean normalize, int fadeInMs, int fadeOutMs) {
            this(sampleRate, bitRateKbps, bitDepth, normalize, fadeInMs, fadeOutMs, -14f);
        }

        public Settings(int sampleRate, int bitRateKbps, int bitDepth, boolean normalize, int fadeInMs, int fadeOutMs, float targetLufs) {
            this.sampleRate = sampleRate == 48000 ? 48000 : SAMPLE_RATE;
            this.bitRateKbps = Math.max(64, Math.min(320, bitRateKbps));
            this.bitDepth = bitDepth == 24 ? 24 : 16;
            this.normalize = normalize;
            this.fadeInMs = Math.max(0, fadeInMs);
            this.fadeOutMs = Math.max(0, fadeOutMs);
            this.targetLufs = Math.max(-24f, Math.min(-8f, targetLufs));
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
        int outputLength = Math.max(1, Math.round(source.length * settings.sampleRate / (float) SAMPLE_RATE));
        short[] result = new short[outputLength];
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
        int fadeInSamples = Math.min(outputLength, Math.round(settings.fadeInMs * settings.sampleRate / 1000f));
        int fadeOutSamples = Math.min(outputLength, Math.round(settings.fadeOutMs * settings.sampleRate / 1000f));
        for (int i = 0; i < outputLength; i++) {
            if ((i & 2047) == 0) { checkCancelled(listener); report(listener, Math.round(30f * i / outputLength)); }
            float sourcePosition = i * (source.length - 1f) / Math.max(1, outputLength - 1);
            int left = Math.max(0, Math.min(source.length - 1, (int) sourcePosition));
            int right = Math.min(source.length - 1, left + 1);
            float value = source[left] + (source[right] - source[left]) * (sourcePosition - left);
            value *= gain;
            if (fadeInSamples > 0 && i < fadeInSamples) value *= i / (float) fadeInSamples;
            if (fadeOutSamples > 0 && i >= outputLength - fadeOutSamples) value *= (outputLength - 1 - i) / (float) fadeOutSamples;
            result[i] = (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, Math.round(value)));
        }
        return result;
    }

    private static void writeWav(Context context, Uri destination, short[] pcm, Settings settings, ProgressListener listener) throws Exception {
        OutputStream stream = context.getContentResolver().openOutputStream(destination);
        if (stream == null) throw new IllegalStateException("Could not open the selected destination.");
        try (DataOutputStream out = new DataOutputStream(stream)) {
            int bytesPerSample = settings.bitDepth / 8, dataSize = pcm.length * bytesPerSample;
            out.writeBytes("RIFF"); writeIntLE(out, 36 + dataSize); out.writeBytes("WAVE"); out.writeBytes("fmt "); writeIntLE(out, 16); writeShortLE(out, 1); writeShortLE(out, 1); writeIntLE(out, settings.sampleRate); writeIntLE(out, settings.sampleRate * bytesPerSample); writeShortLE(out, bytesPerSample); writeShortLE(out, settings.bitDepth); out.writeBytes("data"); writeIntLE(out, dataSize);
            for (int i = 0; i < pcm.length; i++) { if ((i & 4095) == 0) { checkCancelled(listener); report(listener, 30 + Math.round(70f * i / pcm.length)); } short sample = pcm[i]; if (settings.bitDepth == 24) write24LE(out, sample); else writeShortLE(out, sample); }
        }
    }

    private static void writeM4a(Context context, Uri destination, short[] pcm, Settings settings, ProgressListener listener) throws Exception {
        ParcelFileDescriptor descriptor = context.getContentResolver().openFileDescriptor(destination, "w");
        if (descriptor == null) throw new IllegalStateException("Could not open the selected destination.");
        MediaCodec codec = null; MediaMuxer muxer = null; boolean muxerStarted = false;
        try {
            codec = createEncoder("audio/mp4a-latm");
            if (codec == null) throw new IllegalStateException("This device does not provide an AAC encoder. Export WAV instead.");
            MediaFormat format = MediaFormat.createAudioFormat("audio/mp4a-latm", settings.sampleRate, 1); format.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC); format.setInteger(MediaFormat.KEY_BIT_RATE, settings.bitRateKbps * 1000); format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384);
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE); codec.start(); muxer = new MediaMuxer(descriptor.getFileDescriptor(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            drainAac(codec, muxer, pcm, settings.sampleRate, listener); muxerStarted = true;
        } finally {
            if (codec != null) { try { codec.stop(); } catch (Exception ignored) {} codec.release(); }
            if (muxer != null) { try { muxer.stop(); } catch (Exception ignored) {} muxer.release(); }
            descriptor.close();
        }
    }

    private static void drainAac(MediaCodec codec, MediaMuxer muxer, short[] pcm, int sampleRate, ProgressListener listener) throws Exception {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo(); int sampleOffset = 0, track = -1; boolean inputDone = false, outputDone = false, muxerStarted = false;
        while (!outputDone) {
            checkCancelled(listener);
            if (!inputDone) {
                int inputIndex = codec.dequeueInputBuffer(10000);
                if (inputIndex >= 0) {
                    ByteBuffer input = codec.getInputBuffer(inputIndex); input.clear(); input.order(ByteOrder.LITTLE_ENDIAN); int capacity = input.remaining() / 2; int count = Math.min(capacity, pcm.length - sampleOffset);
                    for (int i = 0; i < count; i++) input.putShort(pcm[sampleOffset + i]);
                    long pts = sampleOffset * 1000000L / sampleRate;
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
            out.write(new byte[]{'I', 'D', '3', 4, 0, 0, 0, 0, 0, 0});
            MediaFormat format = MediaFormat.createAudioFormat("audio/mpeg", settings.sampleRate, 1); format.setInteger(MediaFormat.KEY_BIT_RATE, settings.bitRateKbps * 1000); format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384); codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE); codec.start();
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo(); int sampleOffset = 0; boolean inputDone = false, outputDone = false;
            while (!outputDone) {
                checkCancelled(listener);
                if (!inputDone) { int inputIndex = codec.dequeueInputBuffer(10000); if (inputIndex >= 0) { ByteBuffer input = codec.getInputBuffer(inputIndex); input.clear(); input.order(ByteOrder.LITTLE_ENDIAN); int capacity = input.remaining() / 2; int count = Math.min(capacity, pcm.length - sampleOffset); for (int i = 0; i < count; i++) input.putShort(pcm[sampleOffset + i]); long pts = sampleOffset * 1000000L / settings.sampleRate; if (count > 0) { sampleOffset += count; report(listener, 30 + Math.round(70f * sampleOffset / pcm.length)); codec.queueInputBuffer(inputIndex, 0, count * 2, pts, sampleOffset >= pcm.length ? MediaCodec.BUFFER_FLAG_END_OF_STREAM : 0); if (sampleOffset >= pcm.length) inputDone = true; } else { codec.queueInputBuffer(inputIndex, 0, 0, pts, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputDone = true; } } }
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
    private static void report(ProgressListener listener, int percent) { if (listener != null) listener.onProgress(Math.max(0, Math.min(100, percent))); }
    private static void checkCancelled(ProgressListener listener) { if (listener != null && listener.isCancelled()) throw new CancellationException("Export canceled"); }
}

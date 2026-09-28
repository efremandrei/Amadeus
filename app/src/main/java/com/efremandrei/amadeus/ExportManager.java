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
import android.os.ParcelFileDescriptor;

/** Writes the mixed loop to common audio formats. */
public final class ExportManager {
    public static final int FORMAT_WAV = 0;
    public static final int FORMAT_M4A = 1;
    public static final int FORMAT_MP3 = 2;
    private static final int SAMPLE_RATE = LoopEngine.SAMPLE_RATE;

    private ExportManager() { }

    public static String extension(int format) { return format == FORMAT_WAV ? "wav" : format == FORMAT_M4A ? "m4a" : "mp3"; }
    public static String mimeType(int format) { return format == FORMAT_WAV ? "audio/wav" : format == FORMAT_M4A ? "audio/mp4" : "audio/mpeg"; }

    public static void export(Context context, Uri destination, int format, short[] pcm) throws Exception {
        if (pcm == null || pcm.length == 0) throw new IllegalArgumentException("There is no recorded loop to export yet.");
        if (format == FORMAT_WAV) writeWav(context, destination, pcm);
        else if (format == FORMAT_M4A) writeM4a(context, destination, pcm);
        else writeMp3(context, destination, pcm);
    }

    private static void writeWav(Context context, Uri destination, short[] pcm) throws Exception {
        OutputStream stream = context.getContentResolver().openOutputStream(destination);
        if (stream == null) throw new IllegalStateException("Could not open the selected destination.");
        try (DataOutputStream out = new DataOutputStream(stream)) {
            int dataSize = pcm.length * 2;
            out.writeBytes("RIFF"); writeIntLE(out, 36 + dataSize); out.writeBytes("WAVE"); out.writeBytes("fmt "); writeIntLE(out, 16); writeShortLE(out, 1); writeShortLE(out, 1); writeIntLE(out, SAMPLE_RATE); writeIntLE(out, SAMPLE_RATE * 2); writeShortLE(out, 2); writeShortLE(out, 16); out.writeBytes("data"); writeIntLE(out, dataSize);
            for (short sample : pcm) writeShortLE(out, sample);
        }
    }

    private static void writeM4a(Context context, Uri destination, short[] pcm) throws Exception {
        ParcelFileDescriptor descriptor = context.getContentResolver().openFileDescriptor(destination, "w");
        if (descriptor == null) throw new IllegalStateException("Could not open the selected destination.");
        MediaCodec codec = null; MediaMuxer muxer = null; boolean muxerStarted = false;
        try {
            codec = createEncoder("audio/mp4a-latm");
            if (codec == null) throw new IllegalStateException("This device does not provide an AAC encoder. Export WAV instead.");
            MediaFormat format = MediaFormat.createAudioFormat("audio/mp4a-latm", SAMPLE_RATE, 1); format.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC); format.setInteger(MediaFormat.KEY_BIT_RATE, 128000); format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384);
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE); codec.start(); muxer = new MediaMuxer(descriptor.getFileDescriptor(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            drainAac(codec, muxer, pcm); muxerStarted = true;
        } finally {
            if (codec != null) { try { codec.stop(); } catch (Exception ignored) {} codec.release(); }
            if (muxer != null) { try { muxer.stop(); } catch (Exception ignored) {} muxer.release(); }
            descriptor.close();
        }
    }

    private static void drainAac(MediaCodec codec, MediaMuxer muxer, short[] pcm) throws Exception {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo(); int sampleOffset = 0, track = -1; boolean inputDone = false, outputDone = false, muxerStarted = false;
        while (!outputDone) {
            if (!inputDone) {
                int inputIndex = codec.dequeueInputBuffer(10000);
                if (inputIndex >= 0) {
                    ByteBuffer input = codec.getInputBuffer(inputIndex); input.clear(); input.order(ByteOrder.LITTLE_ENDIAN); int capacity = input.remaining() / 2; int count = Math.min(capacity, pcm.length - sampleOffset);
                    for (int i = 0; i < count; i++) input.putShort(pcm[sampleOffset + i]);
                    long pts = sampleOffset * 1000000L / SAMPLE_RATE;
                    if (count > 0) { sampleOffset += count; codec.queueInputBuffer(inputIndex, 0, count * 2, pts, sampleOffset >= pcm.length ? MediaCodec.BUFFER_FLAG_END_OF_STREAM : 0); if (sampleOffset >= pcm.length) inputDone = true; }
                    else { codec.queueInputBuffer(inputIndex, 0, 0, pts, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputDone = true; }
                }
            }
            int outputIndex = codec.dequeueOutputBuffer(info, 10000);
            if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) { track = muxer.addTrack(codec.getOutputFormat()); muxer.start(); muxerStarted = true; }
            else if (outputIndex >= 0) { ByteBuffer output = codec.getOutputBuffer(outputIndex); if (output != null && info.size > 0 && muxerStarted && (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) { output.position(info.offset); output.limit(info.offset + info.size); muxer.writeSampleData(track, output, info); } codec.releaseOutputBuffer(outputIndex, false); if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outputDone = true; }
        }
    }

    private static void writeMp3(Context context, Uri destination, short[] pcm) throws Exception {
        MediaCodec codec = createEncoder("audio/mpeg");
        if (codec == null) throw new IllegalStateException("This device does not provide an MP3 encoder. Export WAV or M4A instead.");
        try (OutputStream out = context.getContentResolver().openOutputStream(destination)) {
            if (out == null) throw new IllegalStateException("Could not open the selected destination.");
            out.write(new byte[]{'I', 'D', '3', 4, 0, 0, 0, 0, 0, 0});
            MediaFormat format = MediaFormat.createAudioFormat("audio/mpeg", SAMPLE_RATE, 1); format.setInteger(MediaFormat.KEY_BIT_RATE, 192000); format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384); codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE); codec.start();
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo(); int sampleOffset = 0; boolean inputDone = false, outputDone = false;
            while (!outputDone) {
                if (!inputDone) { int inputIndex = codec.dequeueInputBuffer(10000); if (inputIndex >= 0) { ByteBuffer input = codec.getInputBuffer(inputIndex); input.clear(); input.order(ByteOrder.LITTLE_ENDIAN); int capacity = input.remaining() / 2; int count = Math.min(capacity, pcm.length - sampleOffset); for (int i = 0; i < count; i++) input.putShort(pcm[sampleOffset + i]); long pts = sampleOffset * 1000000L / SAMPLE_RATE; if (count > 0) { sampleOffset += count; codec.queueInputBuffer(inputIndex, 0, count * 2, pts, sampleOffset >= pcm.length ? MediaCodec.BUFFER_FLAG_END_OF_STREAM : 0); if (sampleOffset >= pcm.length) inputDone = true; } else { codec.queueInputBuffer(inputIndex, 0, 0, pts, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputDone = true; } } }
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
}

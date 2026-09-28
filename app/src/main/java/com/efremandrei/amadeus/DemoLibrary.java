package com.efremandrei.amadeus;

import java.util.ArrayList;
import java.util.List;

/** Small on-device demo sessions that demonstrate the loop workflow without network assets. */
public final class DemoLibrary {
    public static final class Layer {
        public final String name;
        public final int colorIndex;
        public final short[] pcm;
        Layer(String name, int colorIndex, short[] pcm) { this.name = name; this.colorIndex = colorIndex; this.pcm = pcm; }
    }

    public static final class Demo {
        public final String name;
        public final String description;
        public final int loopLength;
        public final List<Layer> layers;
        Demo(String name, String description, int loopLength, List<Layer> layers) { this.name = name; this.description = description; this.loopLength = loopLength; this.layers = layers; }
    }

    private static final int RATE = LoopEngine.SAMPLE_RATE;
    private static final int LENGTH = RATE * 4;
    private static final int BEAT = RATE / 2;

    private DemoLibrary() { }

    public static String[] names() { return new String[]{"First Steps", "Pocket Beat", "Neon Drive", "Ambient Bloom", "Pad Jam"}; }

    public static Demo get(int index) {
        switch (index) {
            case 1: return pocketBeat();
            case 2: return neonDrive();
            case 3: return ambientBloom();
            case 4: return padJam();
            default: return firstSteps();
        }
    }

    private static Demo firstSteps() {
        short[] pulse = empty(), click = empty();
        for (int beat = 0; beat < 8; beat += 2) addTone(pulse, beat * BEAT, 110, .42, .48);
        for (int beat = 0; beat < 8; beat++) addClick(click, beat * BEAT, .16);
        return demo("First Steps", "A simple pulse and click track showing how layers repeat.", layer("PULSE", 0, pulse), layer("CLICK", 1, click));
    }

    private static Demo pocketBeat() {
        short[] kick = empty(), snare = empty(), hats = empty();
        for (int beat : new int[]{0, 4}) addKick(kick, beat * BEAT);
        for (int beat : new int[]{2, 6}) addSnare(snare, beat * BEAT);
        for (int beat = 0; beat < 8; beat++) addHat(hats, beat * BEAT);
        return demo("Pocket Beat", "Kick, snare, and hi-hat layers for a classic loop-station build.", layer("KICK", 2, kick), layer("SNARE", 3, snare), layer("HI-HAT", 4, hats));
    }

    private static Demo neonDrive() {
        short[] bass = empty(), lead = empty(), kick = empty(); double[] notes = {110, 130.8, 146.8, 130.8, 110, 130.8, 164.8, 146.8};
        for (int beat = 0; beat < notes.length; beat++) addTone(bass, beat * BEAT, notes[beat], .38, .42);
        for (int beat : new int[]{1, 3, 5, 7}) addTone(lead, beat * BEAT, notes[beat] * 2, .28, .24);
        for (int beat : new int[]{0, 4}) addKick(kick, beat * BEAT);
        return demo("Neon Drive", "A bass pattern, bright lead, and kick layer demonstrating contrast.", layer("BASS", 0, bass), layer("LEAD", 5, lead), layer("KICK", 2, kick));
    }

    private static Demo ambientBloom() {
        short[] pad = empty(), chime = empty(), pulse = empty();
        for (double frequency : new double[]{130.8, 164.8, 196.0}) addTone(pad, 0, frequency, 3.7, .18);
        for (int beat : new int[]{0, 3, 6}) addTone(chime, beat * BEAT, beat == 3 ? 523.2 : 659.2, .8, .35);
        for (int beat : new int[]{0, 4}) addTone(pulse, beat * BEAT, 65.4, .65, .34);
        return demo("Ambient Bloom", "Slow chords and chimes showing that loops can be spacious and melodic.", layer("PAD", 1, pad), layer("CHIMES", 6, chime), layer("LOW PULSE", 0, pulse));
    }

    private static Demo padJam() {
        short[] bass = empty(), clap = empty(), tone = empty();
        for (int beat : new int[]{0, 2, 4, 6}) addTone(bass, beat * BEAT, beat % 4 == 0 ? 98 : 123.5, .32, .4);
        for (int beat : new int[]{2, 6}) addSnare(clap, beat * BEAT);
        for (int beat : new int[]{1, 3, 5, 7}) addTone(tone, beat * BEAT, 392 + (beat % 3) * 55, .25, .28);
        return demo("Pad Jam", "A playful three-layer example designed for switching to Sound Pads afterward.", layer("BASS", 2, bass), layer("CLAP", 3, clap), layer("TONE", 7, tone));
    }

    private static Demo demo(String name, String description, Layer... layers) { ArrayList<Layer> list = new ArrayList<>(); for (Layer layer : layers) list.add(layer); return new Demo(name, description, LENGTH, list); }
    private static Layer layer(String name, int color, short[] pcm) { return new Layer(name, color, pcm); }
    private static short[] empty() { return new short[LENGTH]; }

    private static void addTone(short[] target, int start, double frequency, double duration, double amplitude) { int count = Math.min((int) (duration * RATE), target.length - start); for (int i = 0; i < count; i++) { double n = i / (double) count; double value = Math.sin(2 * Math.PI * frequency * i / RATE) * Math.exp(-4.0 * n) * amplitude; add(target, start + i, value); } }
    private static void addKick(short[] target, int start) { int count = Math.min(RATE / 3, target.length - start); for (int i = 0; i < count; i++) { double n = i / (double) count; add(target, start + i, Math.sin(2 * Math.PI * (150 - 100 * n) * i / RATE) * Math.exp(-9 * n) * .72); } }
    private static void addSnare(short[] target, int start) { int count = Math.min(RATE / 4, target.length - start); long seed = start * 31L + 7; for (int i = 0; i < count; i++) { seed = seed * 1103515245L + 12345; double noise = (((seed >>> 16) & 0x7fff) / 16384.0) - 1.0; double n = i / (double) count; add(target, start + i, noise * Math.exp(-12 * n) * .42); } }
    private static void addHat(short[] target, int start) { int count = Math.min(RATE / 12, target.length - start); long seed = start * 17L + 3; for (int i = 0; i < count; i++) { seed = seed * 1103515245L + 12345; double noise = (((seed >>> 16) & 0x7fff) / 16384.0) - 1.0; add(target, start + i, noise * Math.exp(-20 * i / (double) count) * .2); } }
    private static void addClick(short[] target, int start, double duration) { int count = Math.min((int) (duration * RATE), target.length - start); for (int i = 0; i < count; i++) add(target, start + i, Math.sin(2 * Math.PI * 1800 * i / RATE) * Math.exp(-20.0 * i / Math.max(1, count)) * .25); }
    private static void add(short[] target, int index, double value) { if (index >= 0 && index < target.length) target[index] = (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, target[index] + (int) (value * 32767))); }
}

# Amadeus

Amadeus is a loop-first Android music sketchpad. Record a sound, let it repeat, and layer more loops on top.

## Current slice

- Native Android project with a dark-first loop-station interface
- Microphone recording with runtime permission handling
- First loop establishes the loop length
- Additional loops are fit to the first loop and played together
- Mute individual loops, undo the last loop, and toggle light/dark mode
- Long press any recorded loop to open its actions and delete that layer
- Save the current project inside the app without exporting an audio file
- Session audio is persisted locally between launches
- Debug APK export
- Sound Pads mode with three preset banks and eight one-shot buttons per bank
- Configure mode for assigning each pad from the built-in sound library
- Help and About dialogs with workflow guidance and app information
- Informative sound icons with configurable pad display: icons, text, or both
- Export the mixed loop through Android's file picker as WAV, M4A/AAC, or MP3 when a device encoder is available
- Safe system-bar spacing for status and navigation areas
- Top app-bar Help and About actions with ambient header glow and recording-button halo
- Live status badge, animated ambient lighting, active-pad glow, and responsive record feedback
- Two guided tutorials and five built-in demo loop sessions
- Overlap-safe responsive layout with swipeable loop layers and a scroll indicator
- Branded waveform-loop launcher icon and splash screen
- Exit action with confirmation from the bottom action bar
- Separated bottom action bands with larger, centered control labels
- Thin purple borders on dark-mode buttons for clearer affordances
- Clickable email and repository links in About
- Adjustable 40–220 BPM tempo with optional metronome click
- Long-press loop controls for mute, solo, volume, and delete
- Rename projects directly from the header
- Beat-snapped first recordings based on the selected tempo
- Import WAV/MP3/audio files directly into Sound Pads
- Per-loop echo and reverb controls applied to playback and export
- Full Timeline arrangement editor with draggable loop blocks, trim handles, duplicate/reorder/reset actions, and persisted ranges
- Advanced export quality controls for sample rate, WAV bit depth, codec bitrate, normalization, and fade in/out processing

## Build

```text
gradlew.bat assembleDebug
```

The APK is generated at `app/build/outputs/apk/debug/app-debug.apk`.

The first implementation uses Android's platform `AudioRecord` and `AudioTrack` APIs. A later audio-engine pass can move the realtime path to Oboe/AAudio for lower latency on supported devices.

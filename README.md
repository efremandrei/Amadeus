# Amadeus

Amadeus is a loop-first Android music sketchpad. Record a sound, let it repeat, and layer more loops on top.

## Current slice

- Native Android project with a dark-first loop-station interface
- Microphone recording with runtime permission handling
- First loop establishes the loop length
- Additional loops are fit to the first loop and played together
- Mute individual loops, undo the last loop, and toggle light/dark mode
- Session audio is persisted locally between launches
- Debug APK export
- Sound Pads mode with three preset banks and eight one-shot buttons per bank
- Configure mode for assigning each pad from the built-in sound library
- Help and About dialogs with workflow guidance and app information

## Build

```text
gradlew.bat assembleDebug
```

The APK is generated at `app/build/outputs/apk/debug/app-debug.apk`.

The first implementation uses Android's platform `AudioRecord` and `AudioTrack` APIs. A later audio-engine pass can move the realtime path to Oboe/AAudio for lower latency on supported devices.

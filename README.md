# Video Player

Android app for browsing folders on the phone and playing a video in the app when you tap it.

Grant access to storage, open a folder, and tap a file. Playback stays in this app. The player can play and pause, seek, skip 10 seconds, change speed and aspect ratio, and enter picture-in-picture.

## Playback

Decoding uses [LibVLC](https://code.videolan.org/videolan/vlc-android) (`org.videolan.android:libvlc-all`). Before play, the app reads the file's codec and profile and walks the device decoder list the way VLC does. MediaCodec is used when the first decoder VLC would accept is a hardware decoder. Otherwise the file is decoded with FFmpeg, so a software MediaCodec decoder is not left to draw a bad picture.

Qualcomm HEVC is the exception. Those chips play Main 10 and only advertise Main, so a Main 10 file is presented as Main and the hardware decoder is selected.

## Build

JDK 17 and an Android SDK. The project uses Android Gradle Plugin 8.13.0, Gradle 8.13, and Kotlin 2.3.20. `minSdk` is 24 and `compileSdk` / `targetSdk` are 36.

```sh
./gradlew :app:installDebug
./gradlew :app:testDebugUnitTest
```

The first launch asks you to choose a folder. Android then shows its own screen, where opening a row moves into that folder and **Use this folder** grants access. The grant is kept, and the in-app list can open everything inside it.

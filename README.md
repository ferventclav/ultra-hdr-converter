# Ultra HDR Converter

An Android app (Android 14 and up) that gives ordinary photos real HDR highlights. Pick photos, or
share them to the app from Gallery, and the bright parts (sun, lamps, sky through leaves,
reflections) glow on an HDR screen the way a new phone-camera photo does. On a normal screen the
photos look exactly as before.

Version 2.0. Package `com.example.ultrahdr`.

## What it does to a photo

An Ultra HDR photo is a normal JPEG with a second, small grey picture stored behind it, called a
gain map. White in the gain map means "show this part brighter than normal white". The app works
out that map for a photo that has none.

The rule the whole app is built around: **the original picture data is never re-compressed.**
The gain map and a few small header blocks are added around the JPEG the camera wrote, and every
byte of the picture itself stays the same. So converting costs no quality, and file size grows by
well under 1 MB.

| Photo | What happens |
| --- | --- |
| JPEG stored on the phone | Updated where it is. Same file, same place in Gallery. |
| Already HDR | Skipped. |
| No bright highlights at all | Skipped, because nothing would change. |
| Cloud photo, HEIC or PNG, motion photo, unusual JPEG | Original left alone. An HDR copy is saved to `Pictures/UltraHDR`. |
| "Update original photos" switched off | Every photo is saved as a copy. |

Before an original is touched, three things must be true: the new file keeps the picture data
byte for byte, the phone's own decoder confirms the new file is an Ultra HDR photo, and the file
on disk is the same photo that was read. If any of them fails, the original is left alone and a
copy is saved, with the reason shown on the result screen.

While an original is being rewritten, a backup of it sits in the app's private storage. If the app
is killed or the phone dies in that moment, the next launch puts the original back.

## How the gain map is worked out

`GainMapSynth` (plain Kotlin, no Android code):

1. Brightness per pixel, mostly luminance, so skin and coloured objects are not boosted. A colour
   channel that has hit its ceiling (sunsets, flames) still counts as a highlight.
2. A light blur, so the boost follows shapes and not noise.
3. A starting point (the "knee") taken from the photo itself: a bright photo starts boosting
   higher up, so only its real highlights move.
4. A smooth curve from the knee to white.
5. A cap on the peak that depends on how much of the photo is bright. A small sparkle can reach
   the full strength. A sky or a snowfield gets less. A page of text only gets a slight lift.

Strength settings: Subtle (up to 3x), Balanced (up to 4.5x, the default), Vivid (up to 6x).

## The file format

`UltraHdrContainer` writes the same three things a Galaxy or Pixel camera writes, so any reader
finds the gain map whichever way it looks for it:

- XMP with `hdrgm:Version` and a `Container:Directory` listing the primary image and the gain map
- an ISO 21496-1 block (version in the primary image, the numbers in the gain map image)
- an MPF index pointing at the gain map

Existing XMP is kept and extended, not replaced. Anything after the photo (for example Samsung's
own trailer) is kept after the gain map.

## Project map

```
app/src/main/java/com/example/ultrahdr/
  MainActivity.kt              permissions, share intents, Android's "allow changes" dialog
  processing/
    GainMapSynth.kt            which parts get brighter, and by how much
    UltraHdrContainer.kt       reading a JPEG's layout and writing the Ultra HDR version
    UltraHdrEngine.kt          Android side: decoding, gain map pictures, the self-check
    BatchConverter.kt          the decision for each photo: update, copy, skip or fail
    MediaStoreSaver.kt         finding the real file, safe in-place writes, saving copies
    RecoveryStore.kt           the backup that survives a crash mid-write
    ImageExifHelper.kt         capture date, metadata for re-saved copies
    JpegMpfFixer.kt            repairs the MPF index after EXIF edits (re-saved copies only)
  ui/
    UltraHdrViewModel.kt       state, queue checks, preview, settings
    UltraHdrScreen.kt          the main screen
    PreviewOverlay.kt          full-screen before/after and boost map
    Components.kt              card, segmented picker, photo tile
    ResultSummary.kt           the wording of the result card
    theme/                     One UI colours, type and shapes
```

## Build and test on this PC

There is no Gradle wrapper. The toolchain lives in `C:\Users\bahni\.android-build`
(JDK 17, Gradle 8.9, Android SDK 35). In PowerShell:

```powershell
$env:JAVA_HOME = "C:\Users\bahni\.android-build\jdk-17.0.20.1+1"
$env:ANDROID_HOME = "C:\Users\bahni\.android-build\sdk"
$env:Path = "$env:JAVA_HOME\bin;C:\Users\bahni\.android-build\gradle-8.9\bin;$env:Path"

gradle assembleDebug                      # app\build\outputs\apk\debug\app-debug.apk
gradle testDebugUnitTest                  # all tests
gradle testDebugUnitTest -Pscreenshots    # also saves every screen as a PNG in app\build\screens
```

The tests run on the PC through Robolectric, with Android's real picture decoder:

- `GainMapSynthTest`: the look. Highlights get the boost, skin and mid-tones stay put, a white
  page only gets a slight lift.
- `UltraHdrContainerTest`: the file. The output is read back by a separate reader written the way
  Android's decoder looks for gain maps, the original bytes are checked to be intact, and odd
  inputs (motion photos, multi-picture files, broken files, unsafe XMP) are refused.
- `ScreensTest`: draws every state of the app at phone, tablet, sideways and large-text sizes,
  with painted stand-in photos.
- `ResultSummaryTest`, `LogicTest`: wording, and the older MPF and detection checks.

Two container tests use a real camera file when one is supplied:

```powershell
$env:UHDR_SAMPLE = "C:\path\to\a real Ultra HDR photo.jpg"
```

Without it they are skipped. The PC cannot show or decode HDR itself, so what stays untested here
is the phone's side: the self-check with the phone's decoder, the system permission dialog, and
how the result looks on an HDR screen.

## Known limits

- Very long batches run while the app is open. There is no background service, so keep the app on
  screen until it finishes.
- There is no "remove HDR" button. A copy of the original is the way back when that matters:
  switch off "Update original photos".
- Text on screen is in the code, not in string resources, so the app is English only.

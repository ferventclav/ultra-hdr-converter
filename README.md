<img src="tools/icon_art.jpg" alt="Ultra HDR icon" width="96" align="right">

# Ultra HDR Converter

An Android app (Android 14 and up) that turns ordinary photos into Ultra HDR photos. Pick photos,
or share them to the app from Gallery, and it converts them straight away. On an HDR screen the
bright parts (sun, lamps, sky, reflections) then glow brighter than white. On a normal screen the
photos look as before.

There are no settings. Version 1.0, package `com.example.ultrahdr`. Built for a Samsung Galaxy
running One UI, and it should work on any Android 14 phone that shows Ultra HDR photos.
## What happens to a photo

An Ultra HDR photo is a JPEG with a small grey picture stored behind it, called a gain map. White
in the gain map means "show this part brighter than normal white". The app works out a gain map
for a photo that has none and saves the photo again with it.

| Photo | What happens |
| --- | --- |
| JPEG stored on the phone | Updated where it is. Same file, same place in Gallery. |
| Already HDR (camera or another app) | Skipped, never touched. |
| No bright highlights | Skipped, because nothing would change. |
| Cloud photo | An HDR copy is saved to `Pictures/UltraHDR`. |
| Not a JPEG, motion photo, over 26 MP, or the real file can't be found | Left alone, with the reason on the result screen. |

Each photo is decoded upright, given its gain map, saved as a JPEG at quality 96 with its EXIF
data (including GPS) copied over, and written back in place. Before anything is replaced, the
phone's own decoder must confirm the new file is an Ultra HDR photo. While the original is being
rewritten, a backup of it sits in the app's private storage; if the app is killed in that moment,
the next launch puts the original back. A batch can only stop between photos, never part way
through one.

## The gain map

`GainMapSynth` (plain Kotlin, no Android code):

- plain brightness decides everything; colour, however saturated, never counts as a highlight
- the boost starts at the photo's own 85th percentile, and never below a floor that keeps skin
  and midtones out of it
- it rises steeply, so only the top of the range moves
- the map is worked out at up to 1280 px on the long side; nothing is blurred

## Permissions

| Permission | Why |
| --- | --- |
| Photos (full or limited access) | Find the real file behind a picked photo, so it can be updated where it is. |
| Photo location | Read a photo complete with its GPS position, so the position survives the update. |
| Media management (Settings, Apps, Special access) | Optional. Once on, Android lets the app update photos without asking for each batch. The start screen offers it while it is off. |

## Project map

```
app/src/main/java/com/example/ultrahdr/
  MainActivity.kt              permissions, share intents, media management
  processing/
    BatchConverter.kt          one photo at a time: HDR check, where it may go, convert, save
    SaveDecision.kt            in place, copy or leave alone, and the Copy report text
    GainMapSynth.kt            which parts get brighter, and by how much
    UltraHdrEngine.kt          decoding, attaching the gain map, the decoder check
    UltraHdrContainer.kt       reading a JPEG's layout
    MediaStoreSaver.kt         finding the real file, safe in-place writes, saving copies
    RecoveryStore.kt           the backup that survives a crash mid-write
    ImageExifHelper.kt         upright decoding, capture date, EXIF copy
    JpegMpfFixer.kt            repairs the MPF index after EXIF edits
    Models.kt                  photo, outcome and result types
  ui/
    UltraHdrViewModel.kt       queue, background checks, conversion, Copy report
    UltraHdrScreen.kt          the one screen: empty state, photo grid, results
    Components.kt, Glass.kt    One UI buttons, rows, tiles, glass toolbar and dialogs
    ResultSummary.kt           the wording of the result card
    theme/                     One UI colours (the accent follows the wallpaper), type, shapes
tools/
  icon.py                      cuts icon_art.jpg into the adaptive icon layers
```

## Build and test

There is no Gradle wrapper script. You need JDK 17, Gradle 8.9 and the Android SDK (platform 35).
Point `ANDROID_HOME` at the SDK, or set `sdk.dir` in `local.properties`, then:

```powershell
gradle assembleDebug                      # app\build\outputs\apk\debug\app-debug.apk
gradle testDebugUnitTest                  # all tests
gradle testDebugUnitTest -Pscreenshots    # also saves every screen as a PNG in app\build\screens
```

The tests run on a PC through Robolectric, with Android's real picture decoder:

- `GainMapSynthTest`, `V19ParityTest`: the gain map
- `ConversionRulesTest`: where each kind of photo goes, HDR detection, GPS, the Copy report
- `UltraHdrContainerTest`: reading JPEG layouts and refusing odd files
- `ScreensTest`: every screen at phone, tablet, sideways and large-text sizes, light and dark
- `ResultSummaryTest`, `LogicTest`: wording, MPF and detection checks

Two container tests use a real camera file when one is supplied, and are skipped otherwise:

```powershell
$env:UHDR_SAMPLE = "C:\path\to\a real Ultra HDR photo.jpg"
```

A PC cannot show HDR, so how a converted photo looks can only be judged on a phone.

## The icon

`python tools/icon.py app/src/main/res preview.png` rebuilds the launcher icon from
`tools/icon_art.jpg`: a flat background, the art with its black taken out as the foreground
(scaled into the 66dp safe zone), and a monochrome layer for themed icons.

## Known limits

- An updated photo replaces the original. The safety backup is deleted once the new file is in
  place, so there is no undo.
- Long batches run while the app is open. There is no background service.
- Text on screen is in the code, not in string resources, so the app is English only.

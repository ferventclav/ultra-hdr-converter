<img src="tools/icon_art.jpg" alt="Ultra HDR icon" width="96" align="right">

# Ultra HDR Converter

An Android app (Android 14 and up) that turns ordinary photos into Ultra HDR photos. Pick photos,
or share them to the app from Gallery, and it converts them straight away. On an HDR screen the
bright parts (sun, lamps, sky, reflections) then glow brighter than white. On a normal screen the
photos look as before.

Photos are picked from the app's own grid, which lists only photos stored on the phone, so every
one converts at full quality in place. Photos still only in Google Photos can be picked from the
⋮ menu, and come back as smaller copies without camera details or location.

There are no settings. Version 1.3, package `com.example.ultrahdr`. Built for a Samsung Galaxy
running One UI, and it should work on any Android 14 phone that shows Ultra HDR photos.
## What happens to a photo

An Ultra HDR photo is a JPEG with a small grey picture stored behind it, called a gain map. White
in the gain map means "show this part brighter than normal white". The app works out a gain map
for a photo that has none and stores it behind the photo.

| Photo | What happens |
| --- | --- |
| JPEG stored on the phone | Updated where it is. Same file, same place and date in Gallery. |
| Already HDR (camera or another app) | Skipped, never touched. |
| No bright highlights | Skipped, because nothing would change. |
| Cloud photo (⋮ menu, or shared from Google Photos) | An HDR copy is saved to `Pictures/UltraHDR`, named and dated the same whichever way it arrived. Converting it again replaces that copy. |
| Not a JPEG, motion photo, or the real file can't be found | Left alone, with the reason on the result screen. |

The photo itself is kept byte for byte: its picture, full colour detail, EXIF (GPS included),
colour profile and XMP stay as they were, and the gain map is added behind them. A file whose
layout can't take that (one that already holds other embedded pictures, or XMP that can't be
edited safely) is decoded upright and saved again as a JPEG at quality 96 with its EXIF copied
over, exposure time and f-number to the exact fraction. A photo over 26 MP that would need saving
again is left alone.

Before anything is replaced, the phone's own decoder must confirm the new file is an Ultra HDR
photo; if it can't read a kept-picture file, the photo is saved again instead. An updated photo
keeps its last-change time, so a photo with no date inside it (a WhatsApp photo) stays on the same
day in Gallery. Where Android won't let the app keep that time, the date goes inside the file.

While the original is being rewritten, a backup of it sits in the app's private storage; if the
app is killed in that moment, the next launch puts the original back. After a batch, Undo on the
result screen puts the originals back and deletes any new copies. The backups are deleted when
the result screen is left or the next batch starts.

A batch keeps going when the app is closed or another app is opened, with its progress in a
notification. It can only stop between photos, never part way through one.

## The gain map

`GainMapSynth` (plain Kotlin, no Android code):

- plain brightness decides everything; colour, however saturated, never counts as a highlight
- the boost starts at the photo's own 85th percentile, and never below a floor that keeps skin
  and midtones out of it
- it rises steeply, so only the top of the range moves
- faint ripples in smooth areas such as skies are evened out first, so the steep boost can't turn
  them into visible bands; edges and texture are left sharp
- the map is worked out at up to 1280 px on the long side

## Permissions

| Permission | Why |
| --- | --- |
| Photos (full or limited access) | List the photos on the phone in the grid, and find the real file behind a shared photo, so it can be updated where it is. |
| Photo location | Read a photo complete with its GPS position, so the position survives the update. |
| Media management (Settings, Apps, Special access) | Optional. Once on, Android lets the app update photos without asking for each batch. The start screen offers it while it is off. |
| Notifications | Optional, asked the first time a batch has four or more photos. Shows a batch's progress while the app is in the background. |

## Project map

```
app/src/main/java/com/example/ultrahdr/
  MainActivity.kt              permissions, share intents, media management
  ConversionService.kt         keeps a batch running in the background, with its notification
  processing/
    BatchConverter.kt          one photo at a time: HDR check, where it may go, convert, save
    SaveDecision.kt            in place, copy or leave alone
    LocalPhotos.kt             the JPEGs on the phone for the grid, albums, HDR label check
    PhotoNames.kt              readable names for photos that arrive with cache names
    GainMapSynth.kt            which parts get brighter, and by how much
    UltraHdrEngine.kt          decoding, attaching the gain map, the decoder check
    UltraHdrContainer.kt       a JPEG's layout; adding the gain map behind it and taking it out
    MediaStoreSaver.kt         finding the real file, safe in-place writes, saving copies
    RecoveryStore.kt           the backup that survives a crash mid-write
    UndoStore.kt               the last batch's originals and new copies, for Undo
    ImageExifHelper.kt         upright decoding, capture date, EXIF copy
    ExifRationals.kt           exact exposure time and f-number after the EXIF copy
    JpegMpfFixer.kt            repairs the MPF index after EXIF edits
    Models.kt                  photo, outcome and result types
  ui/
    ConversionSession.kt       the batch, shared by every screen so it outlives them
    UltraHdrViewModel.kt       queue, background checks, conversion, Undo
    UltraHdrScreen.kt          the main screen: empty state, photo grid, results
    GalleryPage.kt             the photo picker, laid out like Galaxy Enhance-X's home screen
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
gradle assembleRelease                    # app\build\outputs\apk\release\app-release.apk, the one to install
gradle assembleDebug                      # app\build\outputs\apk\debug\app-debug.apk, for debugging only
gradle testDebugUnitTest                  # all tests
gradle testDebugUnitTest -Pscreenshots    # also saves every screen as a PNG in app\build\screens
```

The tests run on a PC through Robolectric, with Android's real picture decoder:

- `GainMapSynthTest`, `V19ParityTest`: the gain map
- `ConversionRulesTest`: where each kind of photo goes, keeping the picture, dates, HDR detection,
  GPS, Undo
- `UltraHdrContainerTest`: reading JPEG layouts and refusing odd files
- `ScreensTest`: every screen at phone, tablet, sideways and large-text sizes, light and dark
- `ResultSummaryTest`, `LogicTest`: wording, MPF and detection checks

Two container tests use a real camera file when one is supplied, and are skipped otherwise:

```powershell
$env:UHDR_SAMPLE = "C:\path\to\a real Ultra HDR photo.jpg"
```

A PC cannot show HDR, so how a converted photo looks can only be judged on a phone. The PC's
decoder also never reports a gain map in a kept-picture file, so the tests check those files'
labels and layout, and the decoder check is left to the phone.

## The icon

`python tools/icon.py app/src/main/res preview.png` rebuilds the launcher icon from
`tools/icon_art.jpg`: a flat background, the art with its black taken out as the foreground
(scaled into the 66dp safe zone), and a monochrome layer for themed icons.

## Known limits

- Undo covers the last batch only, and only until its result screen is left. Its backups are not
  kept while the phone has less than 2 GB free.
- Motion photos and HEIC photos are left alone. Saving a motion photo again would drop its video,
  and Android can't store a gain map in a HEIC file, so the only way would be a second copy.
- Text on screen is in the code, not in string resources, so the app is English only.

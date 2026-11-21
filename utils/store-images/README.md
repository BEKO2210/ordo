# Play Store images

Real Ordo screens in a device frame with one headline each. Nothing here uploads to Play; the images land in
`fastlane/metadata/android/<locale>/images/` and go into the Play Console by hand (docs/PLAY-AUTO-UPLOAD.md).

| Step | Tool | What it does |
|---|---|---|
| 1 | `art.py` | Optional: makes the AI pictures of `art/prompts.json` again (album covers, trip photos). They are committed in `art/`, so this is only for a new picture |
| 2 | `demo_media.py <dir>` | Builds the demo files: `Music/OrdoDemo`, `Movies/OrdoDemo`, `Documents/OrdoDemo`. Push the three folders to the device |
| 3 | `capture.sh <serial> <en\|de> <phone\|tablet\|seven> <raw dir>/<en\|de>` | Raw screenshots, only while Ordo is in front; restores size, density, rotation, night mode and app language |
| 4 | `compose.py <raw dir> <repo> [strip dir]` | Frames and headlines from `shots.json`; with a strip dir also a row of 200 px thumbnails per set |
| 5 | `check.py <images dir>...` | Play's rules for size, format and count |

Read the 200 px strip yourself before you keep a set: that is the size at which Play shows the screenshots first.
The first three decide; each shows one idea with a headline of at most six words.

## shots.json

One file says what is made. To add an app to the suite (say Ordo Pictures): capture its screen as
`<raw dir>/<lang>/pictures.png` (a new part in `capture.sh`) and add one entry to each set's `shots`:

```json
{"id": "pictures", "raw": ["pictures"], "accent": "#F59E0B",
 "headline": {"en": "Your photos, *neatly sorted*", "de": "Deine Fotos, *schön sortiert*"}}
```

| Field | Meaning |
|---|---|
| `locales` | Language of the raw folder → Play locale folder. A new language is one entry here plus its headlines |
| `feature` | The 1024×500 graphic: the word "Ordo", `headline`, and three phone raw shots (two upright, one wide) |
| `sets.<folder>` | One device class: `prefix` of its raw files, `canvas` size, `headline` font size, device `width`, `radius`, `bezel`, and `bars` (status bar and navigation bar in raw pixels, cut off because they show the capture device's own clock and icons) |
| `shots[].id` | File name part; the order in the list is the order in the store |
| `shots[].raw` | Raw file name(s) without prefix and `.png`. One for a single device; three with `"layout": "trio"` (two upright, one wide); two with `"layout": "stack"` (one above the other). An entry may be `["name", [left, top, right, bottom]]`: that raw shot with a crop of its own, so two parts of one screen can stand as two cards (Sound: the curve and the presets, without the line between them) |
| `shots[].headline` | Per language; the part between `*stars*` is set in Ordo blue. At most six words |
| `shots[].accent` | Colour of the glow behind the device |
| `shots[].crop` | Optional `[left, top, right, bottom]` as fractions of the screen (after the bars are cut): zooms into the part that matters when the whole screen's text would be too small |
| `shots[].width` | Optional device width in pixels for this shot (wider = closer) |
| `shots[].bars` | Optional `[top, bottom]` for a raw shot from another device than the set's |

A shot whose raw file is missing is left out with a note, so a set can be built before every screen is captured.

## Capture rules

- Only demo files are opened; the owner's files never appear. `LIBRARY=0` opens the player from the demo folder on
  a device that also holds its owner's music. The home screen and the storage analysis show the device's own
  numbers and standard folder names.
- `PARTS="home files vault ai music video storage"` picks the screens, `SONG="Artist - Title"` the song in the player.
- `phone` on a tablet: 1080×2280 at 420 dpi gives Ordo's real phone layout. `seven`: 600 dp wide.
- Media volume stays at 0 (e2e/lib.sh).
- A test build is called "Ordo Dev". For the shots, build the debug variant with the app id `com.beko2210.ordo.dev`
  but the name left as "Ordo". The vault opens with the screen lock, which a test device does not have: in the same
  build, add `viewModel.unlock()` after `viewModel.onStart()` in `VaultFragment.onStart`. Never commit that build's
  changes; install the normal "Ordo Dev" again afterwards.
- AI pictures (`art/`) are only content inside the app: covers and photos. Screens are never generated.

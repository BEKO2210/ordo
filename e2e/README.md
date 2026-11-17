# Device tests

Ordo is tested on real devices before every release. Every walk-through that once found a bug lives here as a
script, so the same bug cannot come back unnoticed.

```bash
e2e/run.sh <adb serial>            # read-only tests
e2e/run.sh --write <adb serial>    # plus tests that create and delete their own files
```

`E2E_FAST=1 e2e/run.sh <adb serial>` remembers every tap position per device, screen size and screen in
`~/.cache/ordo-e2e/` and taps it straight away next time; checks still read the real screen. Delete the cache file
after a layout change. Targets inside a scrolling view are never remembered (their spot moves with the
scroll position). A control that moves on a fixed screen (e.g. below a message of varying length) is tapped with
`E2E_FAST=0 ui tap ...`, so it is always looked up. On a Pixel 11 Pro the read-only run went from 618 s to 491 s, all 110 checks passing;
`ui visible ID` (0.2 s instead of a 2.4 s screen read) then cut `drawer.sh` from 282 s to 220 s. Galaxy S21 Ultra,
0.6.0: all seven read-only tests, 136 checks, pass in 558 s.

| Test | What it proves | Since |
|---|---|---|
| `first-run.sh` | a fresh start shows the welcome pages, Skip closes them, home then shows categories and the storage card, the next start goes straight home. Clears Ordo's data, so only on an emulator; a real device prints SKIP | after 0.9.0 |
| `smoke.sh` | start, home screen, every settings section and its way back | 0.4.2 |
| `settings-visual.sh` | all nine settings rows; visual night-mode, motion and home-header choices round-trip and restore; the inline accent swatches change and restore | 0.12.0 |
| `category-search.sh` | searching inside a category does not crash | fixed in 0.3.3 |
| `back-navigation.sh` | back from a storage or the analysis returns to home in one step | fixed in 0.3.2 |
| `bars-replay.sh` | every storage bar, measured on its own in a screen recording, is still empty when home comes back and then grows in | fixed in 0.4.2 / 0.4.3 |
| `home-header.sh` | the home screen's top bar: search, assistant and every entry under "more" work, back returns home | 0.5.0 |
| `header-position.sh` | the search bar set to the bottom moves without a restart, floats above the navigation bar, the page's end scrolls clear of it, search, wand, more and side menu still work; the setting is put back as it was | after 0.7.1 |
| `home-pull.sh` | pulling down on the home screen only refreshes when meant: 90 dp or scrolling back up into the top does nothing, 170 dp from the top refreshes once | 0.9.0 |
| `home-auto-refresh.sh` (`--write`) | 50 new files reload the home screen once or twice, the storage bar moves in place instead of emptying and growing in again (screen recording), nothing reloads while it is hidden, coming back reloads once | 0.9.0, bar after 0.9.0 |
| `drawer.sh` | every entry of the side menu opens its screen and back returns home with the menu closed; a menu dragged all the way open closes on back instead of leaving the app | 0.5.0, drag fixed in 0.5.0 |
| `selection-more.sh` | with files selected, "More" in the bottom bar opens its menu right above the bar on the button's side, not at the top of the screen | 0.9.0 |
| `tablet-landscape.sh` | as a landscape tablet, selecting a file no longer crashes, it shows the bar beside the side pane, "More" opens above it. Only an emulator is resized (2560x1600 at 320 dpi; its previous overrides come back exactly, also on Ctrl-C); a real device is never resized or rotated: it runs only when it already is a tablet in landscape, otherwise SKIP | fixed 0.9.0 |
| `analysis.sh` | the scan ends in the overview; ring, folder map and rows open their pages; back goes one step at a time; rotation keeps the page | 0.6.0 |
| `audio-player.sh` (`--write`) | audio opens with its complete transport row visible; the Sound sheet exposes deliberate EQ, playback and sleep controls, the equalizer switches on, a preset tile (Bass) is selected and named in the status, "Fine-tune" opens its band rows, and Flat plus off restore the defaults; closing the screen keeps the service playing, media Next changes the track, the player reconnects, and an external `file://` intent is refused | after 0.11.1 |
| `music-library.sh` (`--write`) | Ordo Musik opens its library (never the player on its own) in a task of its own (`dumpsys activity recents`) with the tabs Songs, Albums, Artists and Folders; tagged 16-bit stereo MP3s and an untagged WAV in two folders are listed by title in title order with "Shuffle all" first; search filters the list; tapping a song opens the player and it plays (`dumpsys media_session`); back shows the library with the mini player naming the song, whose button pauses; Folders lists both folders and opens one with Play/Shuffle; Albums opens an album in track order; back leaves the music app instead of landing in the file manager. Every list is narrowed by search first, so other music on the device does not matter | after 0.13.3 |
| `launcher-icons.sh` | Settings › Built-in apps: the "Ordo Musik" switch enables and disables the launcher alias (checked with `cmd package query-activities`), the other four apps are listed under "Coming soon" with "Coming as soon as the app is ready", the switch shows the real component state and a change made from outside, the alias opens the music library with its tabs; the alias is put back as it was, also on Ctrl-C | after 0.11.1 |
| `icon-colors.sh` | Settings › Built-in apps › Icon colours: a swatch in Ordo's row makes exactly that colour's alias Ordo's launcher entry (`cmd package query-activities`), Ordo keeps running, the choice shows again after reopening; Ordo Musik's colour is remembered while its icon is off and used when it is switched on; a row partly off screen is scrolled into view before its swatch is tapped; both are put back as they were, also on Ctrl-C | after 0.13.3 |
| `pick-file.sh` (`--write`) | Ordo opened to pick a file for another app (GET_CONTENT, as from the system picker's "Open from") shows its pick title, asks for no notification permission, offers the test file and closes after the tap | after 0.13.3 |
| `backup-restore.sh` (`--write`, SD card) | an encrypted backup to the SD card restores byte for byte; a wrong password writes nothing; a changed file is never replaced and its backed up version comes back beside it; restoring twice adds no second copy | fixed in 0.7.0 |
| `ai-show-guard.sh` | another app's explicit intent with the AI list's extras opens the normal file list, never Ordo's private folders; debug builds: the rules on the device (ICU regex) equal rules.py for all lab cases, and the net of Guarded/Tuned equals rules2.py/rules3.py for their fixtures | after 0.8.1 |
| `ai-preview-setting.sh` | Settings › AI assistant: the preview switch for the new commands exists, is off by default, toggles and keeps its state over a restart (German + English); leaves it off | 16.11.2026 |
| `ai-sheet-handle.sh` | AI command sheet drag handle is wrap_content (not a full-width focus band): at least 48 dp and at most a third of the sheet content width; works with or without a model | 16.11.2026 |
| `ai-guarded.sh` | emulator + debug build, real 4B model (`MODEL=`): the net design Guarded (`DESIGN=guarded` through the debug activity, `DESIGN=settings` through the preview switch in the settings; `DESIGN=tuned ADAPTER=` for Tuned): show by kind/time/size, content, exclusion, a filtered move preview, a delete with confirm, asks back, refusal, question, English, the five ai-real commands, prompt state of the new prompt; nothing changes without a confirm | 16.11.2026 |
| `ai-show.sh` (`--write`, debug build) | without the model (DebugAiActivity): "show" opens the file list with what was understood, a question never deletes, a hint instead of a guess, storage and duplicate questions open the analysis, a delete keeps preview and confirm, a file replaced since the preview stays and is named | after 0.8.1 |
| `stress-delete.sh` (`--write`) | 3000 files to the trash: no crash, bounded memory, the list follows | fixed in 0.3.1 / 0.3.3 |

Every test starts through `launch` in `lib.sh`, which also copes with a fresh install: on an emulator it grants what
adb may grant (notifications on Android 13+, all-files access as an app op on Android 11+), so no system dialog comes
to the front; a real device keeps its owner's choices, and if a permission question comes to the front the test stops
and prints the `adb` command the owner can run himself. If Ordo's welcome pages are in front (on any device) it skips
them with their own button and prints `first run: onboarding skipped`. A system dialog is never tapped, and app data is
only ever cleared on an emulator (`first-run.sh`). To put an older build over a newer one, install it with `adb install -r -d`.

The wand checks in `home-header.sh` and `header-position.sh` print `SKIP (not enough RAM for the model on this
device)` instead of failing only when the device's MemTotal is below the model's 10 GB floor (`CommandModel.MIN_RAM`)
and the sheet shows its setup page saying the AI can't run here. Any other screen, or that page on a device with
enough memory, is a FAIL.

Rules for every test:

- It acts only while Ordo is in front and aborts the run (exit 2) the moment another app is.
- It reads, taps and goes back. A test that has to change a setting (`header-position.sh`) puts it back as it was. A test that writes creates its own files under a folder named `ordo-e2e-*`, says so
  at the top, runs only with `--write`, and removes exactly what it created.
- It finds controls by resource id, or by text with the German and the English wording (`"Aussehen|Appearance"`).
- It ends with `finish`, which also fails the test if the crash log is not empty.
- A bug found on a device gets a test here in the same pull request as its fix.

Needs: `adb`, `python3`, and `ffmpeg` for `bars-replay.sh`. `ADB` and `PKG` can be overridden through the
environment.

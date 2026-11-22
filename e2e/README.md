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
| `back-navigation.sh` | back from a storage or the analysis returns to home in one step; in the file list the toolbar stays while the list scrolls (menu, search, sort and more, each icon measured at 3:1 or more against the bar) and the end of the list scrolls clear of the + button | fixed in 0.3.2, toolbar 0.18.0 |
| `bars-replay.sh` | every storage bar, measured on its own in a screen recording, is still empty when home comes back and then grows in | fixed in 0.4.2 / 0.4.3 |
| `home-header.sh` | the home screen's top bar: search, assistant and every entry under "more" work, back returns home | 0.5.0 |
| `header-position.sh` | the search bar set to the bottom moves without a restart, floats above the navigation bar, the page's end scrolls clear of it, search, wand, more and side menu still work; the setting is put back as it was | after 0.7.1 |
| `home-pull.sh` | pulling down on the home screen only refreshes when meant: 90 dp or scrolling back up into the top does nothing, 170 dp from the top refreshes once | 0.9.0 |
| `home-auto-refresh.sh` (`--write`) | 50 new files reload the home screen once or twice, the storage bar moves in place instead of emptying and growing in again (screen recording), nothing reloads while it is hidden, coming back reloads once | 0.9.0, bar after 0.9.0 |
| `drawer.sh` | Ordo Musik and Ordo Video are the first entries of the side menu and open their libraries; every entry of the side menu opens its screen and back returns home with the menu closed; a menu dragged all the way open closes on back instead of leaving the app | 0.5.0, drag fixed in 0.5.0 |
| `selection-more.sh` | with files selected, "More" in the bottom bar opens its menu right above the bar on the button's side, not at the top of the screen | 0.9.0 |
| `tablet-landscape.sh` | as a landscape tablet, selecting a file no longer crashes, it shows the bar beside the side pane, "More" opens above it. Only an emulator is resized (2560x1600 at 320 dpi; its previous overrides come back exactly, also on Ctrl-C); a real device is never resized or rotated: it runs only when it already is a tablet in landscape, otherwise SKIP | fixed 0.9.0 |
| `analysis.sh` | the scan ends in the overview; ring, folder map and rows open their pages; back goes one step at a time; rotation keeps the page | 0.6.0 |
| `audio-player.sh` (`--write`) | audio opens with its complete transport row visible; the Sound sheet exposes deliberate EQ, playback and sleep controls, the equalizer switches on, a preset tile (Bass) is selected and named in the status, "Fine-tune" opens its band rows, and Flat plus off restore the defaults; closing the screen keeps the service playing, media Next changes the track, the player reconnects, and an external `file://` intent is refused | after 0.11.1 |
| `klang.sh` (`--write`) | a 16-bit stereo WAV opens in the player; in the Sound sheet every effect card of the lower part (bass, room sound for headphones, stereo width, reverb, analogue warmth, clarity) is reachable, switches on and its live picture reports the value (bass and clarity SKIP where the device lacks them), a reverb room can be chosen, the sleep timer shows its picture; the title then reaches `state=PLAYING` with all effects on and stays there; finally every effect is switched off again | after 0.13.3 |
| `music-formats.sh` (`--write`) | format matrix made by ffmpeg on the host: MP3 CBR/VBR/mono, AAC stereo and 5.1, FLAC 16/24-bit at 44.1/96/192 kHz, mono and 5.1, Opus stereo and 5.1, Vorbis, WAV 16/24-bit and 32-bit float up to 192 kHz; each once with every Klang effect off and once with all on: `state=PLAYING`, the player shows 12 s, a tap at 3/4 of the seek bar lands at 7.5–10.5 s and playback goes on; three FLAC and three MP3 parts of one tone follow each other with at most 50 ms gap (two switches compared, so the start-up delay cancels) (from the session's position stamps); no playback error in the log, no crash. The sound settings are written through `run-as` and restored | after 0.14.2 |
| `klang-stress.sh` (`--write`) | a 60-s stereo FLAC (48 kHz, `STRESS_RATE` for others) with every Klang effect on: CPU share of the app over 20 s (INFO), then while it plays the equalizer switch and every effect switch in reach are tapped 20 times in a row and the preset tiles switched 20 times; it still plays, pauses and plays again, the CPU share comes back, no crash. Sound settings through `run-as`, restored | after 0.14.2 |
| `klang-landscape.sh` (`--write`) | the Sound sheet in landscape: the last card ("Resume when headphones connect") is reachable by scrolling; from 840 dp on the equalizer curve stays in sight beside the cards (two panes); rotation is restored | after 0.13.3 |
| `cast.sh` (`--write`, needs `CAST_TV=<name>`, else SKIP) | the player's cast button opens the cast sheet with "This phone" and the named Chromecast/Google TV; picking it shows "Playing on <name>", the media session plays remotely, media Pause and Next control the TV (second title), "Stop casting" brings the queue back to the phone with the second title, paused; no crash | after 0.14.0 |
| `music-library.sh` (`--write`) | (also: a folder page shows each untagged song's own embedded cover, compared by colour on screen; tapping the song that is already playing keeps its position) Ordo Musik opens its library (never the player on its own) in a task of its own (`dumpsys activity recents`) with the tabs Songs, Albums, Artists and Folders; tagged 16-bit stereo MP3s and an untagged WAV in two folders are listed by title in title order with "Shuffle all" first; search filters the list; tapping a song opens the player and it plays (`dumpsys media_session`); back shows the library with the mini player naming the song, whose button pauses; a song's ⋮ offers play next, add to queue and show file; the side menu (Ordo's) opens, Recently played lists the song just played, the menu returns to the library and opens Sound over the player; Folders lists both folders and opens one with Play/Shuffle; Albums opens an album in track order; back leaves the music app instead of landing in the file manager. Every list is narrowed by search first, so other music on the device does not matter | after 0.13.3 |
| `launcher-icons.sh` | Settings › Built-in apps: the "Ordo Musik" switch enables and disables the launcher alias (checked with `cmd package query-activities`), so does "Ordo Video" for its own alias, which opens the video library, the other three apps are listed under "Coming soon" with "Coming as soon as the app is ready", the switch shows the real component state and a change made from outside, the alias opens the music library with its tabs; the aliases are put back as they were, also on Ctrl-C | after 0.11.1 |
| `icon-colors.sh` | Settings › Built-in apps › Icon colours: a swatch in Ordo's row makes exactly that colour's alias Ordo's launcher entry (`cmd package query-activities`), Ordo keeps running, the choice shows again after reopening; Ordo Musik's colour is remembered while its icon is off and used when it is switched on; a row partly off screen is scrolled into view before its swatch is tapped; both are put back as they were, also on Ctrl-C | after 0.13.3 |
| `documents-provider.sh` | Android's own file picker (ACTION_OPEN_DOCUMENT, as from a browser upload) lists "Ordo" among its places and shows the bookmarks there; only looks and leaves with Back. SKIP without all-files access or without an existing bookmark folder | after 0.13.3 |
| `pick-file.sh` (`--write`) | Ordo opened to pick a file for another app (GET_CONTENT, as from the system picker's "Open from") shows its pick title, asks for no notification permission, offers the test file and closes after the tap | after 0.13.3 |
| `backup-restore.sh` (`--write`, SD card) | an encrypted backup to the SD card restores byte for byte; a wrong password writes nothing; a changed file is never replaced and its backed up version comes back beside it; restoring twice adds no second copy | fixed in 0.7.0 |
| `ai-show-guard.sh` | another app's explicit intent with the AI list's extras opens the normal file list, never Ordo's private folders; debug builds: the rules on the device (ICU regex) equal rules.py for all lab cases, and the net of Guarded/Tuned equals rules2.py/rules3.py for their fixtures | after 0.8.1 |
| `ai-preview-setting.sh` | Settings › AI assistant: the preview switch for the new commands exists, is off by default, toggles and keeps its state over a restart (German + English); leaves it off | 16.11.2026 |
| `ai-sheet-handle.sh` | AI command sheet drag handle is wrap_content (not a full-width focus band): at least 48 dp and at most a third of the sheet content width; works with or without a model | 16.11.2026 |
| `ai-guarded.sh` | emulator + debug build, real 4B model (`MODEL=`): the net design Guarded (`DESIGN=guarded` through the debug activity, `DESIGN=settings` through the preview switch in the settings; `DESIGN=tuned ADAPTER=` for Tuned): show by kind/time/size, content, exclusion, a filtered move preview, a delete with confirm, asks back, refusal, question, English, the five ai-real commands, prompt state of the new prompt; nothing changes without a confirm | 16.11.2026 |
| `ai-show.sh` (`--write`, debug build) | without the model (DebugAiActivity): "show" opens the file list with what was understood, a question never deletes, a hint instead of a guess, storage and duplicate questions open the analysis, a delete keeps preview and confirm, a file replaced since the preview stays and is named | after 0.8.1 |
| `no-midword-breaks.sh` (`--write`, debug build) | no visible text breaks inside a word (owner's rule after "Absp-ielen" on the Tab): with one silent test song it walks home, settings, the four music tabs, a folder and an album page, the player and its Sound sheet, the video player (locked, and its speed sheet; needs ffmpeg), and fails on every line `MidwordWatchProvider` logs; run it on a phone and a tablet | after 0.14.1 |
| `player-seek.sh` (`--write`) | the player's wavy seek bar keeps the house rule: a tap, a quick swipe and a vertical scroll over it never move the position, a finger held 400 ms and dragged seeks to three quarters, a double tap on the cover's right half jumps 10 s ahead (positions from `dumpsys media_session`) | after 0.14.1 |
| `video-player.sh` (`--write`) | video player phase 1 with clips made by ffmpeg on the host: a tap in the file list opens Ordo's player and it plays (`dumpsys media_session`); buttons keep 48 dp (play 88 dp on a tablet); a tap, a quick swipe and a vertical swipe on the seek bar never seek, 400 ms held and dragged to 3/4 does; three quick taps on the left 28 % jump 20 s back, a double tap on the right 28 % 10 s ahead; locked, taps do nothing until "hold to unlock" is held; closed after 10 s and opened again it offers "Resume at …", a tap goes there; Ordo Video's library lists the clip under "Continue watching" and opens it at its place without the pill; Ordo Video's own colour (Amber, from its library's settings) changes the play button's tint and is put back to "Same as Ordo"; its settings page offers "At the end"; the player's ⋮ › Video info names "1920 × 1080 · 30 fps" and "H.264"; at the end the player stops on the last frame with "Watch again" and "Next video" (the default), Next video opens the next clip; one swipe from the left edge leaves the player (gesture navigation); a vertical swipe on the right half raises the volume (restored); a 0-byte `.mp4` (and an Xvid AVI the device cannot play) shows the message with "Open with another app", which opens the system chooser; an external `file://` intent is refused, a `content://` one with a read grant plays; the rotate button (phone) keeps position and playback. Headphones and audio focus: SKIP, by hand | after 0.14.4 |
| `player-stage2.sh` (`--write`) | a song with a `.lrc` beside it offers lyrics, the lyrics layer shows its lines and back closes only the layer; the queue sheet lists the songs; a vertical drag on the cover keeps the song, a sideways swipe plays the next; a crafted 20,000-line `.lrc` still offers lyrics within 20 s, opens, and flinging through it leaves no app-not-responding | after 0.14.1 |
| `stress-delete.sh` (`--write`) | 3000 files to the trash: no crash, bounded memory, the list follows | fixed in 0.3.1 / 0.3.3 |
| `copy-replace.sh` (`--write`) | replacing a file, then aborting or killing the process, leaves the old target; a half-written `.ordo-part` is not the destination | 1.0.0 |
| `ftp-server-fd.sh` (`--write`) | the built-in FTP server: 2000 LIST commands, the process fd count stays stable | 1.0.0 |

Every test starts through `launch` in `lib.sh`, which also copes with a fresh install: on an emulator it grants what
adb may grant (notifications on Android 13+, all-files access as an app op on Android 11+), so no system dialog comes
to the front; a real device keeps its owner's choices, and if a permission question comes to the front the test stops
and prints the `adb` command the owner can run himself. If Ordo's welcome pages are in front (on any device) it skips
them with their own button and prints `first run: onboarding skipped`. A system dialog is never tapped, and app data is
only ever cleared on an emulator (`first-run.sh`). To put an older build over a newer one, install it with `adb install -r -d`.

The wand checks in `home-header.sh` and `header-position.sh` print `SKIP (not enough RAM for the model on this
device)` instead of failing only when the device's MemTotal is below the model's 10 GB floor (`CommandModel.MIN_RAM`)
and the sheet shows its setup page saying the AI can't run here. On a device with enough memory but no model yet,
the setup page offering the download is a PASS (the sheet opened, as it should). Any other screen is a FAIL.

Rules for every test:

- It acts only while Ordo is in front and aborts the run (exit 2) the moment another app is.
- It reads, taps and goes back. A test that has to change a setting (`header-position.sh`) puts it back as it was. A test that writes creates its own files under a folder named `ordo-e2e-*`, says so
  at the top, runs only with `--write`, and removes exactly what it created.
- It finds controls by resource id, or by text with the German and the English wording (`"Aussehen|Appearance"`).
- It ends with `finish`, which also fails the test if the crash log is not empty.
- A bug found on a device gets a test here in the same pull request as its fix.

Needs: `adb`, `python3`, and `ffmpeg` for `bars-replay.sh`. `ADB` and `PKG` can be overridden through the
environment.

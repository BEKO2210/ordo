# Device tests

Ordo is tested on real devices before every release. Every walk-through that once found a bug lives here as a
script, so the same bug cannot come back unnoticed.

```bash
e2e/run.sh <adb serial>            # read-only tests
e2e/run.sh --write <adb serial>    # plus tests that create and delete their own files
e2e/preflight.sh <adb serial>      # a few seconds: can a run start at all?
e2e/farm.sh --write                # every suite, portrait and landscape, on all test devices at once
```

## The farm

`run.sh` walks through the suites on one device, one after the other. `farm.sh` shares the same list out over every
test device that is connected, each suite once in portrait and once in landscape:

```bash
PKG=com.beko2210.ordo.dev E2E_GRANT=1 e2e/farm.sh --write            # all devices of farm-devices.conf
PKG=com.beko2210.ordo.dev E2E_GRANT=1 e2e/farm.sh --write tab a8     # only these
e2e/farm.sh --write --only "klang video-seek" --orient landscape tab # a few suites, one orientation
```

- **Devices** are named in `farm-devices.conf` with what they can do (`shots`, `hdr`, `slow`, …). Each one is taken
  through a lock command (`FARM_LOCK`, on pop-os `~/ordo-geraet.sh <name> <cmd>`: it waits for the device, exports
  `ORDO_SERIAL` and runs the command), so two jobs never drive one device. Without a lock command a device's name is
  its adb serial. The lock is taken per suite and given back after it, with the rotation as it was: between two
  suites other jobs get the device. `touch <FARM_OUT>/hold/<device>` lends a device out for longer (its running
  suite finishes, no new one starts until the file is gone). A device that is offline, locked or fails `preflight.sh`
  is left out; the others take its share.
- **Jobs** are suite × orientation. A free device takes the next job it is able to run, longest first (times of the
  last run), and keeps its orientation as long as there is work in it. The time limit of a suite (900 s, doubled on a
  `slow` device) runs inside the device lock, so waiting for a device never eats it.
- **A failed suite is run once more on another device** (on the same one only if no other device can run it). Passed
  there, it is listed as `FLAKY` with the device it failed on; failed twice, it is a `FAIL`.
- **A suite that stops with exit code 2** (another app in front, media volume not 0) takes its device out of the run:
  somebody is using it. The job goes back and another device takes it.
- `summary.txt` in the run's folder (`FARM_OUT`, default `~/.cache/ordo-e2e/farm/<start time>/`) is rewritten every
  3 s; at the end the table suite × orientation × result × device × seconds × checks is printed. Exit code 0: all
  passed; 1: a suite failed twice or ran out of time; 3: a suite could not run on any device.
- What the farm changes on a device it puts back after every suite, also on Ctrl-C: rotation, a size or density override a suite left,
  and at night (22–7) do-not-disturb, which it switches on for the run. It never sets `E2E_ALLOW_VOLUME`.

Measured on 21.11.2026, Galaxy Tab S9 Ultra, Ordo Dev 0.18.0 (versionCode 37), `--write`, portrait, one device: the
38 suites that also ran in the morning's `run.sh` pass took 7608 s there and 5427 s through the farm, although two
of them (`ai-show-guard`, `video-player`) broke off early in the morning and now run to their end; all 45 suites of
the list took 6329 s (105 min), 44 passed at once and `video-detailplus` after a fix to its test. `stress-delete`
went from 822 s to 145 s, `music-formats` from 1111 s to 574 s, `backup-restore` from 369 s to 233 s. A second
device was not reachable that night, so two devices at once and a whole landscape pass through the farm are not
measured yet; what a screen read costs (2.3 s for one `uiautomator dump`) is the floor of a single device.

**Adding a suite:** put its name into the list in `run.sh`, make the file executable, and if it needs something
special add one comment line to it:

```bash
# farm: needs=shots,hdr timeout=1200 orient=once
```

`needs` are capabilities from `farm-devices.conf`: `shots` for a suite that compares screenshots or recordings by
colour (the Tab A8 has a blue-light tint and is too slow for recordings), `hdr` for HDR and "Detail plus".
`timeout` is in seconds. `orient=once` is for a suite that does not touch the layout (it runs in the first
orientation only). **Adding a device:** one line in `farm-devices.conf`, and the lock command must know the name.

`preflight.sh` is what the farm runs first on every device, and worth running by hand before a long run: test
resources (`app/src/test/resources`, a copy of only `e2e/` fails here), every suite of `run.sh` present and
executable, adb, python3 with Pillow, ffmpeg with every encoder the suites ask for, and on the device the package
with the expected versionCode (`app/build.gradle`, or `E2E_VERSION_CODE`), notification permission and all-files
access (granted with `E2E_GRANT=1` on a test device), media volume 0, unlocked and awake, 2 GB free, no size or
density override, Ordo comes to the front. `preflight.sh --install <apk> <serial>` installs first, and only an APK
that `aapt dump badging` names `com.beko2210.ordo.dev`.

Reusable outside Ordo (notes for `android-baukasten`): `preflight.sh` (only the package name, the activity it starts
and the resource paths are Ordo's), `farm.sh` with `farm-devices.conf` (it knows nothing about Ordo beyond the suite
list in `run.sh`; the device lock is any command with the contract `<lock> <device> <cmd...>` that exports
`ORDO_SERIAL`, as `geraete/device-lock.sh` there does), and from `lib.sh`/`ui.py` the waits below.

## Waiting

A fixed `sleep` is the slowest way to wait and still too short on a slow device. Use the waits that return as soon
as the thing has happened:

| Instead of | Write | Returns when |
|---|---|---|
| `ui tap X; sleep 2; check "…" ui has Y` | `ui tap X; check "…" ui see Y` | Y is on screen (up to 5 s, `E2E_SEE`) |
| `…; sleep 2; check "…" on Screen` | `check "…" wait_on Screen 4` | that screen is in front |
| `ui tap X; sleep 1; ui tap Y` | `ui tap X; ui tap Y` | `tap` looks for its target for up to 3 s |
| `back; sleep 1` | `back` | the focused window has changed (up to 1.5 s) |
| `…MEDIA_PAUSE; sleep 1.5` | `media_key PAUSE 1.5` | the media session no longer says PLAYING |
| `wait_on …VideoPlayerActivity 8; sleep 3` | `…; media_wait PLAY 3` | playback has started |

Every screen read (`uiautomator dump`) already waits until the UI has been quiet for a second, so a sleep in front
of `ui has`, `ui tap` or `ui wait` only adds time. Never negate `ui see` (it would wait its whole time); for
something that has to go away there is `ui waitgone`. A sleep stays where time itself is the test or nothing can be
polled, and then it carries a comment saying why (`# deliberate: …`): a gesture whose effect is read right after, an
animation before a screenshot, playing for N seconds, a debounce. `ui tap` and `ui longtap` refuse to touch anything
while another app is in front (the test stops like with `guard`); a system screen the test opened itself (file
picker) is tapped with `E2E_TAP_ANY=1 ui tap …`.

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
| `backup-restore.sh` (`--write`, SD card) | an encrypted backup to the SD card restores byte for byte; a wrong password writes nothing; a changed file is never replaced and its backed up version comes back beside it; restoring twice adds no second copy; an old backup chosen as the target is refused and stays unchanged | fixed in 0.7.0 |
| `copy-unreadable.sh` (`--write`, Android 11+) | copying a selection with `Android` asks retry / skip / cancel for what can't be read and still copies the rest | fixed in 1.0.0 |
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
| `video-notification.sh` (`--write`) | with "Sound only", the media notification's target (`VideoSessionActivity`) opened over the playing player and again after Home: no crash, Ordo still runs, the player is back in front (or in its picture-in-picture window) and still plays. Reached by `am start` (adb root) or, with `E2E_SHADE=1`, by tapping Ordo Video's notification in the shade; else SKIP | blocker before 1.0.0 |
| `ftp-server-defaults.sh` (`--write`, debug build) | with Ordo's FTP settings taken out of its preferences (put back at the end): anonymous login off, the login has a password (no user such as `admin` with an empty password), no anonymous write access, the root folder not the whole internal storage; on an emulator or with `E2E_FTP_LIVE=1` also live through `adb forward` and ftplib: an anonymous login and an empty-password login for `admin` and the configured user are refused, and for any login that gets in, MKD and CWD into `Android/` are refused | blocker before 1.0.0 |
| `backup-self-include.sh` (`--write`) | a backup saved through the system picker into the very folder it backs up, twice (the second time over the first ZIP): "Backup finished", the ZIP still exists, passes a CRC test, holds the three files and no entry for itself; the ticks are put back | blocker before 1.0.0 |
| `vault-secure-flag.sh` (`--write`, `E2E_VAULT_HAND=1`, release build) | a test PNG and PDF moved into the Tresor and opened from it: each viewer window has FLAG_SECURE like the Tresor screen (`dumpsys window windows`) and a screen capture (stand-in for the Recents preview) shows no picture; the owner unlocks by hand, both files are restored out of the Tresor; a debug build only says SKIP | blocker before 1.0.0 |
| `text-editor-atomic-save.sh` (`--write`, emulator or `E2E_FILL_DISK=1`) | a 128-KiB text file edited in the text editor and saved while the storage is full: the file keeps its size and MD5, the editor keeps the typed word; the filler file goes at once | blocker before 1.0.0 |

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

Needs: `adb`, `python3` (with Pillow), and `ffmpeg` for the suites that make their own media; `preflight.sh` checks all of it. `ADB` and `PKG` can be overridden through the
environment.

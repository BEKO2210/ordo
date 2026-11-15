# Device tests

Ordo is tested on real devices before every release. Every walk-through that once found a bug lives here as a
script, so the same bug cannot come back unnoticed.

```bash
e2e/run.sh <adb serial>            # read-only tests
e2e/run.sh --write <adb serial>    # plus tests that create and delete their own files
```

`E2E_FAST=1 e2e/run.sh <adb serial>` remembers every tap position per device, screen size and screen in
`~/.cache/ordo-e2e/` and taps it straight away next time; checks still read the real screen. Delete the cache file
after a layout change. On a Pixel 11 Pro the read-only run went from 618 s to 491 s, all 110 checks passing;
`ui visible ID` (0.2 s instead of a 2.4 s screen read) then cut `drawer.sh` from 282 s to 220 s.

| Test | What it proves | Since |
|---|---|---|
| `smoke.sh` | start, home screen, every settings section and its way back | 0.4.2 |
| `category-search.sh` | searching inside a category does not crash | fixed in 0.3.3 |
| `back-navigation.sh` | back from a storage or the analysis returns to home in one step | fixed in 0.3.2 |
| `bars-replay.sh` | every storage bar, measured on its own in a screen recording, is still empty when home comes back and then grows in | fixed in 0.4.2 / 0.4.3 |
| `home-header.sh` | the home screen's top bar: search, assistant and every entry under "more" work, back returns home | 0.5.0 |
| `drawer.sh` | every entry of the side menu opens its screen and back returns home with the menu closed; a menu dragged all the way open closes on back instead of leaving the app | 0.5.0, drag fixed in 0.5.0 |
| `analysis.sh` | the scan ends in the overview; ring, folder map and rows open their pages; back goes one step at a time; rotation keeps the page | 0.6.0 |
| `stress-delete.sh` (`--write`) | 3000 files to the trash: no crash, bounded memory, the list follows | fixed in 0.3.1 / 0.3.3 |

Rules for every test:

- It acts only while Ordo is in front and aborts the run (exit 2) the moment another app is.
- It reads, taps and goes back. A test that writes creates its own files under a folder named `ordo-e2e-*`, says so
  at the top, runs only with `--write`, and removes exactly what it created.
- It finds controls by resource id, or by text with the German and the English wording (`"Aussehen|Appearance"`).
- It ends with `finish`, which also fails the test if the crash log is not empty.
- A bug found on a device gets a test here in the same pull request as its fix.

Needs: `adb`, `python3`, and `ffmpeg` for `bars-replay.sh`. `ADB` and `PKG` can be overridden through the
environment.

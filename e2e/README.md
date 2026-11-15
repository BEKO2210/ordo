# Device tests

Ordo is tested on real devices before every release. Every walk-through that once found a bug lives here as a
script, so the same bug cannot come back unnoticed.

```bash
e2e/run.sh <adb serial>            # read-only tests
e2e/run.sh --write <adb serial>    # plus tests that create and delete their own files
```

| Test | What it proves | Since |
|---|---|---|
| `smoke.sh` | start, home screen, every settings section and its way back | 0.4.2 |
| `category-search.sh` | searching inside a category does not crash | fixed in 0.3.3 |
| `back-navigation.sh` | back from a storage or the analysis returns to home in one step | fixed in 0.3.2 |
| `bars-replay.sh` | the storage bars grow in again when home comes back (screen recording) | fixed in 0.4.2 |
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

# Working on Ordo

- Every change that alters what users see or what the app does updates `README.md` and the docs in `docs/`
  in the same commit. `DocsConsistencyTest` checks the key facts (model, size, requirements, actions, version)
  against the code and fails the build when they drift.
- Only write in the docs what is measured or in the code. Numbers come from a measurement with date and device.
- New logic gets a unit test; run `./gradlew testDebugUnitTest` before pushing.
- The roadmap is alive (`docs/ROADMAP-LIVE.md`, kept with [autopoiesis](https://github.com/BEKO2210/autopoiesis)):
  every goal is a `cell` line in `utils/roadmap.sh` with a check command, and a goal is done when its command
  passes, not when someone ticks a box. Add new goals there. After every release run `utils/roadmap.sh` and commit
  the regenerated file; the run opens the cells for the version after it, before 1.0.0 and after it alike.
- Every release gets its own version number (`versionName` and `versionCode` in `app/build.gradle`, both READMEs).
- Device tests live in `e2e/` (see `e2e/README.md`). Run `e2e/run.sh <serial>` on a test device before every
  release. Every walk-through written to check a change, and every bug found on a device, stays there as a test in
  the same pull request, so it is checked again with every later release.

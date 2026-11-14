# Working on Ordo

- Every change that alters what users see or what the app does updates `README.md` and the docs in `docs/`
  in the same commit. `DocsConsistencyTest` checks the key facts (model, size, requirements, actions, version)
  against the code and fails the build when they drift.
- Only write in the docs what is measured or in the code. Numbers come from a measurement with date and device.
- New logic gets a unit test; run `./gradlew testDebugUnitTest` before pushing.

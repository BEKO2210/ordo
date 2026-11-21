# Grok review — Ordo 0.18.0

Independent review of `origin/main...HEAD` on branch `grokbot/review-0.18` (merge-base `44f8125b`, the integrate/0.18.0 history). `git diff origin/main...HEAD --stat`: 76 files, +3867 / −309. Only defects that fail in a concrete scenario. No style notes.

No high-severity finding. The HDR video-graph clock call compiles (see the clean list).

## Findings

### 1. Medium — HDR measurement PBO is reused while the GPU read is still in flight

`app/src/main/java/me/zhanghai/android/files/viewer/video/picture/HdrToneMapEffect.kt:181-193` and `:280-285`

`queueInputFrame` issues `glReadPixels` into a pixel-pack buffer, then stores `glFenceSync`. A slot is eligible again only when `fences[slot] == 0`. `flush()` (seek) deletes every fence and writes 0 without waiting:

```280:285:app/src/main/java/me/zhanghai/android/files/viewer/video/picture/HdrToneMapEffect.kt
    override fun flush() {
        super.flush()
        for (i in fences.indices) {
            if (fences[i] != 0L) GLES30.glDeleteSync(fences[i])
            fences[i] = 0L
        }
```

`glDeleteSync` itself is legal: the sync object is only flagged if something still waits on it. The pixel-pack buffer is not. The next HDR frame sees `fences[slot] == 0` and starts another `glReadPixels` into the same store. ES 3.0 leaves that undefined while the previous read has not completed (Khronos: a pack buffer stays in use until the read finishes). `glFenceSync` returning 0 (failure) is stored the same way, so that slot is reused on the next measured frame with no wait at all.

Failure: seek during HDR playback, or a failed fence, overlaps two reads in one PBO. The histogram then takes a torn 128×72 frame, so the scene peak (and the EETF) is wrong until the next good read. On drivers that fault on a buffer written twice, the GL thread dies inside the effect and playback stops.

`release()` (`:290-294`) only deletes the names. Deleting a buffer that is still in use keeps the storage until the GPU finishes, so that path is not the bug.

Fix: in `flush()`, `glClientWaitSync(fence, GL_SYNC_FLUSH_COMMANDS_BIT, GL_TIMEOUT_IGNORED)` before `glDeleteSync`. If `glFenceSync` returns 0, orphan that PBO (`glBufferData(..., null, GL_STREAM_READ)`) and allocate new storage before the next `glReadPixels`. Do not treat fence id 0 as "idle" when a read was just submitted.

### 2. Medium — Detail Plus fragment shader is illegal GLSL ES 3.00

`app/src/main/java/me/zhanghai/android/files/viewer/video/picture/MpvHook.kt:145` and `:161-164`
`app/src/main/java/me/zhanghai/android/files/viewer/video/picture/DetailPlusEffect.kt:249-253`

Every fused pass is compiled as `#version 300 es`. For same-size passes the generated macro is:

```163:164:app/src/main/java/me/zhanghai/android/files/viewer/video/picture/MpvHook.kt
                sb.append("#define ${n}_texOff(off) ${n}_sample(texelFetch(${n}_raw, clamp(ivec2(gl_FragCoord.xy) + ")
                sb.append("ivec2(vec2(off)) * ivec2(1, -1), ivec2(0), ivec2(${n}_size) - 1), 0))\n")
```

`clamp` on `ivec2` is not in GLSL ES 3.00 (common functions are `genType` / float only). Integer `clamp` arrives in ES 3.10. Integer `min` / `max` do exist in ES 3.00. The capability gate still accepts ES 3.0 when a half-float color-buffer extension is present:

```249:253:app/src/main/java/me/zhanghai/android/files/viewer/video/picture/DetailPlusEffect.kt
            check(major >= 3) { "no OpenGL ES 3: $version" }
            check(
                major > 3 || minor >= 2 || "GL_EXT_color_buffer_half_float" in extensions ||
                    "GL_EXT_color_buffer_float" in extensions
            ) { "no half-float render targets" }
```

`minSdk` is 24, so ES 3.0 is a real device floor. A strict ES 3.0 compiler rejects the shader. The `init` catch marks the model unsupported for the rest of the process and the program copies the frame. Automatic then stays on Lanczos (`DetailPlus.auto` drops unsupported models). Not a crash. Lenient drivers that accept integer `clamp` under `300 es` are unaffected; this was not run on a device compiler.

Fix: edge-clamp with `max(ivec2(0), min(coord, ivec2(size) - ivec2(1)))`. That is legal in ES 3.00. Requiring ES 3.1 would also work and would drop the devices the half-float check tried to keep.

### 3. Medium — a failed decoder query is treated as "a platform decoder exists"

`app/src/main/java/me/zhanghai/android/files/viewer/video/picture/VideoPicture.kt:506-513`

```510:513:app/src/main/java/me/zhanghai/android/files/viewer/video/picture/VideoPicture.kt
    private fun platformDecodes(format: Format): Boolean = runCatching {
        val mimeType = format.sampleMimeType ?: return true
        MediaCodecUtil.getDecoderInfos(mimeType, false, false).any { it.isFormatFunctionallySupported(activity, format) }
    }.getOrDefault(true)
```

`MediaCodecUtil.getDecoderInfos` throws `DecoderQueryException` (an `Exception`, so `runCatching` swallows it). `getOrDefault(true)` then tells `toneMaps` that this is not the software path (`VideoPicture.kt:363-370`, `PicturePolicy.toneMaps`). The comment on the function says the opposite: without a platform decoder, FFmpeg draws the frame itself, and engaging the HDR pass first only rebuilds the surface twice.

Failure: codec enumeration fails (broken codec xml, transient mediaserver). HDR-to-SDR is engaged, `stop()` + `prepare()` runs, then the effect path errors or the player rebuilds the surface again when the software decoder is discovered. `return true` on a null MIME is a non-local return from `platformDecodes` (same assumption). A selected video track almost always has a MIME; the swallowed query is the case that matters.

Fix: `getOrDefault(false)`. A failed query takes the no-tone-map path. An empty decoder list already returns false via `any`.

### 4. Medium — DeepFilterNet3 native state leaks if the player is released with the load result queued

`app/src/main/java/me/zhanghai/android/files/viewer/video/sound/VideoSoundEngine.kt:347-361` and `:499-514`
`app/src/main/java/me/zhanghai/android/files/viewer/video/sound/DialogIsolation.kt:33-48`

The worker loads the model, measures it, then posts the result. The `DeepFilter` is assigned to `isolation.filter` only inside that runnable. `release()` drops every pending main-thread message and closes only `isolation.filter`:

```352:361:app/src/main/java/me/zhanghai/android/files/viewer/video/sound/VideoSoundEngine.kt
            handler.post {
                isolationLoading = false
                if (released || filter == null || DialogModel.tooSlow(rtf)) {
                    filter?.close()
                    // Too slow here: the option goes away (DialogModel.offered) and switches off.
                    if (!released && settings.isolateDialog) update(settings.copy(isolateDialog = false))
                    notifyChanged()
                    return@post
                }
                isolation.filter = filter
```

```508:514:app/src/main/java/me/zhanghai/android/files/viewer/video/sound/VideoSoundEngine.kt
        handler.removeCallbacksAndMessages(null)
        worker.shutdownNow()
        loudnessWorker.shutdownNow()
        effects.release()
        output.release()
        // Safe while the audio thread is still in a hop: DeepFilter.close waits for it, later hops pass the sound.
        isolation.filter?.close()
```

`DeepFilter` has no finalizer. `close()` is the only `ordo_df_free`. Both `release()` and the runnable run on the main looper, so they do not interleave mid-function. The leak is the message that is already queued: `removeCallbacksAndMessages` discards it, `isolation.filter` is still null, and the local `DeepFilter` is never closed.

The other order is safe. If `release()` runs before `handler.post`, the later runnable still runs, sees `released`, and closes. `shutdownNow()` does not cancel a runnable that has not been posted yet.

Failure: leave the player in the window after verify + load + 300 hops have finished and before the main looper runs the post (back press, or `onDestroy` already running). The native model stays mapped until process death. The comment on `release()` covers only a filter that was already installed.

Fix: publish the in-flight instance on an `AtomicReference` before `handler.post`. `release()` closes that reference and `isolation.filter`. The runnable takes the reference with `getAndSet(null)` and closes it when `released` is set. `close()` is synchronized and idempotent, so a double close is safe.

### 5. Medium — one failed hop is stored as "this device is too slow" with no retry

`app/src/main/java/me/zhanghai/android/files/viewer/video/sound/DialogModel.kt:53-61` and `:153-170`
`app/src/main/java/me/zhanghai/android/files/viewer/video/sound/VideoSoundEngine.kt:349-350`

`measureRtf` returns `Float.POSITIVE_INFINITY` on the first hop whose `process` returns false (native NaN, or a closed handle). `loadIsolation` then always persists that value:

```349:350:app/src/main/java/me/zhanghai/android/files/viewer/video/sound/VideoSoundEngine.kt
            val rtf = filter?.let(DialogModel::measureRtf) ?: Float.NaN
            if (filter != null) DialogModel.saveRtf(context, rtf)
```

`tooSlow` is `!rtf.isNaN() && rtf > 0.7f`. Infinity matches. `offered()` stays false for every later launch. `KEY_RTF` has no `VERSION_CODE`, unlike the Detail Plus budget key. Nothing clears it except app data.

A finite RTF above 0.7 is the intended permanent hide. A measurement that never finished is not. The download hash check does not prevent a later `process` panic (catch_unwind in `ordo_df_process` returns NaN).

Fix: on a failed hop return `Float.NaN` and skip `saveRtf` unless the value is finite. `tooSlow(NaN)` is already false, so the next playback measures again. Persist only a completed 300-hop median.

### 6. Low — offscreen Detail Plus probe returns without releasing the thread's EGL state

`app/src/main/java/me/zhanghai/android/files/viewer/video/picture/DetailPlusEffect.kt:78-106`

`msPerMp` runs on `Dispatchers.Default` (`VideoPicture.kt:493`). After a successful `eglInitialize` on `EGL_DEFAULT_DISPLAY`:

- `eglChooseConfig` failure returns at line 88
- `eglCreateContext == EGL_NO_CONTEXT` returns at line 92

both skip the `try/finally`. No context object is leaked on those two returns (no context was created, or it is `EGL_NO_CONTEXT`). The success path destroys the pbuffer and the context, then returns the thread to the pool without `eglReleaseThread()`.

`eglTerminate` on the default display would be the wrong fix: the player shares that display, and terminate is refcounted process-wide.

Failure: a pool thread that has touched this probe keeps a driver thread-local EGL binding. A later probe on the same thread, or any other EGL user scheduled there, can fail `eglMakeCurrent` or attach to a display connection that was never released. Bounded by the pool size, not one leak per film.

Fix: one `try/finally` starting after a successful `eglInitialize`, covering the early returns. In that `finally`, destroy whatever was created and call `eglReleaseThread()`. Do not `eglTerminate` the default display.

### 7. Low — legacy per-film delay file is marked deleted even when the delete fails

`app/src/main/java/me/zhanghai/android/files/viewer/video/sound/VideoSoundSettings.kt:98-109`

`dropLegacyOnce` calls `delete()` and then always `markDone()`. The lambda is `() -> Unit`, so the `Boolean` from `Context.deleteSharedPreferences("video_av_delay")` is discarded. The done flag lives in `video_sound`, not in the file being deleted.

Failure: `deleteSharedPreferences` returns false (file busy, storage error). `legacy_av_delay_dropped` is still set. Nothing in 0.18 opens `video_av_delay` again, so the 0.17 keys — film path plus delay — stay on disk. A delete that returns true is fine; `apply()` racing process death just retries, which is safe.

Fix: `delete(): Boolean`, and call `markDone()` only when it returns true.

## Checked and found clean

- **`KlangChain.kt:468` `.setClock(clock)`.** Media3 1.11.1 `BaseRenderer` keeps `clock` private and exposes `protected final Clock getClock()` (`BaseRenderer.java` in the 1.11.1 tag). Kotlin maps that getter to the property `clock`. The call is the same one Media3's own `createPlaybackVideoGraphWrapper` makes, and it runs from `onEnabled` after `init`. Not an unresolved reference. The replacement `MediaCodecVideoRenderer.Builder` leaves `parseAv1SampleDependencies` at the 1.11.1 default (`true`) and sets the same late-drop threshold constant the factory uses. The secondary / prewarming renderer is not overridden; prewarming defaults off.
- **`HdrGraph` encoder-input flag.** `HdrToneMapEffect.kt:93` builds `SurfaceInfo(surface, width, height, orientationDegrees, true)`. In Media3 1.11.1 the fifth argument is `isEncoderInputSurface`. Combined with `PQ_OUTPUT` (BT.2020, limited, ST2084) this is the path that stops SurfaceFlinger applying a second tone map. `toneMaps` excludes Dolby Vision, DRM, the software decoder, a previous GPU failure, and the process-wide slow fallback.
- **HDR slow fallback.** `hdrTooSlow` is written and read on the main handler only. After `SLOW_SECONDS` the pass is dropped, a notice is shown, then the pipeline is recreated. `frameRate == NO_VALUE` yields the 2 ms budget on purpose. The PBO stall on `GL_TIMEOUT_EXPIRED` (`HdrToneMapEffect.kt:252`) retries next frame; it does not free the slot early.
- **Detail Plus budget units.** `measureOffscreen` records `detailBudget.record(model, 1000, 1000, msPerMp)` and stores the same ms-per-megapixel value. `predict` scales by `width * height / 1e6`. A null probe adds the model to `probeFailed` for the session and does not put it on screen (`DetailPlus.auto` returns `MEASURING` and names that model). Prefs key includes `VERSION_CODE`. Timer readback masks the 32-bit query with `0xffffffffL` and reads `GL_GPU_DISJOINT_EXT` before the result. Missing timer extension falls back to wall time with a slowdown, which errs toward Lanczos.
- **Offscreen probe context object.** On the success path `eglMakeCurrent(NO_CONTEXT)`, `eglDestroySurface`, and `eglDestroyContext` run in `finally`. A failed pbuffer (`EGL_NO_SURFACE`) still hits that `finally`. The gap that remains is finding 6, not a leaked `EGLContext`.
- **DeepFilter JNI / Rust** (`utils/deepfilter/ordo_df_jni.c`, `ordo_df/src/lib.rs`). `create` rejects a null path and a bad C string, copies the path before `ReleaseStringUTFChars`, and turns a panic into null. `process` copies with `Get/SetFloatArrayRegion` (not a critical pin). A length mismatch returns NaN before the VLA. `free` is null-safe; `DeepFilter.close` is synchronized, sets the handle to 0, and ignores a second close. `process` bails out when the handle is 0. The hop VLA is 480 samples for the pinned archive; `verify()` checks SHA-256 `c94d91f7…` and size `7_983_136` before `DeepFilter.load`. No `app/src/main/cpp` diff (existing `hiddenapi.c` / `syscall.c` only). `build.sh` pins the NDK, arm64-v8a and x86_64, and 16 KB alignment.
- **Dialog ring.** `ringSize = delay + hop`, read index `(ringPos + hop) % ringSize` is the sample written `delay` samples earlier. `delay == 0` reads the sample just written. `finish()` pads to a hop and stops emitting at `framesIn`. `hop == 0` would spin; the pinned model does not produce that. `queueInput` assumes whole 16-bit stereo frames, which is the `AudioProcessor` contract. `onConfigure` returns `NOT_SET` unless 16-bit stereo at the model rate.
- **Model download.** `.part` is hashed before `renameTo`. A failed hash returns false and `finally` deletes the part. Same-directory `renameTo` replaces an existing target; deleting the old `.part` path afterwards is a no-op. `refresh()` treating `length == SIZE` as ready is closed by `verify()` on every load, which deletes a bad file. `catch (Exception)` on the download returns false and surfaces `State.Failed` rather than a crash.
- **A/V delay privacy and permissions.** Bluetooth key is `type` plus 6 hex bytes of SHA-256 of the uppercased address (`VideoDeviceDelay.kt:50-54`). The MAC is not stored. Blank address falls back to `type|productName`. `AudioDeviceInfo.getAddress()` in AOSP `AudioDeviceInfo.java` is `return mPort.address()` with no `@RequiresPermission`. The manifest has no `BLUETOOTH` / `BLUETOOTH_CONNECT`, and this call does not need them. `VIDEO_NO_HISTORY` still gates loudness only; the device delay is not a film path. Two identical headphones with a blank address share one delay (degraded key, not a crash).
- **`MODIFY_AUDIO_SETTINGS`.** Added at `AndroidManifest.xml:29`. It is a normal permission and it is the one `AudioManager.setPreferredMixerAttributes` checks (API 34). `HiResOutput` was not logically changed in this diff; `setPreferred`, `clearPreferred`, `getSupportedMixerAttributes`, and `getAudioDevicesForAttributes` already sit in `runCatching`. A security failure skips bit-perfect instead of crashing. `OutputPolicy.MIN_SDK` gates the route.
- **`RateOrdo`.** No Play SDK. The issue body is version, manufacturer, model, and OS release (`RateOrdo.kt:82-86`), not paths or account names. `openStore` catches `ActivityNotFoundException` and opens the https store URL. Settings uses `requireContext()` on an attached fragment. `recordSuccess` can lose a count if two jobs write at once; that only delays the one-time hint. `takeIfDue` marks the hint shown before the snackbar is on screen; that consumes the hint early and does not crash.
- **Onboarding.** `onboarding_activity.xml` has `pageApps`, `featureAnalysis`, `featureMusic`, `featureVideo`. The new page does not reference a missing id.
- **`VideoPicture.onDestroy`.** Removes the handler callbacks, including the HDR ticker, before dropping listeners.

Not verified on a device or by compiling `:app:compileDebugKotlin`. Shader rejection (finding 2) is from the GLSL ES 3.00 / 3.10 specs; the PBO overlap (finding 1) is from the ES 3.0 buffer-object and `glReadPixels` rules.

## Verdict (checked by Claude against code and specs, 2026-11-21)

| # | Finding | Verdict | Action |
|---|---|---|---|
| 1 | HDR PBO reused while read in flight | Not real | GL commands execute in order; a second `glReadPixels` into the same pack buffer is serialized by the GL, and `glMapBufferRange` without `MAP_UNSYNCHRONIZED_BIT` synchronizes pending operations first (ES 3.0 spec §2.10.3: only that bit makes overlapping operations undefined). A failed fence only loses that one measurement. No change. |
| 2 | `clamp` on `ivec2` illegal in GLSL ES 3.00 | Not real | GLSL ES 3.00 spec §8.3 lists `genIType clamp(genIType x, genIType minVal, genIType maxVal)` (and integer `min`/`max`). No change. |
| 3 | Failed decoder query counts as platform decoder | Real (edge) | `platformDecodes` defaults to `false`: the same query failing makes Media3's decoder choice fall back to FFmpeg too. |
| 4 | DF3 filter leaks when `release()` drops the queued result | Real | In-flight filter held in an `AtomicReference`; `release()` closes it, the runnable takes it with `getAndSet(null)`. |
| 5 | One failed hop persisted as "too slow" | Real | `measureRtf` returns NaN on a failed hop and retries once; NaN is never saved and the filter is not installed. Test `aFailedHopIsNoMeasurement`. |
| 6 | Probe leaves the pool thread's EGL state | Real (hygiene, low) | `eglReleaseThread()` in a `finally` right after `eglInitialize`; no `eglTerminate`. |
| 7 | Legacy delete marked done on failure | Real (low) | `delete` returns `Boolean`, `markDone` only on success. Test `aFailedLegacyDeleteIsTriedAgain`. |

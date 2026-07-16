# RootlessJamesDSP Audit Backlog and Remediation Roadmap

The four workstreams discussed in chat group every finding below. Checked items are fixed and verified.

## Repository reproducibility

- [x] Move the accumulated work to `feature/darwin-liveprog-audio-stability`.
- [x] Commit the native DSP changes and reference that concrete submodule revision from the Android repository.
- [x] Keep the user-supplied Darwin archive and downloaded F-Droid evidence out of version control.
- [ ] Publish the native commit to a `051-lab/JamesDSPManager` fork and update `.gitmodules`.
- [ ] Push the Android branch and verify a clean recursive clone produces the same tested APK.

## Independent review remediation roadmap (2026-07-16)

Work proceeds in the order below. Each batch must pass its focused checks before the next batch starts. The existing native engine and Android service remain in place; this roadmap does not add a second main DSP pipeline, a routing framework, or new dependencies.

### Batch 1: Playback stability and sample-rate correctness

Goal: keep allocation and convolution setup off the recorder thread and initialize every rate-dependent stage with the active processing rate.

- [x] Add a native block-capacity setter in `jdspController.c`/`jdsp_header.h` that reallocates and refreshes convolution only when the configured capacity changes.
- [x] Expose that setter through `JamesDspWrapper.cpp`, `JamesDspWrapper.kt`, and `JamesDspLocalEngine.kt`.
- [x] Change every native processing entry point so a smaller partial block does not change the configured convolution partition or refresh DSP state. Unexpected capacity growth remains a guarded fallback.
- [x] In `RootlessAudioProcessorService`, validate the configured interleaved buffer size to the existing 128-16,384 range, convert it to stereo frames, and reserve that capacity before loading effects or starting the recorder thread.
- [x] Remove the premature preference synchronization from service `onCreate()`. Set the actual processing sample rate and block capacity first, then perform the initial preference sync.
- [x] Reserve the same frame capacity on each Darwin replacement engine before loading its coefficients.
- [x] Refresh the vacuum-tube coefficients and LiveProg `srate` variable from `JamesDSPSetSampleRate`; retain the existing limiter/sample-rate refresh behavior.

Checks:

- [x] Add an instrumentation regression that processes identical audio using fixed and varying partial-block lengths and compares the results.
- [x] Extend the LiveProg runtime check to prove `srate` changes after an engine sample-rate update.
- [x] Run host unit tests and `assembleRootlessFdroidDebug`.
- [ ] On S24, verify playback with the screen on and off before and after enabling Darwin.

Completion gate: no native allocation or convolution refresh occurs for ordinary partial reads, and rate-dependent tests pass at both 44.1 and 48 kHz.

### Batch 2: Deterministic Darwin handoff and cleanup

Goal: preserve the active signal path until a replacement is valid, never run the main and Darwin convolution stages together, and keep native destruction off the audio loop.

- [x] Add a small synchronous local-engine operation for disabling the main convolver. Reuse the existing synchronous vacuum-tube and output-stage setters.
- [x] During a validated Darwin enable/replacement handoff, disable conflicting main-engine convolver/tube stages before publishing the Darwin engine pointer.
- [x] During Darwin disable, remove the Darwin engine first and let the existing preference sync restore the configured main convolver/tube stages. The existing fade masks this short handoff without allowing double processing.
- [x] Move disposal of the replaced Darwin engine to the service IO scope after the pointer swap.
- [x] On Darwin build failure, keep the active engine/configuration, reset only the matching failed request marker so the same preference can be retried, and report the invalid package through the existing user-visible error path.
- [x] Ensure stale queued/background builds cannot replace a newer request and close every discarded replacement off the recorder thread.
- [x] Keep final limiter/post-gain ownership synchronous: main engine without Darwin, Darwin engine with Darwin.

Checks:

- [ ] Add focused state-transition checks for disabled -> enabled, enabled -> replacement, enabled -> disabled, stale build, and invalid replacement.
- [ ] Repeatedly switch Darwin filters while audio is running and monitor crashes, underruns, limiter ownership, and audible doubling.
- [ ] Verify normal Convolver and Tube preferences are restored after Darwin is disabled.

Completion gate: a failed update leaves the previous sound active, transitions never process both convolution stages, and old engines are not freed from the processing loop.

### Batch 3: Transactional LiveProg reload

Goal: invalid EEL source must not destroy or disable the last valid program.

- [x] In `liveprogWrapper.c`, split and compile all sections into a temporary `LiveProg` VM/program state.
- [x] Execute temporary `@init` and `@slider` only after every requested section compiles.
- [x] Swap the temporary state into `jdsp->eel` under the existing DSP mutex only on complete success; otherwise free the temporary state and leave the active VM untouched.
- [x] Preserve the compiler error text before freeing a failed temporary VM and return failure from the JNI setter.
- [x] In `JamesDspWrapper.cpp`, stop disabling LiveProg before compilation. Apply the requested enabled state after a successful load; an explicit disable still disables immediately.
- [x] Keep the implemented execution contract unchanged: `@slider` after load/parameter changes, `@block` once per native block, and `@sample` once per stereo frame.

Checks:

- [x] Extend `LiveProgRuntimeInstrumentedTest` with valid -> invalid -> valid reload and confirm the first program continues processing after the invalid reload.
- [x] Re-run parser host tests and existing `@slider`/`@block` runtime checks.

Completion gate: every failed parse or compile preserves the previous VM, variables, enabled state, and audible processing.

### Batch 4: Imported-state and Darwin validation

Goal: malformed backups/preferences/packages fail closed without canceling future updates or introducing non-finite samples.

- [x] Replace throwing preference integer conversions in `JamesDspBaseEngine` with defaults matching existing XML defaults.
- [x] Require exactly 30 multimodal-EQ values and 14 compander values before writing fixed native arrays.
- [x] Catch and log top-level preference-sync failures so one malformed import cannot stop later synchronization.
- [x] Clamp rootless buffer size, Darwin harmonic amount, limiter threshold/release, and post-gain to their existing UI ranges; replace non-finite values with defaults.
- [x] Reject non-finite limiter/post-gain/harmonic values at the JNI/native setter boundary as defense in depth.
- [x] Refactor `DarwinFilterPackage` so list/read share one `ZipFile`, coefficient reads are exactly bounded to 1,024 bytes, and normalization uses `Double` compensated summation.
- [x] Reject zero or poorly conditioned DC sums relative to coefficient L1 magnitude before unity normalization.
- [x] Preserve manifest order and the existing fallback to the first valid filter when a saved selection is missing.

Checks:

- [ ] Add host tests for extra/missing EQ and compander tokens, malformed integer preferences where practical, NaN/Infinity configuration sanitization, bounded ZIP reads, and near-canceling Darwin coefficients.
- [x] Re-run all Darwin parser and headroom host tests.

Completion gate: malformed imported state is rejected or defaulted without a crash, prior working DSP state remains usable, and a later valid update still applies.

### Final verification and packaging

- [x] Run `testRootlessFdroidDebugUnitTest`, `lintRootlessFdroidDebug`, and `assembleRootlessFdroidDebug`.
- [x] Run native instrumentation tests on the connected S24 Ultra.
- [ ] Test S24 speakers and Bluetooth with screen on/off, browser playback, route reconnect, service restart, bypass, Darwin switching, and LiveProg reload stress.
- [x] Install the final debug APK on the S24 Ultra and record its path and SHA-256.
- [x] Add a Gradle-launcher line-ending rule for future source archives; this is packaging-only and does not block Android runtime testing.

Deferred until reproduced by testing: bypass-state reset semantics, asynchronous service-destruction redesign, direct JNI audio buffers, immutable routing snapshots, and a different true-peak detector. These are not required to fix the confirmed defects above.

## 1. Native and audio lifecycle

- [x] **High:** Make JamesDSP global memory process-owned so Darwin engine replacement cannot invalidate another engine.
- [x] **High:** Make local-engine close synchronous with in-flight JNI calls and cancel preference synchronization.
- [x] **High:** Stop and join the recorder thread before freeing native engines; make cross-thread state visible.
- [x] **High:** Replace stored `JNIEnv*` use with `JavaVM` thread attachment and make LiveProg callback ownership safe.
- [x] **Medium:** Clean up failed `AudioRecord`/`AudioTrack` initialization without crashing or leaking the recorder.
- [x] **Medium:** Honor partial/error `AudioRecord.read` and `AudioTrack.write` results instead of processing stale samples.
- [x] **Medium:** Validate JNI handles, array bounds, offsets, frame counts, and native allocation failures.
- [x] **Medium:** Fix native cleanup defects, including mutex destruction and temporary-string lifetime.

## 2. Files, archives, and restore safety

- [x] **High:** Block TAR absolute/traversal paths and cap entry count and expanded size.
- [x] **High:** Validate backup identity and payload before clean restore can delete current configuration.
- [x] **Medium:** Sanitize SAF display names, close cursors, avoid validation/import races, and return UI work to the main thread.
- [x] **Medium:** Enforce basename-only Darwin manifest paths before resolving filter entries.

## 3. Android lifecycle and integration

- [x] **Medium:** Destroy session managers, polling jobs, observers, preference listeners, binders, and engine scopes deterministically.
- [x] **Medium:** Handle `MediaProjection` revocation and explicit shutdown even while the activity is foreground.
- [x] **Medium:** Fix fragment view-binding owners, activity-scoped observers, and the mismatched preference listener unregister.
- [x] **Medium:** Fix activity state saving and unmanaged coroutines that retain an activity.
- [x] **Medium:** Retain the exported power receiver as a public automation API, but require its documented action and explicit state extra.
- [x] **Low:** Remove stale package-installer callbacks after terminal results.

## 4. Sessions, routing, and robustness

- [x] **Medium:** Synchronize session and policy collections shared by poller, callback, and recorder threads.
- [x] **Medium:** Support multiple active browser session IDs from one PID.
- [x] **Medium:** Make dump-method selection and OEM dump parsing fail safely.
- [x] **Medium:** Resolve the actual active output when multiple Bluetooth devices share a route group.

## Verification and maintenance

- [x] Add focused host regression checks for Darwin, archives, backup identity, dump fallback, and browser session resolution.
- [x] Make Darwin parser tests self-contained instead of requiring an untracked local archive.
- [x] Run unit tests, strict lint, and `assembleRootlessFdroidDebug`; make CI run tests and fail on actionable lint errors.
- [ ] Add device/instrumentation stress coverage for native engine replacement and recorder teardown.
- [ ] Complete S24 and S10 playback acceptance checks for speakers, Bluetooth, browsers, restart, and Darwin switching.

## Open pull request review

- [x] Harden the current LiveProg declaration parser for legacy comments, optional steps, decimal/scientific notation, duplicate names, and bounded parameter discovery.
- [ ] Revisit stable name-based LiveProg parameter persistence after a lifecycle-owned engine control API exists.
- [x] Implement LiveProg `@slider` and `@block` locally without importing PR #329's unpublished native revision.
- [ ] Revisit runtime module ordering only with every existing DSP module preserved and a race-free state publication design.
- [ ] Consider a home-screen widget separately if the existing Quick Settings tile is insufficient; do not import PR #239's stale service changes.

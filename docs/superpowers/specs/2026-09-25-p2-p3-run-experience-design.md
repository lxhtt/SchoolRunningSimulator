# P2/P3: simulator and real-run experience

Status: design approved in conversation; implementation and device acceptance pending.

## Boundaries

- P2 is an in-app simulated session. Its speed, cadence, steps, distance, and route are generated, labeled as simulated, and never written to system location or reported as measurements.
- P3 is a separately initiated real-run session. GPS and sensor readings are observations with timestamps, age, accuracy, and availability, never replaced with simulated values. Preserve S7 raw CSV semantics and private storage.
- P4/P5/P7 location and sensor injection, third-party compatibility claims, personal calibration in the APK, and Play eligibility claims are excluded.
- Existing engineering defaults remain explicitly uncalibrated. A user's body measurements or target ranges must not silently imply a personally fitted model.
- CI owns Gradle, lint, and APK builds. Do not download Gradle dependencies locally. Private raw and derived recordings never enter Git, CI, or the APK.

## First-launch power guidance

On the first normal launch, open this application's Android system details screen automatically so the user can find its battery policy and choose unrestricted operation. Use the package-specific `ACTION_APPLICATION_DETAILS_SETTINGS` intent; do not use an undocumented Xiaomi-specific component or request battery-optimization exemption permission. Mark the guidance as shown before launching so returning to or recreating the activity does not reopen settings. If no handler exists, stay in the app. Provide a persistent in-app route to the same settings page for later use. Explain that the user must select the policy and that device power management can still interrupt background work. Never infer or display an assertion that the policy is unrestricted merely from returning to the app. Test first launch, return, relaunch, missing settings handler, and existing-install behavior.

## P2: in-app simulation

Continue using `InteractiveSimulation` and `SimulatorService` as the source of simulated session state, with its monotonic clock, bounded wake lock, notification actions, and separate recorder service. Preserve accumulated time, distance, steps, and acceleration when changing speed. Reject an infeasible manual cadence/speed combination before start and during a live change, showing an actionable inline error; never change cadence silently. Show the selected manual/automatic mode consistently in the active UI.

Keep pause, resume, and stop accessible without scrolling past live speed controls on supported small screens and enlarged fonts. Add a bounded session history and present speed/cadence curves and a clearly labeled synthetic route view. Synthetic positions need an explicitly configured or non-personal reference route; do not plot private real locations as a simulation. Session exports must use the existing core CSV/JSON/GPX concepts, include units and simulation/uncalibrated provenance, and be stored privately until an explicit Android share/export action. Generate valid GPX timestamps and test the output with a parser. Provide an end-of-session summary and preserve the existing interrupted-state distinction. Voice cues, when enabled, announce state/target changes without blocking the simulation worker and must be switchable off.

The product plan mentions ranges, distance, and body measurements. Add these as explicit, separately validated inputs only where they have defined effects in `sim-core`; do not present a nonfunctional control or use invented body-to-gait formulas. Target duration/speed/cadence remain the supported baseline until those contracts and tests are in place.

## P3: real-run assistant

Provide a separate start/stop foreground real-run flow and private recording independent of the P2 service and export. Reuse S7's GPS freshness, accuracy, and step-counter handling where practical without changing its raw CSV format. Derive live cadence preferentially from timestamped `TYPE_STEP_DETECTOR` events over a documented rolling window; cross-check cumulative `TYPE_STEP_COUNTER` and label missing, delayed, reset, or unavailable sensors instead of fabricating a value. Accelerometer peak detection is a separately validated fallback, not an assumed equivalent. Show measured and target values side by side, with stale GPS identified as stale. Optional voice guidance uses cooldowns and is disabled by default; no voice cue presents simulated data as actual motion.

Service termination, permission denial, disabled GPS, sensor absence, long callback gaps, process death, and device power restrictions must produce legible states rather than success-looking data. Define a real-session export schema with raw versus derived fields and test parsing/round trips; keep all recordings private until explicit sharing. A cadence error claim of within 5 steps/minute requires three outdoor runs with an independent manual/reference count, varied carry positions, and a published aggregate error report. Until then, report the feature as implemented but not accuracy-validated.

## Delivery and verification

1. Implement and device-check first-launch guidance independently. CI must pass; confirm the settings page opens on the Xiaomi test device and that returning/relaunching does not loop.
2. Finish P2 in small verified increments: validation and controls, history/visualizations, exports/voice, and lifecycle/responsive acceptance. Keep existing tests and add focused core and Android checks. Verify a CI-built APK on small and large text, landscape, light/dark, natural completion, interruptions, and notification actions. Xiaomi's screen-off throttling remains a documented limitation, not an automatic pass.
3. Implement P3 independently with sensor/GPS state tests and CI builds, then do three reference-backed outdoor sessions. Do not close P3 accuracy acceptance when those measurements are unavailable.

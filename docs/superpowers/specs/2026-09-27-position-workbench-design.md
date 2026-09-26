# RateMock Position Workbench and Mock Location Design

Date: 2026-09-27

## Scope

RateMock gains a single-APK position workbench without copying GoGoGo source, assets, SDKs, keys, or signing material. The existing simulation, real recording, diagnostics, local replay receiver, and export flows remain available and keep their provenance boundaries.

The new workbench provides:

- coordinate entry and validation;
- an optional OpenStreetMap tile view with an offline coordinate-grid fallback;
- network place search through a rate-limited geocoding adapter;
- a private history of user-selected locations;
- joystick movement that produces a route of explicit points;
- a user-controlled Android Mock Location foreground service.

This feature does not inject step sensors, communicate with third-party applications, bypass detection, or claim compatibility with a third-party running platform.

## Architecture

`sim-core` owns platform-neutral location primitives: `PositionPoint`, validation, movement by meters, and great-circle distance. These primitives are deterministic and JVM-testable.

The Android app owns `PositionHistoryStore`, network adapters, the map renderer, and `MockLocationService`. The store uses app-private JSON in SharedPreferences and caps history. The service consumes validated points from the workbench through explicit intents and publishes status in app-private preferences.

The workbench is a new top-level tab in `MainActivity`. It combines the coordinate editor, map surface, search results, history, joystick, and service controls. Existing services keep their data boundaries: the user may explicitly start a replay of the latest successfully closed simulation CSV, but the position service never reads real recordings or automatically starts from simulation state.

## Map and Search

The map renderer uses public OSM raster tiles only when the network is available. It uses small memory and app-private disk caches, bounded zoom, and an identifying User-Agent. No provider key is stored in source or build configuration. Failed tile requests never block coordinate editing or mock updates; the renderer displays a coordinate grid and the current route instead. OSM attribution remains visible on the map.

Search uses a dedicated geocoding request path with timeout, query length limits, and a minimum request interval. Results are parsed into validated `PositionPoint` values. A failed or unavailable network returns an inline error while preserving the current point and history.

## Mock Location Lifecycle

The service is started only from an explicit user action while the app is visible. It runs as a location foreground service and posts one current point at a fixed interval. Start validates the selected point, checks location permission, and reports the Android requirement that RateMock be selected as the device's mock-location app. Provider registration is idempotent.

Pause stops publishing while retaining the selected point. Stop removes test providers, clears the active state, removes the foreground notification, and stops the service. `onDestroy` repeats cleanup defensively. A provider registration or update failure is recorded as an actionable status rather than silently treated as success.

The service does not persist real GPS observations into the position history, and it never alters the recording or simulation directories.

## Data Flow

1. The user edits or selects a validated `PositionPoint`.
2. The point is drawn on the map and may be saved to app-private history.
3. Joystick actions create new points using deterministic meter-to-degree movement.
4. Start sends the current point to `MockLocationService`, which uses a fixed one-second update interval. Alternatively, the user selects the latest successfully closed simulation history, and the service replays its validated east/north samples from the selected point.
5. The service registers providers, publishes Android `Location` objects, and writes status/heartbeat preferences.
6. The workbench polls status for UI display and sends pause/resume/stop actions. Simulation CSV parsing is bounded and provenance-checked; recordings are never a source.

## Error Handling

Invalid coordinates, non-finite numeric fields, and out-of-range values are rejected before persistence or service start. Network failures are local to map/search. Missing runtime permission, an unselected mock-location app, provider security failures, and service exceptions are visible in the workbench status. Cleanup is attempted on every stop and destroy path.

## Verification

JVM tests cover coordinate validation, longitude wrapping, movement, distance, and bounded simulation-route parsing. Android lint and CI compilation must cover manifests and service APIs; Android history and provider lifecycle still require device acceptance, including map fallback, point editing, history, joystick movement, mock-app selection guidance, provider updates, pause/resume, stop cleanup, route replay, rotation, and dark mode. The acceptance report must state that this verifies local Android Mock Location only and does not verify any third-party app.

## Licensing

RateMock remains Apache-2.0. The implementation is original RateMock code and uses no GoGoGo source or bundled proprietary/third-party SDK assets. Any future map provider integration must be documented separately with its license and terms.

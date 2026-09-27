# RateMock AMap SDK Integration Design

Date: 2026-09-27

## Scope

Replace the position workbench's self-drawn AMap raster tile canvas with the official AMap Android map SDK, and replace the workbench's Android `LocationManager` subscription with the AMap location SDK. RateMock's existing coordinate editor, route drawing, history, joystick, Android Mock Location service, simulation replay boundary, real-recording boundary, and receiver flows remain in place.

The enabled AMap console services are broader than this product needs. This integration uses only the Android map and Android location SDKs. Navigation, indoor map, indoor location, and Falcon tracking SDKs are not added until RateMock has a corresponding feature and data policy.

## Goals and Non-goals

Goals:

- Use the SDK-owned map camera and gesture handling for click, pan, pinch zoom, double-tap zoom, markers, and polylines.
- Keep all RateMock business and Mock Location coordinates in WGS84.
- Convert WGS84 to GCJ-02 at the map boundary and convert map taps and AMap location results back to WGS84.
- Make both local debug APKs and GitHub Actions APKs load the map with a stable, authorized signing certificate.
- Keep the app usable for coordinate editing and Mock Location when the map, network, key, or location provider is unavailable.

Non-goals:

- No sensor injection, third-party app integration, detection bypass, or unauthorized location behavior.
- No reuse of GoGoGo source, resources, SDK binaries, keys, or signing material.
- No automatic coupling between real recordings and synthetic simulations.
- No navigation, indoor, or cloud trajectory feature implementation in this change.

## Configuration and Signing

`local.properties` supplies the local `AMAP_API_KEY` and local signing settings. The file is already ignored by Git. The AMap key is injected into the Android manifest through a manifest placeholder and is never stored in Kotlin source, resources, or committed build files.

GitHub Actions receives the AMap key and a RateMock-owned fixed debug keystore through repository or environment secrets. The workflow restores the keystore only in the runner workspace, configures Gradle signing for the debug APK, and deletes temporary signing files after artifact creation where practical. The CI debug certificate must match the SHA1 registered in the AMap console. Secrets are not echoed, uploaded, or written into test reports.

A separate release signing certificate remains a release concern. Its SHA1 can be registered with the same AMap key, but release signing credentials are not created or committed as part of this integration.

## Architecture

`MainActivity` initializes the AMap SDK privacy agreement and API key before Compose content is created. Initialization failures are recorded for the position screen rather than preventing unrelated tabs from opening.

`PositionScreen` keeps the existing WGS84 state and service actions. Its map surface is a dedicated `CoordinateMap` adapter backed by Compose `AndroidView` and an AMap `MapView`. The adapter owns:

- `MapView` lifecycle forwarding;
- camera position and zoom synchronization;
- WGS84/GCJ-02 conversion at the SDK boundary;
- selected and live markers;
- route and draft-route polylines;
- map click and location-center actions;
- mutually exclusive normal and drawing modes.

Normal mode delegates pan, pinch zoom, and double-tap zoom to AMap. Drawing mode uses the map touch listener to sample screen locations through AMap's projection API into WGS84 points, without moving the camera. Drawing completion sends the bounded route to the existing `onRouteDrawn` callback.

The old tile cache, raster downloader, custom viewport projection, custom pointer state machine, and grid fallback are removed from the map path after the SDK adapter is working. Coordinate controls and service controls remain independent of map rendering.

## Location and Search

The AMap location client subscribes to high-accuracy updates using the existing foreground permission model. AMap location results marked as mock are ignored where the SDK exposes that information; otherwise provider metadata and Android mock indicators are checked before updating `liveLocation`. The live location remains separate from the selected simulation point. `LocationClient` is stopped and destroyed with the Compose owner lifecycle.

The existing Nominatim search adapter is replaced by an AMap POI/geocoding adapter. Requests are bounded by query length, result count, timeout, and cancellation. Results are converted to WGS84 before calling the existing point application path. A failed search leaves the current point and history unchanged.

## Error Handling and Compatibility

The map screen displays an inline initialization or network error while preserving coordinate entry, route state, history, and Mock Location actions. Missing location permission requests the existing permission flow. A missing or invalid AMap key is actionable and does not crash the process. SDK lifecycle calls are guarded against repeated disposal and configuration changes.

The Android manifest keeps the existing internet and location permissions. Any SDK-specific privacy consent is initialized explicitly and documented in the third-party notices. No location data is sent to RateMock servers; AMap requests go directly to the AMap SDK service under its terms.

## Verification

Offline checks and JVM tests continue to run without downloading Android dependencies. CI additionally runs Android lint and builds the signed debug APK with the configured AMap key and fixed debug certificate. A configuration check confirms that the key is not present in tracked files or logs.

Device acceptance uses the APK produced by the current CI run and checks:

- AMap base map and attribution render;
- click selection updates WGS84 fields without resetting the camera;
- single-finger pan, two-finger pan, continuous pinch zoom, zoom focus anchoring, and double-tap zoom;
- selected marker, live marker, route polyline, and coordinate conversion alignment;
- mutually exclusive normal and drawing modes, route sampling, and field synchronization;
- AMap search results and explicit current-location centering;
- permission denial/recovery, rotation, dark mode, and offline/error behavior;
- Mock Location start, pause, resume, stop, and route replay remain independent of AMap rendering.

Acceptance evidence must use the current commit's CI APK, include the APK SHA-256 and certificate SHA1, and exclude screenshots obscured by system UI or invalid/blank states.

## Licensing and Data Boundaries

RateMock remains Apache-2.0 for its own code. AMap SDK artifacts, services, attribution, privacy requirements, and terms remain third-party obligations and are documented separately. Real recordings remain private and are never sent to AMap search or location services by RateMock. Synthetic simulation histories remain the only source for explicit route replay.

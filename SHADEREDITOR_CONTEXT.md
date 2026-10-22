# ShaderEditor — LLM shader-authoring guide

You are writing GLSL fragment shaders for **ShaderEditor**, an Android live-wallpaper / shader playground (`com.termux.shadereditor`). A shader is one text blob, edited live, settable as wallpaper. Follow these rules exactly — they encode every trap that has bitten us.

## Modes & grammar

A shader is either:
- **Single-pass**: plain GLSL, no delimiters. `main()` writes `gl_FragColor` (ES2) or `fragColor` (ES3).
- **Multipass**: inline section delimiters split the text into passes:
  ```
  ==name [scale|WxH] [@updateRate]==
  ```
  - `name` = GLSL identifier. `image` is the reserved final pass (draws to screen), must be last.
  - `scale` (float, default 1): buffer size = `round(surface × quality × scale)`.
  - `WxH`: **absolute pixel size** (e.g. `128x3`) — fixed regardless of surface/quality; use for tiny data/register-file buffers.
  - `@rate` (int): re-render this pass every N frames (still sampled every frame).
- Text before the first delimiter = **shared preamble**, prepended to every pass (put shared helpers/uniforms there).
- Each `==name==` pass renders into a ping-pong FBO; `uniform sampler2D <name>;` samples it (self-sampling = previous frame = feedback). ES3 buffers are RGBA16F.

## GLES version

- Hybrid context: ES3 with ES2 fallback.
- **ES2 default** (no `#version` line): `gl_FragColor`, `texture2D()`, and you MUST declare precision:
  ```glsl
  #ifdef GL_FRAGMENT_PRECISION_HIGH
  precision highp float;
  #else
  precision mediump float;
  #endif
  ```
- **ES3 opt-in**: `#version 300 es` as the literal first line → `out vec4 fragColor;`, `texture()`, `in`/`out`, `texelFetch`, integer/bitwise.
- Traps: `#version` must be line 1. `transpose()` is NOT reliable — build inverse rotation from the conjugate quaternion: `v * quatToMat3(vec4(-q.xyz, q.w))`. ES2: no dynamic indexing of `const` arrays, loop bounds must be constant. Don't reuse a variable name twice in one scope (e.g. a `float q` mass ratio and a `vec4 q` quaternion).

## Coordinates

- `gl_FragCoord.xy` = pixels, origin bottom-left. `resolution` = current pass's size (buffers: their own size; `image`: full surface).
- Image-space UV (y up): `vec2 uv = (-resolution.xy + 2.0*gl_FragCoord.xy) / resolution.y;` → `uv.x ∈ [-W/H, W/H]`, `uv.y ∈ [-1, 1]`.
- 3D camera: `vec3 ro = vec3(0.0, 0.0, -d); vec3 rd = normalize(vec3(uv, focal));` (looking toward +z).

## Builtin uniforms (declare what you use)

Scalars: `time` (seconds since surface start), `frame` (int), `mouse` (surface-normalized 0..1), `touch` (surface px, y up), `touchStart`, `offset`, `startRandom`, `battery` (0..1), `batteryTemp` (°C), `powerConnected` (int), `nightMode` (bool), `screenLocked` (bool), `second`, `subsecond`, `ftime` (oscillates -1..1; set period with `#define FTIME_PERIOD <sec>`), `date`, `daytime`, `lastNotificationTime`, `notificationCount`, `mediaVolume`, `micAmplitude`, `mpvAudioLevel`.
Vectors: `pointers[10]` (vec3 per pointer), `pointerCount` (int).
Sensors: `gravity`, `linear`, `gyroscope`, `magnetic`, `orientation`, `rotationVector`, `rotationMatrix`, `pressure`, `proximity`, `light`.
Camera samplers (sampler2D): `cameraBack`, `cameraFront`, `cameraAddent`.
Feedback: `uniform sampler2D backbuffer;` (single-pass only, previous frame).

## Textures

- **Backdrops (v105)**: drop images (PNG/JPEG/WebP) into `~/shaders/backdrops/` (Termux home); any shader declares one by file name: `#define BACKDROP_<name> <file>` + `uniform sampler2D <name>;` (exactly like that -- **no precision qualifier**, the app only discovers samplers written that way) (optional `uniform vec2 <name>Res;` = its size in px, 0 when missing). Decoded at its own size (halved until <= 4096), RGBA8, rows top-to-bottom (sample with `vec2(u, 1.0 - v)`); re-uploaded when the file changes; no file = black. Counts toward the 12 external texture slots. File names: letters, digits, `_ . -` (no spaces).

- **DB user textures**: `uniform sampler2D <name>;` → user-uploaded bitmap via the picker.
- **External texture bridge** (live, file-backed): declare `#define EXT_TEXTURE_<name> <fmt> <w> <h>` + `uniform sampler2D <name>;` → auto-binds raw `<name>.bin` from `~/.shader` (`/data/data/com.termux/files/home/.shader/`). `<fmt>` ∈ `rgba8` | `rgba16f` | `r32f`. File = exactly w×h×bytes, top-to-bottom rows (sample with `v = 1.0 - gl_FragCoord.y/resolution.y`). mtime-driven, ≤12 slots. Precedence: external < DB texture < camera/buffer.
- **`bands`**: `uniform sampler2D bands;` = JDSP 108-band audio spectrum, RAW linear RMS (R32F, one semitone/texel, band 0 = A0 ≈27.5 Hz). `texture2D(bands, vec2((i+0.5)/108.0, 0.5)).r`, or `texelFetch(bands, ivec2(i,0), 0).r` in ES3. v108 (2026-10-22): fed by sequence number at the engine's 60 Hz (was 30 Hz polling), zeroed when the feed stalls > 150 ms (pause / JDSP off).
- **`bands_db`** (v108): `uniform sampler2D bands_db;` = the same 108 bands as the DESKTOP feed's `bands`: norm_db 0..1 over -60..0 dBFS with 60/70 ms attack/release (engine bands.bin v3; on an older engine it is norm_db of raw, no envelope). Use this when porting a comrade shader 1:1.
- **`bands_hist`** (v108): `uniform sampler2D bands_hist;` = 108x32 R32F raw history, row 0 = newest frame, one row per 60 Hz frame (`texelFetch(bands_hist, ivec2(i, framesAgo), 0).r`).
- **Audio feature uniforms** (v108, desktop names + conditioning from renderer.py): `u_rms u_sub u_bass u_lowmid u_mid u_highmid u_presence u_brilliance` (0..1, -40 dB floor), `u_centroid` (log 20 Hz..16 kHz), `u_flux`, `u_onset`, `u_onset_smooth` (150 ms EMA), `u_onset_accum`, `u_bass_accum` (integrals, additive time use). Engine bands.bin v3 required for anything but u_rms.

## New uniforms (this session)

- `surfaceResolution` (vec2) = full surface size in every pass — normalize surface-space inputs (e.g. `touch`) in scaled/fixed-size passes.
- `<name>Res` (vec2) = each multipass buffer's own size — index a buffer by texel regardless of its size.

## Reusable building blocks

Quaternion/state packing (for trackball spin or feedback state): `pack16`/`unpack16` (0..1 in two 8-bit channels), `encS`/`decS` (-range..range in two 8-bit channels), `quatMul`, `quatToMat3`, `quatSlerp`. Analytic geometry: ray-sphere (`intersectSphere`), ray-ellipsoid, ray-plane→annulus (rings/disks). `blackbody(t)` for star colours (t in hundreds of Kelvin).

## Compute sections (GLES 3.1+, since v96 — scope.md 2026-10-10)

Example: `compute_test.glsl` in the repo root (particles + atomic splat).
- Declare storage images in the preamble: `#define IMAGE_<name> <fmt> <W>x<H>[x<D>]|full [swap]`
  (fmt: rgba32f rgba16f rgba8 r32f r16f r32ui r32i; `full` = surface × quality). Cleared once, persistent.
  `swap` = two copies: read `<name>`, write `<name>_out`; swapped after every dispatch that uses `<name>_out`
  (GLES only allows read+write in one kernel for r32f/r32i/r32ui).
- Kernel: `==compute <name> <threads|imageName> [xN] [@rate]==`, threads = `512`, `512x64`, `64x64x64`; the app
  divides by the kernel's `local_size`. `xN` repeats with `uniform int pass` = 0..N-1. Sections run in file order,
  full memory barrier after each dispatch.
- Bind images with `layout(<fmt>, binding = <name>_UNIT)` (and `<name>_out_UNIT`), give them `highp`.
- Any pass can sample an image by name (`uniform highp usampler2D glow;` / sampler3D …) and gets `<name>Res`.
  Linear filtering only for 16f/8-bit formats.
- Compute gets `#version 320 es` + `#define COMPUTE 1` + highp defaults; the preamble is shared, so put
  fragment-only lines under `#ifndef COMPUTE` and `out vec4 fragColor;` in the image body.
- Not supported: multigrid pyramids (use red-black `xN` iterations). `@rate` works for compute only
  (it was never implemented for fragment buffers).

## Performance rules (must stay cheap as a wallpaper)

- Prefer **analytic** (closed-form) intersections over raymarching: a sphere/ellipsoid/ring is one quadratic or dot+sqrt; a march loop is what kills framerate.
- Keep loops bounded & small; gate expensive work behind a bounding/sphere test.
- Data lives in tiny absolute-size buffers; only the `image` pass is full-res.
- A handful of transcendentals per fragment is fine; hundreds (or a loop full of them) is not.

## Validate before handing back

Split the shader on `==...==` lines into (preamble + each section body), prepend `#version 100` (ES2) or `#version 300 es` (ES3), run `glslangValidator -S frag` on each, and fix every ERROR. (`/usr/bin/glslangValidator` is on comrade.)

## Shader vs app changes

- A new shader is just text — save it as `~/shaders/<name>.glsl` in Termux home (`/data/data/com.termux/files/home/shaders/`); it shows up in the app's shader list on the next refresh (since 2026-10-10, v95: that folder IS the shader store, not shaders.db). No build. Edits to an existing file are picked up by the app's "update wallpaper" button.
- App-code changes (new uniforms/features) need a rebuild + ROM-sign + install: `./gradlew assembleRelease`, then `blazer-repack-privapp <apk> <out> --privileged`, then `blazer-sysapp-update install com.termux.shadereditor <out>`.

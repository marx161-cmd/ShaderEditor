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

- **DB user textures**: `uniform sampler2D <name>;` → user-uploaded bitmap via the picker.
- **External texture bridge** (live, file-backed): declare `#define EXT_TEXTURE_<name> <fmt> <w> <h>` + `uniform sampler2D <name>;` → auto-binds raw `<name>.bin` from `~/.shader` (`/data/data/com.termux/files/home/.shader/`). `<fmt>` ∈ `rgba8` | `rgba16f` | `r32f`. File = exactly w×h×bytes, top-to-bottom rows (sample with `v = 1.0 - gl_FragCoord.y/resolution.y`). mtime-driven, ≤12 slots. Precedence: external < DB texture < camera/buffer.
- **`bands`**: `uniform sampler2D bands;` = JDSP 108-band audio spectrum (R32F, one semitone/texel, band 0 = A0 ≈27.5 Hz). `texture2D(bands, vec2((i+0.5)/108.0, 0.5)).r`, or `texelFetch(bands, ivec2(i,0), 0).r` in ES3.

## New uniforms (this session)

- `surfaceResolution` (vec2) = full surface size in every pass — normalize surface-space inputs (e.g. `touch`) in scaled/fixed-size passes.
- `<name>Res` (vec2) = each multipass buffer's own size — index a buffer by texel regardless of its size.

## Reusable building blocks

Quaternion/state packing (for trackball spin or feedback state): `pack16`/`unpack16` (0..1 in two 8-bit channels), `encS`/`decS` (-range..range in two 8-bit channels), `quatMul`, `quatToMat3`, `quatSlerp`. Analytic geometry: ray-sphere (`intersectSphere`), ray-ellipsoid, ray-plane→annulus (rings/disks). `blackbody(t)` for star colours (t in hundreds of Kelvin).

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

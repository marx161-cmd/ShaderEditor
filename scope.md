# Scope

Append-only. Never overwrite or delete an entry below — if a decision
changes, add a new dated entry explaining what changed and why; the old
entry stays as the record of what was decided before.

## 2026-09-04 — Multi-buffer (multi-pass) support in ShaderEditor

### What it is

Let a single shader document define multiple render passes that render into
offscreen textures (FBOs), which later passes can sample. The existing app
compiles one fragment shader and draws it to the screen (via an offscreen
ping-pong + a fixed compositing pass — see below). Multi-buffer generalises
exactly that mechanism: N named offscreen buffers, each its own program, plus
a final `image` pass that draws to the screen.

### Source format (one text field, no new UI)

Sections are delimited inline inside the existing single shader string / DB
column. Delimiter line grammar (line-anchored, full-line match):

    ==name [scale] [@updateRate]==

- `name`: GLSL identifier `[A-Za-z_][A-Za-z0-9_]*`. `image` is the reserved
  final pass that renders to the default framebuffer.
- `scale`: optional positive float, default `1.0`. Buffer renders at
  `round(surface * scale)` resolution.
- `@updateRate`: optional `@` + positive int, default `1`. Buffer re-renders
  every N frames; it is still sampled every frame from its last-rendered
  texture.

Examples: `==buffera==`, `==buffera 0.25==`, `==buffera 0.25@3==`,
`==image==`.

Rules:
- Everything before the first delimiter line is a **shared preamble**,
  prepended to every section's compiled source. Helper functions/uniforms
  live there instead of being duplicated per pass.
- No delimiter anywhere in the document => single pass, current behaviour.
  **Backwards compatibility with every existing saved shader is mandatory and
  is preserved bit-for-bit**: the single-pass path keeps the existing fixed
  `surfaceProgram` compositing flow, untouched.
- Section order = execution order. `image` must be last.
- A section named `buffera` makes `uniform sampler2D buffera;` bindable in any
  pass (the user still declares it; the renderer binds the texture). A buffer
  sampling itself reads its own previous frame (ping-pong feedback).
- The legacy `backbuffer` sampler name is single-pass only; in multipass mode
  it is not special-cased.

### Architecture decisions (grounded in the actual code)

- **Reuse, not rewrite.** `ShaderRenderPipeline` already implements the exact
  shape: a ping-pong FBO pair (`framebuffers[2]` + `targetTextures[2]` +
  `frontTarget`/`backTarget` + `swapTargets()`), the user shader rendered into
  the front FBO, and a separate fixed `surfaceProgram` compositing `frame` to
  the screen. Multi-buffer = N named ping-pong pairs + N user programs + an
  optional user-authored `image` program replacing the fixed surface pass.
- **Error line numbers.** `ShaderLineMapping` already maps prepared-line →
  source-line for the injected `#define SHADER_EDITOR` / OES directives. For
  multipass, each section's compiled string is `preamble + body`, so a GLSL
  error line needs a *piecewise* offset: preamble lines map 1:1, body lines
  map `delimiterLine + (compiledLine - preambleLines)`. This is added to
  `ShaderLineMapping` as an optional `(preambleLines, lineOffset)` transform
  applied *after* the existing insertion removal. No `#line` directive — the
  codebase deliberately does manual mapping because `#line` conformance is
  driver-fragile.
- **Per-program uniform presence, union value sources.** `BuiltinUniforms` and
  its three sub-systems currently compute `hasX = device.hasUniform(program,X)`
  against a single program. For multipass they accept the full program list
  and register sensor/system/camera listeners on the *union* (any pass uses
  it); per-pass binding sets whatever the pass declares, and undeclared
  uniforms are a harmless no-op at apply time (`GlDevice.applyBindings` skips
  `location < 0`). `BuiltinSystemUniforms.bridgeUniformPresence` (per-name
  presence map) is the existing pattern being generalised.
- **Buffer samplers must not become user textures.** `ShaderSourcePreparer`
  discovers samplers with a regex and feeds `ShaderTextureResources` (user
  texture files). `uniform sampler2D buffera;` would otherwise be treated as a
  DB texture. Sampler discovery in multipass excludes any name that is a
  declared buffer section, and the union of the rest (deduped by name) becomes
  the user-texture set shared across passes.
- **Resolution / viewport are per-pass.** Each pass gets its own `resolution`
  uniform (its own scaled size) and viewport; the image pass gets the full
  surface. The pipeline overrides the per-pass `resolution` right before
  `applyBindings`, mirroring how `drawSurface` already sets a draw-size
  `resolution` for the fixed surface pass.
- **Input coordinate space (multipass only).** `mouse` is already
  surface-normalised (the `quality` factor cancels in `updateTouch`), so it is
  correct for every pass as-is. `touch` and `pointers` are computed in
  quality-scaled pixel space, which only matches a pass whose own scale equals
  `quality`. Decision: in multipass mode, `touch`/`pointers` are reported in
  **surface** pixel coordinates, and each buffer pass scales by its own
  resolution (documented convention, same spirit as Shadertoy
  `fragCoord / iResolution`). Single-pass behaviour is unchanged.
- **Texture format.** Existing `GlDevice.allocateTexture2D` hardcodes RGBA8.
  Buffers allocate RGBA16F when supported so HDR/glow accumulation survives,
  else fall back to RGBA8. RGBA16F is version-branched: ES3 context uses
  `GL_RGBA16F` + `GL_HALF_FLOAT` (renderability via `GL_EXT_color_buffer_float`
  on ES3.0/3.1, core in ES3.2); ES2 uses `GL_RGBA` + `GL_HALF_FLOAT_OES` with
  `GL_EXT_color_buffer_half_float` required. Buffer textures set
  `CLAMP_TO_EDGE` + `LINEAR` explicitly.
- **Clearing / lifecycle.** Clear each buffer once on allocation; never clear
  feedback buffers per frame. GL context loss on surface destroy resets
  feedback accumulation — accepted, not treated as a bug.
- **Cost model.** No assumption that more passes = slower; per-buffer scale is
  the headline feature (a cheap low-res pass can beat a heavy full-res pass).
  `MAX_TEXTURES = 32` is the binding-unit budget (2 units per sampled
  ping-pong buffer); the parser caps the number of buffers accordingly.

### Corrections found during code review (all incorporated above)

1. Buffer samplers leaking into user-texture discovery (regex scanner).
2. Per-program uniform presence (was single-program flags).
3. RGBA16F must branch on GLES context version, not one boolean.
4. `touch`/`pointers` coordinate space wrong for independently-scaled buffers.
5. `backbuffer` legacy-name coexistence rule (single-pass only).
6. 32 texture-unit budget cap.

### Phasing (implementation order)

Single-pass backward compat (parser returns one section, behaviour identical)
→ two passes no feedback → ping-pong self-sampling → error line offsets →
resolution scale + update-rate modifier. Error offsets were moved ahead of
resolution scale because debugging scale/coordinate feedback bugs without
correct line numbers is miserable.

### Known limitations (first cut)

- Multipass thumbnails render the `image` pass into the thumbnail FBO; not
  pixel-perfect for passes that depend on high feedback resolution, acceptable
  for a 144px list preview.
- `touch`/`pointers` in multipass are surface-space (see coordinate decision);
  existing single-pass shaders are unaffected.

## 2026-09-04 (later) — quality spinner applies to multipass buffers

**Correction to the initial implementation.** The first multipass cut sized
buffers at `surface × scale` and ignored the app's per-shader quality
multiplier (the half/quarter-resolution spinner). That was wrong: the quality
knob is how the user already runs expensive shaders at reduced resolution, and
it must keep working.

**Decision**: quality scales the buffer passes only, exactly mirroring how
single-pass works — in single-pass the quality multiplier scales the expensive
main pass while the fixed surface composite stays full-res; in multipass it
scales each buffer (`surface × quality × per-buffer scale`) while the `image`
pass (the final composite) always renders at full surface. So:

- Existing single-pass shaders: unchanged, quality works as before.
- Multipass: `buffer resolution = round(surface × quality × scale)`, image at
  full surface. Quality and per-buffer `scale` stack multiplicatively.

Applied at buffer-allocation time (`ensureMultipassTargets`), consistent with
how single-pass bakes quality in at surface setup; a live spinner change
without a surface recreation does not resize existing buffers (same limitation
as single-pass).

## 2026-09-09 — swipe-fling spin physics for wallpaper shaders (ported from AuraOrbit), not yet started

**Motivation**: `ferrofluid.mp` (a multipass shader on comrade,
`~/builds/amd/shader-backdrop/shaders/ferrofluid.mp/`, a floating
audio-reactive raymarched spiky sphere) currently just auto-spins at a
constant rate (`SPIN_SPEED`/`SPIN_SPEED_2` in `image.glsl`, driven by
`iTime`). Idea: give it AuraOrbit's swipe-and-let-go "slick" spin feel
(momentum that decelerates naturally) if/when it's deployed as a
ShaderEditor wallpaper. Session arrived here after several wrong turns
worth recording so they aren't re-tried:

**Rejected: cross-app live bridge to AuraOrbit.** First idea was
renaming AuraOrbit to `com.termux.orbit` (matching the existing
`sharedUserId` → `android.uid.system` migration ShaderEditor and
`com.termux.mpv` already did) and exposing its live `sphereRotation`
continuously, the same file-bridge shape ShaderEditor already uses for
`mpvAudioLevel` (`ShaderEditor` commit `1869f4d`). Rejected in favor of
a self-contained port — no dependency on AuraOrbit being installed/
running, no package rename, no cross-app reliability/latency concerns.

**Rejected: pulling in libGDX for the physics.** AuraOrbit's actual
rotation state (`SphereEngine.java`) is a libGDX `Quaternion` +
`Vector3` angular velocity, because it needs full gimbal-lock-free 3D
orientation (icons must stay coherent under drag around any arbitrary
axis). Confirmed unnecessary here: `ferrofluid.mp`'s rotation is
already just two independent fixed-axis scalar rotations (`rotateY`
then `rotateX` in `mapSDF`), not a free 3D orientation. The friction/
momentum FEEL doesn't require quaternions or a 3D engine dependency —
just two scalar angular velocities, each decaying via the same curve.

**Decided: port the physics constants/formula only, not the engine.**
From `~/builds/android/AuraOrbit/app/src/main/java/dev/jaimin/auraorbit/SphereEngine.java`:
- `FRICTION = 0.97` (line 319) — per-frame exponential decay:
  `frictionThisFrame = FRICTION^(deltaTime*120)` (line 2861), applied
  to angular velocity every frame (`angularVelocity.scl(...)`, 2862).
- `VELOCITY_EPSILON = 0.001` (line 325) — snap angular velocity to
  zero below this magnitude (2865) so decay doesn't tail forever.
- `FLING_SENSITIVITY = 0.002` (line 338) — fling velocity (px/sec) →
  angular velocity (rad/sec) conversion.
- `ROTATION_SENSITIVITY = 0.005` (line 331) — drag distance (px) →
  radians, for live drag-to-rotate before release.
- Reduce to two independent scalar angular velocities (one per axis),
  not a quaternion, matching `SPIN_SPEED`/`SPIN_SPEED_2`'s existing
  two-axis model.

**Hook point, confirmed by reading ShaderEditor's source (not
guessed)**: `ShaderWallpaperService.onTouchEvent()`
(`app/src/main/java/com/termux/shadereditor/service/ShaderWallpaperService.java:106-111`)
already forwards raw `MotionEvent`s to `ShaderRenderer.touchAt(e)`
(`opengl/ShaderRenderer.java:299`) every touch — the plumbing to
receive swipes already exists. Today it only updates a static
normalized `mouse` uniform (`opengl/BuiltinUniforms.java:36,135-136,229`)
with no velocity/momentum tracking at all — that's the actual gap to
fill, not new touch-event wiring.

**Not yet decided / not started**:
- New state to add at the `touchAt()` hook: previous position +
  timestamp per pointer → drag delta → sensitivity-scaled rotation
  while touching; velocity at release → fling angular velocity; a
  per-frame update applying the friction curve when not actively
  touched (mirroring `SphereEngine.java`'s pan/fling/idle state
  machine, ~lines 2135-2384 and 2756-2865, but with two scalars
  instead of a quaternion).
- New uniform name(s) to expose the resulting angle(s) to
  `image.glsl`, replacing its current `-iTime*SPIN_SPEED` formula —
  not named yet.
- Whether to keep AuraOrbit's separate friction-free "idle auto-spin"
  behavior (engages after fling settles) or drop it — AuraOrbit has
  this specifically to avoid a visible pulse/stop/pulse artifact
  between fling-decay and idle spin (documented in its own comments,
  `SphereEngine.java` ~line 96-99, 2772-2774) — worth reading before
  reinventing that transition.
- This whole feature presupposes `ferrofluid.mp` is actually running
  as a ShaderEditor wallpaper on the Pixel at acceptable performance
  first (multipass support exists per ShaderEditor commit `a1c4f6b`,
  but nobody has actually deployed/profiled `ferrofluid.mp` there yet)
  — that's a real prerequisite, not assumed done.

## 2026-09-14 — arbitrary named external textures (shader-declared, `~/.shader`)

### What it is

Generalise the one-off JDSP `bands` texture into a generic bridge that lets
any shader declare up to **12** arbitrary `sampler2D <name>` textures whose
pixel data lives in raw files in Termux home's `~/.shader`
(`/data/data/com.termux/files/home/.shader`, shared-UID writable).

### Declaration (in the shader source, shared preamble or anywhere)

    #define EXT_TEXTURE_<name> <format> <width> <height>

e.g. `#define EXT_TEXTURE_state rgba16f 256 256`. This is a legal, unused
object-like GLSL macro, so it survives compilation unchanged and has no effect
on line mapping. The data file is `<name>.bin` in `~/.shader`; the shader
supplies format + dimensions, the file supplies raw pixels.

### Formats

- `r32f` → `GL_R32F`/`GL_RED`/`GL_FLOAT` (ES3)
- `rgba8` → `GL_RGBA`/`GL_UNSIGNED_BYTE` (ES2 ok)
- `rgba16f` → `GL_RGBA16F`/`GL_RGBA`/`GL_HALF_FLOAT` (ES3)

### Decisions (grounded in the code)

- **Auto-discover by filename, but declare format/dims in-shader** — chosen
  over a sidecar/header/manifest so the shader stays the single source of
  truth and the raw file stays a dumb pixel blob. Parser is
  `ShaderSourcePreparer.parseExternalTextures` (`PATTERN_EXT_TEXTURE`), same
  shape as `PATTERN_FTIME` / `getGLES3Version`.
- **Precedence (lowest → highest):** external bridge → DB user texture →
  camera/buffer builtins. The bridge applies in `BuiltinUniforms.beginFrame`
  before `textureResources.applyTo`, so an explicitly-uploaded DB texture of
  the same name wins.
- **A declared texture is only bound if the shader also declares the matching
  `uniform sampler2D <name>;`** (checked against the discovered-sampler
  union), and float formats are skipped on the ES2 fallback (`requiresEs3`).
- **mtime-driven upload, not periodic:** each slot stats its file every frame
  and re-reads only on mtime change; short/torn reads keep the previous
  texture content. NEAREST + CLAMP_TO_EDGE filtering (data-texture semantics,
  matching the existing `bands` path).
- **JDSP `bands` is left untouched** — it remains a hardcoded one-off in
  `BuiltinSystemUniforms`, not folded into this bridge.

### Files

- `ExternalTextureSpec.java` (new): record + `Format` enum (`requiresEs3`,
  `bytesPerPixel`).
- `ExternalTextureBridge.java` (new): watch dir + 12 slots + mtime upload.
- `GlDevice.java`: `uploadRgba8Texture2D` / `uploadRgba16fTexture2D`
  (reuses `uploadFloatTexture2D` for R32F).
- `ShaderSourcePreparer.java` / `PreparedShaderSource.java`: parse + carry
  specs.
- `ShaderTextureResources.java`: `samplerNames()`.
- `RendererProgramManager.java`: `getExternalTextures()`.
- `BuiltinUniforms.java` / `ShaderRenderer.java`: wire configure/apply/release.

## 2026-09-14 (later) — absolute-size buffers + per-buffer/surface resolution uniforms

### Why

`SE.glsl`/`storm_ball_SE.glsl` use a multipass buffer (`state`) as a
texel-indexed *register file*: row 0 = physics, row 1 = 108 audio bands, row 2
= 9 storm targets, each column a scalar. Two things blocked shrinking it:

1. The `image` pass indexed `state` with its own `resolution` (surface),
   hardcoding `state.width == surface.width`.
2. The `state` pass's touch→trackball math normalized `touch` (surface pixels)
   by its own `resolution`, which only matched while the buffer was full-res.

Relative `scale` couldn't be the answer either: buffer size is
`surface × quality × scale`, and the quality spinner goes down to 1/32, so no
relative factor guarantees a stable ≥108-wide buffer.

### Changes

- **Absolute-size delimiter**: `==name WxH==` (e.g. `==state 128x3==`) renders
  the buffer at a fixed pixel size, ignoring surface and quality. Threaded
  through `ShaderSectionParser` → `ShaderSourcePreparer` →
  `PreparedShaderSource.PreparedSection` → `RendererProgramManager.PassProgram`
  → `ShaderRenderPipeline.ensureMultipassTargets`. Backwards compatible — the
  old `==name [scale] [@rate]==` grammar is unchanged.
- **`surfaceResolution` builtin** (`vec2`, full surface size) bound in
  `BuiltinUniforms.bindFrameUniforms`, so a scaled/fixed-size pass can normalize
  surface-space inputs (`touch`) correctly.
- **`<name>Res` per-buffer uniform** (`vec2`, each buffer's own size) bound in
  `ShaderRenderPipeline.bindBufferResolutions` for every pass, so any pass can
  index a buffer by texel regardless of its size.

## 2026-10-09 — beat clock in ShaderEditor (shared, for every phone shader) (user)

Need: the Pixel port of the swarm shader (shader-backdrop scope.md 2026-10-09, fairy swarm / "pockets") wants bar-
locked behaviour; the phone had no beat clock. User OK'd putting it at the SHARED layer, not in one shader:
- Port the desktop's proven tracker (`~/builds/amd/shader-backdrop/beat_track.py`: autocorrelation over ~8 s of onset
  history + harmonic comb + log-BPM prior, 50-210 BPM, phase lock) to Java in `BuiltinSystemUniforms`, fed by the
  JDSP `bands.bin` the renderer already reads every frame.
- New builtins: `beatCount` (CONTINUOUS, flywheels through silence -- the desktop's u_beat_count invariants),
  `beatPhase`, `beatConf`, `beatBpm`, plus a kick envelope. tools/se_to_mp.py maps them to the desktop's uniforms.
- Known limits vs the desktop: no look-ahead (the desktop uses the ~0.9 s speaker delay), so kicks register just after
  the hit; the locked clock still predicts beats on time; ~4-8 s to lock after a track starts (gate on beatConf).
- Order: (1) measure how often JDSP rewrites bands.bin (tempo tracking needs a steady >= 30 Hz); (2) port + verify on
  the phone against the desktop clock on the same track; (3) then the swarm prototype on the desktop.

2026-10-09 (later) — step 1 measured, BLOCKER found: bands.bin content changes at ~12 Hz (300 md5 reads over 10.0 s
at 30 reads/s, 123 changes, music playing). JDSP source (jdspController.c JamesDSPProcess, ~l.513-546): the 108 band
biquads only run on the ONE audio block of each "30 Hz" Lua tick (luaTickInterval = fs / n / 30 blocks; the tick
fires every interval+1 blocks), so each bands.bin value is the RMS of a single ~10-20 ms block with filter state
that skipped all samples in between -- a noisy snapshot, not a continuous energy. Beat tracking on that is possible
but degraded (onset timing +-40 ms, low bands barely settle within one block). Options put to the user: track on the
12 Hz feed anyway / fix the meter at the source in JDSP (filters on every sample, energy accumulated between ticks,
higher publish rate -- a native change that ships via the vendor .so / OTA chain) / something else.

## 2026-10-10 — no desktop compatibility; phone compute stage is phone-native (user)

Checked live: the Pixel GPU is OpenGL ES 3.2 (PowerVR DXT-48-1536, ro.opengles.version 196610), and this fork already
gets an ES3 context (widget/ShaderView.java ContextFactory), so GLES 3.1 compute is available. The phone shaders were
designed fragment-only because we assumed it wasn't.
Decided (user): drop desktop<->phone format compatibility as a goal. tools/se_to_mp.py (shader-backdrop) stays in the
repo but is no longer maintained and no longer limits the design; the few shaders a year that matter get ported by
hand. So a future compute stage here gets its own GLES-native syntax and does NOT need to mirror the desktop's .mp /
sim.comp format. Known requirement when it's built: images written by compute need immutable storage
(glTexStorage2D). opengl/GlDevice.java allocates everything with glTexImage2D today, which glBindImageTexture
rejects. Compute stage design not started -- agree it before building.

## 2026-10-10 — shaders live as .glsl files in Termux home, not in shaders.db (user)

Problem (user): shaders sit inside shaders.db (8.6 MB, ~200 rows, most recent saves unnamed), so they can't be
edited easily from Termux/SSH.
Decided (user):
- Source of truth = `/data/data/com.termux/files/home/shaders/<name>.glsl` (Termux `~/shaders/`), one file per
  shader, filename = shader name. Same UID 1000 + system_app_data_file label as the existing `~/.shader/` texture
  bridge, so no permission work.
- NO live file watching -- the existing "update wallpaper" button re-reads the file.
- Migrate ALL rows once, unnamed ones included (`shader_<id>.glsl`), file mtime = the row's modified time.
Design (mine, flagged):
- Only the storage under `database/dao/ShaderDao.java` changes; its public API keeps numeric ids, so the ~20 callers
  (editor, list, wallpaper service, prefs) stay as they are. An id -> filename index plus per-shader quality lives in
  the app's own files dir (not in ~/shaders). Thumbnails are cached there too, keyed by id. Migration keeps the DB
  ids, so the wallpaper/last-opened prefs survive.
- New .glsl files dropped into ~/shaders show up on the next list refresh. Renaming in the app renames the file (id
  kept). Renaming in Termux = new id (the wallpaper pref then falls back like a deleted shader would).
- Delete in the app moves the file to `~/shaders/.trash/`; it's never hard-deleted.
- The shaders table in shaders.db is left in place, read only by the one-time migration. Textures stay in the DB.
- The unused `project/FolderShaderProjectSource` multi-file scaffolding (pre-07-25 checkpoint) is NOT used for
  this; one flat file per shader.

## 2026-10-10 — GLES compute stage (phone-native) (user OK'd the design)

Goal: real GPU simulation on the phone (swarm boids first; later tonnetz-needle-style physics and small fluid sims).
Decided (user OK'd my design):
- Images are declared in the preamble like EXT_TEXTURE_: `#define IMAGE_<name> <fmt> <W>x<H>[x<D>] [swap]`
  (also `full` = surface x quality). fmt: rgba32f rgba16f r32f r32ui (+ r16f rgba8 if easy). Allocated with
  glTexStorage (immutable, required for image load/store), cleared once on allocation, persistent otherwise.
  `swap` = two copies: kernels read `<name>`, write `<name>_out`; after a section that writes `<name>_out` the pair
  swaps. Needed because GLES only allows read+write in one kernel for r32f/r32i/r32ui images.
- Kernel sections: `==compute <name> <size> [xN] [@rate]==`. size = threads: `512`, `512x64`, `64x64x64`, or the
  name of a declared image (dispatch over its size). The app divides by the shader's own local_size (rounding up).
  xN = repeat N times per frame with `uniform int pass` = 0..N-1 (substeps, red-black parity).
- Sections run in FILE ORDER (compute dispatches, fragment buffers draw, ==image== last), with a full
  glMemoryBarrier after every compute dispatch.
- Each image gets a GPU unit; the app defines `<name>_UNIT` (and `<name>_out_UNIT`) macros for
  `layout(..., binding = <name>_UNIT)`. Every pass (compute or fragment) can also read any image as a texture by
  name (sampler2D/3D/usampler2D); linear filtering only for 16f/8-bit formats (no OES_texture_float_linear here).
- Compute sections get `#version 320 es` (image atomics) instead of the file's version line, plus `#define COMPUTE 1`;
  the preamble is shared, so fragment-only lines go under `#ifndef COMPUTE`. All existing uniforms (time, bands,
  touch...) are bound in compute too.
- NOT now: multigrid pyramids. A phone fluid port uses red-black iterations (xN) on a small grid instead; add
  pyramids only if that looks soft on screen.
- Beat clock: SKIPPED for now (user). JDSP meter fix comes after this patch, tested as a sideloaded overlay like
  other test builds.
Shaders without compute sections must behave exactly as before.

## 2026-10-10 — phone beat clock: desktop beat-map cache first, live tracker as fallback (user)

Input fixed first: JDSP's meter now runs continuously and publishes 60 Hz frames into a v2 bands.bin (latest 108
floats + header + 32-frame ring; JamesDSP-src scripts/bands_bin_check.py). Live overlay test 2026-10-10: 59.99 Hz.
Findings (offline replay of two recorded phone sessions, desktop beat_track.py unchanged):
- The live tracker on the phone's full-mix bands is NOT solid: its confidence formula (best/mean score) breaks when
  the mean score sits near zero (dense full-mix ODF), and single 8 s windows wander. Desktop HPSS (stem_split.py)
  did NOT help on phone input. What helped: confidence from the score spread + 8 s autocorrelation averaging
  (TODESFLOETE 100% on its tempo; GARGAMEL 66%, a 3:4 ambiguity).
- The desktop cache (~/.local/share/shader-backdrop/tracks.db, 807 tracks, all with beat maps) has both test songs
  and shows the live tracker was on a 3:2 / 3:4 sub-grid even when "solid" (TODESFLOETE 119.6, GARGAMEL 100.2).
Decided (user): use the desktop cache on the phone.
- comrade exports the beat maps to the phone (a sync script, like docs-sync-pixel). Keys as on the desktop:
  `meta:artist|title|duration_us` (YouTube Music -- the user's usual source; the phone's YT Music app and Firefox YT
  Music play the same streams, so song positions line up), `file:` (VLC music videos, same files as comrade --
  match by filename), `yt:` if the phone ever exposes a video id.
- ShaderEditor reads the active media session (its existing NotificationListener grants MediaSessionManager access):
  title/artist/duration + playback position. Matching and the duration tolerance are PORTED from beat_session.py, not
  re-invented; a matched map feeds the same PLL as the desktop's set_reference (continuous count, never jumps).
- No match -> the live tracker (beat_track.py ported to Java with the two fixes above, phone only; the desktop file
  stays unchanged because its drum-stem input doesn't hit these problems).
- Uniforms: beatCount, beatPhase, beatConf, beatBpm, + a source flag (0 none / 1 live / 2 map) for debugging.
  A debug shader shows the flash so the user can judge phase on screen.
Known open items: (1) com.termux.vlc reports position=0 in its media session -> needs a fix in the VLC fork before
file: matches can give phase; (2) output latency (speaker vs Bluetooth) between player position and what is heard
-> a tunable offset, judged on screen; (3) a map from a different upload of the same song could be offset -> check
map phase against live onsets before trusting it (later, if it shows).

## 2026-10-10 — tap tempo in the beat clock (user)

Use case (user): music from another device (mic-driven shaders at home) has no audio the phone can track.
Decided (user): tap the screen to the beat; after two bars (8 taps) the clock takes that tempo and holds it until a
known song is recognised or a "clear reset" -- the audio stopping, i.e. the short silence between songs.
Built (v101, user-confirmed "turns blue and takes my tempo"): 8 taps whose intervals all sit within 10% of their
median -> least-squares grid (tempo + phase); up to 16 taps refine it; a >2.5 s gap starts a new count. Priority:
cached map > taps > live tracker. Reset: song change in the media session, a map match, or >= 1.5 s of silence
24 dB below the running music level (JDSP frame rms while frames flow, else the mic while a shader has it open;
frames that stop after flowing count as silence). beatSource 3 = tap (blue in beat_test.glsl).
Known limit: with no phone-side audio nothing corrects a slightly-off tapped tempo -- re-tap. Mic shaders must
declare a beat uniform for the clock (and its mic-based reset) to run.

## 2026-10-10 — crash-recovery #if 0 wrapper REMOVED (user)

The guard (MainActivity.recoverFromCrashIfNeeded) wrapped the last-loading shader in #if 0 after any process death
during load. Today it only ever fired on blazer-sysapp-update install kills (am_kill "installPackageLI"), never a
real crash, and since v95 it rewrote the user's own ~/shaders file. Decided (user): drop it wholesale -- a shader
that genuinely crash-loops the app is now fixed or moved out of ~/shaders from Termux. Removed in v102 (pending-
crash pref, ShaderManager tracking, string).

## 2026-10-10 — desktop "go" signal: the phone plays the cached map for the song playing on comrade (user)

Use case (user): music playing on comrade (YT Music in LibreWolf / VLC, heard through the Oppo speaker) has a
cached beat map and a known output delay, so the phone should not have to track or be tapped -- comrade tells it
"this song started at T" and the phone plays the cached map from there.
Decided (user):
- SIMPLE: one "go" per song start, then the phone plays that map to the end of the song. No periodic re-sync, no
  seek handling (user skips within a song rarely). A pause/resume shows up as end+start on comrade, so it re-sends.
  A "stop" is sent when nothing is playing any more.
- Transport: reuse MQTT, no new UDP path. comrade publishes; a Cybersyn profile (MQTT event) runs `file.write` into
  Cybersyn's user_files; ShaderEditor's clock thread picks the file up on its existing 500 ms poll. Delivery latency
  does not matter because the message carries an absolute start time (and the Oppo delay leaves ~0.9 s headroom).
- Accurate start time is the one thing that must be right (user: "if we get accurate start time we can be lazy with
  the rest"). Measured 2026-10-10: LibreWolf's MPRIS position is whole seconds and steps cleanly every 1.000 s, so
  comrade fast-polls at song start and anchors on the step edge (~15 ms). Precise players (VLC) are used directly.
- Clocks: the phone's wall clock was measured 0.25-0.5 s off comrade's (comrade is NTP-exact), so the sender
  measures the phone's clock offset itself (persistent SSH, best of several round trips) and sends the start time
  in PHONE wall-clock ms, already including the Oppo delay (VIDEO_DELAY_MS from ~/.config/oppo-av-sync.env).
- Lookup by the exact desktop key (yt:/file:/meta:) in beatmaps.json's `keys`, same gates as the phone match
  (coverage, confidence, meta duration rule).
- Priority (user): phone media session (matched map) > desktop go > tap > live. beatSource 4 = desktop.
- beat_offset_ms.txt still applies on top as a fine trim.

## 2026-10-10 — desktop go signal DEFERRED behind the song-cache redesign (user)

Found while scoping the entry above: 721 of 733 browser-learned desktop maps are warped by coarse MPRIS positions
(details: shader-backdrop scope.md 2026-10-10 "song cache REDESIGN"). An exact start time can't fix a warped map, so
the user chose to redo the cache (real audio via yt-dlp + offline analysis, richer features). The go signal as
decided above (MQTT -> Cybersyn file.write -> clock thread, phone-clock start time, priority phone session > desktop
> tap > live) is unchanged; it becomes "start this song's feature timeline at T" and is built in that project's
step 4.

## 2026-10-11 — shared baseline: the phone gets the desktop song analysis + the music-state layer (user)

Found: the phone's ~/.shader/beatmaps.json (exported 10-10 19:16) still holds 807 of the OLD browser-learned
maps, almost all warped (shader-backdrop scope.md 2026-10-10). Decided (user): DROP the old maps everywhere on the
phone -- songs without an analysis run live (and get recomputed from real audio later); no fallback to old maps.
Three steps, one at a time:
1. EXPORT (beatmaps-sync-pixel rewritten): only songs analysed by shader-backdrop analysis/analyze.py (the
   song_features index). Same file + same per-track fields BeatClock already reads (artist, title, duration_s, keys,
   bpm, coverage, confidence, beats) so the beat clock keeps working unchanged, PLUS what the music-state layer
   needs: beats_per_bar, downbeats, sections (start_s, start_beat, 8 scores, repeat id), drops, builds, and the six
   rel_* curves per beat (u8, base64). version 2. Artist/title: the linked track (yt: key) or else the file's
   artist folder + file name. Keys: the track's keys + file:<path> of the analysed audio.
2. MusicState.java in ShaderEditor: a port of shader-backdrop music_state.py with the SAME u_ms_* uniform names
   and meanings (so movement code copies between phone and desktop unchanged). Cached path 1:1; live path from the
   JDSP bands + the beat clock (no drum/tonal split on the phone -> density/voice from band groups; close, not
   identical).
3. The desktop go signal (entries above), as "start this song's timeline at T".

## 2026-10-11 — step 2 (music-state layer) BUILT, user-confirmed

v103 (de572b0): beat/MusicState.java, u_ms_* bound in BuiltinSystemUniforms (any u_ms_* uniform also acquires
the beat clock). User checked ms_test.glsl on screen: "looks on beat". Log confirmed the cached path on a song
playing on comrade, seen through Cybersyn's mirrored media session ("+analysis", ms src=1, next_in counting down,
section changes counted). So desktop songs already reach the phone's cached timeline via the mirrored session;
step 3 (go signal) only matters if that position proves too coarse.

## 2026-10-11 — step 3: desktop go signal BUILT (transport changed: through Termux, not MQTT/Cybersyn)

User: the Cybersyn mirror session reports no usable position, so the go signal is needed, with the latency in.
Changed (user: "we can do shared memory through termux into shader editor"): comrade's ~/bin/shader-go-signal
(shader-go-signal.service) keeps ONE persistent SSH shell to Termux -- it measures the phone clock offset on it
(best of 9 round trips, every 5 min; measured -300..-318 ms, rtt 20-40 ms) and writes ~/.shader/shader_go.txt on it
(atomic rename): "go <heard_start_phone_ms> <duration_s> <key>" / "stop <ms>". heard_start = nowplaying's anchored
song zero + VIDEO_DELAY_MS (oppo-av-sync.env, 922 ms) + clock offset. (An MQTT -> Cybersyn file.write route was
built first and works -- Cybersyn only subscribes cybersyn/+/event -- but is DISABLED: profile
shader-go-from-comrade.) ShaderEditor v104: BeatClock reads the file on its 500 ms poll, matches the exact key
(file: keys URL-decoded on both sides), and NEVER matches Cybersyn's mirror session (com.termux.cybersyn).
Priority: local media session (matched) > desktop go (beatSource 4) > tap > live; MusicState follows the same
song. beat_offset_ms.txt still trims on top.

## 2026-10-11 — BACKDROPS (v105, user)

- User: a dedicated backdrop folder in the Termux ShaderEditor folder; any shader may declare one of its images by
  file name. Built: `~/shaders/backdrops/` (created by the app), `#define BACKDROP_<name> <file>` + `uniform
  sampler2D <name>;` (+ `<name>Res` = size), via the external-texture bridge (BitmapFactory decode, RGBA8, halved
  until <= 4096, re-upload on mtime, rows top-to-bottom like the .bin bridge, no file = black). Docs in
  SHADEREDITOR_CONTEXT.md. First user: shader-backdrop's mobile/ember_flock_v3.glsl (`BACKDROP_bg
  ember_flock.png`, cover-fit, 60 % brightness, a 2.5 % parallax sway with the view's turn).

## 2026-10-19 — tap tempo: a DELIBERATE on/off gesture replaces the 8-tap auto-detect (user)

Why (user): "I want it more deliberate ... that way I know when it's on." Every finger landing used to feed the
8-tap detector (the glass gestures of emberbox included), and it cleared itself on silence / song change.
Decided (user, 2026-10-19):
- ON = one finger HOLDS, a second finger TAPS 4 times (the 4 taps set tempo + phase). Repeating the gesture while
  on re-taps the tempo. A tap only counts if the second finger lifts again quickly (a pinch / held second finger is
  not a tap); a third finger voids the gesture. Evaluated when the holding finger lifts.
- OFF = hold + ONE tap with the second finger.
- It keeps running until turned off -- no silence / song-change auto-clear any more. Screen off / app switch
  (wallpaper hidden) also turns it off (user: the shader resets anyway, so "it's off").
- Priority UNCHANGED (user: "like we got it rn, it works great"): cached map > desktop > tap > live tracker.
  The map / desktop still win while active; the tap tempo is no longer cleared by them, it resumes after.
- Lawnchair opens its own menu on a one-finger long-press (~0.4 s): the second finger should land before that.

## 2026-10-22 ~13:40 — v108: JDSP band feed read like the desktop reads its feed (user: "easy wins")

Why: the phone meter publishes 60 Hz frames + a 32-frame ring (bands.bin v2, 2026-10-10) but
BuiltinSystemUniforms re-read the first 432 bytes at 30 Hz, missed every other frame, kept a
paused song's last spectrum frozen (no staleness check), re-created the texture storage every
upload, and offered none of the desktop's feature uniforms, so desktop->phone ports had to
re-derive loudness/onset per shader from raw linear RMS.
Decided (JamesDSP-src SCOPE.md 2026-10-22 ~13:00 is the engine side of the same plan):
- New `opengl/JdspBandsFeed`: mmap + sequence number, every engine frame seen once; zeroes
  everything when seq stalls > 150 ms (pause, effect detached, JDSP off); understands v2 and
  v3 files (v3 = cooked frame + 11 features after the ring) and re-maps when the file grows.
- `bands` stays RAW (existing phone shaders keep their tuning). New samplers `bands_db`
  (desktop-equivalent 0..1 cooked frame) and `bands_hist` (108x32 raw history, row 0 newest).
  Each sampler is only uploaded when a program declares it; glTexSubImage2D after the first
  allocation.
- Feature uniforms with the desktop names and renderer.py's conditioning: u_rms u_sub u_bass
  u_lowmid u_mid u_highmid u_presence u_brilliance (1.5x-0.5 floor re-map), u_centroid (log
  20 Hz..16 kHz), u_flux, u_onset, u_onset_smooth (150 ms EMA), u_onset_accum, u_bass_accum
  (bass^3 integral). On a v2 engine only u_rms carries data.
- NOT in this version: per-band group-delay alignment (desktop comp_rows). Open question to the
  user: full alignment costs ~280 ms visual latency at A0; proposal = cap at 100 ms, knob in a
  file next to beat_offset_ms.txt. Bluetooth output latency reported by the HAL is 189 ms.

## 2026-10-22 ~13:40 — v109: per-band group-delay alignment, full (user: "we can account for the offset")

User's call on the open question: no cap. The output-latency offset is handled on-device by the
beat clock's offset file (the HAL reports 189 ms Bluetooth latency anyway), and the desktop
side delivers latency-adjusted timings too, so aligning every band to its slowest neighbour
costs nothing perceptually. Implemented like renderer.py comp_rows(): JdspBandsFeed keeps a
64-frame local raw ring, reads band b DELAY_S[b] frames later for `bands`, `bands_db` and every
row of `bands_hist`; DELAY_S = band_filters.delays_s() for the v3 engine's Butterworth bank
(max 17 frames at 60 Hz). `bands_db` is now computed here from the aligned raw history with the
engine's norm_db + 60/70 ms envelope constants (the engine's own cooked frame stays in the file
for other consumers). v2 engines are read unshifted. Features stay the engine's unaligned
values, as the desktop's are.

## 2026-10-22 ~13:55 — v110: the beat tracker reads the aligned rows too (consistency fix for v109)

Found while checking emberbox / worms v8: both shaders gate their kick on the beat clock's phase
(KICK_GATE 0.15 of a beat, ±75 ms at 120 BPM) and detect it on bands 4..27, which v109 now reads
67..217 ms later than before (their group delay). The live tracker (BeatClock) still consumed the
raw ring unaligned, so its grid and the shaders' kicks would have drifted apart by that much and
the gate would have dropped kicks. Desktop structure is the same: beat_session/beat_track run on
delay-aligned rows (beat_track.py:176). BeatClock now reads band b of frame k from frame
k - delay_b (JdspBandsFeed.delayFrames, v3 engines only), resyncs when it falls behind by more
than ringLen - 2 - maxDelay frames. Map / tap / desktop sources are absolute and unaffected; the
global output-latency offset stays the offset file's job.

## 2026-10-23 — v111: per-shader frame-rate cap `#define FPS_CAP <n>` (user: "that would unlock loads of stuff")

Why: the wallpaper drew on every vsync (120 Hz) with no way to ask for less, so "30 fps" only ever meant
"misses 120, stutters, GPU flat out" -- the reason every phone port was sized for ~8 ms. Slow scenes (orbits,
rings, the dwarf diorama) don't need 120, and the user already avoids the diorama because it heats the battery.
Decided:
- Opt-in per shader, same style as FTIME_PERIOD: a line `#define FPS_CAP 30` anywhere in the source (any
  integer 1..119; absent, 0 or >= the display rate = today's behaviour, every vsync).
- Paced on vsync: GLSurfaceView goes to RENDERMODE_WHEN_DIRTY and a Choreographer callback requests a render
  once at least 1/cap s minus half a vsync has passed -> 30 on a 120 Hz panel = every 4th vsync, even cadence.
  The display rate is measured from the vsync callbacks, so 60/90 Hz panels work too.
- Applies to the wallpaper AND the in-app preview (judge it where you edit it). Battery-low WHEN_DIRTY mode
  still wins (no pacing then). Paused/invisible = pacer stopped.
- `time`/`ftime`/date uniforms are wall-clock, unaffected. `frame` counts drawn frames, so a shader that steps
  a simulation once per frame runs at cap/120 speed -- that shader's author decides.
- NOT in v111: Surface.setFrameRate() hint (lets the panel itself drop refresh = extra display power). Left out
  until the GPU saving is seen, because a wallpaper vote could also change the launcher's refresh.

## 2026-10-23 — v112: KEEP_STATE (opt-in), in-place reload, wallpaper control intent

Why (user): the ringbox port's brain lives in Termux and survives app switches, but every shader's GPU state was
wiped on each hide (GLSurfaceView default: EGL context destroyed on pause), so the ring would refill under moons
that had already cleared it. User: make keeping it OPT-IN per shader; and "add an intent so you can restart and
switch without me needing to pick stuff in the editor".
Decided / built:
- `#define KEEP_STATE` (or `KEEP_STATE 1`; `0` = off): setPreserveEGLContextOnPause(true) for that shader. Hidden =
  GL thread paused with every buffer kept; only listeners stop (sensors, mic, beat clock, camera). On resume (same
  size) the clock skips the hidden span (time stands still while hidden), `frame` keeps counting, uniforms are
  reconfigured on the GL thread. Android can still drop the context (memory pressure, reboot) -> normal fresh start.
  Cost: the shader's GPU memory stays allocated while hidden (diorama ~14 MB; phone has 16 GB shared).
- IN-PLACE RELOAD for every shader: the source used to compile only when a NEW context was made (the next hide/
  show after "Update wallpaper"). Now setFragmentShader flags it and the next frame frees the old programs,
  targets, compute images and textures (stale texture sets queued for the GL thread) and builds the new shader
  with a fresh clock. "Update wallpaper" keeps working; changes also apply while the wallpaper is visible.
- receiver/WallpaperControlReceiver (exported, sender needs WRITE_SECURE_SETTINGS = adb shell / UID 1000):
  SET_WALLPAPER --es shader <file name> (or --el id) and RELOAD_WALLPAPER (re-reads the current file). If
  ShaderEditor is not the live wallpaper (installs reset it) it calls the hidden setWallpaperComponent itself.

## 2026-10-23 — v113: SIM FEED (#define FEED / FEED_EXPORT_<image>), beat-map read fix, intent result fix

For the phone-ringbox port (decisions + layouts: ~/builds/amd/shader-backdrop/phone-ringbox/scope.md). New
opengl/SimFeed.java: a Termux process feeds named uniforms through ~/.shader/feeds/<name>.bin (seqlocked,
prev/cur per record, interpolated one producer step behind) and gets heartbeat/visibility, taps, 3-finger taps
and FEED_EXPORT_<image> readbacks (PBO, every 6 frames) through <name>.in.bin. Multipass shaders only.
BeatClock: beatmaps.json (35 MB) read with one exact-size byte read -- the StringBuilder growth needed a 128 MB
char[] and threw a (retried) OutOfMemoryError on every app start. WallpaperControlReceiver: result codes only for
ordered broadcasts (Termux's am logged an error otherwise).

## 2026-10-24 — v114: even frame pacing (presentation timestamps) + SIM FEED history playback

FPS_CAP frames get eglPresentationTimeANDROID = the vsync the pacer requested + 50 ms (33 ms let ~25 % of frames
slip a vsync); result on the Pixel: 58/58 frames exactly 2 vsyncs apart at cap 60 (was 1..4). SimFeed keeps 8
snapshots and plays them back at a fixed 80 ms delay, sampled at the frame's presentation time (producer steps
were 12..63 ms apart). Details: phone-ringbox/scope.md.

## 2026-10-24 — v115: SIM FEED Catmull-Rom playback + u_feedDt
Curve through 4 snapshots instead of straight segments (camera kinks at each producer step); u_feedDt (s) = the step between consecutive frames' sample moments, so a feed shader can advance its own sim on the same clock as the played-back uniforms.

## 2026-10-24 — IDEA (user, not decided, nothing built): per-shader control notification
User: "couldn't ShaderEditor optionally show a persistent notification for shaders like the phone ringbox, with buttons
like the orbit modes or a brain reset?" Proposal (mine, awaiting OK): shader opts in by directive, e.g.
`#define NOTIFY_BUTTON <id> "<label>"` (repeatable; ongoing notification only while that shader is the live wallpaper).
A press = a counter per button: exposed to the shader as a uniform AND written into the SIM FEED return file
(ringbox.in.bin, next to the boop/3-finger counters), so the brain handles it like a pad press. Custom RemoteViews
layout to fit more than Android's 3 action buttons. Ringbox candidates: sys_mode cycle (normal/slingshot/recover),
STAR SLING, ROSETTE, REFILL, cam cycle, ring farming on/off, RESET (brain re-execs = new world id = fresh ring).

## 2026-10-24 — DECIDED (user: "that's perfect"): control notification, build it
As proposed above, with: ONE mode button cycling normal / slingshot / recover / ROSETTE (rosette is a mode like
slingshot); NO camera button (3-finger taps already cycle cams); STAR SLING, REFILL, ring farming on/off, RESET; status
line written back by the brain shown as the notification text.

## 2026-10-24 ~18:35 — v116 BUILT + installed (uncommitted, user to try): CONTROL NOTIFICATION
`#define BUTTON_<id> <label>` (source order, max 8; label = rest of the line, no trailing comment) -> ongoing silent
notification (channel "Shader controls", public on the lock screen) while that shader is the live wallpaper; one row of
buttons, expanded view adds the status body. Press = counter (service/ShaderControls): uniform int u_btn_<id> in every
pass + return-file header bytes 32..63 (u32 per button) for FEED brains. Status: ~/.shader/feeds/<feed>.status, line 1 =
header subtext, rest = expanded text, re-read every 1 s while visible (and 0.4 s after a press).

## 2026-10-26 — v119: the CONTROL NOTIFICATION joins the crDroid island
setProgress(100, first "NN %" of the status header, else 100) + the header as title: an ONGOING notification with a
progress value is promoted by the dynamic bar (axdynamicbar NotificationIslandManager), which draws this notification's
own button layout when the pill is opened (ExpandedOngoingContent.kt). Seen live: pill "normal · ri… 94%".
Committed together with the uncommitted v116-v118 work that was already installed (control notification, feed bits).

## 2026-10-26 19:35 — v120: island panel buttons as big as the Cybersyn remote's (user: "why is it so crammed")
notification_controls.xml: two rows of four 44 dp buttons (style IslandButton: 12 sp, dark tint #2E2E3A, 2 dp margins),
the second row only with > 4 buttons; was one 40 dp row of eight. The layout file was never committed with v116-v119;
it is now. Still max 8 buttons (the feed's 8 counter slots). Installed via blazer-sysapp-update; the install reset the
wallpaper to ImageWallpaper -> RELOAD_WALLPAPER broadcast put ShaderEditor back.

## 2026-10-29 04:22 — v121: TAP ONSETS -- every finger landing is a beat hit for the flywheel (user)
User: "let my taps both drive the flywheel tempo just like the music and also act as accents, like beat hits"; keep the beat
maps (user agreed: a map is exact, taps cannot improve it). BeatClock.processTaps: every ACTION_DOWN / ACTION_POINTER_DOWN ->
BeatTracker.addOnset(TAP_ONSET 1.0) = the newest onset sample + 1.0 x the loudest recent onset, so the tempo autocorrelation
and phase comb hear taps like kicks. No reference (live audio / unmapped / silence) -> audio + taps steer the flywheel;
a map reference (or the hold + 4-tap override) -> the ODF is not used for tempo, taps are only the shaders' accents.
The hold + 4-tap tempo gesture is unchanged. Installed via blazer-sysapp-update; wallpaper restored by SET_WALLPAPER.
Shader side (shader-backdrop): the ember box now also kicks on every landing (the worms already did).

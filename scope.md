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

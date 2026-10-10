#version 300 es
// compute_test -- exercises the GLES compute stage (scope.md 2026-10-10):
// 16384 particles drift through a curl-noise flow (2 substeps/frame via
// `pass`), touch pulls them in, each frame they are splatted additively
// into an r32ui accumulator with imageAtomicAdd, and the image pass
// tonemaps that. Expect: a soft cyan..white glowing particle cloud,
// moving smoothly; touching draws particles towards the finger.
#define IMAGE_parts rgba32f 16384x1 swap
#define IMAGE_glow  r32ui   full

#define N 16384
#define FIX 256.0

// compute sections are highp already; this is for the fragment passes
precision highp float;

uniform float time;
uniform int frame;
uniform vec2 resolution;
uniform vec2 touch;
uniform int pointerCount;

#ifdef COMPUTE
float hash(float n) { return fract(sin(n) * 43758.5453); }

vec2 flow(vec2 p, float t) {
	// curl of a sum of sines: divergence-free swirl
	float e = 0.01;
	#define PSI(q) (sin(q.x * 3.1 + t * 0.3) * cos(q.y * 2.7 - t * 0.2) + \
		0.5 * sin(q.x * 5.3 - q.y * 4.1 + t * 0.5))
	vec2 dx = vec2(e, 0.0), dy = vec2(0.0, e);
	float a = PSI((p + dy)), b = PSI((p - dy));
	float c = PSI((p + dx)), d = PSI((p - dx));
	return vec2(a - b, -(c - d)) / (2.0 * e);
}
#endif

==compute clear glow==
layout(local_size_x = 16, local_size_y = 16) in;
layout(r32ui, binding = glow_UNIT) uniform writeonly highp uimage2D glow;
void main() {
	ivec2 p = ivec2(gl_GlobalInvocationID.xy);
	if (any(greaterThanEqual(p, imageSize(glow)))) return;
	imageStore(glow, p, uvec4(0u));
}

==compute step 16384 x2==
layout(local_size_x = 64) in;
layout(rgba32f, binding = parts_UNIT) uniform readonly highp image2D parts;
layout(rgba32f, binding = parts_out_UNIT) uniform writeonly highp image2D parts_out;
uniform int pass;
void main() {
	int i = int(gl_GlobalInvocationID.x);
	if (i >= N) return;
	vec4 s = imageLoad(parts, ivec2(i, 0));
	if (frame == 0 && pass == 0) {
		float fi = float(i);
		s = vec4(hash(fi) * 2.0 - 1.0, hash(fi + 7.1) * 2.0 - 1.0, 0.0, 0.0);
	}
	float aspect = resolution.x / resolution.y;
	vec2 pos = s.xy;
	vec2 vel = s.zw;
	float dt = 1.0 / 120.0 / 2.0;
	vec2 want = flow(pos * vec2(aspect, 1.0), time) * 0.35;
	if (pointerCount > 0) {
		vec2 t = touch / resolution * 2.0 - 1.0;
		vec2 d = t - pos;
		want += normalize(d + 1e-5) * 0.8 / (1.0 + 8.0 * dot(d, d));
	}
	vel = mix(vel, want, 0.08);
	pos += vel * dt;
	pos = clamp(pos, -1.0, 1.0);
	imageStore(parts_out, ivec2(i, 0), vec4(pos, vel));
}

==compute splat 16384==
layout(local_size_x = 64) in;
layout(rgba32f, binding = parts_UNIT) uniform readonly highp image2D parts;
layout(r32ui, binding = glow_UNIT) uniform highp uimage2D glow;
void main() {
	int i = int(gl_GlobalInvocationID.x);
	if (i >= N) return;
	vec4 s = imageLoad(parts, ivec2(i, 0));
	ivec2 size = imageSize(glow);
	vec2 c = (s.xy * 0.5 + 0.5) * vec2(size);
	float speed = length(s.zw);
	// 5x5 gaussian dot, a bit hotter when fast
	for (int y = -2; y <= 2; ++y) {
		for (int x = -2; x <= 2; ++x) {
			ivec2 q = ivec2(c) + ivec2(x, y);
			if (any(lessThan(q, ivec2(0))) || any(greaterThanEqual(q, size))) continue;
			vec2 d = vec2(q) + 0.5 - c;
			float w = exp(-dot(d, d) * 0.45) * (0.6 + speed);
			imageAtomicAdd(glow, q, uint(w * FIX + 0.5));
		}
	}
}

==image==
uniform highp usampler2D glow;
uniform vec2 glowRes;
out vec4 fragColor;
void main() {
	ivec2 p = ivec2(gl_FragCoord.xy / resolution * glowRes);
	float g = float(texelFetch(glow, p, 0).r) / FIX;
	vec3 col = vec3(0.25, 0.7, 1.0) * g * 0.6 + vec3(1.0) * g * g * 0.08;
	col = 1.0 - exp(-col);
	fragColor = vec4(col, 1.0);
}

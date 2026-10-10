#version 300 es
// beat_test -- shows the phone beat clock (scope.md 2026-10-10).
// Colour = source: grey none, orange live tracker, green cached desktop map.
// Big disc flashes on every beat; the ring fills over one beat; the four dots
// count beats in the bar (beatCount mod 4). Bottom bars: left = confidence,
// right = BPM (full width = 200 BPM, tick marks every 20).
precision highp float;

uniform vec2 resolution;
uniform float beatCount;
uniform float beatPhase;
uniform float beatConf;
uniform float beatBpm;
uniform int beatSource;

out vec4 fragColor;

void main() {
	vec2 uv = (gl_FragCoord.xy - 0.5 * resolution) / resolution.x;
	vec3 tint = beatSource == 2 ? vec3(0.2, 1.0, 0.45)
			: beatSource == 1 ? vec3(1.0, 0.55, 0.15)
			: vec3(0.5);
	vec3 col = vec3(0.02);

	// flash: bright at the beat, decays over ~1/6 beat
	float flash = exp(-beatPhase * 10.0);
	float r = length(uv - vec2(0.0, 0.15));
	col += tint * flash * smoothstep(0.22, 0.20, r);

	// ring filling clockwise from 12 o'clock over one beat
	float ang = atan(uv.x, uv.y - 0.15) / 6.2831853;
	ang = ang < 0.0 ? ang + 1.0 : ang;
	float ring = smoothstep(0.012, 0.0, abs(r - 0.30));
	col += tint * ring * (ang < beatPhase ? 1.0 : 0.15);

	// beat-in-bar dots
	float inBar = mod(floor(beatCount), 4.0);
	for (int i = 0; i < 4; i++) {
		vec2 c = vec2(-0.24 + 0.16 * float(i), -0.32);
		float d = smoothstep(0.035, 0.03, length(uv - c));
		col += tint * d * (float(i) == inBar ? 1.0 : 0.15);
	}

	// bars
	float y = uv.y + 0.5;
	if (y > 0.06 && y < 0.1) {
		float x = uv.x + 0.5;
		if (x < 0.48) {
			col = mix(col, tint, step(x / 0.48, beatConf) * 0.9);
		} else if (x > 0.52) {
			float b = (x - 0.52) / 0.48;
			col = mix(col, tint, step(b, beatBpm / 200.0) * 0.9);
			col += vec3(0.3) * step(fract(b * 10.0), 0.01);
		}
	}
	fragColor = vec4(col, 1.0);
}

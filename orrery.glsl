#version 300 es
// ==================== Shared preamble ====================
#ifdef GL_FRAGMENT_PRECISION_HIGH
precision highp float;
precision highp sampler2D;
#else
precision mediump float;
precision mediump sampler2D;
#endif

out vec4 fragColor;

#define N_BANDS          108.0
#define PI               3.141592653589793
#define TAU              6.283185307179586
#define FPS              120.0
#define SPHERE_RADIUS    1.02
#define SENSOR_POS       vec2(0.0, 0.0)
#define BALL_ZOOM        1.0

#define N_GEARS          8
#define OMEGA_MAX        0.6

// Snow-globe drive (user): the orrery is driven ONLY by the trackball and
// stalls when you stop. ONE shared signal across every gear, so the relative
// rates never depend on spin direction or on the view orientation.
#define SPIN_GAIN        0.150   // orbital speed per unit of trackball spin

// ---- System Layout ----
// The pulsar is FIXED at the centre; every other body is a direct orbiter on
// its own gear. No binary, no nested sub-systems -- one flat hierarchy. That
// is what keeps this as cheap as (cheaper than) the old binary version: the
// old code composed G2/Terra as "dwarf position + local offset" and swung the
// two stars around a shared barycentre. Both of those layers are gone.
#define GEAR_S1          2      // pulsar spin axis only (its position is origin)
#define GEAR_DWARF       3
#define GEAR_G1          4
#define GEAR_G2          5
#define GEAR_TERRA       6

// Orbit radii around the central pulsar (world units)
#define ORB_TERRA        0.20   // innermost, rocky
#define ORB_G1           0.30   // ice giant + rings
#define ORB_G2           0.40   // ringed giant
#define ORB_DWARF        0.48   // outermost, the red dwarf + its moons

// Radii
#define STAR_R           0.00725
#define DWARF_R          0.060
#define G1_R             0.036
#define G2_R             0.030
#define TERRA_R          0.020

// Moon Allocation: 5 + 4 + 6 + 1 = 16 Moons
#define N_MOONS_G1       5
#define N_MOONS_DWARF    4
#define N_MOONS_G2       6
#define N_MOONS_TERRA    1
#define TOTAL_MOONS      16

// Tight subsystem culling radii
#define G1_BOUND_R       0.090
#define DWARF_BOUND_R    0.125
#define G2_BOUND_R       0.090
#define TERRA_BOUND_R    0.034

// ---- Audio Dynamics ----
#define LEAK_K           0.28
#define H2_K             0.20
#define H3_K             0.10
#define H4_K             0.06
#define FLOOR_K          0.42
#define TILT_DB          4.5
#define DB_MIN           -90.0
#define DB_SPAN          120.0
#define DISPLAY_DB       24.0
#define AGC_FLOOR_DB     -55.0
#define AGC_RELEASE_DBPS 6.0
#define AGC_ATTACK       0.5
#define BAND_ATTACK      0.88
#define BAND_RELEASE     0.22

#define ARC_GATE         0.20
#define ARC_DECAY        0.910
#define IDLE_GATE        0.06
#define IDLE_PERIOD      6.0
#define IDLE_LEVEL       0.55

vec4 quatMul(vec4 a, vec4 b) {
    return vec4(a.w * b.xyz + b.w * a.xyz + cross(a.xyz, b.xyz),
                a.w * b.w - dot(a.xyz, b.xyz));
}

mat3 quatToMat3(vec4 q) {
    q = normalize(q);
    float x2 = q.x + q.x, y2 = q.y + q.y, z2 = q.z + q.z;
    float xx = q.x * x2,  xy = q.x * y2,  xz = q.x * z2;
    float yy = q.y * y2,  yz = q.y * z2,  zz = q.z * z2;
    float wx = q.w * x2, wy = q.w * y2, wz = q.w * z2;
    return mat3(
        1.0 - (yy + zz), xy + wz,         xz - wy,
        xy - wz,         1.0 - (xx + zz), yz + wx,
        xz + wy,         yz - wx,         1.0 - (xx + yy)
    );
}

vec2 intersectSphere(vec3 ro, vec3 rd, float r) {
    float b = dot(ro, rd);
    float c = dot(ro, ro) - r * r;
    float h = b * b - c;
    if (h < 0.0) return vec2(-1.0);
    h = sqrt(h);
    return vec2(-b - h, -b + h);
}

// Orbital planes. The system reads as ONE main disc, with two deliberate
// departures: the ice giant is tipped slightly off-plane, and the red dwarf
// runs 90 deg out so it visibly crosses the disc. gearNode() rotates a plane
// about the view axis -- that is what makes a departure READ as a different
// plane. A pure tilt offset only changes the ellipse's aspect and stays
// visually axis-aligned, which is exactly why the old four-tilt layout was
// unreadable.
#define ORBIT_TILT   -0.65

float gearTilt(float idx) {
    if (idx == 2.0) return 0.06;               // pulsar spin axis -> jets only
    if (idx == 3.0) return ORBIT_TILT;         // red dwarf
    if (idx == 4.0) return ORBIT_TILT + 0.15;  // ice giant, slightly off-plane
    if (idx == 5.0) return ORBIT_TILT;         // ringed giant
    if (idx == 6.0) return ORBIT_TILT;         // terra
    return 0.0;
}

// Plane rotation about the view axis. 0 = the main disc.
float gearNode(float idx) {
    if (idx == 3.0) return 1.5707963;   // red dwarf: 90 deg, crosses the disc
    if (idx == 4.0) return 0.30;        // ice giant: a slight twist
    return 0.0;
}

float gearPhaseOffset(float idx) {
    if (idx == 2.0) return 0.0;
    if (idx == 3.0) return 0.50;
    if (idx == 4.0) return 0.28;
    if (idx == 5.0) return 0.62;
    if (idx == 6.0) return 0.15;
    return fract(idx * 0.6180339887);
}

// ONE orbit-basis convention for every body. gearNode() rotates the plane about
// the view axis, so a body can sit on a genuinely different plane (not just a
// different-aspect ellipse). The same basis feeds the gear phase, the drawn
// orbit and the rings, so they can never disagree.
void hubBasis(float gIdx, out vec3 uLoc, out vec3 vLoc, out vec3 nLoc) {
    float t  = gearTilt(gIdx);
    float ph = gearNode(gIdx);
    float cp = cos(ph), sp = sin(ph);
    uLoc = vec3(-sp, cp, 0.0);
    vLoc = vec3(sin(t) * cp, sin(t) * sp, cos(t));
    nLoc = normalize(cross(uLoc, vLoc));
}

vec3 hubNormal(float gIdx) {
    vec3 u, v, n;
    hubBasis(gIdx, u, v, n);
    return n;
}

void getG1LocalBasis(out vec3 uLoc, out vec3 vLoc, out vec3 nLoc) {
    hubBasis(float(GEAR_G1), uLoc, vLoc, nLoc);
}

void getG2LocalBasis(out vec3 uLoc, out vec3 vLoc, out vec3 nLoc) {
    hubBasis(float(GEAR_G2), uLoc, vLoc, nLoc);
}

void getTerraLocalBasis(out vec3 uLoc, out vec3 vLoc, out vec3 nLoc) {
    hubBasis(float(GEAR_TERRA), uLoc, vLoc, nLoc);
}

#define G1_RING_IN       1.24
#define G1_RING_OUT      2.18
#define G2_RING_IN       1.35
#define G2_RING_OUT      1.75

vec3 ringNormalG1() {
    vec3 u, v, n;
    getG1LocalBasis(u, v, n);
    return n;
}
vec3 ringNormalG2() {
    vec3 u, v, n;
    getG2LocalBasis(u, v, n);
    return n;
}

float ringDensityG1(float r, float R) {
    float u = (r / R - G1_RING_IN) / (G1_RING_OUT - G1_RING_IN);
    if (u < 0.0 || u > 1.0) return 0.0;
    float carrier = sin(u * 2.2 * 135.5);
    float d = (0.5 + 0.5 * carrier) * smoothstep(0.0, 0.06, u) * smoothstep(1.0, 0.88, u);
    d *= step(0.04, abs(u - 0.45));
    return clamp(d, 0.0, 1.0);
}

float ringDensityG2(float r, float R) {
    float u = (r / R - G2_RING_IN) / (G2_RING_OUT - G2_RING_IN);
    if (u < 0.0 || u > 1.0) return 0.0;
    float d = (0.5 + 0.5 * sin(u * 40.0)) * smoothstep(0.0, 0.1, u) * smoothstep(1.0, 0.9, u);
    return clamp(d, 0.0, 1.0);
}

float shadowSphere(vec3 wp, vec3 L, vec3 blocker, float bR) {
    vec3 rel = wp - blocker;
    if (dot(-rel, L) <= 0.0) return 1.0;
    float dPerp = length(cross(rel, L));
    return mix(0.08, 1.0, smoothstep(bR * 0.90, bR * 1.15, dPerp));
}

vec3 neutronStarSurface(vec3 pRel, vec3 rd, float R, float bass) {
    vec3 n = pRel / R;
    float mu = clamp(dot(-rd, n), 0.0, 1.0);
    float t = pow(mu, 0.12) + bass * 0.35;
    vec3 c = mix(vec3(0.18, 0.45, 1.2), vec3(0.85, 0.95, 1.0), smoothstep(0.4, 0.85, t));
    c = mix(c, vec3(1.0), smoothstep(0.85, 1.0, t));
    return c * (3.5 + bass * 4.0);
}

vec3 redDwarfSurface(vec3 pRel, vec3 rd, float R, float mid, float et) {
    vec3 n = pRel / R;
    float mu = clamp(dot(-rd, n), 0.0, 1.0);
    vec2 uv = n.xy / (1.0 + max(n.z, 0.0) * 0.35);
    float r2 = dot(uv, uv);

    float flare = (0.5 + 0.5 * sin((r2 * 0.80 - et * 0.05) * 135.5)) *
                  (0.5 + 0.5 * sin((uv.x * 1.2 + uv.y * 1.2) * 135.5));
    float spots = pow(clamp(1.0 - flare * 1.4, 0.0, 1.0), 3.0);

    float ld = pow(mu, 0.55);
    float t  = ld * (0.70 + 0.45 * flare) + mid * 0.35;

    vec3 c = mix(vec3(0.40, 0.015, 0.005), vec3(0.95, 0.10, 0.015), smoothstep(0.15, 0.60, t));
    c = mix(c, vec3(1.0, 0.42, 0.10), smoothstep(0.60, 0.98, t));
    c = mix(c, vec3(0.08, 0.005, 0.002), spots * 0.65);
    return c * (1.35 + mid * 1.60);
}

vec3 getPulsarAxis() {
    float tilt = gearTilt(float(GEAR_S1));
    return normalize(vec3(-sin(tilt), cos(tilt), 0.0));
}

vec3 shadePulsarBinary(vec3 n, vec3 rd, vec3 wp, vec3 pS1, vec3 pS2, vec3 alb,
                       float bandFreq, float spinRate, float env, float et,
                       float isGasGiant, float bass) {
    vec3 dL1 = pS1 - wp;
    float d2_1 = dot(dL1, dL1);
    vec3 L1 = dL1 * inversesqrt(d2_1);
    float att1 = (2.2 + bass * 2.8) / (1.0 + 5.5 * d2_1);
    float ndl1 = clamp((dot(n, L1) + 0.08) / 1.08, 0.0, 1.0);

    vec3 dL2 = pS2 - wp;
    float d2_2 = dot(dL2, dL2);
    vec3 L2 = dL2 * inversesqrt(d2_2);
    float att2 = 1.75 / (1.0 + 4.5 * d2_2);
    float ndl2 = clamp((dot(n, L2) + 0.18) / 1.18, 0.0, 1.0);

    float spin = et * spinRate;
    float cs = cos(spin), sn = sin(spin);
    vec3 sp = vec3(n.x * cs - n.y * sn, n.x * sn + n.y * cs, n.z);
    float bands = sin(sp.y * bandFreq + sin(sp.x * 3.2) * 0.5);
    vec3 albT = alb * (0.82 + 0.36 * (0.5 + 0.5 * bands));

    vec3 col1 = albT * ndl1 * att1 * vec3(0.55, 0.85, 1.35);
    vec3 h1 = normalize(L1 - rd);
    float spec1 = pow(max(0.0, dot(n, h1)), isGasGiant > 0.5 ? 45.0 : 20.0);
    col1 += vec3(0.7, 0.9, 1.4) * spec1 * att1 * 0.8;

    vec3 col2 = albT * ndl2 * att2 * vec3(1.25, 0.18, 0.03);
    vec3 col = col1 + col2;

    float rim = pow(1.0 - clamp(dot(-rd, n), 0.0, 1.0), 3.2);
    col += (vec3(0.4, 0.8, 1.5) * att1 + vec3(1.3, 0.22, 0.04) * att2) * rim * 0.45;

    if (isGasGiant > 0.5) {
        float gasRim = pow(1.0 - clamp(dot(-rd, n), 0.0, 1.0), 4.5);
        vec3 gasCol = mix(vec3(0.2, 0.5, 1.0), vec3(0.8, 0.9, 1.2), ndl1) * att1
                    + vec3(1.0, 0.2, 0.05) * att2 * 1.6;
        col += gasCol * gasRim * 0.70;
    }

    vec3 ambLight = mix(vec3(0.02, 0.035, 0.06), vec3(0.07, 0.015, 0.005), clamp(att2 / (att1 + att2), 0.0, 1.0));
    col += alb * (ambLight + env * vec3(0.04, 0.05, 0.08) * (0.15 + 0.85 * rim));
    return col;
}

vec3 calcPulsarJets(vec3 ro, vec3 rd, vec3 pPulsar, vec3 pAxis, float bass, float treble, float minT, float et) {
    vec3 w0 = ro - pPulsar;
    float dAlong = dot(w0, pAxis);
    float rdAlong = dot(rd, pAxis);
    vec3 rdPerp = rd - pAxis * rdAlong;

    float a = dot(rdPerp, rdPerp);
    float b = dot(w0 - pAxis * dAlong, rdPerp);
    float tClosest = clamp(-b / max(a, 1e-5), 0.0, minT);

    vec3 pAt = ro + rd * tClosest - pPulsar;
    float zProj = dot(pAt, pAxis);
    vec3 radial = pAt - pAxis * zProj;
    float rDist = length(radial);
    float zDist = abs(zProj);

    float lengthFade = smoothstep(0.32, 0.06, zDist) * smoothstep(0.015, 0.05, zDist);
    if (lengthFade <= 0.0) return vec3(0.0);

    float coneR = 0.005 + 0.040 * zDist;
    float beam = exp(-pow(rDist / coneR, 2.0)) * lengthFade;

    float audioPower = pow(clamp(bass * 1.1 + treble * 0.4, 0.0, 1.5), 1.6);
    // Always on. A pulsar's jets don't switch off with the music; audio only
    // flares them on top of a steady base (user: they had stopped firing,
    // because the old code hard-gated the whole thing behind audioPower).
    float jetPower = 0.40 + audioPower;

    float waves = 0.85 + 0.15 * sin(zDist * 60.0 - et * 8.0);
    vec3 jetCol = mix(vec3(0.12, 0.45, 1.0), vec3(0.75, 0.92, 1.0), exp(-rDist * 70.0));
    return jetCol * beam * waves * jetPower * 1.1;
}

// ==================== Pass 1: State & Ephemeris ====================
==state 128x4==
uniform sampler2D state;
uniform sampler2D bands;
uniform vec2      resolution;
uniform vec2      surfaceResolution;
uniform vec2      touch;
uniform int       pointerCount;
uniform bool      screenLocked;
uniform float     time;
uniform vec3      linear;
uniform vec3      gravity;

#define TRACKBALL_GAIN      2.6
#define FLING_DECAY         0.999
#define FLING_HALT_SQ       4.0e-4   // |avg|^2 below this = finger has stopped
#define FRICTION            0.9994     // Luxurious long coast
#define RESTORE_SPEED_LOCK  0.35
#define FLICK_THRESH        4.5
#define VIRTUAL_KICK_SPEED  0.22
#define COOLDOWN_FRAMES     22.0
#define SLEEP_THRESH        0.0006     // Complete rest threshold

vec4 slot(int px) {
    return texelFetch(state, ivec2(px, 0), 0);
}

vec4 bandTexel(int b) {
    return texelFetch(state, ivec2(b, 1), 0);
}
float amp(int i) {
    if (i < 0 || i > int(N_BANDS) - 1) return 0.0;
    return texelFetch(bands, ivec2(i, 0), 0).r;
}
vec4 quatSlerp(vec4 a, vec4 b, float t) {
    if (dot(a, b) < 0.0) b = -b;
    return normalize(mix(a, b, t));
}
vec2 touchToUv(vec2 t) {
    return (-surfaceResolution.xy + 2.0 * t) / surfaceResolution.y;
}
vec3 trackball(vec2 uv) {
    vec2 c = uv / (SPHERE_RADIUS * BALL_ZOOM);
    float r2 = dot(c, c);
    if (r2 <= 1.0) return vec3(c, -sqrt(1.0 - r2));
    return vec3(normalize(c), 0.0);
}

vec3 calcHubOrbit(float gIdx, float r) {
    float ph = slot(17 + int(gIdx)).r;
    float theta = fract(ph + gearPhaseOffset(gIdx)) * TAU;
    vec3 u, v, n;
    hubBasis(gIdx, u, v, n);
    return (u * cos(theta) + v * sin(theta)) * r;
}

// The pulsar never moves -- it is the hub everything else swings around.
vec3 calcPulsarPos() { return vec3(0.0); }

// Every other body is a direct orbiter: exactly one hub orbit each, no nesting.
vec3 calcDwarfPos()  { return calcHubOrbit(float(GEAR_DWARF), ORB_DWARF); }
vec3 calcG1Pos()     { return calcHubOrbit(float(GEAR_G1),    ORB_G1);    }
vec3 calcG2Pos()     { return calcHubOrbit(float(GEAR_G2),    ORB_G2);    }
vec3 calcTerraPos()  { return calcHubOrbit(float(GEAR_TERRA), ORB_TERRA); }

vec3 moonJitter(float n) {
    return vec3(fract(n * 0.6180339887 + 0.371),
                fract(n * 0.7548776662 + 0.618),
                fract(n * 0.4142135624 + 0.117));
}

void main() {
    int px = int(gl_FragCoord.x);
    int py = int(gl_FragCoord.y);

    // ------------------------------------------------------------ Row 0
    if (py == 0) {
        if (px > 24) discard;

        if (px == 12) {
            float mx = 0.0;
            for (int i = 0; i < int(N_BANDS); i++) mx = max(mx, bandTexel(i).b);
            float agc = slot(12).r;
            agc = mx > agc ? mix(agc, mx, AGC_ATTACK)
                           : max(mx, agc - AGC_RELEASE_DBPS / DB_SPAN / FPS);
            agc = max(agc, (AGC_FLOOR_DB - DB_MIN) / DB_SPAN);
            fragColor = vec4(agc, 0.0, 0.0, 1.0);
            return;
        }

        if (px >= 13 && px <= 15) {
            int start = (px - 13) * 36;
            float fMax = 0.0;
            for (int k = 0; k < 36; k++) fMax = max(fMax, bandTexel(start + k).r);
            fragColor = vec4(fMax, 0.0, 0.0, 1.0);
            return;
        }

        // Dedicated state unpack
        vec4 q         = slot(0);
        vec3 omega     = slot(1).xyz;
        vec3 fling     = slot(2).xyz;
        vec2 lastTouch = slot(3).xy;
        bool wasTouching = (lastTouch.x + lastTouch.y) > 0.5;

        float qq = dot(q, q);
        if (!(qq > 0.5 && qq < 1.5)) {
            q = vec4(0.0, 0.0, 0.0, 1.0);
            omega = vec3(0.0);
            fling = vec3(0.0);
            wasTouching = false;
        }
        q = normalize(q);

        bool isTouching = (pointerCount > 0) && !screenLocked;

        if (isTouching) {
            if (!wasTouching) { omega = vec3(0.0); fling = vec3(0.0); }
            vec3 meas = vec3(0.0);
            if (wasTouching) {
                vec3 v0 = trackball(touchToUv(lastTouch));
                vec3 v1 = trackball(touchToUv(touch));
                vec3 ax = cross(v0, v1);
                float s = length(ax);
                if (s > 1e-4) {
                    meas = ax * (asin(min(s, 1.0)) / s) * TRACKBALL_GAIN;
                } else if (s > 1e-7) {
                    meas = ax * (1.0 + (s * s) * 0.1666667) * TRACKBALL_GAIN;
                }
            }
            vec3 avg = 0.5 * (meas + omega);
            fling *= FLING_DECAY;
            if (dot(avg, avg) > dot(fling, fling)) fling = avg;
            // Halt guard (user's diagnosis): if the finger has essentially
            // stopped, do NOT carry a stale peak into the release. Without
            // this, slowing to a stop before lifting off still fires the peak
            // fling that was banked mid-drag.
            if (dot(avg, avg) < FLING_HALT_SQ) fling = avg;
            omega = meas;
            lastTouch = touch;
        } else {
            if (wasTouching) omega = fling;
            lastTouch = vec2(0.0);
            if (screenLocked) {
                omega *= 0.72;
                q = quatSlerp(q, vec4(0.0, 0.0, 0.0, 1.0), RESTORE_SPEED_LOCK);
            } else {
                omega *= FRICTION;
                // Snap cleanly to rest once momentum bleeds off
                if (length(omega) < SLEEP_THRESH) {
                    omega = vec3(0.0);
                    fling = vec3(0.0);
                }
            }
        }

        // Flick kick
        float flickTimer = slot(16).r;
        float shoveSq = dot(linear.xy, linear.xy);
        if (flickTimer <= (1.0 / COOLDOWN_FRAMES) && shoveSq > (FLICK_THRESH * FLICK_THRESH)) {
            vec2 sdir = linear.xy * inversesqrt(shoveSq);
            omega += vec3(sdir.y, -sdir.x, 0.0) * VIRTUAL_KICK_SPEED;
        }

        float spd = length(omega);
        if (spd > OMEGA_MAX) omega *= OMEGA_MAX / spd;

        // Clean subnormal flush
        if (dot(omega, omega) < 1e-7) omega = vec3(0.0);
        if (dot(fling, fling) < 1e-7) fling = vec3(0.0);

        // Quaternion integration
        float ang = length(omega);
        if (ang > 1e-4) {
            float halfAng = ang * 0.5;
            vec4 dq = vec4(omega * (sin(halfAng) / ang), cos(halfAng));
            q = normalize(quatMul(dq, q));
        } else if (ang > 1e-7) {
            float halfAng = ang * 0.5;
            float sinc = 0.5 * (1.0 - halfAng * halfAng * 0.1666667);
            vec4 dq = vec4(omega * sinc, 1.0);
            q = normalize(quatMul(dq, q));
        }

        if (px == 16) {
            float timer = slot(16).r;
            timer = max(0.0, timer - (1.0 / COOLDOWN_FRAMES));
            if (timer <= 0.0 && dot(linear.xy, linear.xy) > (FLICK_THRESH * FLICK_THRESH)) {
                timer = 1.0;
            }
            fragColor = vec4(timer, 0.0, 0.0, 1.0);
            return;
        }

        if (px >= 17 && px <= 24) {
            float gIdx = float(px - 17);
            float prevPhase = slot(px).r;

            // Keplerian-ish ratios: inner bodies sweep faster (w ~ r^-1.5).
            float gearRatio;
            if (gIdx == float(GEAR_TERRA)) {
                gearRatio = 6.50;   // r 0.20
            } else if (gIdx == float(GEAR_G1)) {
                gearRatio = 3.40;   // r 0.30
            } else if (gIdx == float(GEAR_G2)) {
                gearRatio = 2.20;   // r 0.40
            } else if (gIdx == float(GEAR_DWARF)) {
                gearRatio = 1.60;   // r 0.48
            } else {
                gearRatio = 1.0;    // S1: spin axis only, position is fixed
            }

            // Spin-driven, ONE shared signal for every gear, so the relative
            // rates never depend on spin direction or view orientation (the
            // snow-globe fix). SPIN_GAIN is the speed knob.
            float mechanicalDrive = length(omega) * SPIN_GAIN * (gearRatio / FPS);

            // Audio drive: RESTORED from the original. Bass/mid/treble each
            // push their own body (and, through the gear phase, its moons).
            float bassVal   = slot(13).r;
            float midVal    = slot(14).r;
            float trebleVal = slot(15).r;

            float audioDrive = 0.0;
            if (gIdx == float(GEAR_DWARF)) {
                audioDrive = pow(bassVal, 1.8) * (0.040 / FPS);
            } else if (gIdx == float(GEAR_G1)) {
                audioDrive = (pow(bassVal, 1.6) * 1.2 + trebleVal * 0.4) * (0.080 / FPS);
            } else if (gIdx == float(GEAR_TERRA)) {
                audioDrive = pow(midVal, 1.6) * (0.065 / FPS);
            } else if (gIdx == float(GEAR_G2)) {
                audioDrive = (pow(trebleVal, 1.6) * 0.9 + midVal * 0.3) * (0.045 / FPS);
            }

            float nextPhase = fract(prevPhase + mechanicalDrive + audioDrive);
            fragColor = vec4(nextPhase, 0.0, 0.0, 1.0);
            return;
        }

        if      (px == 0) fragColor = q;
        else if (px == 1) fragColor = vec4(omega, 1.0);
        else if (px == 2) fragColor = vec4(fling, 1.0);
        else if (px == 3) fragColor = vec4(lastTouch, 0.0, 1.0);
        return;
    }

    // ------------------------------------------------------------ Row 1
    if (py == 1) {
        if (px >= int(N_BANDS)) discard;
        float a = amp(px);
        float x = max(0.0, a - LEAK_K * max(amp(px - 1), amp(px + 1)));
        x = max(0.0, x - H2_K * amp(px - 12) - H3_K * amp(px - 19) - H4_K * amp(px - 24));

        float sum = 0.0;
        for (int k = -6; k <= 6; k++) {
            sum += amp(px + k);
        }
        float floorFactor = mix(FLOOR_K, FLOOR_K * 0.35, float(px) / N_BANDS);
        x = max(0.0, x - floorFactor * (sum / 13.0));

        float db    = 6.0206 * log2(max(x, 1e-9)) + TILT_DB * (float(px) / 12.0 - 4.5);
        float level = clamp((db - DB_MIN) / DB_SPAN, 0.0, 1.0);
        float agc   = max(slot(12).r, (AGC_FLOOR_DB - DB_MIN) / DB_SPAN);
        float target = clamp((level - agc) * (DB_SPAN / DISPLAY_DB) + 1.0, 0.0, 1.0);

        float prevB = bandTexel(px).g;
        float raw   = mix(prevB, target, target > prevB ? BAND_ATTACK : BAND_RELEASE);
        float clean = pow(raw, 1.5);
        fragColor = vec4(clean, clean, level, 1.0);
        return;
    }

    // ------------------------------------------------------------ Row 2
    if (py == 2) {
        if (px < 3) {
            float lvl = slot(13 + px).r;
            vec4 prev = texelFetch(state, ivec2(px, 2), 0);
            float above  = lvl > ARC_GATE ? 1.0 : 0.0;
            float rising = (above > 0.5 && prev.a <= 0.5) ? 1.0 : 0.0;
            float env    = rising > 0.5 ? min(1.0, 0.45 + lvl * 0.75) : prev.g * ARC_DECAY;

            float loud = max(max(slot(13).r, slot(14).r), slot(15).r);
            if (loud < IDLE_GATE) {
                float epoch = floor(time / IDLE_PERIOD);
                float h     = fract(sin(epoch * 91.37) * 43758.5453);
                float pick  = floor(h * 3.0);
                float when  = epoch * IDLE_PERIOD + fract(h * 7.13) * IDLE_PERIOD * 0.8;
                if (float(px) == pick && abs(time - when) < 2.0 / FPS) env = IDLE_LEVEL;
            }
            fragColor = vec4(lvl, env, 0.0, above);
            return;
        }

        if (px == 3) { fragColor = vec4(calcPulsarPos(), STAR_R); return; }
        if (px == 4) { fragColor = vec4(calcDwarfPos(), DWARF_R); return; }
        if (px == 5) { fragColor = vec4(calcG1Pos(), G1_R); return; }
        if (px == 6) { fragColor = vec4(calcG2Pos(), G2_R); return; }
        if (px == 7) { fragColor = vec4(calcTerraPos(), TERRA_R); return; }
        discard;
    }

    // ------------------------------------------------------------ Row 3: Moons
    if (px >= TOTAL_MOONS) discard;

    float mIdx = float(px);
    vec3 j = moonJitter(mIdx);
    float jA = j.x;
    float jB = j.y;
    float jC = j.z;

    vec3 parentPos;
    float ph, mPh, inc, dist, rad;
    vec3 uAxis, vAxis;

    if (mIdx < 5.0) {
        // Ice giant moons (mIdx 0..4)
        parentPos = calcG1Pos();
        ph   = slot(17 + GEAR_G1).r;
        mPh  = fract(ph * (3.8 + 1.2 * mIdx + 0.9 * jB) + jA) * TAU;
        inc  = 0.22 * (mIdx - 2.0) + (jC - 0.5) * 0.30;
        dist = 0.048 + 0.0050 * mIdx;
        rad  = 0.0035 + 0.0009 * mIdx;
        vec3 rn = ringNormalG1();
        uAxis = normalize(cross(rn, vec3(0.0, 1.0, 0.0)));
        vAxis = cross(rn, uAxis) * cos(inc) + rn * sin(inc);
    } else if (mIdx < 9.0) {
        // Red dwarf moons (mIdx 5..8). Placed clear of DWARF_R -- the old
        // dwarf moons sat at 0.054-0.063, i.e. INSIDE a 0.092-radius star.
        float fk = mIdx - 5.0;
        parentPos = calcDwarfPos();
        ph   = slot(17 + GEAR_DWARF).r;
        mPh  = fract(ph * (5.0 + 3.0 * fk + 1.0 * jB) + jA) * TAU;
        inc  = 0.35 * (fk - 1.5) + (jC - 0.5) * 0.50;
        dist = 0.080 + 0.009 * fk;
        rad  = 0.0040 + 0.0010 * fk;
        vec3 rn = hubNormal(float(GEAR_DWARF));
        uAxis = normalize(cross(rn, vec3(0.0, 1.0, 0.0)));
        vAxis = cross(rn, uAxis) * cos(inc) + rn * sin(inc);
    } else if (mIdx < 15.0) {
        // Ringed giant moons (mIdx 9..14)
        float fk = mIdx - 9.0;
        parentPos = calcG2Pos();
        ph   = slot(17 + GEAR_G2).r;
        mPh  = fract(ph * (3.2 + 0.9 * fk + 0.8 * jB) + jA) * TAU;
        inc  = 0.16 * (fk - 2.5) + (jC - 0.5) * 0.28;
        dist = 0.044 + 0.0060 * fk;
        rad  = 0.0032 + 0.0011 * fk;
        vec3 rn = ringNormalG2();
        uAxis = normalize(cross(rn, vec3(0.0, 1.0, 0.0)));
        vAxis = cross(rn, uAxis) * cos(inc) + rn * sin(inc);
    } else {
        // Terra's single moon (mIdx 15)
        parentPos = calcTerraPos();
        ph   = slot(17 + GEAR_TERRA).r;
        mPh  = fract(ph * (6.5 + 1.0 * jB) + jA) * TAU;
        inc  = 0.28 + (jC - 0.5) * 0.50;
        dist = 0.024;
        rad  = 0.0036;
        uAxis = vec3(1.0, 0.0, 0.0);
        vAxis = vec3(0.0, cos(inc), sin(inc));
    }

    vec3 moonWorld = parentPos + (uAxis * cos(mPh) + vAxis * sin(mPh)) * dist;
    fragColor = vec4(moonWorld, rad);
}

// ==================== Pass 2: Image ====================
==image==
uniform sampler2D state;
uniform vec2      resolution;
uniform float     time;
uniform bool      screenLocked;

vec4 loadQuat() {
    return normalize(texelFetch(state, ivec2(0, 0), 0));
}

void testSphere(vec3 ro, vec3 rd, vec3 c, float r, float id,
                inout float minT, inout float hitId, inout vec3 hitC, inout float hitR) {
    vec2 h = intersectSphere(ro - c, rd, r);
    if (h.y > h.x && h.x > 0.0 && h.x < minT) {
        minT  = h.x;
        hitId = id;
        hitC  = c;
        hitR  = r;
    }
}

// Fast point-to-ray test for moons
void testTinySphere(vec3 ro, vec3 rd, vec3 c, float r, float id,
                    inout float minT, inout float hitId, inout vec3 hitC, inout float hitR) {
    vec3 rc = c - ro;
    float t = dot(rc, rd);
    if (t > 0.0 && (t - r) < minT) {
        vec3 perp = rc - rd * t;
        float d2 = dot(perp, perp);
        float r2 = r * r;
        if (d2 <= r2) {
            float surfaceT = t - sqrt(r2 - d2);
            if (surfaceT > 0.0 && surfaceT < minT) {
                minT  = surfaceT;
                hitId = id;
                hitC  = c;
                hitR  = r;
            }
        }
    }
}

void main() {
    vec2 uv = (-resolution.xy + 2.0 * gl_FragCoord.xy) / resolution.y;
    vec3 ro = vec3(0.0, 0.0, -2.4 / BALL_ZOOM);
    vec3 rd = normalize(vec3(uv - SENSOR_POS, 1.8));

    vec4 q = loadQuat();
    mat3 invRot = quatToMat3(vec4(-q.xyz, q.w));
    vec3 roL = invRot * (ro - vec3(SENSOR_POS, 0.0));
    vec3 rdL = invRot * rd;

    vec2 outerHit = intersectSphere(roL, rdL, SPHERE_RADIUS);
    if (outerHit.x <= 0.0) {
        fragColor = vec4(0.0, 0.0, 0.0, 1.0);
        return;
    }

    float bass   = texelFetch(state, ivec2(13, 0), 0).r;
    float mid    = texelFetch(state, ivec2(14, 0), 0).r;
    float treble = texelFetch(state, ivec2(15, 0), 0).r;
    float envMoons = texelFetch(state, ivec2(2, 2), 0).g;

    vec4 bS1    = texelFetch(state, ivec2(3, 2), 0);
    vec4 bDwarf = texelFetch(state, ivec2(4, 2), 0);
    vec4 bG1    = texelFetch(state, ivec2(5, 2), 0);
    vec4 bG2    = texelFetch(state, ivec2(6, 2), 0);
    vec4 bTerra = texelFetch(state, ivec2(7, 2), 0);

    float minT  = 1e5;
    float hitId = 0.0;
    vec3  hitC  = vec3(0.0);
    float hitR  = 1.0;

    // Central pulsar (fixed) + red dwarf orbiter
    testSphere(roL, rdL, bS1.xyz, bS1.w, 1.0, minT, hitId, hitC, hitR);
    testSphere(roL, rdL, bDwarf.xyz, bDwarf.w, 3.0, minT, hitId, hitC, hitR);

    // G1 Subsystem
    testSphere(roL, rdL, bG1.xyz, bG1.w, 2.0, minT, hitId, hitC, hitR);
    if (intersectSphere(roL - bG1.xyz, rdL, G1_BOUND_R).y > 0.0) {
        for (int m = 0; m < N_MOONS_G1; m++) {
            vec4 moon = texelFetch(state, ivec2(m, 3), 0);
            testTinySphere(roL, rdL, moon.xyz, moon.w, 10.0 + float(m), minT, hitId, hitC, hitR);
        }
    }

    // Dwarf Moons (mIdx 5..8)
    if (intersectSphere(roL - bDwarf.xyz, rdL, DWARF_BOUND_R).y > 0.0) {
        for (int m = 5; m < 9; m++) {
            vec4 moon = texelFetch(state, ivec2(m, 3), 0);
            testTinySphere(roL, rdL, moon.xyz, moon.w, 10.0 + float(m), minT, hitId, hitC, hitR);
        }
    }

    // G2 Subsystem (moons mIdx 9..14)
    testSphere(roL, rdL, bG2.xyz, bG2.w, 4.0, minT, hitId, hitC, hitR);
    if (intersectSphere(roL - bG2.xyz, rdL, G2_BOUND_R).y > 0.0) {
        for (int m = 9; m < 15; m++) {
            vec4 moon = texelFetch(state, ivec2(m, 3), 0);
            testTinySphere(roL, rdL, moon.xyz, moon.w, 10.0 + float(m), minT, hitId, hitC, hitR);
        }
    }

    // Terra Subsystem (moon mIdx 15)
    testSphere(roL, rdL, bTerra.xyz, bTerra.w, 5.0, minT, hitId, hitC, hitR);
    if (intersectSphere(roL - bTerra.xyz, rdL, TERRA_BOUND_R).y > 0.0) {
        vec4 moon = texelFetch(state, ivec2(15, 3), 0);
        testTinySphere(roL, rdL, moon.xyz, moon.w, 25.0, minT, hitId, hitC, hitR);
    }

    // Planetary Rings
    float ringHitG1 = 1e5, ringOpacG1 = 0.0;
    vec3  ringShadeG1 = vec3(0.0);
    vec3  rn1 = ringNormalG1();
    float dnr1 = dot(rdL, rn1);
    if (abs(dnr1) > 1e-3) {
        float tr = dot(bG1.xyz - roL, rn1) / dnr1;
        if (tr > 0.0 && tr < minT) {
            vec3 rp = roL + rdL * tr - bG1.xyz;
            float rDist = length(rp);
            float dens = ringDensityG1(rDist, bG1.w);
            if (dens > 0.0) {
                vec3 wp = bG1.xyz + rp;
                float sh1 = shadowSphere(wp, normalize(bS1.xyz - wp), bG1.xyz, bG1.w);
                float sh2 = shadowSphere(wp, normalize(bDwarf.xyz - wp), bG1.xyz, bG1.w);
                ringShadeG1 = (vec3(0.55, 0.85, 1.4) * (sh1 * 1.1 + 0.1) + vec3(0.9, 0.15, 0.02) * (sh2 * 0.5)) * (0.65 + 0.45 * dens);
                ringOpacG1 = 1.0 - exp(-dens * 2.1 / max(abs(dnr1), 0.15));
                ringHitG1  = tr;
            }
        }
    }

    float ringHitG2 = 1e5, ringOpacG2 = 0.0;
    vec3  ringShadeG2 = vec3(0.0);
    vec3  rn2 = ringNormalG2();
    float dnr2 = dot(rdL, rn2);
    if (abs(dnr2) > 1e-3) {
        float tr = dot(bG2.xyz - roL, rn2) / dnr2;
        if (tr > 0.0 && tr < minT) {
            vec3 rp = roL + rdL * tr - bG2.xyz;
            float rDist = length(rp);
            float dens = ringDensityG2(rDist, bG2.w);
            if (dens > 0.0) {
                vec3 wp = bG2.xyz + rp;
                float sh1 = shadowSphere(wp, normalize(bS1.xyz - wp), bG2.xyz, bG2.w);
                float sh2 = shadowSphere(wp, normalize(bDwarf.xyz - wp), bG2.xyz, bG2.w);
                ringShadeG2 = (vec3(0.35, 0.70, 1.2) * (sh1 * 0.85 + 0.15) + vec3(1.2, 0.22, 0.04) * (sh2 * 1.1)) * (0.55 + 0.45 * dens);
                ringOpacG2 = 1.0 - exp(-dens * 1.8 / max(abs(dnr2), 0.15));
                ringHitG2  = tr;
            }
        }
    }

    // Surface Shading
    vec3 col = vec3(0.0);
    if (hitId > 0.5) {
        vec3 hitP = roL + rdL * minT;
        vec3 n = (hitP - hitC) / hitR;

        if (hitId == 1.0) {
            col = neutronStarSurface(hitP - hitC, rdL, hitR, bass);
        } else if (hitId == 3.0) {
            col = redDwarfSurface(hitP - hitC, rdL, hitR, mid, time);
        } else {
            vec3 alb; float bFreq, spin, isGiant = 0.0;

            if (hitId == 2.0) {
                alb = vec3(0.88, 0.74, 0.46); bFreq = 18.0; spin = 0.08; isGiant = 1.0;
            } else if (hitId == 4.0) {
                alb = vec3(0.38, 0.68, 0.92); bFreq = 12.0; spin = 0.06; isGiant = 1.0;
            } else if (hitId == 5.0) {
                alb = vec3(0.48, 0.64, 0.58); bFreq = 6.0;  spin = 0.04;
            } else {
                float mIdx = hitId - 10.0;
                alb = mix(vec3(0.72, 0.70, 0.68), vec3(0.85, 0.78, 0.65), fract(mIdx * 0.35));
                bFreq = 4.0; spin = 0.03;
            }

            col = shadePulsarBinary(n, rdL, hitP, bS1.xyz, bDwarf.xyz, alb, bFreq,
                                    spin, envMoons, time, isGiant, bass);
        }
    }

    if (ringOpacG1 > 0.001 && ringHitG1 < minT) col = mix(col, ringShadeG1, ringOpacG1);
    if (ringOpacG2 > 0.001 && ringHitG2 < minT) col = mix(col, ringShadeG2, ringOpacG2);

    // Pulsar Jets
    vec3 toPulsar = bS1.xyz - roL;
    float tPulsar = dot(toPulsar, rdL);
    if (tPulsar > 0.0) {
        float dPulsarSq = dot(toPulsar, toPulsar) - tPulsar * tPulsar;
        if (dPulsarSq < 0.08) {
            col += calcPulsarJets(roL, rdL, bS1.xyz, getPulsarAxis(), bass, treble, minT, time);
        }
    }

    // Pulsar Pin-Light Core & 4-Point Spike
    if (tPulsar > 0.0 && tPulsar < minT) {
        vec3 perp = toPulsar - rdL * tPulsar;
        float dRay = length(perp);
        float k = bS1.w / max(dRay, bS1.w * 0.70);
        float pinGlow = pow(k, 5.5) * (0.25 + bass * 0.75);

        vec3 e1 = normalize(cross(rdL, vec3(0.0, 1.0, 0.0)));
        vec3 e2 = cross(rdL, e1);
        float ang = atan(dot(perp, e2), dot(perp, e1));
        float spike = pow(abs(sin(ang * 2.0)), 12.0) * pow(k, 2.2) * 0.35;

        col += vec3(0.40, 0.80, 1.5) * (pinGlow + spike);
    }

    // Red Dwarf Glare
    vec3 toDwarf = bDwarf.xyz - roL;
    float tDwarf = dot(toDwarf, rdL);
    if (tDwarf > 0.0 && tDwarf < minT) {
        float dRay = length(toDwarf - rdL * tDwarf);
        float k = bDwarf.w / max(dRay, bDwarf.w * 0.88);
        col += vec3(1.1, 0.22, 0.06) * pow(k, 3.5) * (0.35 + mid * 1.1);
    }

    col = col / (1.0 + col * 0.32);

    if (screenLocked) {
        col *= smoothstep(0.08, 0.16, length(uv - SENSOR_POS));
    }

    fragColor = vec4(col, 1.0);
}

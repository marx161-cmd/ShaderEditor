#!/usr/bin/env python3
"""Validate a ShaderEditor shader with glslangValidator, assembling every
section the way the app does (ShaderSourcePreparer): IMAGE_ lines commented
out, compute sections get #version 320 es + COMPUTE + <name>_UNIT macros +
highp defaults after the last #extension, fragment sections get the sampler
precision header. Usage: se_validate.py shader.glsl
glslang is more lenient than the Pixel's PowerVR compiler (it accepted
'16384x1' in an unused #define), so a pass here is necessary, not sufficient."""
import os, re, subprocess, sys, tempfile

src = open(sys.argv[1]).read()
img = re.compile(r"^#define[ \t]+IMAGE_([A-Za-z_]\w*)[ \t]+(\w+)[ \t]+(?:\d+[xX]\d+(?:[xX]\d+)?|full)(?:[ \t]+(swap))?[ \t]*$", re.M)
units, u = [], 0
for m in img.finditer(src):
    units.append(f"#define {m.group(1)}_UNIT {u}"); u += 1
    if m.group(3):
        units.append(f"#define {m.group(1)}_out_UNIT {u}"); u += 1
src = img.sub(lambda m: "// " + m.group(0)[1:], src)
lines = src.split("\n")
delims = [i for i, l in enumerate(lines) if re.match(r"^\s*==.*==\s*$", l)]
if not delims:  # single-pass: the whole file is one fragment shader
    lines.insert(1, "==image==")
    delims = [1]
pre = "\n".join(lines[:delims[0]]) + "\n"
types_c = ("image2D", "image3D", "uimage2D", "uimage3D", "iimage2D", "iimage3D",
           "sampler3D", "usampler2D", "usampler3D", "isampler2D", "isampler3D")
ch = "precision highp float;\nprecision highp int;\n" + "".join(f"precision highp {t};\n" for t in types_c)
fh = "".join(f"precision highp {t};\n" for t in types_c[6:])
ext = re.compile(r"^[ \t]*#extension[^\n]*\n", re.M)
bad = 0
for k, d in enumerate(delims):
    end = delims[k + 1] if k + 1 < len(delims) else len(lines)
    s = pre + "\n".join(lines[d + 1:end]) + "\n"
    comp = re.match(r"^\s*==\s*compute\s", lines[d]) is not None
    v, rest = s.split("\n", 1)
    exts = list(ext.finditer(rest))
    at = exts[-1].end() if exts else 0
    if comp:
        out = "#version 320 es\n#define COMPUTE 1\n" + "\n".join(units) + "\n#define SHADER_EDITOR 1\n" + rest[:at] + ch + rest[at:]
    else:
        out = v + "\n#define SHADER_EDITOR 1\n" + rest[:at] + (fh if units else "") + rest[at:]
    with tempfile.NamedTemporaryFile("w", suffix=".comp" if comp else ".frag", delete=False) as f:
        f.write(out)
        path = f.name
    r = subprocess.run(["glslangValidator", path], capture_output=True, text=True)
    msg = "\n".join(l for l in r.stdout.splitlines() if l.strip() and not l.startswith(path))
    print(("OK   " if r.returncode == 0 else "FAIL ") + lines[d].strip() + ("" if r.returncode == 0 else "\n" + msg))
    bad += r.returncode != 0
    os.unlink(path)
sys.exit(1 if bad else 0)

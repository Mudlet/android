#!/usr/bin/env python3
"""Makes the app's launcher icon layers, as Android vector drawables, from Mudlet's logo.

Usage: scripts/launcher-icons.py <Mudlet/Mudlet's mudlet.svg> [app/src]

Writes, for each release channel (app/build.gradle.kts adds src/<channel>/res), the adaptive icon's foreground layer
and its monochrome layer (Android 13's themed icons): src/stable/res/drawable/ic_launcher_{foreground,monochrome}.xml
and the same under src/ptb. The PTB's arch looks out on night, as on Mudlet's desktop PTB icon. docs/launcher-icon.md
says where the logo comes from and under which licence.

The SVG is converted, not traced: paths, rectangles, transforms, solid and gradient fills and strokes, opacity and
clip paths carry over, and arcs become cubic Béziers. A vector drawable has no filters or masks, so an element under
a mask, or under a Gaussian blur too wide to leave out at icon size, is dropped; those are the logo's soft shading and
the sun's glow. The logo is scaled to fit the adaptive icon's safe zone, the 66dp circle in its 108dp square that no
launcher's mask cuts into.
"""

import math
import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

SVG = "{http://www.w3.org/2000/svg}"
NUMBER = r"[-+]?(?:\d+\.?\d*|\.\d+)(?:[eE][-+]?\d+)?"
XLINK_HREF = "{http://www.w3.org/1999/xlink}href"
LAYER = 108.0  # an adaptive icon layer's size, in dp
SAFE_RADIUS = 33.0  # the safe zone: the 66dp circle at the layer's centre
# A blur narrower than this, in the logo's units (a third of a dp each in the icon), is left out and its element kept
MAX_BLUR = 1.0


# --- affine transforms, as (a, b, c, d, e, f) like SVG's matrix() ---

IDENTITY = (1.0, 0.0, 0.0, 1.0, 0.0, 0.0)


def multiply(m, n):
    """The transform that applies n, then m: SVG's "m n"."""
    a, b, c, d, e, f = m
    a2, b2, c2, d2, e2, f2 = n
    return (a * a2 + c * b2, b * a2 + d * b2, a * c2 + c * d2, b * c2 + d * d2,
            a * e2 + c * f2 + e, b * e2 + d * f2 + f)


def apply(m, p):
    a, b, c, d, e, f = m
    return (a * p[0] + c * p[1] + e, b * p[0] + d * p[1] + f)


def scale_of(m):
    """The transform's mean linear scale, for stroke widths and blur radii."""
    return math.sqrt(abs(m[0] * m[3] - m[1] * m[2]))


def parse_transform(text):
    m = IDENTITY
    for name, args in re.findall(r"(\w+)\s*\(([^)]*)\)", text or ""):
        v = [float(x) for x in re.findall(NUMBER, args)]
        if name == "matrix":
            t = tuple(v)
        elif name == "translate":
            t = (1, 0, 0, 1, v[0], v[1] if len(v) > 1 else 0)
        elif name == "scale":
            t = (v[0], 0, 0, v[1] if len(v) > 1 else v[0], 0, 0)
        elif name == "rotate":
            r = math.radians(v[0])
            t = (math.cos(r), math.sin(r), -math.sin(r), math.cos(r), 0, 0)
            if len(v) == 3:
                t = multiply(multiply((1, 0, 0, 1, v[1], v[2]), t), (1, 0, 0, 1, -v[1], -v[2]))
        else:
            raise ValueError(f"unsupported transform {name}")
        m = multiply(m, t)
    return m


# --- path data: parsed to absolute M, L, C, Q and Z segments ---

def tokens(d):
    for cmd, args in re.findall(r"([MmLlHhVvCcSsQqTtAaZz])([^MmLlHhVvCcSsQqTtAaZz]*)", d):
        # an arc's flags may run together with the numbers after them: "a1 1 0 01.5.5"
        nums = arc_numbers(args) if cmd in "Aa" else re.findall(NUMBER, args)
        yield cmd, [float(n) for n in nums]


def arc_numbers(args):
    out, s, i = [], args.replace(",", " "), 0
    number = re.compile(rf"\s*({NUMBER})")
    flag = re.compile(r"\s*([01])")
    while s[i:].strip():
        pattern = flag if len(out) % 7 in (3, 4) else number
        m = pattern.match(s, i)
        out.append(m.group(1))
        i = m.end()
    return out


def arc_to_cubics(p0, rx, ry, phi, large, sweep, p):
    """SVG's endpoint arc as cubic Béziers (SVG 1.1, appendix F.6)."""
    if p0 == p:
        return []
    if rx == 0 or ry == 0:
        return [(p0, p, p)]
    rx, ry = abs(rx), abs(ry)
    cphi, sphi = math.cos(math.radians(phi)), math.sin(math.radians(phi))
    dx, dy = (p0[0] - p[0]) / 2, (p0[1] - p[1]) / 2
    x1, y1 = cphi * dx + sphi * dy, -sphi * dx + cphi * dy
    lam = (x1 / rx) ** 2 + (y1 / ry) ** 2
    if lam > 1:
        rx, ry = rx * math.sqrt(lam), ry * math.sqrt(lam)
    num = rx * rx * ry * ry - rx * rx * y1 * y1 - ry * ry * x1 * x1
    den = rx * rx * y1 * y1 + ry * ry * x1 * x1
    k = math.sqrt(max(0.0, num / den)) * (-1 if large == sweep else 1)
    cx1, cy1 = k * rx * y1 / ry, -k * ry * x1 / rx
    cx = cphi * cx1 - sphi * cy1 + (p0[0] + p[0]) / 2
    cy = sphi * cx1 + cphi * cy1 + (p0[1] + p[1]) / 2

    def angle(u, v):
        return math.atan2(u[0] * v[1] - u[1] * v[0], u[0] * v[0] + u[1] * v[1])

    t1 = angle((1, 0), ((x1 - cx1) / rx, (y1 - cy1) / ry))
    dt = angle(((x1 - cx1) / rx, (y1 - cy1) / ry), ((-x1 - cx1) / rx, (-y1 - cy1) / ry))
    if not sweep and dt > 0:
        dt -= 2 * math.pi
    elif sweep and dt < 0:
        dt += 2 * math.pi
    n = max(1, math.ceil(abs(dt) / (math.pi / 2)))
    step = dt / n
    alpha = 4 / 3 * math.tan(step / 4)

    def point(t):
        x, y = rx * math.cos(t), ry * math.sin(t)
        return (cphi * x - sphi * y + cx, sphi * x + cphi * y + cy)

    def deriv(t):
        x, y = -rx * math.sin(t), ry * math.cos(t)
        return (cphi * x - sphi * y, sphi * x + cphi * y)

    out, t = [], t1
    for _ in range(n):
        a, b = point(t), point(t + step)
        da, db = deriv(t), deriv(t + step)
        out.append(((a[0] + alpha * da[0], a[1] + alpha * da[1]), (b[0] - alpha * db[0], b[1] - alpha * db[1]), b))
        t += step
    out[-1] = (out[-1][0], out[-1][1], p)
    return out


def parse_path(d):
    """Absolute segments: ("M", p), ("L", p), ("C", c1, c2, p), ("Q", c, p), ("Z",)."""
    segs, cur, start, last_ctrl, last_cmd = [], (0.0, 0.0), (0.0, 0.0), None, ""
    for cmd, v in tokens(d):
        rel = cmd.islower()
        up = cmd.upper()
        if up == "Z":
            segs.append(("Z",))
            cur, last_cmd = start, "Z"
            continue
        size = {"M": 2, "L": 2, "H": 1, "V": 1, "C": 6, "S": 4, "Q": 4, "T": 2, "A": 7}[up]
        for i in range(0, len(v), size):
            a = v[i:i + size]
            ox, oy = cur if rel else (0.0, 0.0)

            def pt(j):
                return (a[j] + ox, a[j + 1] + oy)

            if up == "M":
                cur = start = pt(0)
                segs.append(("M", cur))
                up = "L"  # further pairs are lines
                last_cmd = "M"
                continue
            if up == "L":
                cur = pt(0)
                segs.append(("L", cur))
            elif up == "H":
                cur = (a[0] + ox, cur[1])
                segs.append(("L", cur))
            elif up == "V":
                cur = (cur[0], a[0] + (cur[1] if rel else 0.0))
                segs.append(("L", cur))
            elif up == "C":
                c1, c2, cur = pt(0), pt(2), pt(4)
                segs.append(("C", c1, c2, cur))
                last_ctrl = c2
            elif up == "S":
                c1 = reflect(last_ctrl, cur) if last_cmd in "CS" else cur
                c2, cur = pt(0), pt(2)
                segs.append(("C", c1, c2, cur))
                last_ctrl = c2
            elif up == "Q":
                c, cur = pt(0), pt(2)
                segs.append(("Q", c, cur))
                last_ctrl = c
            elif up == "T":
                c = reflect(last_ctrl, cur) if last_cmd in "QT" else cur
                cur = pt(0)
                segs.append(("Q", c, cur))
                last_ctrl = c
            elif up == "A":
                end = pt(5)
                for c1, c2, p in arc_to_cubics(cur, a[0], a[1], a[2], int(a[3]), int(a[4]), end):
                    segs.append(("C", c1, c2, p))
                cur = end
            last_cmd = up
    return segs


def reflect(c, p):
    return (2 * p[0] - c[0], 2 * p[1] - c[1])


def rect_path(e):
    x, y = float(e.get("x", 0)), float(e.get("y", 0))
    w, h = float(e.get("width")), float(e.get("height"))
    rx = float(e.get("rx", e.get("ry", 0)))
    ry = float(e.get("ry", rx))
    rx, ry = min(rx, w / 2), min(ry, h / 2)
    if not rx:
        return f"M{x} {y}h{w}v{h}h{-w}z"
    return (f"M{x + rx} {y}h{w - 2 * rx}a{rx} {ry} 0 0 1 {rx} {ry}v{h - 2 * ry}a{rx} {ry} 0 0 1 {-rx} {ry}"
            f"h{-(w - 2 * rx)}a{rx} {ry} 0 0 1 {-rx} {-ry}v{-(h - 2 * ry)}a{rx} {ry} 0 0 1 {rx} {-ry}z")


def shape_path(e):
    tag = e.tag.replace(SVG, "")
    if tag == "path":
        return e.get("d")
    if tag == "rect":
        return rect_path(e)
    if tag in ("circle", "ellipse"):
        cx, cy = float(e.get("cx", 0)), float(e.get("cy", 0))
        rx = float(e.get("r", e.get("rx", 0)))
        ry = float(e.get("r", e.get("ry", 0)))
        return f"M{cx - rx} {cy}a{rx} {ry} 0 1 0 {2 * rx} 0a{rx} {ry} 0 1 0 {-2 * rx} 0z"
    return None


def transform_segments(segs, m):
    out = []
    for s in segs:
        out.append((s[0],) + tuple(apply(m, p) for p in s[1:]))
    return out


def fmt(x):
    s = f"{x:.2f}".rstrip("0").rstrip(".")
    return "0" if s in ("-0", "") else s


def path_data(segs):
    return "".join(s[0] + " ".join(f"{fmt(p[0])},{fmt(p[1])}" for p in s[1:]) for s in segs)


def points(segs):
    for s in segs:
        yield from s[1:]


# --- colours and styles ---

def parse_color(text):
    """#rrggbb, #rgb or a few names, as (r, g, b); None for none."""
    text = text.strip()
    if text == "none":
        return None
    names = {"black": "#000000", "white": "#ffffff"}
    text = names.get(text, text)
    if re.fullmatch(r"#[0-9a-fA-F]{3}", text):
        text = "#" + "".join(ch * 2 for ch in text[1:])
    if not re.fullmatch(r"#[0-9a-fA-F]{6}", text):
        raise ValueError(f"unsupported colour {text}")
    return tuple(int(text[i:i + 2], 16) for i in (1, 3, 5))


def argb(rgb, alpha=1.0):
    a = max(0, min(255, round(alpha * 255)))
    return "#" + (f"{a:02x}" if a != 255 else "") + "".join(f"{c:02x}" for c in rgb)


INHERITED = ("fill", "stroke", "stroke-width", "fill-rule", "stroke-linecap", "stroke-linejoin", "fill-opacity",
             "stroke-opacity")


def style_of(e, parent):
    style = dict(parent)
    attrs = dict(e.attrib)
    for decl in (e.get("style") or "").split(";"):
        if ":" in decl:
            k, v = decl.split(":", 1)
            attrs[k.strip()] = v.strip()
    for k in INHERITED:
        if k in attrs:
            style[k] = attrs[k]
    style["opacity"] = parent.get("opacity", 1.0) * float(attrs.get("opacity", 1))
    return style


class Paint:
    """A solid colour or a gradient, in the output's coordinates."""

    def __init__(self, kind, color=None, stops=None, geometry=None, spread="pad"):
        self.kind, self.color, self.stops, self.geometry, self.spread = kind, color, stops or [], geometry, spread

    def recolored(self, fn):
        if self.kind == "solid":
            return Paint("solid", fn(self.color))
        return Paint(self.kind, stops=[(o, fn(c), a) for o, c, a in self.stops], geometry=self.geometry,
                     spread=self.spread)


class Shape:
    def __init__(self, segs, fill, stroke=None, stroke_width=0.0, fill_alpha=1.0, stroke_alpha=1.0,
                 fill_rule="nonzero", cap="butt", join="miter", clips=()):
        self.segs, self.fill, self.stroke, self.stroke_width = segs, fill, stroke, stroke_width
        self.fill_alpha, self.stroke_alpha, self.fill_rule = fill_alpha, stroke_alpha, fill_rule
        self.cap, self.join, self.clips = cap, join, list(clips)


class Converter:
    def __init__(self, root, ctm=IDENTITY):
        self.ids = {e.get("id"): e for e in root.iter() if e.get("id")}
        self.shapes, self.dropped = [], []
        self.unit = scale_of(ctm)  # the output's units per logo unit
        self.walk(root, ctm, {"fill": "#000000"}, [])

    def gradient_stops(self, g):
        seen = g
        while not seen.findall(SVG + "stop") and seen.get(XLINK_HREF, seen.get("href")):
            seen = self.ids[seen.get(XLINK_HREF, seen.get("href"))[1:]]
        stops = []
        for s in seen.findall(SVG + "stop"):
            st = dict(s.attrib)
            for decl in (s.get("style") or "").split(";"):
                if ":" in decl:
                    k, v = decl.split(":", 1)
                    st[k.strip()] = v.strip()
            offset = s.get("offset", "0")
            offset = float(offset[:-1]) / 100 if offset.endswith("%") else float(offset)
            stops.append((offset, parse_color(st.get("stop-color", "#000000")), float(st.get("stop-opacity", 1))))
        return stops

    def gradient_attr(self, g, name, default):
        while True:
            if g.get(name) is not None:
                return g.get(name)
            href = g.get(XLINK_HREF, g.get("href"))
            if not href:
                return default
            g = self.ids[href[1:]]

    def paint(self, value, ctm):
        if value is None or value == "none":
            return None
        m = re.fullmatch(r"url\(#([^)]+)\)", value.strip())
        if not m:
            return Paint("solid", parse_color(value))
        g = self.ids[m.group(1)]
        if self.gradient_attr(g, "gradientUnits", "objectBoundingBox") != "userSpaceOnUse":
            raise ValueError(f"gradient {m.group(1)}: only userSpaceOnUse is supported")
        gm = multiply(ctm, parse_transform(self.gradient_attr(g, "gradientTransform", "")))
        spread = self.gradient_attr(g, "spreadMethod", "pad")
        stops = self.gradient_stops(g)
        if g.tag == SVG + "linearGradient":
            p1 = apply(gm, (float(self.gradient_attr(g, "x1", 0)), float(self.gradient_attr(g, "y1", 0))))
            p2 = apply(gm, (float(self.gradient_attr(g, "x2", 1)), float(self.gradient_attr(g, "y2", 0))))
            return Paint("linear", stops=stops, geometry=(p1, p2), spread=spread)
        # a vector drawable's radial gradient is a circle: a skewed or stretched one keeps its centre and mean radius
        c = apply(gm, (float(self.gradient_attr(g, "cx", 0.5)), float(self.gradient_attr(g, "cy", 0.5))))
        r = float(self.gradient_attr(g, "r", 0.5)) * scale_of(gm)
        return Paint("radial", stops=stops, geometry=(c, r), spread=spread)

    def blur_width(self, e, ctm):
        f = self.ids[re.fullmatch(r"url\(#([^)]+)\)", e.get("filter")).group(1)]
        blurs = [float(b.get("stdDeviation", 0)) for b in f.iter(SVG + "feGaussianBlur")]
        if len(blurs) != len(list(f)):
            return math.inf  # a filter that does more than blur
        return max(blurs, default=0) * scale_of(ctm) / self.unit

    def clip_segments(self, e, ctm):
        clip = self.ids[re.fullmatch(r"url\(#([^)]+)\)", e.get("clip-path")).group(1)]
        if clip.get("clipPathUnits", "userSpaceOnUse") != "userSpaceOnUse":
            raise ValueError("only userSpaceOnUse clip paths are supported")
        cm = multiply(ctm, parse_transform(clip.get("transform")))
        segs = []
        for child in clip:
            d = shape_path(child)
            if d:
                segs += transform_segments(parse_path(d), multiply(cm, parse_transform(child.get("transform"))))
        return segs

    def walk(self, e, ctm, style, clips):
        tag = e.tag.replace(SVG, "")
        if tag in ("defs", "linearGradient", "radialGradient", "clipPath", "mask", "filter", "metadata", "title",
                   "desc", "style") or tag.startswith("{"):
            return
        ctm = multiply(ctm, parse_transform(e.get("transform")))
        if e.get("mask"):
            self.dropped.append(f"{tag} under a mask")
            return
        if e.get("filter") and self.blur_width(e, ctm) > MAX_BLUR:
            self.dropped.append(f"{tag} under a blur of {self.blur_width(e, ctm):.2f}")
            return
        if e.get("display") == "none" or e.get("visibility") == "hidden":
            return
        style = style_of(e, style)
        if e.get("clip-path"):
            clips = clips + [self.clip_segments(e, ctm)]
        if tag in ("svg", "g"):
            for child in e:
                self.walk(child, ctm, style, clips)
            return
        d = shape_path(e)
        if d is None:
            raise ValueError(f"unsupported element {tag}")
        segs = transform_segments(parse_path(d), ctm)
        opacity = style["opacity"]
        self.shapes.append(Shape(
            segs, self.paint(style.get("fill"), ctm), self.paint(style.get("stroke"), ctm),
            float(style.get("stroke-width", 1)) * scale_of(ctm),
            opacity * float(style.get("fill-opacity", 1)), opacity * float(style.get("stroke-opacity", 1)),
            style.get("fill-rule", "nonzero"), style.get("stroke-linecap", "butt"),
            style.get("stroke-linejoin", "miter"), clips))


# --- the safe zone ---

def fit_to_safe_zone(shapes):
    """The transform that centres the shapes in the layer, as large as the safe zone holds every point of them."""
    pts = [p for s in shapes for p in points(s.segs)]
    xs, ys = [p[0] for p in pts], [p[1] for p in pts]
    cx, cy = (min(xs) + max(xs)) / 2, (min(ys) + max(ys)) / 2
    s = SAFE_RADIUS / max(math.hypot(x - cx, y - cy) for x, y in pts)
    return (s, 0, 0, s, LAYER / 2 - s * cx, LAYER / 2 - s * cy)


# --- writing vector drawables ---

HEADER = """<?xml version="1.0" encoding="utf-8"?>
<!-- Made by scripts/launcher-icons.py from Mudlet's logo: do not edit by hand. docs/launcher-icon.md says where the logo
     comes from and under which licence. -->
"""


def gradient_xml(paint, alpha, indent):
    pad = " " * indent
    attrs = {"linear": lambda g: (f'android:type="linear" android:startX="{fmt(g[0][0])}" '
                                  f'android:startY="{fmt(g[0][1])}" android:endX="{fmt(g[1][0])}" '
                                  f'android:endY="{fmt(g[1][1])}"'),
             "radial": lambda g: (f'android:type="radial" android:centerX="{fmt(g[0][0])}" '
                                  f'android:centerY="{fmt(g[0][1])}" android:gradientRadius="{fmt(g[1])}"')}
    tile = {"pad": "clamp", "reflect": "mirror", "repeat": "repeat"}[paint.spread]
    lines = [f'{pad}<gradient {attrs[paint.kind](paint.geometry)} android:tileMode="{tile}">']
    for offset, color, a in paint.stops:
        lines.append(f'{pad}    <item android:offset="{fmt(offset)}" android:color="{argb(color, a * alpha)}" />')
    lines.append(f"{pad}</gradient>")
    return lines


def path_xml(shape, indent):
    pad = " " * indent
    attrs = [f'android:pathData="{path_data(shape.segs)}"']
    children = []
    for name, paint, alpha in (("fillColor", shape.fill, shape.fill_alpha), ("strokeColor", shape.stroke,
                                                                             shape.stroke_alpha)):
        if paint is None:
            continue
        if paint.kind == "solid":
            attrs.append(f'android:{name}="{argb(paint.color)}"')
            if alpha < 1:
                attrs.append(f'android:{"fillAlpha" if name == "fillColor" else "strokeAlpha"}="{fmt(alpha)}"')
        else:
            # a gradient's alpha goes into its stops' colours
            children += [f'{pad}    <aapt:attr name="android:{name}">'] + gradient_xml(paint, alpha, indent + 8) + \
                [f"{pad}    </aapt:attr>"]
    if shape.stroke is not None:
        attrs.append(f'android:strokeWidth="{fmt(shape.stroke_width)}"')
        if shape.cap != "butt":
            attrs.append(f'android:strokeLineCap="{shape.cap}"')
        if shape.join != "miter":
            attrs.append(f'android:strokeLineJoin="{shape.join}"')
    if shape.fill is not None and shape.fill_rule == "evenodd":
        attrs.append('android:fillType="evenOdd"')
    head = f"{pad}<path\n" + "\n".join(f"{pad}    {a}" for a in attrs)
    if not children:
        return [head + " />"]
    return [head + ">"] + children + [f"{pad}</path>"]


def vector_xml(shapes):
    uses_aapt = any(p is not None and p.kind != "solid" for s in shapes for p in (s.fill, s.stroke))
    ns = ['xmlns:android="http://schemas.android.com/apk/res/android"']
    if uses_aapt:
        ns.append('xmlns:aapt="http://schemas.android.com/aapt"')
    ns.append('xmlns:tools="http://schemas.android.com/tools"')
    # The launcher draws the icon once, at its size, and caches it: the long paths lint warns of cost nothing there
    lines = [HEADER + "<vector " + "\n    ".join(ns), f'    android:width="{fmt(LAYER)}dp"',
             f'    android:height="{fmt(LAYER)}dp"', f'    android:viewportWidth="{fmt(LAYER)}"',
             f'    android:viewportHeight="{fmt(LAYER)}"', '    tools:ignore="VectorPath">']
    for s in shapes:
        if s.clips:
            lines.append("    <group>")
            for c in s.clips:
                lines.append(f'        <clip-path android:pathData="{path_data(c)}" />')
            lines += path_xml(s, 8)
            lines.append("    </group>")
        else:
            lines += path_xml(s, 4)
    lines.append("</vector>")
    return "\n".join(lines) + "\n"


# --- the two channels ---

def is_stone(shape):
    """The arch's stones and pillars: the logo's sandstone colours, as against the sky, the hills and the shading."""
    paint = shape.fill
    if paint is None or shape.fill_alpha < 1:
        return False
    colors = [paint.color] if paint.kind == "solid" else [c for _, c, a in paint.stops if a > 0.5]
    # sandstone: warm (red above blue) and light
    return bool(colors) and all(r > b + 20 and r > 150 for r, g, b in colors)


def night(rgb):
    """The PTB's palette: the logo's daylight sky and green hills by moonlight, as on Mudlet's desktop PTB icon."""
    r, g, b = rgb
    if g > r and g >= b:  # the green hills: blue-grey, as dark as they were
        lum = 0.3 * r + 0.59 * g + 0.11 * b
        return tuple(int(min(255, lum * k)) for k in (0.45, 0.5, 0.62))
    if b > r:  # the blue sky: night blue
        return tuple(int(c * k) for c, k in zip(rgb, (0.12, 0.16, 0.42)))
    return rgb


def scene_bounds(shapes):
    """The sky's box: the first shape the logo draws is the sky inside the arch."""
    pts = list(points(shapes[0].segs))
    return min(p[0] for p in pts), min(p[1] for p in pts), max(p[0] for p in pts), max(p[1] for p in pts)


def disc(cx, cy, r):
    return parse_path(f"M{cx - r} {cy}a{r} {r} 0 1 0 {2 * r} 0a{r} {r} 0 1 0 {-2 * r} 0z")


def moon(shapes, rgb):
    """A moon high in the PTB's night sky, and its glow, which the sky clips: [glow, moon]."""
    x0, y0, x1, y1 = scene_bounds(shapes)
    cx, cy, r = x0 + (x1 - x0) * 0.62, y0 + (y1 - y0) * 0.30, (x1 - x0) * 0.11
    glow = Paint("radial", stops=[(0, rgb, 0.55), (0.45, rgb, 0.2), (1, rgb, 0)], geometry=((cx, cy), r * 2.6))
    return [Shape(disc(cx, cy, 2.6 * r), glow, clips=[shapes[0].segs]),
            Shape(disc(cx, cy, r), Paint("solid", rgb))]


def silhouette(shapes):
    """The monochrome layer: the stones in one opaque colour, which the launcher tints."""
    out = []
    for s in shapes:
        if is_stone(s):
            out.append(Shape(s.segs, Paint("solid", (0, 0, 0)), fill_rule=s.fill_rule, clips=s.clips))
    return out


def main():
    if len(sys.argv) not in (2, 3):
        sys.exit(__doc__.strip().splitlines()[2])
    root = ET.parse(sys.argv[1]).getroot()
    out = Path(sys.argv[2] if len(sys.argv) == 3 else Path(__file__).resolve().parent.parent / "app" / "src")
    fit = fit_to_safe_zone(Converter(root).shapes)
    conv = Converter(root, fit)
    for reason in sorted(set(conv.dropped)):
        print(f"dropped: {conv.dropped.count(reason)} x {reason}", file=sys.stderr)
    day = conv.shapes
    night_shapes = [s if is_stone(s) else
                    Shape(s.segs, s.fill and s.fill.recolored(night), s.stroke and s.stroke.recolored(night),
                          s.stroke_width, s.fill_alpha, s.stroke_alpha, s.fill_rule, s.cap, s.join, s.clips)
                    for s in day]
    # the moon in front of the sky, behind the hills: the sky is the first shape
    night_shapes = night_shapes[:1] + moon(day, (0xf4, 0xf1, 0xe0)) + night_shapes[1:]
    for channel, shapes, mono in (("stable", day, silhouette(day)),
                                  ("ptb", night_shapes, silhouette(day) + moon(day, (0, 0, 0))[1:])):
        res = out / channel / "res" / "drawable"
        res.mkdir(parents=True, exist_ok=True)
        (res / "ic_launcher_foreground.xml").write_text(vector_xml(shapes))
        (res / "ic_launcher_monochrome.xml").write_text(vector_xml(mono))


if __name__ == "__main__":
    main()

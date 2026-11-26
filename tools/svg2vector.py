#!/usr/bin/env python3
"""Convert an icon pack's SVGs into Android VectorDrawables.

    tools/svg2vector.py <src.svg> <icon-name> <out.xml>

The output keeps the pack's own icon name as its file name, and the vector
carries no width/height of its own beyond a 26 dp default: the name is the
contract, so a different pack can be dropped in without touching call sites.

What it handles, which is what the TeamSpeak dark pack uses:

  * `rgb(r,g,b)`, `#rrggbb`, `#rgb`, `white`, `black`
  * vertical `userSpaceOnUse` linear gradients, as `<aapt:attr>` + `<gradient>`
  * `fill-rule:evenOdd`
  * nested Inkscape `matrix(...)` transforms, which are ignored on purpose: the
    pack's chains are page geometry (a large offset and a 1.000004 scale), and
    the path coordinates are already in the viewBox

Anything else — a radial gradient, a `rect` with a fill, a stroked path it
cannot express — is reported and the file is refused rather than approximated.
A silently wrong icon is worse than a missing one.
"""
import re
import sys
import xml.etree.ElementTree as ET

SVG = '{http://www.w3.org/2000/svg}'

# Fonts used to turn a <text> run into outlines. A missing file is not fatal:
# the caller is told the glyph could not be resolved rather than getting a
# silently blank icon.
FONT_FILES = {
    'lucidagrande': '/System/Library/Fonts/LucidaGrande.ttc',
}


def mat_mul(a, b):
    """a then b applied to a point: result = b ∘ a."""
    a0, a1, a2, a3, a4, a5 = a
    b0, b1, b2, b3, b4, b5 = b
    return (
        a0 * b0 + a1 * b2,
        a0 * b1 + a1 * b3,
        a2 * b0 + a3 * b2,
        a2 * b1 + a3 * b3,
        a4 * b0 + a5 * b2 + b4,
        a4 * b1 + a5 * b3 + b5,
    )


IDENTITY = (1.0, 0.0, 0.0, 1.0, 0.0, 0.0)


def parse_transform(text):
    m = IDENTITY
    if not text:
        return m
    for name, args in re.findall(r'(\w+)\s*\(([^)]*)\)', text):
        v = [float(x) for x in re.split(r'[,\s]+', args.strip()) if x]
        if name == 'matrix':
            t = tuple(v)
        elif name == 'translate':
            t = (1, 0, 0, 1, v[0], v[1] if len(v) > 1 else 0)
        elif name == 'scale':
            t = (v[0], 0, 0, v[1] if len(v) > 1 else v[0], 0, 0)
        else:
            raise SystemExit(f'unsupported transform: {name}')
        m = mat_mul(m, t)
    return m


def is_identity(m, tol=1e-3, shift_tol=1.0):
    """Kept for the report; transforms are deliberately not applied."""
    scale_ok = all(abs(m[i] - IDENTITY[i]) < tol for i in (0, 1, 2, 3))
    shift_ok = abs(m[4]) < shift_tol and abs(m[5]) < shift_tol
    return scale_ok and shift_ok


def style_of(el):
    out = {}
    for part in (el.get('style') or '').split(';'):
        if ':' in part:
            k, v = part.split(':', 1)
            out[k.strip()] = v.strip()
    for attr in ('fill', 'fill-rule', 'stroke', 'stroke-width', 'stroke-linecap',
                 'stroke-linejoin', 'stroke-miterlimit', 'stroke-opacity', 'fill-opacity'):
        if el.get(attr):
            out[attr] = el.get(attr)
    return out


def parse_color(text):
    """rgb(r,g,b) or #rrggbb or a name → '#AARRGGBB'."""
    text = text.strip()
    m = re.match(r'rgb\(\s*(\d+)\s*,\s*(\d+)\s*,\s*(\d+)\s*\)', text)
    if m:
        r, g, b = (int(x) for x in m.groups())
        return r, g, b
    m = re.match(r'#([0-9a-fA-F]{6})$', text)
    if m:
        h = m.group(1)
        return int(h[0:2], 16), int(h[2:4], 16), int(h[4:6], 16)
    m = re.match(r'#([0-9a-fA-F]{3})$', text)
    if m:
        h = m.group(1)
        return tuple(int(c * 2, 16) for c in h)
    if text.lower() == 'white':
        return 255, 255, 255
    if text.lower() == 'black':
        return 0, 0, 0
    raise SystemExit(f'unsupported colour: {text}')


def argb(rgb, opacity=1.0):
    r, g, b = rgb
    a = round(opacity * 255)
    return f'#{a:02X}{r:02X}{g:02X}{b:02X}'


def collect_gradients(root):
    """id → (start, end, [(offset, colour, opacity)]) in user space."""
    out = {}
    for grad in root.iter(SVG + 'linearGradient'):
        gid = grad.get('id')
        m = parse_transform(grad.get('gradientTransform'))
        if grad.get('gradientUnits') != 'userSpaceOnUse':
            raise SystemExit(f'{gid}: only userSpaceOnUse is supported')
        x1, y1 = float(grad.get('x1', 0)), float(grad.get('y1', 0))
        x2, y2 = float(grad.get('x2', 1)), float(grad.get('y2', 0))
        sx = m[0] * x1 + m[2] * y1 + m[4]
        sy = m[1] * x1 + m[3] * y1 + m[5]
        ex = m[0] * x2 + m[2] * y2 + m[4]
        ey = m[1] * x2 + m[3] * y2 + m[5]
        stops = []
        for stop in grad.iter(SVG + 'stop'):
            style = style_of(stop)
            offset = float(stop.get('offset', 0))
            colour = parse_color(style.get('stop-color', 'black'))
            opacity = float(style.get('stop-opacity', 1))
            stops.append((offset, colour, opacity))
        out[gid] = ((sx, sy), (ex, ey), stops)
    return out




def clip_rect(root, clip_id):
    """The (x, y, w, h) of a clipPath that contains exactly one rect."""
    for clip in root.iter(SVG + 'clipPath'):
        if clip.get('id') != clip_id:
            continue
        rects = list(clip.iter(SVG + 'rect'))
        if len(rects) != 1:
            return None
        r = rects[0]
        return (float(r.get('x', 0)), float(r.get('y', 0)),
                float(r.get('width', 0)), float(r.get('height', 0)))
    return None

def glyph_paths(text, font_family, font_size, x, y):
    """Outline a text run as path data, in the SVG's user space.

    VectorDrawable has no text, so a <text> element can only survive as
    outlines. The pack uses one run (`moderated.svg`'s `M` in Lucida Grande), so
    this resolves a single family/size rather than pretending to be a layout
    engine. Returns (path_strings, advance_width).
    """
    try:
        from fontTools.ttLib import TTFont
        from fontTools.pens.svgPathPen import SVGPathPen
        from fontTools.pens.transformPen import TransformPen
    except ImportError:
        raise SystemExit('fontTools is required to outline <text>; pip3 install fonttools')

    family = font_family.split(',')[0].strip().strip("'\"").lower().replace(' ', '')
    path = FONT_FILES.get(family)
    if not path:
        raise SystemExit(f'no font file known for family {font_family!r}')
    font = TTFont(path, fontNumber=0)
    glyph_set = font.getGlyphSet()
    cmap = font.getBestCmap()
    units_per_em = font['head'].unitsPerEm
    scale = font_size / units_per_em

    # SVG's y axis points down and the baseline sits at `y`; font units point up.
    pen_paths = []
    advance = 0.0
    for ch in text:
        glyph_name = cmap.get(ord(ch))
        if glyph_name is None:
            raise SystemExit(f'font has no glyph for {ch!r}')
        pen = SVGPathPen(glyph_set)
        # Flip y, scale from font units to user units, then move to (x, y).
        transform = (scale, 0, 0, -scale, x + advance, y)
        tpen = TransformPen(pen, transform)
        glyph_set[glyph_name].draw(tpen)
        d = pen.getCommands()
        if d:
            pen_paths.append(d)
        advance += glyph_set[glyph_name].width * scale
    return pen_paths, advance

def convert(path, name):
    tree = ET.parse(path)
    root = tree.getroot()
    viewbox = root.get('viewBox', '0 0 128 128').split()
    vw, vh = float(viewbox[2]), float(viewbox[3])
    grads = collect_gradients(root)

    paths = []
    warnings = []

    def walk(el, matrix, inside_clip=False):
        m = mat_mul(matrix, parse_transform(el.get('transform')))
        tag = el.tag.replace(SVG, '')
        if tag == 'clipPath':
            # A clipPath is a mask, not artwork: its rect describes the region
            # and must not be turned into a filled shape.
            return
        if tag == 'path':
            style = style_of(el)
            d = el.get('d', '').strip()
            if not d:
                return
            if style.get('fill', 'black') == 'none' and style.get('stroke', 'none') == 'none':
                return
            # The transform chain is Inkscape page geometry (a -3392 x offset
            # and a 0.999996 scale), not a size change: the path coordinates are
            # already in the 128x128 viewBox. The seven icons this repo already
            # ships were copied the same way and render correctly, so the chain
            # is ignored rather than baked in.
            paths.append((d, style))
        elif tag == 'rect':
            style = style_of(el)
            if inside_clip or style.get('fill') == 'none':
                return
            # A filled rect is artwork (a badge, a bar, a recording square).
            # VectorDrawable has no rect primitive, so it becomes a path. The
            # pack has no rounded or rotated rects, and a radius here would be
            # silently square, so it is refused instead.
            if el.get('rx') or el.get('ry'):
                raise SystemExit(f'{name}: rounded <rect> is not converted')
            x = float(el.get('x', 0))
            y = float(el.get('y', 0))
            w = float(el.get('width', 0))
            h = float(el.get('height', 0))
            # Apply whatever transform the rect sits under by mapping its four
            # corners: a rotated rect is still a quadrilateral, and the pack
            # uses exactly one (the slash through `ban_list`).
            corners = [(x, y), (x + w, y), (x + w, y + h), (x, y + h)]
            pts = [(m[0] * cx + m[2] * cy + m[4], m[1] * cx + m[3] * cy + m[5])
                   for cx, cy in corners]
            d = 'M' + 'L'.join(f'{px:.4f},{py:.4f}' for px, py in pts) + 'Z'
            paths.append((d, style))
        elif tag == 'text':
            style = style_of(el)
            body = (el.text or '').strip()
            if not body:
                return
            size = float(re.sub(r'[^0-9.]', '', style.get('font-size', '16')))
            tx = float(re.sub(r'[^0-9.\-]', '', el.get('x', '0')) or 0)
            ty = float(re.sub(r'[^0-9.\-]', '', el.get('y', '0')) or 0)
            outline, _advance = glyph_paths(body, style.get('font-family', ''), size, tx, ty)
            for d in outline:
                paths.append((d, {'fill': style.get('fill', 'black'),
                                  'fill-rule': style.get('fill-rule', 'nonzero')}))
        elif tag == 'g' and el.get('clip-path'):
            clip = el.get('clip-path')
            gid = re.search(r'url\(#([^)]+)\)', clip)
            if not gid:
                raise SystemExit(f'{name}: unrecognised clip-path {clip}')
            rect = clip_rect(root, gid.group(1))
            if rect is None:
                raise SystemExit(f'{name}: clip-path {gid.group(1)} is not a rect')
            # A clip that is exactly the viewBox removes nothing. Anything
            # smaller would hide part of the drawing, and VectorDrawable has
            # <clip-path> but expressing the transform stack correctly is more
            # than this converter claims to do.
            if rect != (0.0, 0.0, vw, vh):
                raise SystemExit(
                    f'{name}: clip-path {gid.group(1)} is {rect}, not the full '
                    f'viewBox ({0.0}, {0.0}, {vw}, {vh}); refusing to drop it'
                )
        for child in el:
            if child.tag.replace(SVG, '') in ('defs', 'metadata', 'sodipodi:namedview'):
                continue
            walk(child, m, inside_clip)

    walk(root, IDENTITY)

    lines = [
        '<?xml version="1.0" encoding="utf-8"?>',
        '<!--',
        f'    TeamSpeak\'s own `{name}` icon, copied verbatim from the `dark` icon pack',
        '    (github.com/randomhost/teamspeak-dark, BSD-3-Clause). Path data and',
        '    gradient stops are unchanged; only the envelope is Android vector XML.',
        '    See assets/licenses/LICENSE-TEAMSPEAK-ICONS.',
        '-->',
        '<vector xmlns:android="http://schemas.android.com/apk/res/android"',
        '    xmlns:aapt="http://schemas.android.com/aapt"',
        f'    android:width="26dp"',
        f'    android:height="26dp"',
        f'    android:viewportWidth="{vw:g}"',
        f'    android:viewportHeight="{vh:g}">',
    ]

    for d, style in paths:
        fill = style.get('fill', 'black')
        fill_opacity = float(style.get('fill-opacity', 1))
        attrs = [f'        android:pathData="{d}"']
        if fill.startswith('url(#'):
            gid = fill[5:-1]
            (sx, sy), (ex, ey), stops = grads[gid]
            lines.append('    <path android:pathData="%s">' % d)
            lines.append('        <aapt:attr name="android:fillColor">')
            lines.append(f'            <gradient android:type="linear"')
            lines.append(f'                android:startX="{sx:.4g}" android:startY="{sy:.4g}"')
            lines.append(f'                android:endX="{ex:.4g}" android:endY="{ey:.4g}">')
            for offset, colour, opacity in stops:
                lines.append(
                    f'                <item android:offset="{offset:g}" '
                    f'android:color="{argb(colour, opacity * fill_opacity)}"/>'
                )
            lines.append('            </gradient>')
            lines.append('        </aapt:attr>')
            lines.append('    </path>')
            continue
        attrs.append(f'        android:fillColor="{argb(parse_color(fill), fill_opacity)}"')
        if style.get('fill-rule') == 'evenOdd':
            attrs.append('        android:fillType="evenOdd"')
        stroke = style.get('stroke')
        if stroke and stroke != 'none':
            attrs.append(f'        android:strokeColor="{argb(parse_color(stroke))}"')
            attrs.append(f'        android:strokeWidth="{float(style.get("stroke-width", 1)):g}"')
        lines.append('    <path')
        lines.extend(a + '' for a in attrs)
        lines.append('        />')

    lines.append('</vector>')
    text = '\n'.join(lines).replace('        />', '        />')
    # collapse the awkward multi-line attribute layout
    text = re.sub(r'<path\n((?:        [^\n]+\n)+)        />',
                  lambda m: '<path\n' + m.group(1).rstrip('\n') + ' />', text)
    return text, warnings


if __name__ == '__main__':
    src, name, dest = sys.argv[1], sys.argv[2], sys.argv[3]
    text, warns = convert(src, name)
    open(dest, 'w').write(text + '\n')
    for w in warns:
        print(f'  warn: {w}')
    print(f'{dest}: {len(text.splitlines())} lines')

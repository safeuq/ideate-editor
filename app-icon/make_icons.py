"""Builds the Ideate icon (option C4: bulb in IntelliJ colours) for every platform.

Usage: python3 make_icons.py <output folder>
Needs rsvg-convert and (for the .icns) macOS iconutil.
"""
import struct
import subprocess
import sys
from pathlib import Path

OUTPUT = Path(sys.argv[1])

GRADIENTS = [("a", "#FDB60D", "#FC801D"), ("b", "#FC801D", "#FE2857"), ("c", "#FF318C", "#AF1DF5"),
             ("d", "#AF1DF5", "#6B57FF"), ("e", "#6B57FF", "#087CFA")]
CIRCLES = [("a", 100, 60, 30), ("b", 70, 80, 30), ("c", 130, 80, 30), ("d", 84, 106, 28), ("e", 116, 106, 28)]
BARS = [(76, 142, 48), (82, 157, 36), (92, 172, 16)]
VARIANTS = {
    "dark": dict(tile="#000000", bar="#FFFFFF", blend="screen", opacity="0.92"),
    "light": dict(tile="#FFFFFF", bar="#000000", blend="multiply", opacity="0.9"),
}


def definitions():
    return "<defs>" + "".join(
        f'<linearGradient id="{name}" x1="0" y1="0" x2="1" y2="1"><stop offset="0" stop-color="{start}"/>'
        f'<stop offset="1" stop-color="{end}"/></linearGradient>' for name, start, end in GRADIENTS) + "</defs>"


def art(variant):
    """The bulb in the 200-unit grid of the design board, drawn over the tile colour."""
    circles = "".join(
        f'<circle cx="{x}" cy="{y}" r="{radius}" fill="url(#{name})" opacity="{variant["opacity"]}" '
        f'style="mix-blend-mode: {variant["blend"]}"/>' for name, x, y, radius in CIRCLES)
    bars = "".join(f'<rect x="{x}" y="{y}" width="{width}" height="9" fill="{variant["bar"]}"/>' for x, y, width in BARS)
    return (circles + '<path d="M100 36 L100 128" fill="none" stroke="#FFFFFF" stroke-width="4" '
            'stroke-linecap="round" opacity="0.7"/>' + bars)


def silhouette():
    circles = "".join(f'<circle cx="{x}" cy="{y}" r="{radius}" fill="#FFFFFF"/>' for _, x, y, radius in CIRCLES)
    bars = "".join(f'<rect x="{x}" y="{y}" width="{width}" height="9" fill="#FFFFFF"/>' for x, y, width in BARS)
    return circles + bars


def tile_svg(variant, corner_radius):
    """Full-bleed 200x200 tile; corner_radius 0 = square, 100 = circle."""
    return (f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 200 200">{definitions()}'
            f'<clipPath id="shape"><rect width="200" height="200" rx="{corner_radius}"/></clipPath>'
            f'<g clip-path="url(#shape)"><rect width="200" height="200" fill="{variant["tile"]}"/>{art(variant)}</g></svg>')


def macos_svg(variant):
    """Apple's grid: an 824 px rounded square centred on a 1024 px transparent canvas."""
    return (f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 1024 1024">{definitions()}'
            f'<clipPath id="shape"><rect width="200" height="200" rx="45"/></clipPath>'
            f'<g transform="translate(100 100) scale(4.12)" clip-path="url(#shape)">'
            f'<rect width="200" height="200" fill="{variant["tile"]}"/>{art(variant)}</g></svg>')


# Android adaptive icon: 108 dp layer, launcher masks show at most the middle 72 dp, and the art must stay
# inside the middle 66 dp circle. The bulb is centred on (100, 105.5) and reaches 76 units from there.
ANDROID_TRANSFORM = "translate(54 54) scale(0.408) translate(-100 -105.5)"


def android_foreground_svg(variant):
    return (f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 108 108">{definitions()}'
            f'<rect width="108" height="108" fill="{variant["tile"]}"/>'
            f'<g transform="{ANDROID_TRANSFORM}">{art(variant)}</g></svg>')


def android_monochrome_svg():
    return (f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 108 108">'
            f'<g transform="{ANDROID_TRANSFORM}">{silhouette()}</g></svg>')


def write(path, text):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text)
    return path


def render(svg_path, png_path, size):
    png_path.parent.mkdir(parents=True, exist_ok=True)
    subprocess.run(["rsvg-convert", "--width", str(size), "--height", str(size), "--output", str(png_path),
                    str(svg_path)], check=True)
    return png_path


def write_ico(png_paths_by_size, ico_path):
    """ICO file whose entries are PNG images (read by Windows Vista and later)."""
    entries = sorted(png_paths_by_size.items())
    header = struct.pack("<HHH", 0, 1, len(entries))
    offset = len(header) + 16 * len(entries)
    directory, payload = b"", b""
    for size, png_path in entries:
        data = png_path.read_bytes()
        directory += struct.pack("<BBBBHHII", size % 256, size % 256, 0, 0, 1, 32, len(data), offset)
        payload += data
        offset += len(data)
    ico_path.write_bytes(header + directory + payload)


for variant_name, variant in VARIANTS.items():
    root = OUTPUT / variant_name
    square = write(root / "ideate-square.svg", tile_svg(variant, 0))
    rounded = write(root / "ideate-rounded.svg", tile_svg(variant, 44))
    circle = write(root / "ideate-circle.svg", tile_svg(variant, 100))
    macos = write(root / "macos" / "ideate-macos.svg", macos_svg(variant))

    iconset = root / "macos" / "Ideate.iconset"
    for points in (16, 32, 128, 256, 512):
        render(macos, iconset / f"icon_{points}x{points}.png", points)
        render(macos, iconset / f"icon_{points}x{points}@2x.png", points * 2)
    subprocess.run(["iconutil", "--convert", "icns", "--output", str(root / "macos" / "Ideate.icns"), str(iconset)],
                   check=True)

    windows_sizes = (16, 20, 24, 32, 40, 48, 64, 128, 256)
    windows_pngs = {size: render(rounded, root / "windows" / "png" / f"ideate-{size}.png", size)
                    for size in windows_sizes}
    write_ico(windows_pngs, root / "windows" / "ideate.ico")

    for size in (16, 22, 24, 32, 48, 64, 128, 256, 512):
        render(rounded, root / "linux" / "hicolor" / f"{size}x{size}" / "apps" / "ideate.png", size)
    write(root / "linux" / "hicolor" / "scalable" / "apps" / "ideate.svg", rounded.read_text())

    render(square, root / "ios" / "AppIcon-1024.png", 1024)

    resources = root / "android" / "res"
    foreground = write(root / "android" / "ic_launcher_foreground.svg", android_foreground_svg(variant))
    monochrome = write(root / "android" / "ic_launcher_monochrome.svg", android_monochrome_svg())
    for density, scale in (("mdpi", 1), ("hdpi", 1.5), ("xhdpi", 2), ("xxhdpi", 3), ("xxxhdpi", 4)):
        folder = resources / f"mipmap-{density}"
        render(foreground, folder / "ic_launcher_foreground.png", int(108 * scale))
        render(monochrome, folder / "ic_launcher_monochrome.png", int(108 * scale))
        render(rounded, folder / "ic_launcher.png", int(48 * scale))
        render(circle, folder / "ic_launcher_round.png", int(48 * scale))
    adaptive = ('<?xml version="1.0" encoding="utf-8"?>\n'
                '<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">\n'
                '    <background android:drawable="@color/ic_launcher_background" />\n'
                '    <foreground android:drawable="@mipmap/ic_launcher_foreground" />\n'
                '    <monochrome android:drawable="@mipmap/ic_launcher_monochrome" />\n'
                '</adaptive-icon>\n')
    write(resources / "mipmap-anydpi-v26" / "ic_launcher.xml", adaptive)
    write(resources / "mipmap-anydpi-v26" / "ic_launcher_round.xml", adaptive)
    write(resources / "values" / "ic_launcher_background.xml",
          '<?xml version="1.0" encoding="utf-8"?>\n<resources>\n'
          f'    <color name="ic_launcher_background">{variant["tile"]}</color>\n</resources>\n')
    render(square, root / "android" / "play-store-512.png", 512)

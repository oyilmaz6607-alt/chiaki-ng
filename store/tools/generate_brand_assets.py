# Kullanım: python3 store/tools/generate_brand_assets.py (Pillow + fontTools, Inter yazı tipi gerekir)
# AltyazıTR brand asset generator (original artwork)
import os, math
from fontTools.ttLib import TTFont
from fontTools.pens.svgPathPen import SVGPathPen
from fontTools.pens.transformPen import TransformPen
from fontTools.pens.recordingPen import RecordingPen
from PIL import Image, ImageDraw, ImageFont, ImageFilter

RES = "/home/claude/fork/android/app/src/main/res"
STORE = "/home/claude/fork/store"
FONT_BOLD = "/usr/share/fonts/opentype/inter/InterDisplay-Bold.otf"
FONT_XBOLD = "/usr/share/fonts/opentype/inter/InterDisplay-ExtraBold.otf"
FONT_MED = "/usr/share/fonts/opentype/inter/Inter-Medium.otf"
FONT_SEMI = "/usr/share/fonts/opentype/inter/Inter-SemiBold.otf"

BG = "#0B0F17"; BG2 = "#18233A"
AMBER = "#FFC83D"; DARK = "#0B0F17"; RED = "#E5262E"; WHITE = "#F4F6FA"; MUTED = "#A3ACBD"

# --- Mark geometry in a 108x108 adaptive-icon space ---
BUBBLE = (26, 28, 82, 74, 12)          # x0,y0,x1,y1,r
TAIL = [(36, 73), (37, 83), (49, 73)]
BAR1 = (35, 57, 73, 62, 2.5)
BAR2 = (35, 65, 58, 70, 2.5)
TEXT_BASELINE = 51.5; TEXT_CAP = 16.5; TEXT_CX = 54

def rrect_path(x0, y0, x1, y1, r):
	return (f"M{x0+r:.2f},{y0:.2f} H{x1-r:.2f} A{r},{r} 0 0 1 {x1:.2f},{y0+r:.2f} "
		f"V{y1-r:.2f} A{r},{r} 0 0 1 {x1-r:.2f},{y1:.2f} H{x0+r:.2f} "
		f"A{r},{r} 0 0 1 {x0:.2f},{y1-r:.2f} V{y0+r:.2f} A{r},{r} 0 0 1 {x0+r:.2f},{y0:.2f} Z")

def poly_path(pts):
	return "M" + " L".join(f"{x:.2f},{y:.2f}" for x, y in pts) + " Z"

def text_path(text, fontfile, cap, cx, baseline, tracking=0.0):
	f = TTFont(fontfile)
	gs = f.getGlyphSet(); cmap = f.getBestCmap()
	upm = f["head"].unitsPerEm
	capH = f["OS/2"].sCapHeight
	s = cap / capH
	names = [cmap[ord(c)] for c in text]
	width = sum(gs[n].width for n in names) * s + tracking * (len(names) - 1)
	x = cx - width / 2
	pen = SVGPathPen(None, ntos=lambda v: f"{v:.2f}")
	for n in names:
		tp = TransformPen(pen, (s, 0, 0, -s, x, baseline))
		gs[n].draw(tp)
		x += gs[n].width * s + tracking
	return pen.getCommands()

def vector_xml(w, h, vw, vh, paths, tx=0, ty=0, scale=1.0):
	out = ['<?xml version="1.0" encoding="utf-8"?>',
		'<!-- AltyazıTR logo (özgün çizim) -->',
		'<vector xmlns:android="http://schemas.android.com/apk/res/android"',
		f'    android:width="{w}dp"', f'    android:height="{h}dp"',
		f'    android:viewportWidth="{vw}"', f'    android:viewportHeight="{vh}">']
	grouped = bool(tx or ty or scale != 1.0)
	if grouped:
		out.append(f'    <group android:translateX="{tx}" android:translateY="{ty}" android:pivotX="{vw/2}" android:pivotY="{vh/2}" android:scaleX="{scale}" android:scaleY="{scale}">')
	for color, d in paths:
		ft = ' android:fillType="evenOdd"' if color.startswith("EO") else ""
		color = color[2:] if color.startswith("EO") else color
		out.append(f'    <path android:fillColor="{color}"{ft} android:pathData="{d}" />')
	if grouped:
		out.append('    </group>')
	out.append('</vector>')
	return "\n".join(out) + "\n"

def mark_paths(mono=False):
	tr = text_path("TR", FONT_XBOLD, TEXT_CAP, TEXT_CX, TEXT_BASELINE, tracking=0.6)
	if mono:
		# monochrome: only the silhouette, inner details as separate shapes are not possible -> bubble+tail
		return [
			("EO#FFFFFFFF", " ".join([rrect_path(*BUBBLE), tr, rrect_path(*BAR1), rrect_path(*BAR2)])),
			("#FFFFFFFF", poly_path([(37.5, 74), (37, 83), (49, 74)])),
		]
	return [
		(AMBER, rrect_path(*BUBBLE)),
		(AMBER, poly_path(TAIL)),
		(DARK, tr),
		(DARK, rrect_path(*BAR1)),
		(RED, rrect_path(*BAR2)),
	]

def write(path, text):
	os.makedirs(os.path.dirname(path), exist_ok=True)
	with open(path, "w", encoding="utf-8") as f:
		f.write(text)

# 1) adaptive foreground (108dp) + monochrome + in-app mark (cropped)
write(f"{RES}/drawable/ic_launcher_foreground.xml", vector_xml(108, 108, 108, 108, mark_paths(), scale=0.9))
write(f"{RES}/drawable/ic_launcher_monochrome.xml", vector_xml(108, 108, 108, 108, mark_paths(mono=True), scale=0.9))
# crop box for in-app logo: x 24..84, y 26..86 (60x60)
write(f"{RES}/drawable/ic_altyazi_mark.xml", vector_xml(48, 48, 60, 60, mark_paths(), tx=-24, ty=-26))

# --- raster rendering of the mark ---
def draw_mark(img, ox, oy, scale):
	"""Draws the mark (108-space) at offset/scale onto RGBA img using 4x supersampling."""
	W, H = img.size
	ss = 4
	layer = Image.new("RGBA", (W * ss, H * ss), (0, 0, 0, 0))
	d = ImageDraw.Draw(layer)
	def P(x, y): return ((ox + x * scale) * ss, (oy + y * scale) * ss)
	def rr(b, color):
		x0, y0, x1, y1, r = b
		a = P(x0, y0); c = P(x1, y1)
		d.rounded_rectangle([a, c], radius=r * scale * ss, fill=color)
	rr(BUBBLE, AMBER)
	d.polygon([P(*p) for p in TAIL], fill=AMBER)
	rr(BAR1, DARK); rr(BAR2, RED)
	# text
	f = TTFont(FONT_XBOLD); capH = f["OS/2"].sCapHeight; upm = f["head"].unitsPerEm
	size = TEXT_CAP / capH * upm * scale * ss
	font = ImageFont.truetype(FONT_XBOLD, int(round(size)))
	track = 0.6 * scale * ss
	widths = [font.getlength(ch) for ch in "TR"]
	total = sum(widths) + track
	x = (ox + TEXT_CX * scale) * ss - total / 2
	base = (oy + TEXT_BASELINE * scale) * ss
	for ch, wch in zip("TR", widths):
		d.text((x, base), ch, font=font, fill=DARK, anchor="ls")
		x += wch + track
	layer = layer.resize((W, H), Image.LANCZOS)
	img.alpha_composite(layer)

def gradient(w, h, c0, c1, diag=True):
	import numpy as np
	a = np.array(Image.new("RGB", (1, 1), c0).getpixel((0, 0)), dtype=float)
	b = np.array(Image.new("RGB", (1, 1), c1).getpixel((0, 0)), dtype=float)
	yy, xx = np.mgrid[0:h, 0:w]
	t = ((xx / max(w - 1, 1)) * 0.35 + (yy / max(h - 1, 1)) * 0.65) if diag else yy / max(h - 1, 1)
	t = np.clip(t, 0, 1)[..., None]
	arr = (b * t + a * (1 - t)).astype("uint8")
	return Image.fromarray(arr, "RGB").convert("RGBA")

def glow(img, cx, cy, radius, color, alpha):
	g = Image.new("RGBA", img.size, (0, 0, 0, 0))
	d = ImageDraw.Draw(g)
	rgb = Image.new("RGB", (1, 1), color).getpixel((0, 0))
	d.ellipse([cx - radius, cy - radius, cx + radius, cy + radius], fill=rgb + (alpha,))
	g = g.filter(ImageFilter.GaussianBlur(radius * 0.45))
	img.alpha_composite(g)

# 2) legacy launcher PNGs
DENS = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}
def legacy_icon(px, round_):
	ss = 4
	base = gradient(px, px, BG2, BG)
	mask = Image.new("L", (px * ss, px * ss), 0)
	md = ImageDraw.Draw(mask)
	inset = px * ss * 0.04
	if round_:
		md.ellipse([inset, inset, px * ss - inset, px * ss - inset], fill=255)
	else:
		md.rounded_rectangle([inset, inset, px * ss - inset, px * ss - inset], radius=px * ss * 0.2, fill=255)
	mask = mask.resize((px, px), Image.LANCZOS)
	# mark: 108-space content box ~24..86 -> scale to fill ~ 0.86 of icon
	scale = px / 108 * 1.28
	off = px / 2 - 54 * scale
	draw_mark(base, off, off + px * 0.01, scale)
	out = Image.new("RGBA", (px, px), (0, 0, 0, 0))
	out.paste(base, (0, 0), mask)
	return out

for dens, px in DENS.items():
	d = f"{RES}/mipmap-{dens}"
	os.makedirs(d, exist_ok=True)
	legacy_icon(px, False).save(f"{d}/ic_launcher.png", optimize=True)
	legacy_icon(px, True).save(f"{d}/ic_launcher_round.png", optimize=True)

# 3) TV banner 320x180 (xhdpi -> 160x90dp)
def banner(w, h):
	img = gradient(w, h, BG2, BG)
	d = ImageDraw.Draw(img)
	title = ImageFont.truetype(FONT_XBOLD, int(h * 0.22))
	sub = ImageFont.truetype(FONT_SEMI, int(h * 0.09))
	s = h / 108 * 0.78
	mark_w = 58 * s; gap = w * 0.035
	tw = d.textlength("AltyazıTR", font=title)
	total = mark_w + gap + tw
	mx = (w - total) / 2
	draw_mark(img, mx - 25 * s, h / 2 - 56 * s, s)
	d = ImageDraw.Draw(img)
	tx = mx + mark_w + gap
	base = h * 0.53
	d.text((tx, base), "Altyazı", font=title, fill=WHITE, anchor="ls")
	d.text((tx + d.textlength("Altyazı", font=title), base), "TR", font=title, fill=AMBER, anchor="ls")
	d.text((tx + 1, base + h * 0.16), "Oyun · Türkçe altyazı", font=sub, fill=MUTED, anchor="ls")
	return img.convert("RGB")

os.makedirs(f"{RES}/drawable-xhdpi", exist_ok=True)
banner(320, 180).save(f"{RES}/drawable-xhdpi/tv_banner.png", optimize=True)

# 4) store assets
os.makedirs(STORE, exist_ok=True)
def store_icon(px):
	img = gradient(px, px, BG2, BG)
	glow(img, px // 2, int(px * 0.48), int(px * 0.36), AMBER, 22)
	scale = px / 108 * 1.25
	off = px / 2 - 54 * scale
	draw_mark(img, off, off + px * 0.012, scale)
	return img.convert("RGB")
store_icon(512).save(f"{STORE}/play_icon_512.png", optimize=True)

def promo(w, h):
	img = gradient(w, h, BG2, BG)
	
	d = ImageDraw.Draw(img)
	u = h / 720
	# left: mark + title + tagline
	s = 150 * u / 60
	draw_mark(img, 96 * u - 24 * s, 150 * u - 26 * s, s)
	d = ImageDraw.Draw(img)
	title = ImageFont.truetype(FONT_XBOLD, int(96 * u))
	tag = ImageFont.truetype(FONT_SEMI, int(38 * u))
	small = ImageFont.truetype(FONT_MED, int(26 * u))
	x = 96 * u; y = 400 * u
	d.text((x, y), "Altyazı", font=title, fill=WHITE, anchor="ls")
	d.text((x + d.textlength("Altyazı", font=title), y), "TR", font=title, fill=AMBER, anchor="ls")
	d.text((x, y + 62 * u), "Oyununu oyna,", font=tag, fill=WHITE, anchor="ls")
	d.text((x, y + 110 * u), "altyazısı Türkçe aksın.", font=tag, fill=WHITE, anchor="ls")
	d.text((x, y + 176 * u), "Google TV için · cihaz içinde çeviri", font=small, fill=MUTED, anchor="ls")
	# right: stylised TV frame with subtitle lines
	fx0, fy0, fx1, fy1 = 640 * u, 120 * u, 1200 * u, 520 * u
	frame = Image.new("RGBA", img.size, (0, 0, 0, 0))
	fd = ImageDraw.Draw(frame)
	fd.rounded_rectangle([fx0 - 10 * u, fy0 - 10 * u, fx1 + 10 * u, fy1 + 10 * u], radius=26 * u, fill=(28, 36, 54, 255))
	scene = gradient(int(fx1 - fx0), int(fy1 - fy0), "#2B3A5C", "#0E1422")
	# simple abstract landscape
	sd = ImageDraw.Draw(scene)
	sw, sh = scene.size
	sd.ellipse([sw * 0.62, sh * 0.12, sw * 0.80, sh * 0.12 + sw * 0.18], fill=(255, 200, 61, 200))
	sd.polygon([(0, sh * 0.72), (sw * 0.25, sh * 0.42), (sw * 0.45, sh * 0.66), (sw * 0.7, sh * 0.38), (sw, sh * 0.68), (sw, sh), (0, sh)], fill=(20, 28, 44, 255))
	sd.polygon([(0, sh * 0.86), (sw * 0.4, sh * 0.70), (sw, sh * 0.88), (sw, sh), (0, sh)], fill=(12, 17, 28, 255))
	m = Image.new("L", scene.size, 0)
	ImageDraw.Draw(m).rounded_rectangle([0, 0, sw - 1, sh - 1], radius=18 * u, fill=255)
	frame.paste(scene, (int(fx0), int(fy0)), m)
	fd = ImageDraw.Draw(frame)
	en = ImageFont.truetype(FONT_MED, int(22 * u))
	tr = ImageFont.truetype(FONT_SEMI, int(30 * u))
	cx = (fx0 + fx1) / 2
	ten = "We have to leave before sunrise."
	ttr = "Gün doğmadan yola çıkmalıyız."
	wtr = fd.textlength(ttr, font=tr)
	by = fy1 - 40 * u
	fd.rounded_rectangle([cx - wtr / 2 - 22 * u, by - 46 * u, cx + wtr / 2 + 22 * u, by + 14 * u], radius=14 * u, fill=(0, 0, 0, 190))
	fd.text((cx, by), ttr, font=tr, fill=(255, 255, 255, 255), anchor="ms")
	fd.text((cx, by - 66 * u), ten, font=en, fill=(255, 255, 255, 120), anchor="ms")
	# TR chip
	chip = ImageFont.truetype(FONT_XBOLD, int(20 * u))
	fd.rounded_rectangle([fx0 + 20 * u, fy0 + 20 * u, fx0 + 140 * u, fy0 + 58 * u], radius=18 * u, fill=(255, 200, 61, 255))
	fd.text((fx0 + 80 * u, fy0 + 46 * u), "CANLI TR", font=chip, fill=(23, 18, 10, 255), anchor="ms")
	img.alpha_composite(frame)
	return img.convert("RGB")

promo(1280, 720).save(f"{STORE}/tv_banner_1280x720.png", optimize=True)

def feature(w, h):
	img = gradient(w, h, BG2, BG)
	glow(img, int(w * 0.25), int(h * 0.5), int(h * 0.5), AMBER, 20)
	s = h * 0.62 / 60
	draw_mark(img, w * 0.08 - 24 * s, h / 2 - 56 * s, s)
	d = ImageDraw.Draw(img)
	title = ImageFont.truetype(FONT_XBOLD, int(h * 0.2))
	tag = ImageFont.truetype(FONT_SEMI, int(h * 0.075))
	x = w * 0.08 + 66 * s; y = h * 0.5
	d.text((x, y), "Altyazı", font=title, fill=WHITE, anchor="ls")
	d.text((x + d.textlength("Altyazı", font=title), y), "TR", font=title, fill=AMBER, anchor="ls")
	d.text((x + 4, y + h * 0.13), "Oyun altyazıları, anında Türkçe.", font=tag, fill=MUTED, anchor="ls")
	return img.convert("RGB")
feature(1024, 500).save(f"{STORE}/feature_graphic_1024x500.png", optimize=True)
print("done")

#!/usr/bin/env python3
"""Downloads Draftout's goal icons and packs them as textures for the mod.

Run from the project root before building:

    pip install pillow
    python3 tools/fetch_icons.py

Each icon is saved to
src/main/resources/assets/opendraft/textures/goal/<goal id>/<variant>.png as a 32x32 PNG,
where <variant> is "null" for goals without variants (the same path Draftout uses).
Goals without a texture fall back to an item icon in game, so a partial download is fine.

The icons are Draftout's artwork (https://draftoutmc.com). Ask them before you
redistribute a jar that includes them.
"""
import argparse
import io
import json
import sys
import urllib.request
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

CATALOGUE = "https://raw.githubusercontent.com/draftout/draftout-wiki/main/src/lib/goal-catalogue.json"
ORIGIN = "https://draftoutmc.com"
OUT = Path(__file__).resolve().parent.parent / "src/main/resources/assets/opendraft/textures/goal"
SIZE = 32


def fetch(url):
    req = urllib.request.Request(url, headers={"User-Agent": "opendraft-icon-fetch"})
    with urllib.request.urlopen(req, timeout=30) as r:
        return r.read()


def convert(data):
    from PIL import Image
    img = Image.open(io.BytesIO(data)).convert("RGBA")
    # Small pixel-art icons stay crisp; larger renders are smoothed down.
    resample = Image.NEAREST if max(img.size) <= SIZE else Image.LANCZOS
    img.thumbnail((SIZE, SIZE), resample)
    canvas = Image.new("RGBA", (SIZE, SIZE), (0, 0, 0, 0))
    canvas.paste(img, ((SIZE - img.width) // 2, (SIZE - img.height) // 2))
    buf = io.BytesIO()
    canvas.save(buf, "PNG", optimize=True)
    return buf.getvalue()


def main():
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--force", action="store_true", help="download icons that already exist")
    ap.add_argument("--jobs", type=int, default=8)
    args = ap.parse_args()
    try:
        import PIL  # noqa: F401
    except ImportError:
        sys.exit("Pillow is needed: pip install pillow")

    catalogue = json.loads(fetch(CATALOGUE))
    jobs = []
    for variants in catalogue.values():
        for v in variants:
            path = v["path"]  # /goal-icons/<id>/<variant>/icon.webp
            parts = path.strip("/").split("/")
            target = OUT / parts[1] / (parts[2] + ".png")
            if args.force or not target.exists():
                jobs.append((ORIGIN + path, target))
    total = sum(len(v) for v in catalogue.values())
    print(f"{total} icons in the catalogue, {len(jobs)} to download")

    def work(job):
        url, target = job
        try:
            png = convert(fetch(url))
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(png)
            return None
        except Exception as e:  # keep going; the mod falls back to an item icon
            return f"{url}: {e}"

    with ThreadPoolExecutor(args.jobs) as pool:
        errors = [e for e in pool.map(work, jobs) if e]
    for e in errors:
        print("failed", e)
    print(f"done, {len(jobs) - len(errors)} saved, {len(errors)} failed")
    sys.exit(1 if errors else 0)


if __name__ == "__main__":
    main()

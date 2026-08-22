#!/usr/bin/env python3
"""Download each site's own favicon/logo and generate Android launcher mipmaps.

Run from the root of extensions-source. The workflow installs requests, bs4 and Pillow.
"""
from __future__ import annotations

import io
import os
import sys
from pathlib import Path
from urllib.parse import urljoin, urlparse

import requests
from bs4 import BeautifulSoup
from PIL import Image

SITES = {
    "src/es/lectorxd": "https://lectorxd.com/",
    "src/es/lectorotaku": "https://lectorotakus.com/",
}

DENSITIES = {
    "mipmap-mdpi": 48,
    "mipmap-hdpi": 72,
    "mipmap-xhdpi": 96,
    "mipmap-xxhdpi": 144,
    "mipmap-xxxhdpi": 192,
}

UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/131 Safari/537.36"
SESSION = requests.Session()
SESSION.headers.update({"User-Agent": UA, "Accept": "text/html,image/avif,image/webp,image/*,*/*;q=0.8"})


def icon_candidates(home: str) -> list[str]:
    candidates: list[str] = []
    try:
        response = SESSION.get(home, timeout=20)
        response.raise_for_status()
        soup = BeautifulSoup(response.text, "html.parser")
        links = soup.find_all("link")
        ranked = []
        for link in links:
            rel = " ".join(link.get("rel", [])).lower()
            href = link.get("href")
            if not href or "icon" not in rel:
                continue
            sizes = link.get("sizes", "")
            size_score = 0
            if sizes and sizes != "any":
                try:
                    size_score = max(int(part.split("x")[0]) for part in sizes.split() if "x" in part)
                except Exception:
                    pass
            if "apple-touch-icon" in rel:
                size_score += 10000
            ranked.append((size_score, urljoin(home, href)))
        candidates.extend(url for _, url in sorted(ranked, reverse=True))
    except Exception as exc:
        print(f"[icon] warning: could not parse {home}: {exc}")

    for path in (
        "/apple-touch-icon.png",
        "/favicon-192x192.png",
        "/favicon-96x96.png",
        "/favicon.png",
        "/favicon.ico",
    ):
        candidates.append(urljoin(home, path))

    host = urlparse(home).hostname or ""
    candidates.append(f"https://www.google.com/s2/favicons?domain={host}&sz=256")

    unique = []
    seen = set()
    for item in candidates:
        if item not in seen:
            seen.add(item)
            unique.append(item)
    return unique


def download_raster(home: str) -> tuple[Image.Image, str]:
    errors = []
    for url in icon_candidates(home):
        try:
            response = SESSION.get(url, timeout=20)
            response.raise_for_status()
            content_type = response.headers.get("content-type", "").lower()
            if "svg" in content_type or url.lower().split("?")[0].endswith(".svg"):
                continue
            image = Image.open(io.BytesIO(response.content))
            image.seek(0)
            return image.convert("RGBA"), url
        except Exception as exc:
            errors.append(f"{url}: {exc}")
    raise RuntimeError("No raster icon could be downloaded. " + " | ".join(errors[-4:]))


def square_icon(source: Image.Image, size: int) -> Image.Image:
    image = source.copy()
    image.thumbnail((size, size), Image.Resampling.LANCZOS)
    canvas = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    x = (size - image.width) // 2
    y = (size - image.height) // 2
    canvas.alpha_composite(image, (x, y))
    return canvas


def generate(module: Path, home: str) -> None:
    if not module.exists():
        print(f"[icon] skip missing module: {module}")
        return
    image, source_url = download_raster(home)
    print(f"[icon] {module}: using {source_url} ({image.width}x{image.height})")
    for density, size in DENSITIES.items():
        out_dir = module / "res" / density
        out_dir.mkdir(parents=True, exist_ok=True)
        square_icon(image, size).save(out_dir / "ic_launcher.png", optimize=True)


def main() -> int:
    root = Path.cwd()
    failures = []
    for relative, home in SITES.items():
        try:
            generate(root / relative, home)
        except Exception as exc:
            failures.append(f"{relative}: {exc}")
    if failures:
        print("\n[icon] FAILED:")
        for failure in failures:
            print(" -", failure)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

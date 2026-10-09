"""Shared Chromium launch helpers.

Playwright normally finds its own browser. Two cases need a nudge:
  * a pinned/older Chromium already on the machine (CI images, this project's
    cloud container) that does not match the installed playwright version
  * a corporate machine where the download is blocked

Set PLAYWRIGHT_CHROMIUM_EXECUTABLE, or pass --chromium-path, to point at a
chrome binary directly.
"""
from __future__ import annotations

import os
from pathlib import Path


def chromium_executable(explicit: str | None = None) -> str | None:
    """Resolve a Chromium binary, or None to let Playwright choose."""
    for candidate in (explicit, os.environ.get("PLAYWRIGHT_CHROMIUM_EXECUTABLE")):
        if candidate:
            path = Path(candidate).expanduser()
            if path.is_file():
                return str(path)
            raise SystemExit(f"chromium path {path} does not exist")

    # Pre-provisioned browser pools, newest build first.
    root = Path(os.environ.get("PLAYWRIGHT_BROWSERS_PATH", "/opt/pw-browsers"))
    if root.is_dir():
        builds = sorted(
            (d for d in root.glob("chromium-*") if d.is_dir()),
            key=lambda d: int(d.name.rsplit("-", 1)[-1]) if d.name.rsplit("-", 1)[-1].isdigit() else -1,
            reverse=True,
        )
        for build in builds:
            for rel in ("chrome-linux/chrome", "chrome-mac/Chromium.app/Contents/MacOS/Chromium",
                        "chrome-win/chrome.exe"):
                exe = build / rel
                if exe.is_file():
                    return str(exe)
    return None

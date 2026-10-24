#!/usr/bin/env python3
"""Prints the Debian packages Bootstrap.kt installs, one per line: PACKAGES, or DESKTOP_PACKAGES with --desktop."""
import pathlib
import re
import sys

src = (pathlib.Path(__file__).resolve().parent.parent / "app/src/main/kotlin/dev/omalauncher/linux/Bootstrap.kt").read_text()
name = "DESKTOP_PACKAGES" if "--desktop" in sys.argv else "PACKAGES"
block = re.search(rf"private val {name} =\s*listOf\((.*?)\n\s*\)", src, re.S).group(1)
print("\n".join(re.findall(r'"([^"]+)"', block)))

#!/usr/bin/env python3
"""Check whether the LKMs embedded in payload/lkm are still the upstream latest.

For each bundled module this queries the project's latest GitHub release, finds
the matching `android17-6.18` aarch64 asset and compares its SHA-256 with the
local file. Network only; nothing is written.

Usage:
  python3 tools/check_lkm_updates.py [--fail-on-update] [--timeout N]
"""
import argparse
import hashlib
import json
import os
import sys
import urllib.request

# (label, local path, GitHub repo, asset name)
LKMS = [
    ("tiann", "payload/lkm/lkm-aarch64-android17-6.18_kernelsu.ko",
     "tiann/KernelSU", "lkm-aarch64-android17-6.18_kernelsu.ko"),
    ("next", "payload/lkm/aarch64-next-android17-6.18_kernelsu.ko",
     "KernelSU-Next/KernelSU-Next", "aarch64_android17-6.18_kernelsu.ko"),
    ("sukisu", "payload/lkm/sukisu-aarch64-android17-6.18.ko",
     "SukiSU-Ultra/SukiSU-Ultra", "lkm-aarch64-android17-6.18_kernelsu.ko"),
]


def fetch(url, timeout, accept=None):
    headers = {"User-Agent": "o3root-lkm-check"}
    if accept:
        headers["Accept"] = accept
    req = urllib.request.Request(url, headers=headers)
    return urllib.request.urlopen(req, timeout=timeout)


def latest_asset(repo, asset, timeout):
    """Return (tag, size, sha256) of `asset` in the latest release, or None."""
    with fetch("https://api.github.com/repos/%s/releases/latest" % repo, timeout) as r:
        rel = json.load(r)
    tag = rel.get("tag_name") or "?"
    hit = None
    for a in rel.get("assets", []):
        if a.get("name") == asset:
            hit = a
            break
    if hit is None:
        return tag, None, None, None
    with fetch("https://api.github.com/repos/%s/releases/assets/%s" % (repo, hit["id"]),
               timeout, accept="application/octet-stream") as r:
        data = r.read()
    return tag, len(data), hashlib.sha256(data).hexdigest(), data


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--fail-on-update", action="store_true",
                    help="exit 1 when a newer module exists")
    ap.add_argument("--timeout", type=int, default=90)
    a = ap.parse_args()

    updates = 0
    errors = 0
    print("%-7s %-10s %-36s %-10s %s" % ("fork", "upstream", "sha256", "status", "local"))
    for label, path, repo, asset in LKMS:
        local = b""
        if os.path.exists(path):
            local = open(path, "rb").read()
        lsha = hashlib.sha256(local).hexdigest() if local else ""
        try:
            tag, size, rsha, _ = latest_asset(repo, asset, a.timeout)
            if rsha is None:
                status = "NO 6.18 ASSET"
                errors += 1
            elif lsha and rsha == lsha:
                status = "UP TO DATE"
            else:
                status = "UPDATE AVAILABLE"
                updates += 1
        except Exception as exc:  # network/API errors must not look like "current"
            tag, size, rsha, status = "?", None, "", "UNKNOWN (%s)" % type(exc).__name__
            errors += 1
        print("%-7s %-10s %-36s %-10s %s" % (
            label, tag, (rsha[:16] + "...") if rsha else "-", status,
            "%d B/%s..." % (len(local), lsha[:12]) if local else "(missing)"))
        if status == "UPDATE AVAILABLE":
            print("        local %d B != upstream %d B; update it in payload/lkm and rebuild"
                  % (len(local), size or 0))
    print()
    if updates:
        print("%d bundled module(s) can be updated." % updates)
    else:
        print("all bundled modules match the latest upstream release")
    if errors:
        print("%d probe(s) failed; treat those rows as unknown." % errors)
    return 1 if (a.fail_on_update and updates) else 0


if __name__ == "__main__":
    sys.exit(main())

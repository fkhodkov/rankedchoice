"""Download the NSW Electoral Commission's councillor counts for a local government election.

usage: download.py EVENT OUT_DIR

EVENT is the results site's event code (LG2401 is the 2024 ordinary elections). For every
council's councillor election (each ward's, where there are wards) this saves the ballot
papers (finalpreferencedatafile.zip) and the full official distribution of preferences
(dopfulldetails.xlsx) from https://pastvtr.elections.nsw.gov.au, as
OUT_DIR/<council>[_<ward>].zip and .xlsx. Files already there are kept; requests are made one at
a time.
"""
import os
import pathlib
import re
import sys
import time
import urllib.request

SITE = "https://pastvtr.elections.nsw.gov.au"


def get(url):
    request = urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0 (rankedchoice reference check)"})
    with urllib.request.urlopen(request, timeout=120) as response:
        return response.read()


def contests(event):
    """(name, councillor page path) for every councillor count."""
    index = get(f"{SITE}/{event}/index").decode()
    for council in sorted(set(re.findall(rf'href="/{event}/([^"/]+)/results"', index))):
        page = get(f"{SITE}/{event}/{council}/results").decode()
        paths = sorted(set(re.findall(rf'href="(/{event}/{council}/(?:[^"/]+/)?councillor)"', page)))
        for path in paths:
            parts = path.split("/")
            yield (council if len(parts) == 4 else f"{council}_{parts[3]}"), path
        time.sleep(0.3)


def main():
    event, out = sys.argv[1], sys.argv[2]
    os.makedirs(out, exist_ok=True)
    for name, path in contests(event):
        base = path.rsplit("/", 1)[0]       # the page's relative "download/..." links resolve here
        for remote, local in (("finalpreferencedatafile.zip", f"{name}.zip"), ("dopfulldetails.xlsx", f"{name}.xlsx")):
            target = os.path.join(out, local)
            if os.path.exists(target):
                continue
            try:
                data = get(f"{SITE}{base}/download/{remote}")
            except Exception as e:  # noqa: BLE001 -- report and carry on
                print(f"{name}: {remote}: {e}", flush=True)
                continue
            if data[:2] != b"PK":
                print(f"{name}: {remote}: not a zip/xlsx (no data published?)", flush=True)
                continue
            pathlib.Path(target).write_bytes(data)
            time.sleep(0.3)
        print(name, flush=True)


if __name__ == "__main__":
    main()

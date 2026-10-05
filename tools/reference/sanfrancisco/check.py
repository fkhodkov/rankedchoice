"""Check the irv ruleset against San Francisco's official ranked-choice counts, 2019-2024.

usage: check.py DATA_DIR [--jar PATH] [CONTEST ...]

CONTEST is one of CONTESTS below (all, by default): the contests of 2019, 2020, 2022 and 2024
whose final round-by-round results the Internet Archive kept (sfelections.org turns away
scripted requests) and whose ballots FairVote's collection of single-winner RCV cast vote
records (https://doi.org/10.7910/DVN/AMK8PJ) has. Each record is converted with
../albany/cvr_to_blt.py (an overvote ends the ballot; a skipped ranking or a write-in is passed
over), counted with `irv` (the Department of Elections' Dominion settings: "Declare winners by
threshold: False", candidates eliminated one at a time until two are left), and every official
round's votes compared with this count's, round for round, with the elimination order and the
winner. The latest capture of each result file is used: for 2024's city attorney, district
attorney and sheriff contests, the only captures predate certification. Where the record differs
from the official count by a few votes, each round's largest difference is reported; such a
contest passes on the same eliminations and winner.

The results come as an Excel file (2019, 2022: read with LibreOffice's soffice), a PDF (2020:
pdftotext) or a web page (2024), each with a row per candidate giving the votes, percentage and
transfer of every round.
"""
import argparse
import csv
import glob
import gzip
import html
import json
import os
import re
import shutil
import subprocess
import sys
import time
import urllib.parse
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
DEFAULT_JAR = os.path.join(HERE, "..", "..", "..", "target", "rankedchoice-0.1.0-SNAPSHOT.jar")
CONVERTER = os.path.join(HERE, "..", "albany", "cvr_to_blt.py")
DATAVERSE = "https://dataverse.harvard.edu/api/access/datafile/{id}?format=original"
RESULTS = "sfelections.org/results/"
# contest: (Dataverse file id, the results file under sfelections.org/results/)
CONTESTS = {
    "2019-supervisor-5": (11593655, "20191105/data/20191125/d5/20191125_d5_short.xlsx"),
    "2019-district-attorney": (11593601, "20191105/data/20191125/da/20191125_da_short.xlsx"),
    "2019-mayor": (11593618, "20191105/data/20191125/mayor/20191125_mayor_short.xlsx"),
    "2022-supervisor-4": (11593654, "20221108/data/20221201/d4/d4_short.xlsx"),
    "2022-supervisor-6": (11593622, "20221108/data/20221201/d6/d6_short.xlsx"),
    "2022-supervisor-8": (11593650, "20221108/data/20221201/d8/d8_short.xlsx"),
    "2022-supervisor-10": (11593632, "20221108/data/20221201/d10/d10_short.xlsx"),
    "2022-district-attorney": (11593627, "20221108/data/20221201/da/da_short.xlsx"),
    "2022-public-defender": (11593594, "20221108/data/20221201/defender/defender_short.xlsx"),
    "2024-mayor": (11593607, "20241105/final/round-pages/mayor_short-rounds-en.html"),
    "2024-city-attorney": (11593597, "20241105/final/round-pages/ca_short-rounds-en.html"),
    "2024-district-attorney": (11593649, "20241105/final/round-pages/da_short-rounds-en.html"),
    "2024-sheriff": (11593640, "20241105/final/round-pages/sheriff_short-rounds-en.html"),
}
for n, file_id in ((1, 11593588), (3, 11593637), (5, 11593590), (7, 11593635), (9, 11593589), (11, 11593583)):
    CONTESTS[f"2020-supervisor-{n}"] = (file_id, f"20201103/data/20201201/d{n}/20201201_d{n}_short.pdf")
for n, file_id in ((1, 11593580), (3, 11593648), (5, 11593611), (7, 11593578), (9, 11593579), (11, 11593628)):
    CONTESTS[f"2024-supervisor-{n}"] = (file_id, f"20241105/final/round-pages/d{n}_short-rounds-en.html")
NUMBER = re.compile(r"^[-+]?[\d,]+$")
PERCENT = re.compile(r"^[\d.]+%$")
NOT_CANDIDATES = ("continuing", "blanks", "exhausted", "overvote", "under vote", "non transferable", "total",
                  "remainder")


def fetch(path, url, attempts=3):
    """Downloads to a temporary name first, so a failed download leaves nothing behind."""
    if os.path.exists(path) and os.path.getsize(path) > 0:
        return path
    for attempt in range(attempts):
        try:
            request = urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0", "Accept-Encoding": "identity"})
            with urllib.request.urlopen(request, timeout=900) as response, open(path + ".part", "wb") as out:
                while chunk := response.read(1 << 20):
                    out.write(chunk)
            with open(path + ".part", "rb") as part:
                compressed = part.read(2) == b"\x1f\x8b"    # the archive sometimes sends gzip regardless
            if compressed:
                with gzip.open(path + ".part", "rb") as packed, open(path + ".unpacked", "wb") as out:
                    shutil.copyfileobj(packed, out)
                os.replace(path + ".unpacked", path + ".part")
            os.replace(path + ".part", path)
            return path
        except OSError:
            if attempt == attempts - 1:
                raise
    return path


def cdx(query, attempts=6):
    """One Wayback Machine index query, retried with a growing pause: the service rate-limits."""
    for attempt in range(attempts):
        try:
            body = urllib.request.urlopen("https://web.archive.org/cdx/search/cdx?" + query, timeout=300).read()
            return json.loads(body or b"[]")
        except (OSError, ValueError):
            if attempt == attempts - 1:
                raise
            time.sleep(10 * (attempt + 1))
    return []


def latest_capture(data, contest, path):
    """The Internet Archive's latest good capture of a results file, as a URL to fetch it as captured;
    remembered in DATA_DIR, so a rerun asks once."""
    remembered = os.path.join(data, contest + ".capture")
    if os.path.exists(remembered):
        url, stamp = open(remembered).read().split()
        return url, stamp
    best = None
    for host in ("www.", ""):
        query = urllib.parse.urlencode({"url": host + RESULTS + path, "output": "json", "fl": "timestamp,original",
                                        "filter": "statuscode:200"})
        for stamp, original in cdx(query)[1:]:
            if best is None or stamp > best[0]:
                best = (stamp, original)
    if best is None:
        raise ValueError(f"no capture of {path}")
    url = f"https://web.archive.org/web/{best[0]}id_/{best[1]}"
    open(remembered, "w").write(f"{url} {best[0]}\n")
    return url, best[0]


def rows_of(path):
    ext = os.path.splitext(path)[1].lower()
    if ext == ".html":
        page = open(path, encoding="utf-8", errors="ignore").read()
        for tr in re.findall(r"<tr[^>]*>(.*?)</tr>", page, re.S | re.I):
            yield [html.unescape(re.sub(r"<[^>]+>", "", c)).strip()
                   for c in re.findall(r"<t[dh][^>]*>(.*?)</t[dh]>", tr, re.S | re.I)]
    elif ext == ".xlsx":
        stem = os.path.splitext(path)[0]
        if not glob.glob(stem + "-*.csv"):
            # Every sheet (the last option), comma-separated, UTF-8.
            subprocess.run(["soffice", "--headless", "--convert-to",
                            "csv:Text - txt - csv (StarCalc):44,34,76,1,,0,false,true,false,false,false,-1",
                            "--outdir", os.path.dirname(path), path], check=True, capture_output=True)
        for sheet in sorted(glob.glob(stem + "-*.csv")):
            yield from csv.reader(open(sheet, encoding="utf-8", errors="ignore"))
    elif ext == ".pdf":
        text = subprocess.run(["pdftotext", "-layout", path, "-"], capture_output=True, text=True, check=True).stdout
        for line in text.splitlines():
            match = re.match(r"^\s*(\S.*?)\s{2,}([-+\d,.%\s]+)$", line)
            if match:
                yield [match.group(1)] + match.group(2).split()


def official_rounds(path):
    """{candidate: [votes in round 1, 2, ...]}: a candidate's row is the name, then votes,
    percentage and transfer per round (wrapped onto later pages in a PDF)."""
    totals = {}
    for row in rows_of(path):
        cells = [c.strip() for c in row if c and c.strip()]
        if len(cells) < 2 or NUMBER.match(cells[0]) or PERCENT.match(cells[0]):
            continue
        name, values = cells[0], cells[1:]
        if name.lower().startswith(NOT_CANDIDATES) or not all(NUMBER.match(v) or PERCENT.match(v) for v in values):
            continue
        if not any(PERCENT.match(v) for v in values):
            continue
        numbers = [int(v.replace(",", "").replace("+", "")) for v in values if NUMBER.match(v)]
        totals.setdefault(name, []).extend(numbers[0::2])
    return totals


def check(data, contest, jar):
    file_id, path = CONTESTS[contest]
    url, stamp = latest_capture(data, contest, path)
    local = fetch(os.path.join(data, contest + os.path.splitext(path)[1]), url)
    totals = official_rounds(local)
    rounds = max(len(v) for v in totals.values())
    blt = os.path.join(data, contest + ".blt")
    if not os.path.exists(blt):
        cvr = fetch(os.path.join(data, contest + ".csv"), DATAVERSE.format(id=file_id))
        subprocess.run([sys.executable, CONVERTER, cvr, blt, "--seats", "1"], check=True)
    result = subprocess.run(["java", "-jar", jar, "-i", blt, "--ruleset", "irv", "--report-format", "json"],
                            capture_output=True, text=True)
    if result.returncode:
        return False, f"{contest}: count failed: {result.stderr.strip()[-200:]}"
    ours = json.loads(result.stdout)
    exact, gaps = 0, []
    for k in range(rounds):
        mine = {name: int(float(v)) for name, v in ours["rounds"][k]["tallies"].items()} \
            if k < len(ours["rounds"]) else {}
        figures = {name: votes[k] for name, votes in totals.items() if k < len(votes) and votes[k] > 0}
        gap = max((abs(mine.get(name, 0) - votes) for name, votes in figures.items()), default=0)
        exact += gap == 0
        gaps.append(gap)
    # The official eliminations: who drops to nothing from one round to the next.
    eliminated = []
    for k in range(1, rounds):
        gone = [name for name, votes in totals.items()
                if len(votes) >= k and votes[k - 1] > 0 and (k >= len(votes) or votes[k] == 0)]
        eliminated.extend(sorted(gone, key=lambda name: totals[name][k - 1]))
    winner = max(totals, key=lambda name: totals[name][-1] if len(totals[name]) == rounds else -1)
    ours_eliminated = [r["action"]["candidate"] for r in ours["rounds"] if r["action"]["type"] == "eliminated"]
    named = [name for name in eliminated if name.upper().startswith("WRITE-IN") is False]
    same_order = [n for n in ours_eliminated if n in totals][:len(named)] == named
    same = ours["electedInOrder"] == [winner]
    line = (f"{contest}: {exact} of {rounds} official rounds match to the vote"
            + (f" (largest differences by round: {gaps})" if any(gaps) else "")
            + f"; eliminations in {'the same' if same_order else 'A DIFFERENT'} order"
            + f"; elected {'the same' if same else 'DIFFERENTLY: ' + str(ours['electedInOrder']) + ' vs ' + winner}"
            + f"; results captured {stamp[:8]}")
    return same_order and same, line


def main():
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("data")
    parser.add_argument("contests", nargs="*")
    parser.add_argument("--jar", default=DEFAULT_JAR)
    args = parser.parse_args()
    os.makedirs(args.data, exist_ok=True)
    chosen = args.contests or list(CONTESTS)
    failed = 0
    for contest in chosen:
        ok, line = check(args.data, contest, args.jar)
        failed += not ok
        print(line, flush=True)
    print(f"{len(chosen) - failed} of {len(chosen)} contests check out")
    sys.exit(1 if failed else 0)


if __name__ == "__main__":
    main()

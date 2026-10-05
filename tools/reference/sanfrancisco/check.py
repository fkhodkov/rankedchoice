"""Check the irv ruleset against San Francisco's official ranked-choice counts, 2010-2024.

usage: check.py DATA_DIR [--jar PATH] [CONTEST ...]

CONTEST is one of CONTESTS below (all, by default): the contests of 2010-2024 whose final
round-by-round results the Internet Archive kept (sfelections.org turns away scripted requests)
and whose ballots FairVote's collection of single-winner RCV cast vote records
(https://doi.org/10.7910/DVN/AMK8PJ) has. Each record is converted with ../albany/cvr_to_blt.py
(an overvote ends the ballot; a skipped ranking is passed over, as is a write-in from 2015 on,
while before that the reports count write-ins as a candidate, WRITE-IN) and counted with `irv`
(since 2019, the Department of Elections' Dominion settings: "Declare winners by threshold:
False", candidates eliminated one at a time until two are left). Every official round must be one
of this count's rounds, in order, with the same candidates continuing, and the same candidate
must win; the votes are compared, and where they differ, each round's largest difference is
reported. Before 2019 the Department defeated together the candidates who couldn't overtake the
next one up, which is several of this count's rounds in one, and stopped at a majority. The
latest capture of each result file is used: for 2024's city attorney, district attorney and
sheriff contests, the only captures predate certification, and are a few dozen votes short.

The results come as the counting system's pass report (2010-2014, a PDF: pdftotext), a web page
(2015-2018, 2024), an Excel file (2019, 2022: read with LibreOffice's soffice) or a PDF (2020),
the last three with a row per candidate giving the votes, percentage and transfer of every round.
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
# 2010-2014: the counting system's pass report, as a PDF.
for contest, file_id, path in (
        ("2010-supervisor-2", 11593519, "20101102/data/RCV-D2.pdf"),
        ("2010-supervisor-6", 11593562, "20101102/data/RCV-D6.pdf"),
        ("2010-supervisor-8", 11593527, "20101102/data/RCV-D8.pdf"),
        ("2010-supervisor-10", 11593524, "20101102/data/RCV-D10.pdf"),
        ("2011-district-attorney", 11593523, "20111108/data/RCV-DA.pdf"),
        ("2011-mayor", 11593557, "20111108/data/RCV-Mayor.pdf"),
        ("2011-sheriff", 11593552, "20111108/data/RCV-Sheriff.pdf"),
        ("2012-supervisor-5", 11593531, "20121106/data/D5-PassReport.pdf"),
        ("2012-supervisor-7", 11593532, "20121106/data/D7-PassReport.pdf"),
        ("2014-supervisor-10", 11593528, "20141104/data/RCV_D10.pdf")):
    CONTESTS[contest] = (file_id, path)
# 2015-2018, a web page per contest, in the folder of the last results update.
for year, update, ids in (
        ("2015", "20151103/data/20151119", {"supervisor-3": (11593533, "d3"), "mayor": (11593551, "mayor"),
                                            "sheriff": (11593541, "sheriff")}),
        ("2016", "20161108/data/20161206", {f"supervisor-{n}": (file_id, f"d{n}") for n, file_id in (
            (1, 11593560), (3, 11593539), (5, 11593558), (7, 11593525), (9, 11593563), (11, 11593570))}),
        ("2018-06", "20180605/data/20180627", {"supervisor-8": (11593572, "d8"), "mayor": (11593630, "mayor")}),
        ("2018", "20181106/data/20181127", {"assessor-recorder": (11593625, "assessor"),
                                            "public-defender": (11593600, "defender"),
                                            **{f"supervisor-{n}": (file_id, f"d{n}") for n, file_id in (
                                                (2, 11593582), (4, 11593602), (6, 11593615), (8, 11593652),
                                                (10, 11593617))}})):
    for name, (file_id, page) in ids.items():
        CONTESTS[f"{year}-{name}"] = (file_id, f"{update}/{page}/{update[-8:]}_{page}.html")
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


def pass_report(text):
    """{candidate: [votes after pass 0, 1, ...]} from the "Final State" table of each pass: a row is the name,
    perhaps a note ("Eliminated in pass 1.", "** WINNER **"), the votes and the percentage; a long name
    wraps onto the next line."""
    passes, reading = [], False
    for line in text.splitlines():
        cells = re.split(r"\s{2,}", line.strip())
        if cells[0] == "Final State":
            passes.append([])
            reading = True
        elif reading and "Continuing Ballots" in cells:
            reading = False
        elif reading and len(cells) >= 3 and PERCENT.match(cells[-1]) and NUMBER.match(cells[-2]):
            name = re.sub(r"\s*(Eliminated in pass \d+\.|\*\* WINNER \*\*)$", "", cells[0])
            passes[-1].append([name, int(cells[-2].replace(",", ""))])
        elif reading and len(cells) == 1 and cells[0] and passes[-1]:
            passes[-1][-1][0] += cells[0]
    totals = {}
    for rows in passes:
        for name, votes in rows:
            totals.setdefault(name, []).append(votes)
    return totals


def official_rounds(path):
    """{candidate: [votes in round 1, 2, ...]}: a candidate's row is the name, then votes,
    percentage and transfer per round (wrapped onto later pages in a PDF)."""
    if path.endswith(".pdf"):
        text = subprocess.run(["pdftotext", "-layout", path, "-"], capture_output=True, text=True,
                              check=True).stdout
        if "Pass Number" in text:
            return pass_report(text)
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


def key(name):
    return re.sub(r"[^A-Z0-9]", "", name.upper())


def check(data, contest, jar):
    file_id, path = CONTESTS[contest]
    url, stamp = latest_capture(data, contest, path)
    local = fetch(os.path.join(data, contest + os.path.splitext(path)[1]), url)
    # Names compared in letters and digits only: the reports and the records quote nicknames differently.
    totals = {key(name): votes for name, votes in official_rounds(local).items()}
    rounds = max(len(v) for v in totals.values())
    blt = os.path.join(data, contest + ".blt")
    if not os.path.exists(blt):
        cvr = fetch(os.path.join(data, contest + ".csv"), DATAVERSE.format(id=file_id))
        # Before 2015, the reports count write-ins of no qualified candidate as a candidate, WRITE-IN.
        subprocess.run([sys.executable, CONVERTER, cvr, blt, "--seats", "1"]
                       + (["--write-in-candidate"] if contest < "2015" else []), check=True)
    result = subprocess.run(["java", "-jar", jar, "-i", blt, "--ruleset", "irv", "--report-format", "json"],
                            capture_output=True, text=True)
    if result.returncode:
        return False, f"{contest}: count failed: {result.stderr.strip()[-200:]}"
    ours = json.loads(result.stdout)
    # Each official round is a round of this count, in order, with the same candidates continuing: before 2019,
    # the hopeless were defeated together, and a round was printed when nothing changed.
    exact, gaps, found, start = 0, [], 0, 0
    for k in range(rounds):
        figures = {name: votes[k] for name, votes in totals.items() if k < len(votes) and votes[k] > 0}
        hit = next((i for i in range(start, len(ours["rounds"]))
                    if {key(name) for name, v in ours["rounds"][i]["tallies"].items() if float(v) > 0} == set(figures)),
                   None)
        if hit is None:
            gaps.append(None)
            continue
        found, start = found + 1, hit
        mine = {key(name): int(float(v)) for name, v in ours["rounds"][hit]["tallies"].items()}
        gap = max(abs(mine.get(name, 0) - votes) for name, votes in figures.items())
        exact += gap == 0
        gaps.append(gap)
    winner = max(totals, key=lambda name: totals[name][-1] if len(totals[name]) == rounds else -1)
    same_order = found == rounds
    same = [key(name) for name in ours["electedInOrder"]] == [winner]
    line = (f"{contest}: {exact} of {rounds} official rounds match to the vote"
            + (f" (largest differences by round: {gaps})" if any(gaps) else "")
            + ("; eliminations the same" if same_order
               else f"; ELIMINATIONS DIFFER from official round {gaps.index(None) + 1}")
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

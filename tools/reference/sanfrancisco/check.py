"""Check the irv ruleset against San Francisco's official ranked-choice counts, 2010-2024.

usage: check.py DATA_DIR [--jar PATH] [CONTEST ...]

CONTEST is one of CONTESTS below (all, by default): the contests of 2010-2024 whose final
round-by-round results the Internet Archive kept (sfelections.org turns away scripted requests)
and whose ballots FairVote's collection of single-winner RCV cast vote records
(https://doi.org/10.7910/DVN/AMK8PJ) has. Each record is converted with ../cvr_to_blt.py
(an overvote ends the ballot; a skipped ranking is passed over, as is a write-in from 2015 on,
while before that the reports count write-ins as a candidate, WRITE-IN) and counted with `irv`
(since 2019, the Department of Elections' Dominion settings: "Declare winners by threshold:
False", candidates eliminated one at a time until two are left). Every official round must be one
of this count's rounds, in order, with the same candidates continuing, and the same candidate
must win; the votes are compared, and where they differ, each round's largest difference is
reported. Before 2019 the Department defeated together the candidates who couldn't overtake the
next one up, which is several of this count's rounds in one, and stopped at a majority. The
latest capture of each result file is used (the choice remembered for RECHECK_DAYS, then looked up
again, so a later capture is found): for 2024's city attorney, district attorney and
sheriff contests, the only captures predate certification, and are a few dozen votes short. For
2024 the record's first rankings are also compared with the certified statement of vote's,
citywide (each candidate, undervotes, overvotes), which holds for all ten contests. From 2019, each
elimination's transfers are compared with the report's (rcvcheck.transfer_line).

The results come as the counting system's pass report (2010-2014, a PDF: pdftotext), a web page
(2015-2018, 2024), an Excel file (2019, 2022: read with LibreOffice's soffice) or a PDF (2020),
the last three with a row per candidate giving the votes, percentage and transfer of every round.
"""
import collections
import csv
import datetime
import json
import os
import re
import subprocess
import sys
import time
import urllib.parse
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, ".."))
from rcvcheck import (DATAVERSE, count, fetch, main, match_rounds, name_key as key, pass_report,  # noqa: E402
                      pdf_text, sheets, stale, summary, table_rows, transfer_line, winner)

CONVERTER = os.path.join(HERE, "..", "cvr_to_blt.py")
RESULTS = "sfelections.org/results/"
RECHECK_DAYS = 30
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
# The certified statement of vote of November 2024, a sheet per contest: its first rankings, citywide.
STATEMENT_2024 = "20241105/data/20241203/sov.xlsx"
QUALIFIED = "qualified write-ins"
SHEETS_2024 = {"2024-mayor": "MAYOR", "2024-city-attorney": "CITY ATTORNEY",
               "2024-district-attorney": "DISTRICT ATTORNEY", "2024-sheriff": "SHERIFF",
               **{f"2024-supervisor-{n}": f"MEMBER, BOARD OF SUPERVISORS, DISTRICT {n}" for n in (1, 3, 5, 7, 9, 11)}}
NUMBER = re.compile(r"^[-+]?[\d,]+$")
PERCENT = re.compile(r"^[\d.]+%$")
NOT_CANDIDATES = ("continuing", "blanks", "exhausted", "overvote", "under vote", "non transferable", "total",
                  "remainder")


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


def latest_capture(data, contest, path, today=None):
    """The Internet Archive's latest good capture of a results file, as a URL to fetch it as captured, and its
    timestamp. Remembered in DATA_DIR with the day the index was asked, and reused for RECHECK_DAYS, so a rerun
    asks once but a later capture (a certified report, say) is found within a month; where the index can't be
    reached, the remembered capture is used."""
    remembered = os.path.join(data, contest + ".capture")
    today = today or datetime.date.today()
    known = open(remembered).read().split() if os.path.exists(remembered) else []
    if len(known) == 3 and (today - datetime.date.fromisoformat(known[2])).days < RECHECK_DAYS:
        return known[0], known[1]
    best = None
    try:
        for host in ("www.", ""):
            query = urllib.parse.urlencode({"url": host + RESULTS + path, "output": "json",
                                            "fl": "timestamp,original", "filter": "statuscode:200"})
            for stamp, original in cdx(query)[1:]:
                if best is None or stamp > best[0]:
                    best = (stamp, original)
    except (OSError, ValueError):
        if known:
            return known[0], known[1]
        raise
    if best is None:
        raise ValueError(f"no capture of {path}")
    url = f"https://web.archive.org/web/{best[0]}id_/{best[1]}"
    open(remembered, "w").write(f"{url} {best[0]} {today.isoformat()}\n")
    return url, best[0]


def rows_of(path):
    if path.lower().endswith(".pdf"):
        for line in pdf_text(path).splitlines():
            match = re.match(r"^\s*(\S.*?)\s{2,}([-+\d,.%\s]+)$", line)
            if match:
                yield [match.group(1)] + match.group(2).split()
    else:
        yield from table_rows(path)


def official_rounds(path):
    """{candidate: [votes in round 1, 2, ...]}: a candidate's row is the name, then votes,
    percentage and transfer per round (wrapped onto later pages in a PDF)."""
    if path.endswith(".pdf"):
        text = pdf_text(path)
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


def official_transfers(path, totals):
    """From 2019, when one candidate goes a round: each elimination's transfers, [(eliminated, {destination:
    ballots})], from the change between its round and the next in each candidate's votes and in the "Exhausted" and
    "Overvotes" rows (their votes, then their transfer, round by round)."""
    gone = {}
    for row in rows_of(path):
        cells = [c.strip() for c in row if c and c.strip()]
        if cells and cells[0] in ("Exhausted", "Overvotes") and all(NUMBER.match(v) for v in cells[1:]):
            gone[cells[0]] = [int(v.replace(",", "").replace("+", "")) for v in cells[1:]][0::2]
    moves = []
    for k in range(len(min(gone.values(), key=len, default=[])) - 1):
        losers = [name for name, votes in totals.items() if len(votes) > k and votes[k] and
                  (len(votes) <= k + 1 or not votes[k + 1])]
        if len(losers) != 1:
            continue
        gains = {name: votes[k + 1] - votes[k] for name, votes in totals.items()
                 if name != losers[0] and len(votes) > k + 1 and votes[k + 1] != votes[k]}
        gains.update({row: figures[k + 1] - figures[k] for row, figures in gone.items()})
        moves.append((losers[0], gains))
    return moves


def statement_of_vote(path):
    """{contest title: {candidate (by key), UNDERVOTES or OVERVOTES: first rankings}}: each sheet's title,
    in its second row, its header row (from "Precinct") naming each column, and its "Countywide - Total" row.
    A qualified write-in is named "<NAME> Qualified Write In", and also counted in "Write-in", the write-ins'
    total; the qualified ones are listed under QUALIFIED."""
    found = {}
    for sheet in sheets(path):
        rows = list(csv.reader(open(sheet, encoding="utf-8")))
        header = next((row for row in rows if row and row[0] == "Precinct"), None)
        total = next((row for row in rows if row and row[0] == "Countywide - Total"), None)
        if len(rows) < 2 or not rows[1] or header is None or total is None:
            continue
        figures = {QUALIFIED: []}
        for name, figure in zip(header[1:], total[1:]):
            name = " ".join(name.split())
            if name and name not in ("Registered Voters", "Precinct", "Total Votes") and NUMBER.match(figure):
                if name.endswith(" Qualified Write In"):
                    name = name[:-len(" Qualified Write In")]
                    figures[QUALIFIED].append(key(name))
                figures[key(name)] = int(figure.replace(",", ""))
        found[" ".join(rows[1][0].split())] = figures
    return found


def first_rankings(cvr):
    """The record's first rankings, as the statement of vote counts them: each candidate's (by key), UNDERVOTES
    (skipped) and OVERVOTES."""
    source = open(cvr, newline="", encoding="utf-8-sig")
    delimiter = "\t" if "\t" in source.readline() else ","
    source.seek(0)
    counts = collections.Counter()
    for row in csv.DictReader(source, delimiter=delimiter):
        mark = row["rank1"].strip()
        counts[{"": "UNDERVOTES", "skipped": "UNDERVOTES", "undervote": "UNDERVOTES",
                "overvote": "OVERVOTES"}.get(mark.lower(), key(mark))] += 1
    return counts


def certified_line(data, contest, cvr):
    """For 2024, how the record's first rankings compare with the certified statement of vote's."""
    if contest not in SHEETS_2024:
        return ""
    url, stamp = latest_capture(data, "2024-statement-of-vote", STATEMENT_2024)
    statement = statement_of_vote(fetch(os.path.join(data, f"2024-statement-of-vote-{stamp}.xlsx"), url))
    theirs, ours = dict(statement[SHEETS_2024[contest]]), first_rankings(cvr)
    # The record names a qualified write-in as a candidate: "Write-in" is all its write-ins.
    ours[key("Write-in")] += sum(ours[name] for name in theirs.pop(QUALIFIED))
    gap = max(abs(ours[name] - figure) for name, figure in theirs.items())
    return ("; first rankings the certified statement of vote's" if gap == 0
            else f"; first rankings up to {gap} off the certified statement of vote") + f" (captured {stamp[:8]})"


def check(data, contest, jar):
    file_id, path = CONTESTS[contest]
    url, stamp = latest_capture(data, contest, path)
    # Named by its capture, so a later capture is fetched anew, not mistaken for the one already here.
    local = fetch(os.path.join(data, f"{contest}-{stamp}{os.path.splitext(path)[1]}"), url)
    # Names compared in letters only: the reports and the records quote nicknames differently.
    totals = {key(name): votes for name, votes in official_rounds(local).items()}
    blt = os.path.join(data, contest + ".blt")
    cvr = fetch(os.path.join(data, contest + ".csv"), DATAVERSE.format(id=file_id))
    if stale(blt, cvr):
        # Before 2015, the reports count write-ins of no qualified candidate as a candidate, WRITE-IN.
        subprocess.run([sys.executable, CONVERTER, cvr, blt, "--title", contest]
                       + (["--write-in", "candidate", "--write-in-name", "WRITE-IN"] if contest < "2015" else []),
                       check=True)
    ours, error = count(jar, blt)
    if error:
        return False, f"{contest}: count failed: {error}"
    # Each official round is a round of this count, in order, with the same candidates continuing: before 2019,
    # the hopeless were defeated together, and a round was printed when nothing changed.
    exact, gaps = match_rounds(totals, ours, key)
    same = [key(name) for name in ours["electedInOrder"]] == [winner(totals)]
    transfers = transfer_line(official_transfers(local, totals), ours, key) if contest >= "2019" else ""
    return None not in gaps and same, summary(contest, exact, gaps, same, ours["electedInOrder"]) + \
        f"; results captured {stamp[:8]}" + transfers + certified_line(data, contest, cvr)


if __name__ == "__main__":
    main(__doc__.split("\n\n")[0], CONTESTS, check)

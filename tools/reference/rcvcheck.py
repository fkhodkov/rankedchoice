"""What the reference checkers of US ranked-choice counts share: fetching, matching names, counting, and matching
the official rounds with this count's, to the vote (single-seat) or to the official decimal places (multi-seat).

The checkers (alameda, alaska, maine, nyc, sanfrancisco; albany, eastpointe, minneapolis, portland) import it from
the directory above theirs.
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
import unicodedata
import urllib.request
from decimal import Decimal

DEFAULT_JAR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "target",
                           "rankedchoice-0.1.0-SNAPSHOT.jar")
DATAVERSE = "https://dataverse.harvard.edu/api/access/datafile/{id}?format=original"


def fetch(path, url, attempts=3):
    """Downloads to a temporary name first, so a failed or cut-off download leaves nothing behind."""
    if os.path.exists(path) and os.path.getsize(path) > 0:
        return path
    for attempt in range(attempts):
        try:
            request = urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0", "Accept-Encoding": "identity"})
            with urllib.request.urlopen(request, timeout=900) as response, open(path + ".part", "wb") as out:
                while chunk := response.read(1 << 20):
                    out.write(chunk)
            with open(path + ".part", "rb") as part:
                compressed = part.read(2) == b"\x1f\x8b"    # the Internet Archive sometimes sends gzip regardless
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


def name_key(name, initials=True, one_write_in=False):
    """A name's letters, so a record's spelling and a report's meet. With initials=False, single letters are left
    out ("Selvena N. Brooks-Powers", "Selvena Brooks-Powers"); with one_write_in, every write-in label is one name,
    WRITEIN ("Write-ins", "WRITE-IN"), where named write-ins are otherwise candidates of their own."""
    plain = unicodedata.normalize("NFKD", name).encode("ascii", "ignore").decode().upper()
    words = (re.sub(r"[^A-Z]", "", word) for word in plain.split())
    letters = "".join(word for word in words if initials or len(word) > 1)
    return "WRITEIN" if one_write_in and letters.startswith("WRITEIN") else letters


def table_rows(path):
    """The cells of each table row of a web page, or of each row of every sheet of a spreadsheet (converted with
    LibreOffice's soffice, once, to CSV files next to it)."""
    if path.lower().endswith((".html", ".htm")):
        page = open(path, encoding="utf-8", errors="ignore").read()
        for tr in re.findall(r"<tr[^>]*>(.*?)</tr>", page, re.S | re.I):
            yield [re.sub(r"\s+", " ", html.unescape(re.sub(r"<[^>]+>", "", c))).strip()
                   for c in re.findall(r"<t[dh][^>]*>(.*?)</t[dh]>", tr, re.S | re.I)]
        return
    stem = os.path.splitext(path)[0]
    if not glob.glob(stem + "-*.csv"):
        # Every sheet (the last option), comma-separated, UTF-8.
        subprocess.run(["soffice", "--headless", "--convert-to",
                        "csv:Text - txt - csv (StarCalc):44,34,76,1,,0,false,true,false,false,false,-1",
                        "--outdir", os.path.dirname(path) or ".", path], check=True, capture_output=True)
    for sheet in sorted(glob.glob(stem + "-*.csv")):
        yield from csv.reader(open(sheet, encoding="utf-8", errors="ignore"))


def pdf_text(path):
    return subprocess.run(["pdftotext", "-layout", path, "-"], capture_output=True, text=True, check=True).stdout


def pass_report(text):
    """{candidate: [votes after pass 0, 1, ...]} from the "Final State" table of each pass of a pass report (the
    counting system San Francisco used to 2014 and Alameda County to 2017): a row is the name, perhaps a note
    ("Eliminated in pass 1.", "** WINNER **"), the votes and the percentage; a long name wraps onto the next line."""
    passes, reading = [], False
    for line in text.splitlines():
        cells = re.split(r"\s{2,}", line.strip())
        if cells[0] == "Final State":
            passes.append([])
            reading = True
        elif reading and "Continuing Ballots" in cells:
            reading = False
        elif reading and len(cells) >= 3 and re.match(r"^[\d.]+%$", cells[-1]) and re.match(r"^[\d,]+$", cells[-2]):
            name = re.sub(r"\s*(Eliminated in pass \d+\.|\*\* WINNER \*\*)$", "", cells[0])
            passes[-1].append([name, int(cells[-2].replace(",", ""))])
        elif reading and len(cells) == 1 and cells[0] and passes[-1]:
            passes[-1][-1][0] += cells[0]
    totals = {}
    for rows in passes:
        for name, votes in rows:
            totals.setdefault(name, []).append(votes)
    return totals


def count(jar, blt, rules=("--ruleset", "irv")):
    """This count of a BLT file, as its JSON report, or the error it stopped with."""
    result = subprocess.run(["java", "-jar", jar, "-i", blt, "--report-format", "json", *rules],
                            capture_output=True, text=True)
    if result.returncode:
        return None, result.stderr.strip()[-200:]
    return json.loads(result.stdout), None


def match_rounds(totals, ours, key, first=0):
    """Each official round from the first-th, {name key: [votes in round 1, 2, ...]}, is required to be one of this
    count's rounds, in order, with the same candidates continuing: official counts defeat the hopeless together,
    stop at a majority, or print a round that changes nothing, and none of that changes the rounds passed through.
    Returns how many match to the vote, and each round's largest difference (None where no round of this count
    has its candidates)."""
    exact, gaps, start = 0, [], 0
    for k in range(first, max(len(votes) for votes in totals.values())):
        figures = {name: votes[k] for name, votes in totals.items() if k < len(votes) and votes[k] > 0}
        hit = next((i for i in range(start, len(ours["rounds"]))
                    if {key(name) for name, v in ours["rounds"][i]["tallies"].items() if float(v) > 0}
                    == set(figures)), None)
        if hit is None:
            gaps.append(None)
            continue
        start = hit
        mine = {key(name): int(float(v)) for name, v in ours["rounds"][hit]["tallies"].items()}
        gap = max(abs(mine.get(name, 0) - votes) for name, votes in figures.items())
        exact += gap == 0
        gaps.append(gap)
    return exact, gaps


def winner(totals):
    """The official winner: the candidate with the most votes in the last round."""
    rounds = max(len(votes) for votes in totals.values())
    return max(totals, key=lambda name: totals[name][-1] if len(totals[name]) == rounds else -1)


def summary(contest, exact, gaps, same, elected, rounds="official rounds", round_name="official round", first=1):
    """A contest's line: how many rounds match to the vote, where the rounds' candidates first differ, if they do,
    and whether the same candidate wins. Rounds are numbered from first."""
    return (f"{contest}: {exact} of {len(gaps)} {rounds} match to the vote"
            + (f" (largest differences by {'pass' if rounds == 'passes' else 'round'}: {gaps})" if any(gaps) else "")
            + ("; eliminations the same" if None not in gaps
               else f"; ELIMINATIONS DIFFER from {round_name} {gaps.index(None) + first}")
            + f"; elected {'the same' if same else 'DIFFERENTLY: ' + str(elected)}")


def unmatched_rounds(official, ours, figure=dict.get):
    """Each official round, (label, {candidate: votes}), is required to be one of this count's rounds, in order,
    every candidate of this count's round on exactly the official figure, figure(figures, name) finding it.
    Returns the labels of the official rounds that aren't."""
    def agrees(figures, tallies):
        return all(figure(figures, name) == Decimal(votes) for name, votes in tallies.items())

    start, unmatched = 0, []
    for label, figures in official:
        hit = next((i for i in range(start, len(ours["rounds"])) if agrees(figures, ours["rounds"][i]["tallies"])),
                   None)
        if hit is None:
            unmatched.append(label)
        else:
            start = hit
    return unmatched


def threshold_line(contest, ours, official_threshold, matched, rounds, same, elected, quota="threshold"):
    """A multi-seat contest's line: this count's threshold and the official one, how many official rounds are
    rounds of this count, and whether the same candidates are elected."""
    return (f"{contest}: {quota} {Decimal(ours['quota']):f} (official {official_threshold}); "
            f"{matched} of {rounds} official rounds match a round of this count; "
            f"elected {'the same' if same else 'DIFFERENTLY: ' + str(elected)}")


def record(contest, ok, line):
    """A contest's result as data, read off its line: how many official rounds (or passes) match out of how many,
    and whether the same candidates are elected. Contests checked by winners only have no rounds."""
    rounds = re.search(r"[:;] (\d+) of (\d+) (?:official rounds|passes) match", line)
    return {"contest": contest, "ok": ok,
            "matched": int(rounds.group(1)) if rounds else None, "rounds": int(rounds.group(2)) if rounds else None,
            "winners_same": "DIFFERENTLY" not in line and "count failed" not in line, "line": line}


def main(description, contests, check):
    """Checks the contests named on the command line (all, by default) with check(data, contest, jar), which returns
    whether the contest checks out and its line. With --results FILE, the results are also written there as JSON,
    for the results page (../results_page.py)."""
    parser = argparse.ArgumentParser(description=description)
    parser.add_argument("data")
    parser.add_argument("contests", nargs="*")
    parser.add_argument("--jar", default=DEFAULT_JAR)
    parser.add_argument("--results", help="write each contest's result to this JSON file")
    args = parser.parse_args()
    os.makedirs(args.data, exist_ok=True)
    chosen = args.contests or list(contests)
    failed, results = 0, []
    for contest in chosen:
        ok, line = check(args.data, contest, args.jar)
        failed += not ok
        results.append(record(contest, ok, line))
        print(line, flush=True)
    print(f"{len(chosen) - failed} of {len(chosen)} contests check out")
    if args.results:
        with open(args.results, "w", encoding="utf-8") as out:
            json.dump({"checker": os.path.basename(os.path.dirname(os.path.abspath(sys.argv[0]))),
                       "date": time.strftime("%Y-%m-%d"), "contests": results}, out, indent=1, ensure_ascii=False)
            out.write("\n")
    sys.exit(1 if failed else 0)

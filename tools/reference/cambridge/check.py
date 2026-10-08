"""Check the cambridge method against Cambridge's official counts, 2001-2023.

usage: check.py DATA_DIR [--jar PATH] [CONTEST ...]

CONTEST is council-YEAR or school-YEAR, as in official.json (all 22, by default): the City Council
(9 seats) and School Committee (6 seats) elections of 2001-2023 that FairVote's collection of
proportional RCV cast vote records has (https://doi.org/10.7910/DVN/STVUET). Each record is
fetched into DATA_DIR, converted (cvr_to_blt.py, keeping the ballots' order) and counted with
--method cambridge, and checked against the official count sheet the Cambridge Civic Journal keeps
for it (official.json's `sheet`, also fetched into DATA_DIR; read by its coordinates with pdftotext -bbox):

- 2001-2017: every official count's totals must be one of this count's rounds, in order, for
  every candidate but the write-ins, at the official quota, with the same candidates elected. A
  sheet's last count that only clears the defeated candidates' votes after the last seat is
  filled isn't a round here, and is let pass. Each count's exhausted ballots are compared with
  this count's exhausted votes.
- 2019-2023: the candidates elected only. These records mark no overvotes, so their first counts
  are a few votes a candidate off the City's, and no count can match.

Names are spelled differently in the records and the sheets ("Toomey, Tim", "Toomey, Jr.,
Timothy J."), so they're matched on surname, and on first name where two share one.
"""
import argparse
import collections
import html
import json
import os
import re
import subprocess
import sys
import unicodedata
from decimal import Decimal

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
from rcvcheck import exhausted_line, fetch as rcvcheck_fetch, record, stale, write_results  # noqa: E402

HERE = os.path.dirname(os.path.abspath(__file__))
DEFAULT_JAR = os.path.join(HERE, "..", "..", "..", "target", "rankedchoice-0.1.0-SNAPSHOT.jar")
DATAVERSE = "https://dataverse.harvard.edu/api/access/datafile/{id}?format=original"
# The Civic Journal's server turns away requests that don't look like a browser's.
HEADERS = {"User-Agent": "Mozilla/5.0 (X11; Linux x86_64; rv:128.0) Gecko/20100101 Firefox/128.0",
           "Accept": "*/*", "Accept-Language": "en-US,en;q=0.5", "Accept-Encoding": "identity"}
SUFFIXES = {"jr", "jr."}
WORD = re.compile(r'<word xMin="([\d.]+)" yMin="([\d.]+)" xMax="([\d.]+)" yMax="([\d.]+)">([^<]*)</word>')
FIGURE = re.compile(r"-?\d[\d,]*$")
LAST_WITH_ROUNDS = 2017


def fetch(path, url):
    return rcvcheck_fetch(path, url, headers=HEADERS)


def words(text):
    text = unicodedata.normalize("NFKD", text).encode("ascii", "ignore").decode().lower()
    return [w.strip(".\"") for w in re.split(r"[\s,]+", text) if w.strip(".\"") and w.strip(".\"") not in SUFFIXES]


def name_parts(name):
    """(surname, given names) of "Last, First" or "First Last"."""
    if "," in name:
        last, given = name.split(",", 1)
        return words(last)[-1], set(words(given))
    parts = words(name)
    return parts[-1], set(parts[:-1])


def find(names, name):
    """The one of `names` that's `name`, or None."""
    surname, given = name_parts(name)
    found = [n for n in names if name_parts(n)[0] == surname]
    if len(found) > 1:
        found = [n for n in found if name_parts(n)[1] & given] or found
    return found[0] if len(found) == 1 else None


def sheet_rows(pdf, part=None):
    """Every row of the sheet's pages (those with PART in them, if given), top to bottom: (name, [(centre, figure)]),
    the name being the words before the first figure, each figure's centre in the PDF's coordinates."""
    out = subprocess.run(["pdftotext", "-bbox", pdf, "-"], capture_output=True, text=True, check=True).stdout
    found = []
    for page in out.split("<page ")[1:]:
        words = [(float(x0), float(y0), float(x1), html.unescape(w)) for x0, y0, x1, _, w in WORD.findall(page)]
        if part is not None and part not in " ".join(w for *_, w in words):
            continue
        lines = collections.OrderedDict()
        for x0, y0, x1, w in sorted(words, key=lambda word: word[1]):
            key = next((k for k in lines if abs(k - y0) <= 2), y0)
            lines.setdefault(key, []).append((x0, x1, w))
        for line in lines.values():
            names, figures = [], []
            for x0, x1, w in sorted(line):
                if FIGURE.match(w):
                    figures.append(((x0 + x1) / 2, int(w.replace(",", ""))))
                elif not figures:
                    names.append(w)
            found.append((" ".join(names), figures))
    return found


def sheet_totals(pdf, part=None):
    """{row name: [figure after count 1, 2, ...]} from a count sheet, every row (the Exhausted row among them) as
    long as the sheet has counts, a count that gives a row no figure keeping the last. A sheet is tables, each from a
    header row (with "Candidate") to the next, whose Total row gives each count's change (0) and total (the ballots):
    their centres mark the count's columns, and a figure is the count's whose total column it's centred in, the
    change columns passed over (a count that changes nothing may give a row only its total). Counts follow on from
    table to table: a table whose Total row starts with a total repeats the last count, or finishes it where the
    last table ended on its change; one starting with a change starts the next. A table counting other ballots (a
    later vacancy's recount) is passed over. Where the file holds more than one count (the 2013 Council's original
    count and its recount), PART names the one to read: only pages with it are."""
    series, last, ballots, split = {}, 0, None, False
    tables, current = [], None
    for name, figures in sheet_rows(pdf, part):
        if "Candidate" in name.split():
            current = []
            tables.append(current)
        elif current is not None:
            current.append((name, figures))
    for body in tables:
        total = next((f for n, f in body if re.match(r"Total( valid ballots)?$", n) and len(f) > 1), None)
        if not total:
            continue
        these = max(v for _, v in total)
        ballots = these if ballots is None else ballots
        if these != ballots:
            continue
        columns, k = {}, last
        for i, (centre, value) in enumerate(total):
            if value != these:
                continue
            if i == 0 and last:
                k = last + 1 if split else last
            else:
                k += 1
            columns[k] = centre
        split = total[-1][1] != these
        cols = sorted(columns.items())
        spacing = min((b - a for (_, a), (_, b) in zip(cols, cols[1:])), default=40)
        for name, figures in body:
            if not name or not figures:
                continue
            row = series.setdefault(name, {})
            for centre, value in figures:
                k, c = min(cols, key=lambda col, at=centre: abs(col[1] - at))
                if abs(c - centre) < spacing / 4:
                    row.setdefault(k, value)
        last = max(last, max(columns))
    out = {}
    for name, row in series.items():
        values, current_value = [], 0
        for k in range(1, last + 1):
            current_value = row.get(k, current_value)
            values.append(current_value)
        out[name] = values
    return out


def match_counts(ours, totals):
    """How many official counts there are, and those that aren't rounds of this count (1-based).
    A last count that only clears the defeated after every seat is filled isn't one."""
    rows = {}
    for round_ in ours["rounds"]:
        for name in round_["tallies"]:
            if name not in rows and not name.lower().startswith("write"):
                found = find(totals, name)
                rows[name] = totals[found] if found else None
    missing = [name for name, row in rows.items() if row is None]
    if missing:
        raise ValueError(f"not on the sheet: {missing}")
    counts = max(len(row) for row in totals.values())
    start, unmatched = 0, []
    for k in range(counts):
        hit = next((i for i in range(start, len(ours["rounds"]))
                    if all(len(rows[name]) <= k or rows[name][k] == float(votes)
                           for name, votes in ours["rounds"][i]["tallies"].items() if name in rows)), None)
        if hit is None:
            unmatched.append(k + 1)
        else:
            start = hit
    if unmatched == [counts]:
        # Every seat was filled the count before: the last only moves the defeated's votes away.
        holding = [row for row in rows.values() if len(row) >= counts and row[-1] > 0]
        if len(holding) == len(ours["electedInOrder"]) and all(row[-1] == row[-2] for row in holding):
            unmatched = []
    return counts, unmatched


def exhausted(ours, totals):
    """The sheet's Exhausted row against this count's exhausted votes, in each count that is a round of this count
    (the write-ins, which the sheet lumps, left out of the matching; as this count defeats them one a round, where
    the City may take them together, a count may be any of the rounds they go in, in order)."""
    row = next((votes for name, votes in totals.items() if name.lower().startswith("exhaust")), None)
    if not row:
        return ""
    names = {name for round_ in ours["rounds"] for name in round_["tallies"]}
    write_ins = tuple(name for name in names if name.lower().startswith("write"))
    found = {name: totals[find(totals, name)] for name in names if name not in write_ins and find(totals, name)}
    return exhausted_line([({name: Decimal(votes[k]) for name, votes in found.items()}, Decimal(row[k]), None)
                           for k in range(len(row))], ours, ignore=write_ins, runs=True)


def check(data, contest, official, jar):
    year = int(contest.split("-")[1])
    csv_path = fetch(os.path.join(data, contest + ".csv"), DATAVERSE.format(id=official["dataverseFile"]))
    blt = os.path.join(data, contest + ".blt")
    if stale(blt, csv_path):
        subprocess.run([sys.executable, os.path.join(HERE, "cvr_to_blt.py"), csv_path, blt,
                        "--seats", str(official["seats"])], check=True)
    result = subprocess.run(["java", "-jar", jar, "-i", blt, "-m", "cambridge", "--report-format", "json"],
                            capture_output=True, text=True)
    if result.returncode:
        return record(contest, False, f"{contest}: count failed: {result.stderr.strip()[-200:]}")
    ours = json.loads(result.stdout)
    left, missing = list(ours["electedInOrder"]), []
    for name in official["elected"]:
        found = find(left, name)
        if found:
            left.remove(found)
        else:
            missing.append(name)
    line = f"{contest}: quota {ours['quota']} (official {official['quota']})"
    ok = not missing
    counts = unmatched = None
    if year <= LAST_WITH_ROUNDS:
        sheet = fetch(os.path.join(data, contest + ".pdf"), official["sheet"])
        totals = sheet_totals(sheet, official.get("part"))
        counts, unmatched = match_counts(ours, totals)
        line += f"; {counts - len(unmatched)} of {counts} official counts match a round of this count"
        if unmatched:
            line += f" (first unmatched: count {unmatched[0]})"
        line += exhausted(ours, totals)
        ok = ok and not unmatched and int(ours["quota"]) == official["quota"]
    if missing:
        line += f"; elected {', '.join(left)} where the City elected {', '.join(missing)}"
    else:
        line += f"; the same {official['seats']} elected"
    return record(contest, ok, line, matched=None if counts is None else counts - len(unmatched), rounds=counts,
                  winners_same=not missing)


def main():
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("data")
    parser.add_argument("contests", nargs="*")
    parser.add_argument("--jar", default=DEFAULT_JAR)
    parser.add_argument("--results", help="write each contest's result to this JSON file, for the results page")
    args = parser.parse_args()
    officials = json.load(open(os.path.join(HERE, "official.json"), encoding="utf-8"))
    contests = args.contests or list(officials)
    os.makedirs(args.data, exist_ok=True)
    failed, results = 0, []
    for contest in contests:
        result = check(args.data, contest, officials[contest], args.jar)
        failed += not result["ok"]
        results.append(result)
        print(result["line"], flush=True)
    print(f"{len(contests) - failed} of {len(contests)} contests check out")
    if args.results:
        write_results(args.results, "cambridge", results)
    sys.exit(1 if failed else 0)


if __name__ == "__main__":
    main()

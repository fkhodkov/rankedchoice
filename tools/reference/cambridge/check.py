"""Check the cambridge method against Cambridge's official counts, 2001-2023.

usage: check.py DATA_DIR [--jar PATH] [CONTEST ...]

CONTEST is council-YEAR or school-YEAR, as in official.json (all 22, by default): the City Council
(9 seats) and School Committee (6 seats) elections of 2001-2023 that FairVote's collection of
proportional RCV cast vote records has (https://doi.org/10.7910/DVN/STVUET). Each record is
fetched into DATA_DIR, converted (cvr_to_blt.py, keeping the ballots' order) and counted with
--method cambridge, and checked against the official count sheet the Cambridge Civic Journal keeps
for it (official.json's `sheet`, also fetched into DATA_DIR; read with pdftotext):

- 2001-2017: every official count's totals must be one of this count's rounds, in order, for
  every candidate but the write-ins, at the official quota, with the same candidates elected. A
  sheet's last count that only clears the defeated candidates' votes after the last seat is
  filled isn't a round here, and is let pass.
- 2019-2023: the candidates elected only. These records mark no overvotes, so their first counts
  are a few votes a candidate off the City's, and no count can match.

Names are spelled differently in the records and the sheets ("Toomey, Tim", "Toomey, Jr.,
Timothy J."), so they're matched on surname, and on first name where two share one.
"""
import argparse
import json
import os
import re
import subprocess
import sys
import unicodedata
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
DEFAULT_JAR = os.path.join(HERE, "..", "..", "..", "target", "rankedchoice-0.1.0-SNAPSHOT.jar")
DATAVERSE = "https://dataverse.harvard.edu/api/access/datafile/{id}?format=original"
# The Civic Journal's server turns away requests that don't look like a browser's.
HEADERS = {"User-Agent": "Mozilla/5.0 (X11; Linux x86_64; rv:128.0) Gecko/20100101 Firefox/128.0",
           "Accept": "*/*", "Accept-Language": "en-US,en;q=0.5"}
SUFFIXES = {"jr", "jr."}
NOT_CANDIDATES = ("total", "quota", "invalid", "exhaust", "november", "candidate", "city council", "page",
                  "cambridge")
LAST_WITH_ROUNDS = 2017


def fetch(path, url):
    if not os.path.exists(path):
        request = urllib.request.Request(url, headers=HEADERS)
        open(path, "wb").write(urllib.request.urlopen(request, timeout=300).read())
    return path


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


def sheet_totals(pdf):
    """{candidate: [votes after count 1, after count 2, ...]} from a count sheet. A row reads votes,
    then (change, votes) per count; a count that didn't change a candidate may give the votes once,
    a new page may repeat the last votes, and other columns (the order elected) are passed over."""
    text = subprocess.run(["pdftotext", "-layout", pdf, "-"], capture_output=True, text=True, check=True).stdout
    pages = {}
    for line in text.splitlines():
        match = re.match(r'^\s*([A-Za-z][^\d]*?[A-Za-z.)"])\s+(-?\d.*)$', line)
        if not match or match.group(1).strip().lower().startswith(NOT_CANDIDATES):
            continue
        rest = re.sub(r"\b\d+(st|nd|rd|th)\s+Count\b", " ", match.group(2))
        pages.setdefault(match.group(1).strip(), []).append(
            [int(x.replace(",", "")) for x in re.findall(r"-?\d[\d,]*", rest)])
    totals = {}
    for name, rows in pages.items():
        votes = [rows[0][0]]
        for p, row in enumerate(rows):
            tokens = row[1:] if p == 0 or (row and row[0] == votes[-1]) else row
            i = 0
            while i < len(tokens):
                if i + 1 < len(tokens) and tokens[i + 1] == votes[-1] + tokens[i]:
                    votes.append(tokens[i + 1])
                    i += 2
                elif tokens[i] == votes[-1]:
                    votes.append(tokens[i])
                    i += 1
                else:
                    i += 1
        totals[name] = votes
    return totals


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
        holding = [row for row in totals.values() if len(row) >= counts and row[-1] > 0]
        if len(holding) == len(ours["electedInOrder"]) and all(row[-1] == row[-2] for row in holding):
            unmatched = []
    return counts, unmatched


def check(data, contest, official, jar):
    year = int(contest.split("-")[1])
    csv_path = fetch(os.path.join(data, contest + ".csv"), DATAVERSE.format(id=official["dataverseFile"]))
    blt = os.path.join(data, contest + ".blt")
    if not os.path.exists(blt):
        subprocess.run([sys.executable, os.path.join(HERE, "cvr_to_blt.py"), csv_path, blt,
                        "--seats", str(official["seats"])], check=True)
    result = subprocess.run(["java", "-jar", jar, "-i", blt, "-m", "cambridge", "--report-format", "json"],
                            capture_output=True, text=True)
    if result.returncode:
        return False, f"{contest}: count failed: {result.stderr.strip()[-200:]}"
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
    if year <= LAST_WITH_ROUNDS:
        sheet = fetch(os.path.join(data, contest + ".pdf"), official["sheet"])
        counts, unmatched = match_counts(ours, sheet_totals(sheet))
        line += f"; {counts - len(unmatched)} of {counts} official counts match a round of this count"
        if unmatched:
            line += f" (first unmatched: count {unmatched[0]})"
        ok = ok and not unmatched and int(ours["quota"]) == official["quota"]
    if missing:
        line += f"; elected {', '.join(left)} where the City elected {', '.join(missing)}"
    else:
        line += f"; the same {official['seats']} elected"
    return ok, line


def main():
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("data")
    parser.add_argument("contests", nargs="*")
    parser.add_argument("--jar", default=DEFAULT_JAR)
    args = parser.parse_args()
    officials = json.load(open(os.path.join(HERE, "official.json"), encoding="utf-8"))
    contests = args.contests or list(officials)
    os.makedirs(args.data, exist_ok=True)
    failed = 0
    for contest in contests:
        ok, line = check(args.data, contest, officials[contest], args.jar)
        failed += not ok
        print(line, flush=True)
    print(f"{len(contests) - failed} of {len(contests)} contests check out")
    sys.exit(1 if failed else 0)


if __name__ == "__main__":
    main()

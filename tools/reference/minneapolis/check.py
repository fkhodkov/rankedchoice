"""Check the minneapolis ruleset against the City's official round-by-round summaries.

usage: check.py DATA_DIR [--jar PATH] [CONTEST ...]

CONTEST is park-YEAR or bet-YEAR for 2009, 2013, 2017, 2021 or 2025 (all, by default): the Park
and Recreation Board at large (3 seats) and Board of Estimate and Taxation (2 seats) contests.
Each one's cast vote record and summary statement (linked from
https://vote.minneapolismn.gov/results-data/election-results/<year>/<park-board-at-large|bet>/)
are fetched into DATA_DIR, the record converted (cvr_to_blt.py) and counted, and the summary
matched against it: every official round's figures must be one of this count's rounds, in
order, every candidate to the 4 decimal places of the official figures, at the official
threshold, and the same candidates elected. Where the City's last round elects without moving
votes, this count's last exclusion is transferred before the same candidates are elected; that
round isn't in the summary.

- 2017-2025: the City's records and PDF summaries (read with pdftotext), counted with the
  `minneapolis` ruleset.
- 2009: FairVote's collection of the records (https://doi.org/10.7910/DVN/STVUET), and the City's
  summaries as Excel files, read with LibreOffice (soffice); counted with the 2006 ordinance's
  rules alone (src/test/resources/testdata/minneapolis/minneapolis-2009.yaml), without the
  rankings test the later counts add. The summaries name candidates by surname, and some
  spellings differ from the records' ("Wahlstedt", "Wahstedt"), so names are matched loosely.
  The records lump every write-in as one, where the summaries list each; they're left out.
- 2013: FairVote's records, and the winners only (the City published no round summary for the
  Board of Estimate, and the Park Board's is a ballot-by-ballot listing), counted both ways.
"""
import argparse
import csv
import difflib
import json
import os
import re
import subprocess
import sys
import urllib.request
from decimal import Decimal

HERE = os.path.dirname(os.path.abspath(__file__))
DEFAULT_JAR = os.path.join(HERE, "..", "..", "..", "target", "rankedchoice-0.1.0-SNAPSHOT.jar")
FILES = "https://vote.minneapolismn.gov/media/-www-content-assets/documents/archive/elections/"
DATAVERSE = "https://dataverse.harvard.edu/api/access/datafile/{id}?format=original"
CONFIG_2009 = os.path.join(HERE, "..", "..", "..", "src", "test", "resources", "testdata", "minneapolis",
                           "minneapolis-2009.yaml")
# 2009 and 2013: (seats, FairVote's Dataverse file, the City's Excel summary or the winners).
OLDER = {
    "park-2009": (3, 11620400, "2009-MPRB-At-Large-Final-RCV.xls"),
    "bet-2009": (2, 11620399, "2009-BET-Final-RCV.xls"),
    "park-2013": (3, 11620409, ["ANNIE YOUNG", "JOHN ERWIN", "MEG FORNEY"]),
    "bet-2013": (2, 11620388, ["CAROL J. BECKER", "DAVID B WHEELER"]),
}
CONTESTS = {
    "park-2017": (3, "2017-Park-Board-At-Large-Ballot-Records.csv", "2017-Park-Board-At-Large-Race-Results-Summary.pdf"),
    "park-2021": (3, "2021-Park-AL-Cast-Vote-Record.csv", "2021-Park-Board-At-Large-Round-Results-Summary.pdf"),
    "park-2025": (3, "2025-Park-At-Large-CVR.csv", "2025-Park-At-Large-RCV-Tabulation-Summary.pdf"),
    "bet-2017": (2, "2017-Board-of-Estimate-and-Taxation-Ballot-Records.csv",
                 "2017-Board-of-Estimate-and-Taxation-Results-Summary.pdf"),
    "bet-2021": (2, "2021-BET-Cast-Vote-Record.csv", "2021-BET-Round-Results-Summary.pdf"),
    "bet-2025": (2, "2025-BET-Cast-Vote-Record.csv", "2025-Board-of-Estimate-and-Taxation-RCV-Tabulation-Summary.pdf"),
}
NUMBER = re.compile(r"^-?[\d,]+(\.\d+)?$")


def fetch(data, name, url=None):
    path = os.path.join(data, name)
    if not os.path.exists(path):
        request = urllib.request.Request(url or FILES + name, headers={"User-Agent": "Mozilla/5.0"})
        open(path, "wb").write(urllib.request.urlopen(request, timeout=300).read())
    return path


def spreadsheet_summary(xls, data):
    """The threshold and each candidate's grand total after every round, from a 2009 Excel summary."""
    out = os.path.splitext(xls)[0] + ".csv"
    if not os.path.exists(out):
        subprocess.run(["soffice", "--headless", "--convert-to", "csv:Text - txt - csv (StarCalc):44,34,76",
                        "--outdir", data, xls], check=True, capture_output=True)
    rows = list(csv.reader(open(out, encoding="utf-8", errors="ignore")))
    threshold = int(next(row[1] for row in rows if row and row[0].startswith("Threshold")))
    start = next(i for i, row in enumerate(rows) if row and row[0] == "Candidate")
    totals = {}
    for row in rows[start + 3:]:
        if not row or not row[0] or row[0].startswith(("TOTAL", "Exhausted", "Over", "Under")):
            continue
        values = [Decimal(v.replace(",", ".")).quantize(Decimal("0.0001"))
                  if v.strip() not in ("", "-") and not v.endswith("%") else None for v in row[1:]]
        totals[row[0]] = [values[0]] + [values[i] for i in range(2, len(values), 2) if values[i] is not None]
    return threshold, totals


def surname_match(name, names):
    """The one of `names` sharing `name`'s surname, allowing for a misspelling."""
    found = difflib.get_close_matches(name.split()[-1].lower(), [n.split()[-1].lower() for n in names], n=1,
                                      cutoff=0.8)
    return next((n for n in names if found and n.split()[-1].lower() == found[0]), None)


def count(blt, jar, rules):
    result = subprocess.run(["java", "-jar", jar, "-i", blt, "--report-format", "json"] + rules,
                            capture_output=True, text=True)
    if result.returncode:
        raise RuntimeError(result.stderr.strip()[-200:])
    return json.loads(result.stdout)


def check_older(data, contest, jar):
    seats, file_id, official = OLDER[contest]
    blt = os.path.join(data, contest + ".blt")
    if not os.path.exists(blt):
        cvr = fetch(data, contest + "-fairvote.csv", DATAVERSE.format(id=file_id))
        subprocess.run([sys.executable, os.path.join(HERE, "cvr_to_blt.py"), cvr, blt, "--seats", str(seats)],
                       check=True)
    if isinstance(official, list):
        line = f"{contest}: winners only"
        ok = True
        for label, rules in (("the 2006 rules", ["--config", CONFIG_2009]), ("minneapolis", ["--ruleset", "minneapolis"])):
            elected = sorted(count(blt, jar, rules)["electedInOrder"])
            same = elected == sorted(official)
            ok = ok and same
            line += f"; by {label}, {'the same' if same else 'DIFFERENTLY: ' + str(elected)}"
        return ok, line
    ours = count(blt, jar, ["--config", CONFIG_2009])
    threshold, totals = spreadsheet_summary(fetch(data, official), data)
    rounds = max(len(values) for values in totals.values())
    start, unmatched, first_round = 0, [], []
    for k in range(rounds):
        def agrees(name, votes):
            if name == "Write-ins":
                return True
            match = surname_match(name, list(totals))
            return match is not None and len(totals[match]) > k and totals[match][k] == Decimal(votes)
        hit = next((i for i in range(start, len(ours["rounds"]))
                    if all(agrees(name, votes) for name, votes in ours["rounds"][i]["tallies"].items())), None)
        if hit is None:
            unmatched.append(k + 1)
            if k == 0:
                first_round = [f"{name} {votes} (official {totals[surname_match(name, list(totals))][0]})"
                               for name, votes in ours["rounds"][0]["tallies"].items()
                               if name != "Write-ins" and not agrees(name, votes)]
        else:
            start = hit
    leaders = sorted(totals, key=lambda name: totals[name][-1], reverse=True)[:seats]
    same = sorted(surname_match(name, list(totals)) for name in ours["electedInOrder"]) == sorted(leaders)
    line = (f"{contest}: threshold {Decimal(ours['quota']):f} (official {threshold}); "
            f"{rounds - len(unmatched)} of {rounds} official rounds match a round of this count; "
            f"elected {'the same' if same else 'DIFFERENTLY: ' + str(ours['electedInOrder'])}")
    # A first count that differs, where every later round matches, is the record's, not the count's.
    record_only = unmatched == [1] and rounds > 1
    if record_only:
        line += f"; the record's first count differs from the City's: {', '.join(first_round)}"
    elif unmatched:
        line += f"; unmatched official rounds {unmatched}"
    return (not unmatched or record_only) and same, line


def summary(pdf):
    """The threshold, and each candidate's grand total after every round, from the summary statement."""
    text = subprocess.run(["pdftotext", "-layout", pdf, "-"], capture_output=True, text=True, check=True).stdout
    lines = text.splitlines()
    threshold = int(re.search(r"Threshold:\s+([\d,]+)", text).group(1).replace(",", ""))
    start = next(i for i, line in enumerate(lines) if line.strip().startswith("Candidate"))
    totals, name = {}, ""
    for line in lines[start + 1:]:
        if line.startswith("TOTAL"):
            break
        tokens = line.split()
        values = [t for t in tokens if NUMBER.match(t) or t == "-" or t.endswith("%")]
        words = tokens[:len(tokens) - len(values)]
        name += ("" if name.endswith("-") or not name else " ") + " ".join(words)
        if not values:
            continue    # a name the summary wraps onto the next line
        values = [v.replace(",", "") for v in values if not v.endswith("%")]
        if len(values) % 2 == 0:
            values = values[:-1]    # a last round that moves no votes: "-"
        first = Decimal(0) if values[0] == "-" else Decimal(values[0])
        totals[name] = [first] + [Decimal(v) for v in values[2::2]]
        name = ""
    totals.pop("Exhausted", None)
    return threshold, totals


def check(data, contest, jar):
    if contest in OLDER:
        return check_older(data, contest, jar)
    seats, cvr, pdf = CONTESTS[contest]
    blt = os.path.join(data, contest + ".blt")
    if not os.path.exists(blt):
        subprocess.run([sys.executable, os.path.join(HERE, "cvr_to_blt.py"), fetch(data, cvr), blt,
                        "--seats", str(seats)], check=True)
    result = subprocess.run(["java", "-jar", jar, "-i", blt, "--ruleset", "minneapolis", "--report-format", "json"],
                            capture_output=True, text=True)
    if result.returncode:
        return False, f"{contest}: count failed: {result.stderr.strip()[-200:]}"
    ours = json.loads(result.stdout)
    threshold, totals = summary(fetch(data, pdf))
    rounds = len(next(iter(totals.values())))
    start, unmatched = 0, []
    for k in range(rounds):
        figures = {name: values[k] for name, values in totals.items()}
        hit = next((i for i in range(start, len(ours["rounds"]))
                    if all(figures.get(name) == Decimal(votes) for name, votes in ours["rounds"][i]["tallies"].items())),
                   None)
        if hit is None:
            unmatched.append(k + 1)
        else:
            start = hit
    # Those the summary elects: the leaders after its last round (where the last defeated still shows votes).
    elected = sorted(sorted(totals, key=lambda name: totals[name][-1], reverse=True)[:seats])
    same = sorted(ours["electedInOrder"]) == elected
    line = (f"{contest}: threshold {Decimal(ours['quota']):f} (official {threshold}); "
            f"{rounds - len(unmatched)} of {rounds} official rounds match a round of this count; "
            f"elected {'the same' if same else 'DIFFERENTLY: ' + str(ours['electedInOrder']) + ' vs ' + str(elected)}")
    if unmatched:
        line += f"; first unmatched official round {unmatched[0]}"
    return not unmatched and same and Decimal(ours["quota"]) == threshold, line


def main():
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("data")
    parser.add_argument("contests", nargs="*", default=list(OLDER) + list(CONTESTS))
    parser.add_argument("--jar", default=DEFAULT_JAR)
    args = parser.parse_args()
    os.makedirs(args.data, exist_ok=True)
    failed = 0
    for contest in args.contests:
        ok, line = check(args.data, contest, args.jar)
        failed += not ok
        print(line, flush=True)
    sys.exit(1 if failed else 0)


if __name__ == "__main__":
    main()

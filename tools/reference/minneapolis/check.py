"""Check the minneapolis ruleset against the City's official round-by-round summaries.

usage: check.py DATA_DIR [--jar PATH] [CONTEST ...]

CONTEST is park-2017, park-2021, park-2025, bet-2017, bet-2021 or bet-2025 (all, by default):
the Park and Recreation Board at large (3 seats) and Board of Estimate and Taxation (2 seats)
contests. Each one's cast vote record and summary statement (linked from
https://vote.minneapolismn.gov/results-data/election-results/<year>/<park-board-at-large|bet>/)
are fetched into DATA_DIR, the record converted (cvr_to_blt.py) and counted with the
`minneapolis` ruleset, and the summary (read with pdftotext) matched against it: every official
round's figures must be one of this count's rounds, in order, every candidate to the 4 decimal
places of the official figures, at the official threshold, and the same candidates elected. Where
the City's last round elects without moving votes, this count's last exclusion is transferred
before the same candidates are elected; that round isn't in the summary.
"""
import argparse
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


def fetch(data, name):
    path = os.path.join(data, name)
    if not os.path.exists(path):
        request = urllib.request.Request(FILES + name, headers={"User-Agent": "Mozilla/5.0"})
        open(path, "wb").write(urllib.request.urlopen(request, timeout=300).read())
    return path


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
    parser.add_argument("contests", nargs="*", default=list(CONTESTS))
    parser.add_argument("--jar", default=DEFAULT_JAR)
    args = parser.parse_args()
    failed = 0
    for contest in args.contests:
        ok, line = check(args.data, contest, args.jar)
        failed += not ok
        print(line, flush=True)
    sys.exit(1 if failed else 0)


if __name__ == "__main__":
    main()

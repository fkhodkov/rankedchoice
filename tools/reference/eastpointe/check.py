"""Check Eastpointe, Michigan's City Council counts of 2019 and 2021 against their RCTab summaries.

usage: check.py DATA_DIR [--jar PATH] [CONTEST ...]

CONTEST is council-2019 or council-2021 (both, by default): 2 seats each. The ballots come from
FairVote's collection of proportional RCV cast vote records (https://doi.org/10.7910/DVN/STVUET),
the results from the RCTab summaries of Macomb County's counts published on RCVis (the
`rawDataId` JSON on each page), both fetched into DATA_DIR. The ballots are converted
(cvr_to_blt.py) and counted with the `portland` ruleset (both are RCTab counts at 4 decimal
places), and every official round's tallies must be one of this count's rounds, in order,
every candidate to the 4 decimal places of the summary, at the official threshold, with the
same candidates elected.

Two rounds are let pass, and reported: a last round that only moves a surplus after both seats
are filled, which this count doesn't do, and round 4 of 2021, where the summary's own tally for
Rob Baker is 2 votes more than its round 3 tally plus the transfers it lists (its round 5 builds
on the lower figure, which is this count's).
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
DATAVERSE = "https://dataverse.harvard.edu/api/access/datafile/{id}?format=original"
CONTESTS = {
    "council-2019": (11620398, "https://rcvis.com/v/2019-11-08_13-19-44_summary"),
    "council-2021": (11620416, "https://rcvis.com/v/eastpointe-2021-results"),
}
NOT_CANDIDATES = {"Inactive Ballots", "Residual Surplus"}
# The summary's own slip: (contest, round, candidate, its figure, what its other figures give).
SLIPS = {("council-2021", 4, "Rob Baker"): (Decimal("1058.8175"), Decimal("1056.8175"))}


def fetch(path, url):
    if not os.path.exists(path):
        request = urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0"})
        open(path, "wb").write(urllib.request.urlopen(request, timeout=300).read())
    return path


def summary(path):
    page = open(path, encoding="utf-8", errors="ignore").read()
    return json.loads(re.search(r'<script id="rawDataId" type="application/json">(.*?)</script>', page, re.S)
                      .group(1))


def check(data, contest, jar):
    file_id, page = CONTESTS[contest]
    official = summary(fetch(os.path.join(data, contest + ".html"), page))
    blt = os.path.join(data, contest + ".blt")
    if not os.path.exists(blt):
        cvr = fetch(os.path.join(data, contest + ".csv"), DATAVERSE.format(id=file_id))
        subprocess.run([sys.executable, os.path.join(HERE, "cvr_to_blt.py"), cvr, blt, "--seats", "2"], check=True)
    result = subprocess.run(["java", "-jar", jar, "-i", blt, "--ruleset", "portland", "--report-format", "json"],
                            capture_output=True, text=True)
    if result.returncode:
        return False, f"{contest}: count failed: {result.stderr.strip()[-200:]}"
    ours = json.loads(result.stdout)
    rounds = official["results"]
    elected = [t["elected"] for r in rounds for t in r["tallyResults"] if "elected" in t]
    notes = []
    if not rounds[-1]["tallyResults"]:
        notes.append(f"round {rounds[-1]['round']}, after the last surplus moves, isn't one here (not checked)")
        rounds = rounds[:-1]
    start, matched, unmatched = 0, 0, []
    for official_round in rounds:
        figures = {name: Decimal(str(votes)) for name, votes in official_round["tally"].items()
                   if name not in NOT_CANDIDATES}
        for (slip_contest, slip_round, name), (printed, consistent) in SLIPS.items():
            if slip_contest == contest and slip_round == official_round["round"] and figures.get(name) == printed:
                figures[name] = consistent
                notes.append(f"round {slip_round}: the summary has {name} on {printed}, {printed - consistent} "
                             f"more than its round {slip_round - 1} plus its transfers ({consistent})")
        hit = next((i for i in range(start, len(ours["rounds"]))
                    if all(figures.get(name) == Decimal(votes) for name, votes in ours["rounds"][i]["tallies"].items())),
                   None)
        if hit is None:
            unmatched.append(official_round["round"])
        else:
            matched += 1
            start = hit
    same = sorted(ours["electedInOrder"]) == sorted(elected)
    line = (f"{contest}: threshold {Decimal(ours['quota']):f} (official {official['config']['threshold']}); "
            f"{matched} of {len(rounds)} official rounds match a round of this count; "
            f"elected {'the same' if same else 'DIFFERENTLY: ' + str(ours['electedInOrder'])}")
    if unmatched:
        line += f"; first unmatched official round {unmatched[0]}"
    for note in notes:
        line += f"; {note}"
    return not unmatched and same and Decimal(ours["quota"]) == Decimal(official["config"]["threshold"]), line


def main():
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("data")
    parser.add_argument("contests", nargs="*", default=list(CONTESTS))
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

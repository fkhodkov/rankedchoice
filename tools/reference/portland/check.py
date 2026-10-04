"""Check the portland ruleset against Multnomah County's official round-by-round results.

usage: check.py DATA_DIR [--jar PATH] [DISTRICT ...]

DATA_DIR holds the County's cast vote records for the 2024 Council contests, saved as
d1-cvr.csv ... d4-cvr.csv (links on https://multco.us/info/turnout-and-statistics-november-2024-general-election).
Each is converted (cvr_to_blt.py) and counted with the `portland` ruleset, and the County's
final RCTab tabulation summary for the district (fetched from rcvresults.multco.us into
DATA_DIR) is matched against it: every official round's tallies must be one of this count's
rounds, in order, every candidate to the 4 decimal places of the official figures, and the same
three councillors elected. The County's last round, after the last seats are filled, shows the
final surplus transferred, which this count doesn't do; it's left out. A round that follows a
tie for defeat the County drew by lot, decided otherwise than by this project's declared order,
is reported apart.
"""
import argparse
import json
import os
import subprocess
import sys
import urllib.parse
import urllib.request
from decimal import Decimal

HERE = os.path.dirname(os.path.abspath(__file__))
DEFAULT_JAR = os.path.join(HERE, "..", "..", "..", "target", "rankedchoice-0.1.0-SNAPSHOT.jar")
REPORT = "a18a5db1-04a2-4123-8ccb-de12b485f135"    # the County's final results, 2 December 2024
SUMMARY = ("https://mcdcselectionsrcvprdst.z5.web.core.windows.net/{report}_City of Portland Councilor District {n}/"
           "{report}_City of Portland, Councilor, District {n}.json")


def official(data, n):
    path = os.path.join(data, f"d{n}-official.json")
    if not os.path.exists(path):
        url = urllib.parse.quote(SUMMARY.format(report=REPORT, n=n), safe=":/")
        request = urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0"})
        open(path, "wb").write(urllib.request.urlopen(request, timeout=60).read())
    return json.load(open(path))


def check(data, n, jar):
    blt = os.path.join(data, f"d{n}.blt")
    if not os.path.exists(blt):
        subprocess.run([sys.executable, os.path.join(HERE, "cvr_to_blt.py"), os.path.join(data, f"d{n}-cvr.csv"), blt],
                       check=True)
    result = subprocess.run(["java", "-jar", jar, "-i", blt, "--ruleset", "portland", "--report-format", "json"],
                            capture_output=True, text=True)
    if result.returncode:
        return False, f"District {n}: count failed: {result.stderr.strip()[-200:]}"
    ours = json.loads(result.stdout)
    summary = official(data, n)
    rounds = summary["results"]
    elected = [t["elected"] for r in rounds for t in r["tallyResults"] if "elected" in t]
    if not rounds[-1]["tallyResults"]:
        rounds = rounds[:-1]    # the last seats already filled: only the final surplus moves
    start, unmatched = 0, []
    for official_round in rounds:
        figures = {name: Decimal(votes) for name, votes in official_round["tally"].items()}
        hit = next((i for i in range(start, len(ours["rounds"]))
                    if all(figures.get(name) == Decimal(votes) for name, votes in ours["rounds"][i]["tallies"].items())),
                   None)
        if hit is None:
            unmatched.append(official_round["round"])
        else:
            start = hit
    # A tie for defeat is decided by lot (City Code 2.08.030 A.6), where this project uses
    # declared order: a round that follows such a draw, decided the other way, is counted apart.
    by_round = {r["round"]: r for r in summary["results"]}
    lots = []
    for k in unmatched:
        before = by_round.get(k - 1)
        defeated = [t["eliminated"] for t in before["tallyResults"] if "eliminated" in t] if before else []
        tally = before["tally"] if before else {}
        lowest = min((Decimal(v) for v in tally.values()), default=None)
        tied = sorted(name for name, v in tally.items() if Decimal(v) == lowest)
        if defeated and len(tied) > 1 and defeated[0] in tied:
            lots.append(f"official round {k} follows a draw between {' and '.join(tied)} on {lowest}")
    unmatched = [k for k in unmatched if not any(f"round {k} " in lot for lot in lots)]
    same = sorted(ours["electedInOrder"]) == sorted(elected)
    line = (f"District {n}: quota {Decimal(ours['quota']):f} (official {summary['config']['threshold']}); "
            f"{len(rounds) - len(unmatched) - len(lots)} of {len(rounds)} official rounds match a round of this count; "
            f"elected {'the same' if same else 'DIFFERENTLY: ' + str(ours['electedInOrder'])}")
    if unmatched:
        line += f"; first unmatched official round {unmatched[0]}"
    for lot in lots:
        line += f"; {lot} (decided by lot)"
    return not unmatched and same and Decimal(ours["quota"]) == Decimal(summary["config"]["threshold"]), line


def main():
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("data")
    parser.add_argument("districts", nargs="*", type=int, default=[1, 2, 3, 4])
    parser.add_argument("--jar", default=DEFAULT_JAR)
    args = parser.parse_args()
    failed = 0
    for n in args.districts:
        ok, line = check(args.data, n, args.jar)
        failed += not ok
        print(line, flush=True)
    sys.exit(1 if failed else 0)


if __name__ == "__main__":
    main()

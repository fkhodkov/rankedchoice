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
import json
import os
import subprocess
import sys
import urllib.parse
from decimal import Decimal

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, ".."))
from rcvcheck import count, fetch, main, threshold_line, unmatched_rounds  # noqa: E402

REPORT = "a18a5db1-04a2-4123-8ccb-de12b485f135"    # the County's final results, 2 December 2024
SUMMARY = ("https://mcdcselectionsrcvprdst.z5.web.core.windows.net/{report}_City of Portland Councilor District {n}/"
           "{report}_City of Portland, Councilor, District {n}.json")


def official(data, n):
    path = fetch(os.path.join(data, f"d{n}-official.json"),
                 urllib.parse.quote(SUMMARY.format(report=REPORT, n=n), safe=":/"))
    return json.load(open(path))


def check(data, n, jar):
    blt = os.path.join(data, f"d{n}.blt")
    if not os.path.exists(blt):
        subprocess.run([sys.executable, os.path.join(HERE, "cvr_to_blt.py"), os.path.join(data, f"d{n}-cvr.csv"), blt],
                       check=True)
    ours, error = count(jar, blt, ["--ruleset", "portland"])
    if error:
        return False, f"District {n}: count failed: {error}"
    summary = official(data, n)
    rounds = summary["results"]
    elected = [t["elected"] for r in rounds for t in r["tallyResults"] if "elected" in t]
    if not rounds[-1]["tallyResults"]:
        rounds = rounds[:-1]    # the last seats already filled: only the final surplus moves
    unmatched = unmatched_rounds([(r["round"], {name: Decimal(votes) for name, votes in r["tally"].items()})
                                  for r in rounds], ours)
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
    matched = len(rounds) - len(unmatched) - len(lots)
    line = threshold_line(f"District {n}", ours, summary["config"]["threshold"], matched, len(rounds), same,
                          ours["electedInOrder"], quota="quota")
    if unmatched:
        line += f"; first unmatched official round {unmatched[0]}"
    for lot in lots:
        line += f"; {lot} (decided by lot)"
    return not unmatched and same and Decimal(ours["quota"]) == Decimal(summary["config"]["threshold"]), line


if __name__ == "__main__":
    main(__doc__.split("\n\n")[0], ["1", "2", "3", "4"], check)

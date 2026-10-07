"""Check Eastpointe, Michigan's City Council counts of 2019 and 2021 against their RCTab summaries.

usage: check.py DATA_DIR [--jar PATH] [CONTEST ...]

CONTEST is council-2019 or council-2021 (both, by default): 2 seats each. The ballots come from
FairVote's collection of proportional RCV cast vote records (https://doi.org/10.7910/DVN/STVUET),
the results from the RCTab summaries of Macomb County's counts published on RCVis (the
`rawDataId` JSON on each page), both fetched into DATA_DIR. The ballots are converted
(../cvr_to_blt.py) and counted with the `portland` ruleset (both are RCTab counts at 4 decimal
places), and every official round's tallies must be one of this count's rounds, in order,
every candidate to the 4 decimal places of the summary, at the official threshold, with the
same candidates elected.

Two rounds are let pass, and reported: a last round that only moves a surplus after both seats
are filled, which this count doesn't do, and round 4 of 2021, where the summary's own tally for
Rob Baker is 2 votes more than its round 3 tally plus the transfers it lists (its round 5 builds
on the lower figure, which is this count's).
"""
import json
import os
import re
import subprocess
import sys
from decimal import Decimal

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, ".."))
from rcvcheck import DATAVERSE, count, fetch, main, stale, threshold_line, unmatched_rounds  # noqa: E402

CONVERTER = os.path.join(HERE, "..", "cvr_to_blt.py")
CONTESTS = {
    "council-2019": (11620398, "https://rcvis.com/v/2019-11-08_13-19-44_summary"),
    "council-2021": (11620416, "https://rcvis.com/v/eastpointe-2021-results"),
}
NOT_CANDIDATES = {"Inactive Ballots", "Residual Surplus"}
# The summary's own slip: (contest, round, candidate, its figure, what its other figures give).
SLIPS = {("council-2021", 4, "Rob Baker"): (Decimal("1058.8175"), Decimal("1056.8175"))}


def summary(path):
    page = open(path, encoding="utf-8", errors="ignore").read()
    return json.loads(re.search(r'<script id="rawDataId" type="application/json">(.*?)</script>', page, re.S)
                      .group(1))


def check(data, contest, jar):
    file_id, page = CONTESTS[contest]
    official = summary(fetch(os.path.join(data, contest + ".html"), page))
    blt = os.path.join(data, contest + ".blt")
    cvr = fetch(os.path.join(data, contest + ".csv"), DATAVERSE.format(id=file_id))
    if stale(blt, cvr):
        # As RCTab was set: a skipped ranking or a write-in is passed over, but two in a row end the ballot; an
        # overvote ends it, and so does a candidate ranked again ("exhaust on duplicate candidate").
        subprocess.run([sys.executable, CONVERTER, cvr, blt, "--seats", "2", "--title", contest, "--skipped", "two-end",
                        "--write-in", "skip", "--repeat", "end"], check=True)
    ours, error = count(jar, blt, ["--ruleset", "portland"])
    if error:
        return False, f"{contest}: count failed: {error}"
    rounds = official["results"]
    elected = [t["elected"] for r in rounds for t in r["tallyResults"] if "elected" in t]
    notes = []
    if not rounds[-1]["tallyResults"]:
        notes.append(f"round {rounds[-1]['round']}, after the last surplus moves, isn't one here (not checked)")
        rounds = rounds[:-1]
    figures_by_round = []
    for official_round in rounds:
        figures = {name: Decimal(str(votes)) for name, votes in official_round["tally"].items()
                   if name not in NOT_CANDIDATES}
        for (slip_contest, slip_round, name), (printed, consistent) in SLIPS.items():
            if slip_contest == contest and slip_round == official_round["round"] and figures.get(name) == printed:
                figures[name] = consistent
                notes.append(f"round {slip_round}: the summary has {name} on {printed}, {printed - consistent} "
                             f"more than its round {slip_round - 1} plus its transfers ({consistent})")
        figures_by_round.append((official_round["round"], figures))
    unmatched = unmatched_rounds(figures_by_round, ours)
    same = sorted(ours["electedInOrder"]) == sorted(elected)
    line = threshold_line(contest, ours, official["config"]["threshold"], len(rounds) - len(unmatched), len(rounds),
                          same, ours["electedInOrder"])
    if unmatched:
        line += f"; first unmatched official round {unmatched[0]}"
    for note in notes:
        line += f"; {note}"
    return not unmatched and same and Decimal(ours["quota"]) == Decimal(official["config"]["threshold"]), line


if __name__ == "__main__":
    main(__doc__.split("\n\n")[0], CONTESTS, check)

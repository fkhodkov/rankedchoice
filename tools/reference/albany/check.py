"""Check the albany ruleset against Alameda County's official round-by-round results.

usage: check.py DATA_DIR [--jar PATH] [CONTEST ...]

CONTEST is council-2022, school-2022, council-2024 or school-2024 (all, by default): Albany,
California's City Council and Board of Education contests. Each one's ballots come from FairVote's
collection of proportional RCV cast vote records (https://doi.org/10.7910/DVN/STVUET), its
results from the County's Dominion "RCV Detailed Report" (published under
https://alamedacountyca.gov/rovresults/rcv/), both fetched into DATA_DIR. The ballots are
converted (../cvr_to_blt.py) and counted, the 2024 contests with the `albany` ruleset and the 2022
ones with src/test/resources/testdata/albany/albany-2022.yaml (the County's settings then: the
threshold recomputed each round, 6 decimal places), and every official round's tallies must be one
of this count's rounds, in order, every candidate to the report's decimal places, at the official
first threshold, with the same candidates elected. The CVR and the report spell some names
differently ("ROBIN D  LOPEZ", "ROBIN D. LÓPEZ"); they're matched on their letters. In each matched
round the votes no longer with a candidate are compared too (nontransferable_line).
"""
import os
import re
import subprocess
import sys
import xml.etree.ElementTree as ET
from decimal import Decimal

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, ".."))
from rcvcheck import (DATAVERSE, count, fetch, main, name_key as key, threshold_line,  # noqa: E402
                      unmatched_rounds)

ROOT = os.path.join(HERE, "..", "..", "..")
CONVERTER = os.path.join(HERE, "..", "cvr_to_blt.py")
CONFIG_2022 = os.path.join(ROOT, "src", "test", "resources", "testdata", "albany", "albany-2022.yaml")
REPORT = "https://alamedacountyca.gov/rovresults/rcv/{election}/Albany/{race}/RcvDetailedReport.xml"
# contest: (Dataverse file id, County election id, race)
CONTESTS = {
    "council-2022": (11620395, 248, "001-CityCouncil"),
    "school-2022": (11620390, 248, "002-BoardOfEducation"),
    "council-2024": (11620407, 252, "001-CityCouncil"),
    "school-2024": (11620379, 252, "002-BoardOfEducation"),
}
NS = "{RcvDetailedReport}"
NOT_CANDIDATES = {"Blanks", "Exhausted", "Overvotes", "Remainder Points", "Surplus Transfer Remainder"}


def report(path):
    """Seats, each round's threshold, candidates' votes and votes no longer with a candidate (exhausted and overvotes;
    the remainders of rounding), and those elected, from a Dominion report."""
    root = ET.parse(path).getroot()
    seats = int(re.search(r"\d+", root.find(f".//{NS}Tablix2").get("Textbox53")).group())
    rounds, elected, gone = [], [], []
    for group in root.iter(f"{NS}roundGroup"):
        summary = group.find(f"{NS}Tablix5")
        threshold = Decimal(summary.get("Textbox86").split("Threshold:")[1].strip().replace(",", ""))
        figures = {c.get("choiceName2"): Decimal(c.get("votes")) for c in summary.iter(f"{NS}choiceGroup")}
        votes = {name: value for name, value in figures.items() if name not in NOT_CANDIDATES}
        rounds.append((threshold, votes))
        gone.append((figures.get("Exhausted", 0) + figures.get("Overvotes", 0),
                     figures.get("Remainder Points", 0) + figures.get("Surplus Transfer Remainder", 0)))
        for element in group.iter():
            text = element.get("Textbox21") or ""
            match = re.match(r"(.*) is elected because", text)
            if match and match.group(1) not in elected:
                elected.append(match.group(1))
    return seats, rounds, elected, gone


def nontransferable_line(rounds, gone, ours):
    """Whether, in each official round that is a round of this count, the votes no longer with a candidate are this
    count's: exhausted and overvotes past the first round's (ballots whose first ranking is an overvote never come into
    this count) against its exhausted votes, the rounding remainders against its rounding loss."""
    start, compared, off = 0, 0, []
    for k, (_, votes) in enumerate(rounds):
        figures = {key(name): value for name, value in votes.items()}
        hit = next((i for i in range(start, len(ours["rounds"])) if all(
            figures.get(key(name)) == Decimal(value) for name, value in ours["rounds"][i]["tallies"].items())), None)
        if hit is None:
            continue
        start, compared = hit, compared + 1
        mine = ours["rounds"][hit]
        if (gone[k][0] - gone[0][0] != Decimal(mine["exhaustedVotes"])
                or gone[k][1] != Decimal(mine["roundingLoss"])):
            off.append(k + 1)
    if off:
        return f"; exhausted votes or remainders DIFFER in official rounds {off}"
    return f"; exhausted votes and remainders the official ones ({compared} rounds)" if compared else ""


def check(data, contest, jar):
    file_id, election, race = CONTESTS[contest]
    seats, rounds, elected, gone = report(fetch(os.path.join(data, contest + ".xml"),
                                          REPORT.format(election=election, race=race)))
    blt = os.path.join(data, contest + ".blt")
    if not os.path.exists(blt):
        cvr = fetch(os.path.join(data, contest + ".csv"), DATAVERSE.format(id=file_id))
        # An overvote ends the ballot; a skipped ranking or a write-in is passed over.
        subprocess.run([sys.executable, CONVERTER, cvr, blt, "--seats", str(seats), "--title", "Albany"], check=True)
    rules = ["--config", CONFIG_2022] if contest.endswith("2022") else ["--ruleset", "albany"]
    ours, error = count(jar, blt, rules)
    if error:
        return False, f"{contest}: count failed: {error}"
    unmatched = unmatched_rounds([(k + 1, {key(name): value for name, value in votes.items()})
                                  for k, (_, votes) in enumerate(rounds)], ours,
                                 lambda figures, name: figures.get(key(name)))
    same = sorted(map(key, ours["electedInOrder"])) == sorted(map(key, elected))
    threshold = rounds[0][0]
    line = threshold_line(contest, ours, f"{threshold:f}", len(rounds) - len(unmatched), len(rounds), same,
                          ours["electedInOrder"])
    if unmatched:
        line += f"; first unmatched official round {unmatched[0]}"
    line += nontransferable_line(rounds, gone, ours)
    return not unmatched and same and Decimal(ours["quota"]) == threshold, line


if __name__ == "__main__":
    main(__doc__.split("\n\n")[0], CONTESTS, check)

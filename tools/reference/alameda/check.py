"""Check the irv ruleset against Alameda County's official single-seat ranked-choice counts.

usage: check.py DATA_DIR [--jar PATH] [CONTEST ...]

CONTEST is one of CONTESTS below (all, by default): the Oakland and Berkeley contests of 2022,
2024 and April 2025 that both FairVote's collection of single-winner RCV cast vote records
(https://doi.org/10.7910/DVN/AMK8PJ) and the County's Dominion "RCV Detailed Reports" (under
https://alamedacountyca.gov/rovresults/rcv/) have. Each record and report is fetched into
DATA_DIR, the record converted (../albany/cvr_to_blt.py: an overvote ends the ballot; a skipped
ranking or a write-in is passed over; a candidate ranked again counts once) and counted with
the `irv` ruleset.

Every official round's tallies are compared with this count's rounds, in order, to the vote, and
the elimination order and the winner must be the same. In 2022 the County's count held back, in
its first round only, some ballots whose first ranking is skipped or a write-in ("Suspended"),
which join from the second round; that first round is compared with the record's first rankings
instead. Where the record isn't quite the ballots the County counted (a few votes a candidate,
from the first round on), each round's largest difference is reported, and the contest passes
on the same eliminations and winner.
"""
import argparse
import collections
import csv
import json
import os
import re
import subprocess
import sys
import unicodedata
import urllib.request
import xml.etree.ElementTree as ET
from decimal import Decimal

HERE = os.path.dirname(os.path.abspath(__file__))
DEFAULT_JAR = os.path.join(HERE, "..", "..", "..", "target", "rankedchoice-0.1.0-SNAPSHOT.jar")
CONVERTER = os.path.join(HERE, "..", "albany", "cvr_to_blt.py")
DATAVERSE = "https://dataverse.harvard.edu/api/access/datafile/{id}?format=original"
REPORT = "https://alamedacountyca.gov/rovresults/rcv/{election}/{race}/RcvDetailedReport.xml"
NS = "{RcvDetailedReport}"
NOT_CANDIDATES = {"Blanks", "Exhausted", "Overvotes", "Remainder Points", "Suspended", "Surplus Transfer Remainder"}
PASSED_OVER = {"skipped", "write-in", "write in"}
# contest: (Dataverse file id, County election id, race)
CONTESTS = {
    "oakland-2022-mayor": (11593507, 248, "Oakland/001-Mayor"),
    "oakland-2022-council-4": (11593481, 248, "Oakland/003-CityCouncil_District_4"),
    "oakland-2022-council-6": (11593483, 248, "Oakland/004-CityCouncil_District_6"),
    "oakland-2022-auditor": (11593449, 248, "Oakland/005-CityAuditor"),
    "oakland-2022-school-2": (11593470, 248, "Oakland/006-SchoolDirector_District_2"),
    "oakland-2022-school-4": (11593488, 248, "Oakland/007-SchoolDirector_District_4"),
    "oakland-2022-school-6": (11593499, 248, "Oakland/008-SchoolDirector_District_6"),
    "berkeley-2024-mayor": (11593220, 252, "Berkeley/001-Mayor"),
    "berkeley-2024-council-2": (11593243, 252, "Berkeley/002-CityCouncil_District_2"),
    "berkeley-2024-council-3": (11593206, 252, "Berkeley/003-CityCouncil_District_3"),
    "berkeley-2024-council-5": (11593210, 252, "Berkeley/004-CityCouncil_District_5"),
    "berkeley-2024-council-6": (11593204, 252, "Berkeley/005-CityCouncil_District_6"),
    "oakland-2024-council-at-large": (11593478, 252, "Oakland/001-CityCouncil_AtLarge"),
    "oakland-2024-council-1": (11593492, 252, "Oakland/002-CityCouncil_District_1"),
    "oakland-2024-council-3": (11593469, 252, "Oakland/003-CityCouncil_District_3"),
    "oakland-2024-council-5": (11593473, 252, "Oakland/004-CityCouncil_District_5"),
    "oakland-2024-council-7": (11593501, 252, "Oakland/005-CityCouncil_District_7"),
    "oakland-2024-city-attorney": (11593455, 252, "Oakland/006-CityAttorney"),
    "oakland-2024-school-1": (11593462, 252, "Oakland/007-SchoolDirector_District_1"),
    "oakland-2024-school-3": (11593472, 252, "Oakland/008-SchoolDirector_District_3"),
    "oakland-2024-school-5": (11593461, 252, "Oakland/009-SchoolDirector_District_5"),
    "oakland-2024-school-7": (11593511, 252, "Oakland/010-SchoolDirector_District_7"),
    "oakland-2025-mayor": (11593491, 257, "Oakland/001-Mayor"),
    "oakland-2025-council-2": (11593446, 257, "Oakland/002-CityCouncil_District_2"),
}


def fetch(path, url):
    if not os.path.exists(path):
        request = urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0"})
        open(path, "wb").write(urllib.request.urlopen(request, timeout=300).read())
    return path


def key(name):
    """A name's letters, so the record's and the report's spellings meet."""
    return re.sub(r"[^A-Z]", "", unicodedata.normalize("NFKD", name).encode("ascii", "ignore").decode().upper())


def report(path):
    """Each round's votes per candidate (by key), the suspended ballots of round 1, the candidates
    eliminated in order, and the winners."""
    root = ET.parse(path).getroot()
    rounds, suspended, elected, eliminated = [], 0, [], []
    for group in root.iter(f"{NS}roundGroup"):
        summary = group.find(f"{NS}Tablix5")
        votes = {c.get("choiceName2"): Decimal(c.get("votes")) for c in summary.iter(f"{NS}choiceGroup")}
        if not rounds:
            suspended = votes.get("Suspended", Decimal(0))
        rounds.append({key(name): value for name, value in votes.items() if name not in NOT_CANDIDATES})
        for element in group.iter():
            text = element.get("Textbox21") or ""
            match = re.match(r"(.*) is (elected|eliminated) because", text)
            if match:
                into = elected if match.group(2) == "elected" else eliminated
                if key(match.group(1)) not in into:
                    into.append(key(match.group(1)))
    return rounds, suspended, elected, eliminated


def first_rankings(cvr):
    """The record's first rankings per candidate (by key)."""
    reader = csv.DictReader(open(cvr, newline="", encoding="utf-8-sig"))
    columns = sorted((c for c in reader.fieldnames if c.startswith("rank")), key=lambda c: int(c[4:]))
    return collections.Counter(key(row[columns[0]].strip()) for row in reader)


def check(data, contest, jar):
    file_id, election, race = CONTESTS[contest]
    rounds, suspended, elected, eliminated = report(fetch(os.path.join(data, contest + ".xml"),
                                                          REPORT.format(election=election, race=race)))
    cvr = fetch(os.path.join(data, contest + ".csv"), DATAVERSE.format(id=file_id))
    blt = os.path.join(data, contest + ".blt")
    if not os.path.exists(blt):
        subprocess.run([sys.executable, CONVERTER, cvr, blt, "--seats", "1"], check=True)
    result = subprocess.run(["java", "-jar", jar, "-i", blt, "--ruleset", "irv", "--report-format", "json"],
                            capture_output=True, text=True)
    if result.returncode:
        return False, f"{contest}: count failed: {result.stderr.strip()[-200:]}"
    ours = json.loads(result.stdout)
    ours_rounds = [{key(name): Decimal(votes) for name, votes in r["tallies"].items()} for r in ours["rounds"]]
    ours_eliminated = [key(r["action"]["candidate"]) for r in ours["rounds"] if r["action"]["type"] == "eliminated"]
    exact, worst = 0, Decimal(0)
    for k, figures in enumerate(rounds):
        if k == 0 and suspended:
            first = first_rankings(cvr)
            gap = max(abs(first[name] - votes) for name, votes in figures.items())
        else:
            # This count's round k is the official round k + 1's state, before the k-th action.
            mine = ours_rounds[k] if k < len(ours_rounds) else {}
            gap = max((abs(mine.get(name, Decimal(0)) - votes) for name, votes in figures.items() if votes > 0),
                      default=Decimal(0))
        exact += gap == 0
        worst = max(worst, gap)
    same_order = ours_eliminated[:len(eliminated)] == eliminated
    same = [key(name) for name in ours["electedInOrder"]] == elected
    line = (f"{contest}: {exact} of {len(rounds)} official rounds match to the vote"
            + (f" (round 1 against the record's first rankings, {int(suspended)} ballots suspended)" if suspended else "")
            + (f"; the record differs by up to {int(worst)} votes a candidate" if worst else "")
            + f"; eliminations in {'the same' if same_order else 'A DIFFERENT'} order"
            + f"; elected {'the same' if same else 'DIFFERENTLY: ' + str(ours['electedInOrder'])}")
    return same_order and same, line


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
    print(f"{len(args.contests) - failed} of {len(args.contests)} contests check out")
    sys.exit(1 if failed else 0)


if __name__ == "__main__":
    main()

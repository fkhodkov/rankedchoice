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
differently ("ROBIN D  LOPEZ", "ROBIN D. LÓPEZ"); they're matched on their letters.
"""
import argparse
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
ROOT = os.path.join(HERE, "..", "..", "..")
CONVERTER = os.path.join(HERE, "..", "cvr_to_blt.py")
DEFAULT_JAR = os.path.join(ROOT, "target", "rankedchoice-0.1.0-SNAPSHOT.jar")
CONFIG_2022 = os.path.join(ROOT, "src", "test", "resources", "testdata", "albany", "albany-2022.yaml")
DATAVERSE = "https://dataverse.harvard.edu/api/access/datafile/{id}?format=original"
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


def fetch(path, url):
    if not os.path.exists(path):
        request = urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0"})
        open(path, "wb").write(urllib.request.urlopen(request, timeout=300).read())
    return path


def key(name):
    """A name's letters, so the CVR's and the report's spellings meet."""
    letters = unicodedata.normalize("NFKD", name).encode("ascii", "ignore").decode().upper()
    return re.sub(r"[^A-Z]", "", letters)


def report(path):
    """Seats, each round's threshold and candidates' votes, and those elected, from a Dominion report."""
    root = ET.parse(path).getroot()
    seats = int(re.search(r"\d+", root.find(f".//{NS}Tablix2").get("Textbox53")).group())
    rounds, elected = [], []
    for group in root.iter(f"{NS}roundGroup"):
        summary = group.find(f"{NS}Tablix5")
        threshold = Decimal(summary.get("Textbox86").split("Threshold:")[1].strip().replace(",", ""))
        votes = {c.get("choiceName2"): Decimal(c.get("votes")) for c in summary.iter(f"{NS}choiceGroup")
                 if c.get("choiceName2") not in NOT_CANDIDATES}
        rounds.append((threshold, votes))
        for element in group.iter():
            text = element.get("Textbox21") or ""
            match = re.match(r"(.*) is elected because", text)
            if match and match.group(1) not in elected:
                elected.append(match.group(1))
    return seats, rounds, elected


def check(data, contest, jar):
    file_id, election, race = CONTESTS[contest]
    seats, rounds, elected = report(fetch(os.path.join(data, contest + ".xml"),
                                          REPORT.format(election=election, race=race)))
    blt = os.path.join(data, contest + ".blt")
    if not os.path.exists(blt):
        cvr = fetch(os.path.join(data, contest + ".csv"), DATAVERSE.format(id=file_id))
        # An overvote ends the ballot; a skipped ranking or a write-in is passed over.
        subprocess.run([sys.executable, CONVERTER, cvr, blt, "--seats", str(seats), "--title", "Albany"], check=True)
    rules = ["--config", CONFIG_2022] if contest.endswith("2022") else ["--ruleset", "albany"]
    result = subprocess.run(["java", "-jar", jar, "-i", blt, "--report-format", "json"] + rules,
                            capture_output=True, text=True)
    if result.returncode:
        return False, f"{contest}: count failed: {result.stderr.strip()[-200:]}"
    ours = json.loads(result.stdout)
    start, unmatched = 0, []
    for k, (_, votes) in enumerate(rounds):
        figures = {key(name): value for name, value in votes.items()}
        hit = next((i for i in range(start, len(ours["rounds"]))
                    if all(figures.get(key(name)) == Decimal(value)
                           for name, value in ours["rounds"][i]["tallies"].items())), None)
        if hit is None:
            unmatched.append(k + 1)
        else:
            start = hit
    same = sorted(map(key, ours["electedInOrder"])) == sorted(map(key, elected))
    threshold = rounds[0][0]
    line = (f"{contest}: threshold {Decimal(ours['quota']):f} (official {threshold:f}); "
            f"{len(rounds) - len(unmatched)} of {len(rounds)} official rounds match a round of this count; "
            f"elected {'the same' if same else 'DIFFERENTLY: ' + str(ours['electedInOrder'])}")
    if unmatched:
        line += f"; first unmatched official round {unmatched[0]}"
    return not unmatched and same and Decimal(ours["quota"]) == threshold, line


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

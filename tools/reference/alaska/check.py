"""Check the irv ruleset against the Alaska Division of Elections' ranked-choice counts, 2022-2024.

usage: check.py DATA_DIR [--jar PATH] [CONTEST ...]

CONTEST is one of CONTESTS below (all, by default): the contests of the August 2022 special
election, the November 2022 general election and the November 2024 general election that went
past a first round, for which the Internet Archive kept the Division's Dominion "RCV Detailed
Report" (its own site, elections.alaska.gov, turns away scripted requests) and FairVote's
collection of single-winner RCV cast vote records (https://doi.org/10.7910/DVN/AMK8PJ) has the
ballots. Each is fetched into DATA_DIR, the record converted (../cvr_to_blt.py) and counted with
`irv` (the reports' "Declare winners by threshold: False": the fewest votes are defeated round
by round until one candidate is left), and every official round's votes compared with this
count's, round for round, with the elimination order and the winner. Where the record differs
from the Division's count by a few votes, each round's largest difference is reported; such a
contest passes on the same eliminations and winner.
"""
import functools
import json
import os
import re
import subprocess
import sys
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, ".."))
from rcvcheck import DATAVERSE, count, fetch, main, pdf_text  # noqa: E402

CONVERTER = os.path.join(HERE, "..", "cvr_to_blt.py")
ARCHIVE = "https://web.archive.org/web/{stamp}id_/https://www.elections.alaska.gov/results/{path}"
# contest: (Dataverse file id, archived report: timestamp and path under /results/)
CONTESTS = {
    "2022-special-us-house": (11593054, "20220901162008", "22SSPG/RcvDetailedReport.pdf"),
    "2022-us-house": (11593091, "20221201122645", "22GENR/US%20REP.pdf"),
    "2022-us-senate": (11593072, "20221201102100", "22GENR/US%20SEN.pdf"),
    "2022-house-11": (11593061, "20221201150232", "22GENR/11.pdf"),
    "2022-house-15": (11593080, "20221201101202", "22GENR/15.pdf"),
    "2022-house-18": (11593111, "20221201093140", "22GENR/18.pdf"),
    "2022-house-28": (11593069, "20221201083955", "22GENR/28.pdf"),
    "2022-house-30": (11593082, "20221202130443", "22GENR/30.pdf"),
    "2022-house-31": (11593101, "20221201081715", "22GENR/31.pdf"),
    "2022-house-34": (11593077, "20221204152839", "22GENR/34.pdf"),
    "2022-senate-d": (11593112, "20221201075927", "22GENR/D.pdf"),
    "2022-senate-e": (11593109, "20221201103956", "22GENR/E.pdf"),
    "2022-senate-n": (11593110, "20221201053929", "22GENR/N.pdf"),
}
FILES_2024 = {"2024-us-house": ("Alaska_20241105_US_House.tab", "20241204164835", "24GENR/RCV-USRep.pdf")}
for n in (6, 28, 36, 38, 40):
    FILES_2024[f"2024-house-{n}"] = (f"Alaska_20241105_StateHouseD{n}.tab", None, f"24GENR/RCV-HD{n}.pdf")
for x in "DFL":
    FILES_2024[f"2024-senate-{x.lower()}"] = (f"Alaska_20241105_StateSenate{x}.tab", None, f"24GENR/RCV-Sen{x}.pdf")
STAMPS_2024 = {"RCV-HD28.pdf": "20241122015926", "RCV-HD36.pdf": "20250206032958", "RCV-HD38.pdf": "20250206033002",
               "RCV-HD40.pdf": "20241204024735", "RCV-HD6.pdf": "20250206032953", "RCV-SenD.pdf": "20241213112547",
               "RCV-SenF.pdf": "20241220002622", "RCV-SenL.pdf": "20250206033015"}


@functools.cache
def dataverse_ids():
    listing = json.loads(urllib.request.urlopen(urllib.request.Request(
        "https://dataverse.harvard.edu/api/datasets/:persistentId/?persistentId=doi:10.7910/DVN/AMK8PJ",
        headers={"User-Agent": "Mozilla/5.0"}), timeout=300).read())
    return {f["dataFile"]["filename"]: f["dataFile"]["id"] for f in listing["data"]["latestVersion"]["files"]}


def report(pdf):
    """Each round's votes per candidate, the candidates eliminated in order, and the winners."""
    text = pdf_text(pdf)
    rounds, current, eliminated, elected = [], None, [], []
    for line in text.splitlines():
        if re.match(r"^Round \d+\s*$", line.strip()):
            current = {}
            rounds.append(current)
            continue
        if current is not None:
            if "Continuing Ballots Total" in line:
                current = None
                continue
            match = re.match(r"^(\S.*?)\s{2,}([\d,]+)\s+[\d.]+%\s*$", line)
            if match and match.group(1) != "Candidate":
                current[match.group(1).strip()] = int(match.group(2).replace(",", ""))
        match = re.match(r"^(.*) is (eliminated|elected) because", line.strip())
        if match:
            (eliminated if match.group(2) == "eliminated" else elected).append(match.group(1))
    return rounds, eliminated, elected


def arguments(contest):
    """A contest's Dataverse file id and archived report (timestamp, path); 2024's ids from the dataset's listing."""
    if contest in CONTESTS:
        return CONTESTS[contest]
    filename, stamp, path = FILES_2024[contest]
    return dataverse_ids()[filename], stamp or STAMPS_2024[path.split("/")[-1]], path


def check(data, contest, jar):
    file_id, stamp, path = arguments(contest)
    rounds, eliminated, elected = report(fetch(os.path.join(data, contest + ".pdf"),
                                               ARCHIVE.format(stamp=stamp, path=path)))
    blt = os.path.join(data, contest + ".blt")
    if not os.path.exists(blt):
        cvr = fetch(os.path.join(data, contest + ".csv"), DATAVERSE.format(id=file_id))
        # An unresolved write-in is passed over; two skipped rankings in a row end the ballot; so does an overvote.
        subprocess.run([sys.executable, CONVERTER, cvr, blt, "--title", contest, "--skipped", "two-end"], check=True)
    ours, error = count(jar, blt)
    if error:
        return False, f"{contest}: count failed: {error}"
    exact, gaps = 0, []
    for k, figures in enumerate(rounds):
        mine = {name: int(float(votes)) for name, votes in ours["rounds"][k]["tallies"].items()} \
            if k < len(ours["rounds"]) else {}
        gap = max((abs(mine.get(name, 0) - votes) for name, votes in figures.items() if votes > 0), default=0)
        exact += gap == 0
        gaps.append(gap)
    ours_eliminated = [r["action"]["candidate"] for r in ours["rounds"] if r["action"]["type"] == "eliminated"]
    same_order = ours_eliminated[:len(eliminated)] == eliminated
    same = ours["electedInOrder"] == elected
    line = (f"{contest}: {exact} of {len(rounds)} official rounds match to the vote"
            + (f" (largest differences by round: {gaps})" if any(gaps) else "")
            + f"; eliminations in {'the same' if same_order else 'A DIFFERENT'} order"
            + f"; elected {'the same' if same else 'DIFFERENTLY: ' + str(ours['electedInOrder']) + ' vs ' + str(elected)}")
    return same_order and same, line


if __name__ == "__main__":
    main(__doc__.split("\n\n")[0], list(CONTESTS) + list(FILES_2024), check)

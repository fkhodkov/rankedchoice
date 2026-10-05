"""Check the irv ruleset against Maine's official ranked-choice counts, 2018-2024.

usage: check.py DATA_DIR [--jar PATH] [CONTEST ...]

CONTEST is one of CONTESTS below (all, by default): the contests the Secretary of State counted by ranked
choice whose ballots FairVote's collection of single-winner RCV cast vote records
(https://doi.org/10.7910/DVN/AMK8PJ) has: 11, from the 2018 Democratic primary for governor and the 2018,
2022 and 2024 races for Congress in the 2nd District to five legislative primaries of 2020. Each record and
the State's "RCV Summary Report" are fetched into DATA_DIR, the record converted with
../alaska/cvr_to_blt.py --write-in-candidate (two skipped rankings in a row end the ballot, those before
the first choice included; an overvote ends it; a candidate ranked again counts once; write-ins are one
candidate, Write-in), which reproduces the reports' first rounds to the vote, exhausted ballots included,
and counted with `irv`.

The State defeats together the lowest candidates who couldn't overtake the next one up, and stops at a
majority; so every official round must be one of this count's rounds, in order, with the same candidates
continuing, and the same candidate must win. The votes are compared, and where they differ, each round's
largest difference is reported.

The reports come as Excel files (2018, 2020: read with LibreOffice's soffice) or PDFs (2022: pdftotext),
a row per candidate giving the votes, percentage and transfer of every round. The 2024 report is a scan,
with no text; its figures are transcribed below (SCANNED), and its one round starts after the write-ins
are eliminated.
"""
import argparse
import csv
import glob
import json
import os
import re
import subprocess
import sys
import urllib.parse
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
DEFAULT_JAR = os.path.join(HERE, "..", "..", "..", "target", "rankedchoice-0.1.0-SNAPSHOT.jar")
CONVERTER = os.path.join(HERE, "..", "alaska", "cvr_to_blt.py")
DATAVERSE = "https://dataverse.harvard.edu/api/access/datafile/{id}?format=original"
SITE = "https://www.maine.gov/sos/sites/maine.gov.sos/files/"
# contest: (Dataverse file id, the State's summary report under SITE)
CONTESTS = {
    "2018-dem-governor": (11593316, "content/assets/democraticgovfinal.xls"),
    "2018-dem-congress-2": (11593321, "content/assets/Democraticcgfinal.xls"),
    "2018-congress-2": (11593278, "content/assets/updated-summary-report-CD2.xls"),
    "2020-rep-congress-2": (11593299, "content/assets/offcg2summary.xls"),
    "2020-dem-senate-11": (11593351, "content/assets/offss11summary.xls"),
    "2020-dem-house-41": (11593293, "content/assets/offsr41summary.xls"),
    "2020-dem-house-47": (11593329, "content/assets/offsr47summary.xls"),
    "2020-dem-house-49": (11593254, "content/assets/offsr49summary.xls"),
    "2020-dem-house-90": (11593309, "content/assets/offsr90summary.xls"),
    "2022-congress-2": (11593314, "inline-files/Rep%20to%20Congress%20Dist%202%20RCV%20results.pdf"),
    "2024-congress-2": (11593287, "content/assets/Nov24-20RCV-20CG2-20Summary-20Report.pdf"),
}
# The 2024 report is a scanned page: its figures, read off it.
SCANNED = {"2024-congress-2": {"Golden, Jared Forrest": [197151], "Theriault, Austin": [194445]}}
NUMBER = re.compile(r"^-?\d+$")


def fetch(path, url):
    if not (os.path.exists(path) and os.path.getsize(path) > 0):
        request = urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0"})
        with urllib.request.urlopen(request, timeout=900) as response, open(path + ".part", "wb") as out:
            while chunk := response.read(1 << 20):
                out.write(chunk)
        os.replace(path + ".part", path)
    return path


def key(name):
    """A name's letters, so the record's spelling and the report's meet."""
    return re.sub(r"[^A-Z]", "", name.upper())


def rows_of(path):
    if path.endswith(".pdf"):
        text = subprocess.run(["pdftotext", "-layout", path, "-"], capture_output=True, text=True, check=True).stdout
        for line in text.splitlines():
            yield re.split(r"\s{2,}", line.strip())
    else:
        stem = os.path.splitext(path)[0]
        if not glob.glob(stem + "-*.csv"):
            # Every sheet (the last option), comma-separated, UTF-8.
            subprocess.run(["soffice", "--headless", "--convert-to",
                            "csv:Text - txt - csv (StarCalc):44,34,76,1,,0,false,true,false,false,false,-1",
                            "--outdir", os.path.dirname(path), path], check=True, capture_output=True)
        for sheet in sorted(glob.glob(stem + "-*.csv")):
            yield from csv.reader(open(sheet, encoding="utf-8", errors="ignore"))


def official_rounds(path):
    """{candidate: [votes in round 1, 2, ...]}: under "Candidate Names", a candidate's row is the name, then the
    votes, percentage and transfer of every round, until "Ballot Exhausted"."""
    totals, reading = {}, False
    for row in rows_of(path):
        cells = [c.strip() for c in row]
        if cells and cells[0] == "Candidate Names":
            reading = True
        elif reading and cells and cells[0].startswith("Ballot"):
            break
        elif reading and cells and cells[0]:
            totals[cells[0]] = [int(v) for v in cells[1::3] if NUMBER.match(v)]
    return totals


def check(data, contest, jar):
    file_id, report = CONTESTS[contest]
    if contest in SCANNED:
        totals = SCANNED[contest]
    else:
        local = fetch(os.path.join(data, contest + os.path.splitext(report)[1]), SITE + report)
        totals = official_rounds(local)
    totals = {key(name): votes for name, votes in totals.items()}
    rounds = max(len(v) for v in totals.values())
    blt = os.path.join(data, contest + ".blt")
    if not os.path.exists(blt):
        cvr = fetch(os.path.join(data, contest + ".csv"), DATAVERSE.format(id=file_id))
        subprocess.run([sys.executable, CONVERTER, cvr, blt, "--write-in-candidate"], check=True)
    result = subprocess.run(["java", "-jar", jar, "-i", blt, "--ruleset", "irv", "--report-format", "json"],
                            capture_output=True, text=True)
    if result.returncode:
        return False, f"{contest}: count failed: {result.stderr.strip()[-200:]}"
    ours = json.loads(result.stdout)
    exact, gaps, start = 0, [], 0
    for k in range(rounds):
        figures = {name: votes[k] for name, votes in totals.items() if votes[k] > 0}
        hit = next((i for i in range(start, len(ours["rounds"]))
                    if {key(name) for name, v in ours["rounds"][i]["tallies"].items() if float(v) > 0}
                    == set(figures)), None)
        if hit is None:
            gaps.append(None)
            continue
        start = hit
        mine = {key(name): int(float(v)) for name, v in ours["rounds"][hit]["tallies"].items()}
        gap = max(abs(mine.get(name, 0) - votes) for name, votes in figures.items())
        exact += gap == 0
        gaps.append(gap)
    winner = max(totals, key=lambda name: totals[name][-1])
    same_rounds = None not in gaps
    same = [key(name) for name in ours["electedInOrder"]] == [winner]
    line = (f"{contest}: {exact} of {rounds} official rounds match to the vote"
            + (f" (largest differences by round: {gaps})" if any(gaps) else "")
            + ("; eliminations the same" if same_rounds
               else f"; ELIMINATIONS DIFFER from official round {gaps.index(None) + 1}")
            + f"; elected {'the same' if same else 'DIFFERENTLY: ' + str(ours['electedInOrder'])}"
            + ("; report transcribed from a scan" if contest in SCANNED else ""))
    return same_rounds and same, line


def main():
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("data")
    parser.add_argument("contests", nargs="*")
    parser.add_argument("--jar", default=DEFAULT_JAR)
    args = parser.parse_args()
    os.makedirs(args.data, exist_ok=True)
    chosen = args.contests or list(CONTESTS)
    failed = 0
    for contest in chosen:
        ok, line = check(args.data, contest, args.jar)
        failed += not ok
        print(line, flush=True)
    print(f"{len(chosen) - failed} of {len(chosen)} contests check out")
    sys.exit(1 if failed else 0)


if __name__ == "__main__":
    main()

"""Check the irv ruleset against Maine's official ranked-choice counts, 2018-2026.

usage: check.py DATA_DIR [--jar PATH] [CONTEST ...]

CONTEST is one of CONTESTS below (all, by default): the contests the Secretary of State counted by ranked
choice whose ballots FairVote's collection of single-winner RCV cast vote records
(https://doi.org/10.7910/DVN/AMK8PJ) has: 11, from the 2018 Democratic primary for governor and the 2018,
2022 and 2024 races for Congress in the 2nd District to five legislative primaries of 2020. Each record and
the State's "RCV Summary Report" are fetched into DATA_DIR, the record converted with
../cvr_to_blt.py --skipped two-end --write-in candidate (two skipped rankings in a row end the ballot, those before
the first choice included; an overvote ends it; a candidate ranked again counts once; write-ins are one
candidate, Write-in), which reproduces the reports' first rounds to the vote, exhausted ballots included,
and counted with `irv`.

The State defeats together the lowest candidates who couldn't overtake the next one up, and stops at a
majority; so every official round must be one of this count's rounds, in order, with the same candidates
continuing, and the same candidate must win. The votes are compared, and where they differ, each round's
largest difference is reported.

For the 2018-2022 reports each round's exhausted ballots by cause (overvotes, undervotes, exhausted choices)
are compared with the record's too (exhausted_line).

The reports come as Excel files (2018, 2020: read with LibreOffice's soffice) or PDFs (2022: pdftotext),
a row per candidate giving the votes, percentage and transfer of every round. The 2024 report is a scan,
with no text; its figures are transcribed below (SCANNED), and its one round starts after the write-ins
are eliminated.

The June 2026 primaries (STATE_2026) aren't in FairVote's collection: the State publishes their ballots itself,
each contest's in several Excel exports (the central count, towns counted apart, the manual count of auxiliary
ballots, overseas ballots), a row per ballot with a column per choice. They're fetched, joined (every part must
have the same columns) and read the same way; their reports are PDFs of a newer layout, a row per candidate with
the votes of every round. These counts eliminate one candidate a round.

For each 2026 contest the State also publishes its tally of first choices by town (FIRST_CHOICES): each
candidate marked first, and blanks. The exports' first rankings are compared with it town by town, and the
line says where they differ: where they do, the exports aren't quite the ballots the State counted.
"""
import collections
import csv
import os
import re
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, ".."))
from rcvcheck import (DATAVERSE, count, fetch, main, match_rounds, name_key as key, pdf_text,  # noqa: E402
                      stale, summary, table_rows, winner)

CONVERTER = os.path.join(HERE, "..", "cvr_to_blt.py")
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
# 2026: the State's summary report and every export of the ballots, under SITE's inline-files/.
STATE_2026 = {
    "2026-dem-congress-2": ("CG2%20Democratic%20RCV%20Summary%20Report.pdf", [
        "DEM%20CG2%20Additional%20CVR%20Export%2020260618.xlsx",
        "DEM%20CG2%20Vienna%2020260618.xlsx",
        "Jun26%20DEM%20CG2%2020260618.xlsx",
        "Jun26%20DEM%20CG2%20Auburn%202060616.xlsx",
        "Jun26%20DEM%20CG2%20AUX%20MANUAL1%2020260617.xlsx",
        "Jun26%20DEM%20CG2%20AUX%20MANUAL2%2020260617.xlsx",
        "Jun26%20DEM%20CG2%20AUX%20MANUAL3%20Updated%2020260623.xlsx",
        "Representative%20to%20Congress%20District%202%20%28D%29%20UOCAVA.xlsx"]),
    "2026-dem-governor": ("GOV%20Democratic%20RCV%20Summary%20Report.pdf", [
        "DEM%20Gov%20Additional%20CVR%20Export%2020260618.xlsx",
        "DEM%20Gov%20Vienna%2020260618.xlsx",
        "Governor%20%28D%29%20UOCAVA.xlsx",
        "Jun26%20DEM%20GOV%2020260618%20-%201.xlsx",
        "Jun26%20DEM%20GOV%2020260618%20-%202.xlsx",
        "Jun26%20DEM%20GOV%2020260618.xlsx",
        "Jun26%20DEM%20Gov%20Auburn%2020260616.xlsx",
        "Jun26%20DEM%20Gov%20AUX%20MANUAL1%2020260617.xlsx",
        "Jun26%20DEM%20Gov%20AUX%20MANUAL2%2020260617.xlsx",
        "Jun26%20DEM%20Gov%20AUX%20MANUAL3%20Updated%2020260623.xlsx"]),
    "2026-rep-governor": ("GOV%20Republican%20RCV%20Summary%20Reportxlsx.pdf", [
        "Governor%20%28R%29%20UOCAVA.xlsx",
        "Jun26%20REP%20GOV%2020260618%20-%201.xlsx",
        "Jun26%20REP%20GOV%2020260618_2.xlsx",
        "Jun26%20REP%20Gov%20Auburn%2020260616.xlsx",
        "Jun26%20REP%20Gov%20AUX1%2020260617.xlsx",
        "Jun26%20REP%20Gov%20AUX2%2020260617.xlsx",
        "Jun26%20REP%20Gov%20AUX3%20MANUAL%20Updated%2020260623.xlsx",
        "REP%20Gov%20Additional%20CVR%20Export%2020260618.xlsx",
        "REP%20Gov%20Vienna%2020260618.xlsx"]),
    "2026-rep-senate-4": ("REP%20SS4%20RCV%20Summary%20Report.pdf", [
        "Jun26%20REP%20SS4%2020260618.xlsx",
        "Jun26%20REP%20SS4%20AUX%20MANUAL%2020260617.xlsx",
        "State%20Senator%20District%204%20%28R%29%20UOCAVA.xlsx"]),
    "2026-rep-house-58": ("REP%20SR58%20RCV%20Summary%20Report.pdf", [
        "Jun26%20REP%20SR58%2020260618.xlsx",
        "Jun26%20REP%20SR58%20AUX1.xlsx",
        "REP%20SR58%20Additional%20CVR%20Export%2020260618.xlsx",
        "REP%20SR58%20Vienna%2020260618.xlsx",
        "Representative%20to%20the%20Legislature%20District%2058%20%28R%29%20UOCAVA.xlsx"]),
}
# 2026: the State's tally of first choices by town, under SITE's inline-files/.
FIRST_CHOICES = {
    "2026-dem-congress-2": "Representative%20to%20Congress%20District%202%20-%20Democratic.xlsx",
    "2026-dem-governor": "Governor%20-%20Democratic.xlsx",
    "2026-rep-governor": "Governor%20-%20Republican.xlsx",
    "2026-rep-senate-4": "Senate%20District%204%20-%20Republican.xlsx",
    "2026-rep-house-58": "House%20District%2058%20-%20Republican.xlsx",
}
# Places the exports name otherwise than the tally does: misspelt, or a township counted with a town.
PLACES = {"MILLINOCKETT": "MILLINOCKET", "BUCKSPORTS": "BUCKSPORT", "TD R2 WELS": "BRIDGEWATER",
          "ELLIOTTSVILLE": "MONSON"}
SCANNED = {"2024-congress-2": {"Golden, Jared Forrest": [197151], "Theriault, Austin": [194445]}}
NUMBER = re.compile(r"^-?\d+$")


def rows_of(path):
    if path.endswith(".pdf"):
        for line in pdf_text(path).splitlines():
            yield re.split(r"\s{2,}", line.strip())
    else:
        yield from table_rows(path)


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


CAUSES = {"By Overvotes": "overvotes", "By Undervotes": "undervotes", "By Exhausted Choices": "exhausted choices"}


def exhausted_by_cause(path):
    """{cause: [ballots in round 1, 2, ...]}: the 2018-2022 reports' rows under "Ballot Exhausted": a spreadsheet's
    every third column, as for the candidates; a PDF's every other figure (ballots, then the transfer, a round)."""
    found = {}
    for row in rows_of(path):
        cells = [c.strip() for c in row]
        if cells and cells[0] in CAUSES:
            figures = [int(v) for v in cells[1:] if NUMBER.match(v)] if path.endswith(".pdf") else \
                [int(v) for v in cells[1::3] if NUMBER.match(v)]
            found[CAUSES[cells[0]]] = figures[0::2] if path.endswith(".pdf") else figures
    return found


def where(ballot, out):
    """Where a ballot is, read as the converter reads it (--skipped two-end --write-in candidate) with the
    candidates OUT gone: a candidate (by key), or the cause the reports exhaust it by. One that runs out of
    rankings is "exhausted choices" only if it skips none; the reports count it among the undervotes otherwise."""
    skipped, seen = 0, False
    for mark in ballot:
        lower = mark.lower()
        if lower in ("", "skipped", "undervote"):
            skipped += 1
            if skipped >= 2:
                return "undervotes"
            continue
        skipped = 0
        if lower == "overvote":
            return "overvotes"
        seen = True
        name = key("Write-in" if lower in ("write-in", "writein", "write in") else mark)
        if name not in out:
            return name
    skips = any(mark.lower() in ("", "skipped", "undervote") for mark in ballot)
    return "exhausted choices" if seen and not skips else "undervotes"


def exhausted_line(cvr, totals, causes):
    """How the record's exhausted ballots, by cause, compare with the report's in every round, the candidates the
    report eliminates going in its order."""
    reader = csv.DictReader(open(cvr, newline="", encoding="utf-8-sig"))
    columns = sorted((c for c in reader.fieldnames if c.startswith("rank")), key=lambda c: int(c[4:]))
    ballots = [[row[column].strip() for column in columns] for row in reader]
    out, off = set(), {}
    rounds = len(next(iter(causes.values())))
    for k in range(rounds):
        tally = collections.Counter(where(ballot, out) for ballot in ballots)
        gaps = {cause: tally[cause] - figures[k] for cause, figures in causes.items() if tally[cause] != figures[k]}
        if gaps:
            off[k + 1] = gaps
        out |= {name for name, votes in totals.items() if len(votes) > k + 1 and votes[k] and not votes[k + 1]}
    if not off:
        return "; exhausted ballots, by cause, the report's in every round"
    return "; exhausted ballots by cause DIFFER: " + "; ".join(
        f"round {k} {', '.join(f'{cause} {gap:+d}' for cause, gap in gaps.items())}" for k, gaps in off.items())


def summary_2026(path):
    """{candidate: [votes in round 1, 2, ...]} from a 2026 report: after the "Elected" row, a row per candidate,
    the name and each round's votes, until "Exhausted Ballots"."""
    totals, reading = {}, False
    for line in pdf_text(path).splitlines():
        cells = re.split(r"\s{2,}", line.strip())
        if cells[0].startswith("Elected"):
            reading = True
        elif reading and cells[0].startswith("Exhausted"):
            break
        elif reading and cells[0]:
            totals[cells[0]] = [int(v.replace(",", "")) for v in cells[1:] if re.fullmatch(r"[\d,]+", v)]
    return totals


def ballots_2026(data, contest):
    """Every export of a 2026 contest's ballots, fetched and joined into one CSV file; its path."""
    joined = os.path.join(data, contest + ".csv")
    parts = [fetch(os.path.join(data, f"{contest}-part{n}.xlsx"), SITE + "inline-files/" + export)
             for n, export in enumerate(STATE_2026[contest][1], 1)]
    if not stale(joined, *parts):
        return joined
    header, rows = None, []
    for part, export in zip(parts, STATE_2026[contest][1]):
        sheet = list(table_rows(part))
        if header is None:
            header = sheet[0]
        if sheet[0] != header:
            raise ValueError(f"{export} has other columns than the first export: {sheet[0]}")
        rows += sheet[1:]
    with open(joined + ".part", "w", newline="", encoding="utf-8") as out:
        writer = csv.writer(out)
        writer.writerow(header)
        writer.writerows(rows)
    os.replace(joined + ".part", joined)
    return joined


def place(name):
    """A town as the tally names it: no ward, no townships counted with it ("CARIBOU/CONNOR TWP")."""
    name = re.sub(r"\s+(W\d.*|WARD.*|ALL|\(.*\))$", "", name.strip().upper()).split("/")[0].strip()
    name = re.sub(r"\s+TWP(\s.*)?$", "", name)
    return PLACES.get(name, name)


def first_choices_compared(data, contest, cvr):
    """Where the exports' first rankings differ from the State's tally of first choices: the places, and how many
    ballots are counted differently (each moved from one column to another counts once)."""
    sheet = list(table_rows(fetch(os.path.join(data, contest + "-first-choices.xlsx"),
                                  SITE + "inline-files/" + FIRST_CHOICES[contest])))
    labels = next(i for i, row in enumerate(sheet) if "TOWN" in row)
    town_column = sheet[labels].index("TOWN")
    start = next(i for i, cell in enumerate(sheet[0]) if cell.strip())
    end = sheet[0].index("BLANK") + 1
    columns = [key(name.split(",")[0]) for name in sheet[0][start:end - 1]] + ["BLANK"]
    tally = collections.defaultdict(collections.Counter)
    for row in sheet[labels + 1:]:
        where = "UOCAVA" if "UOCAVA" in "".join(row[:start]).upper() else place(row[town_column])
        if where and len(row) > end and re.fullmatch(r"\d+", row[start].strip() or "x"):    # not the totals
            for column, votes in zip(columns, row[start:end]):
                tally[where][column] += int(votes)
    with open(cvr, newline="", encoding="utf-8") as source:
        rows = list(csv.reader(source))
    ours = collections.defaultdict(collections.Counter)
    for row in rows[1:]:
        mark = row[3].strip()
        column = "BLANK" if mark in ("", "undervote", "overvote") else key(mark.split(",")[0])
        ours["UOCAVA" if row[2].strip().upper() == "UOCAVA" else place(row[1])][column] += 1
    places = [where for where in set(tally) | set(ours) if tally[where] != ours[where]]
    moved = sum(abs(ours[where][c] - tally[where][c]) for where in places for c in columns) // 2
    return sorted(places), moved


def check(data, contest, jar):
    if contest in STATE_2026:
        totals = summary_2026(fetch(os.path.join(data, contest + ".pdf"),
                                    SITE + "inline-files/" + STATE_2026[contest][0]))
    elif contest in SCANNED:
        totals = SCANNED[contest]
    else:
        report = CONTESTS[contest][1]
        local = fetch(os.path.join(data, contest + os.path.splitext(report)[1]), SITE + report)
        totals = official_rounds(local)
        causes = exhausted_by_cause(local)
    totals = {key(name): votes for name, votes in totals.items()}
    blt = os.path.join(data, contest + ".blt")
    cvr = ballots_2026(data, contest) if contest in STATE_2026 else \
        fetch(os.path.join(data, contest + ".csv"), DATAVERSE.format(id=CONTESTS[contest][0]))
    if stale(blt, cvr):
        subprocess.run([sys.executable, CONVERTER, cvr, blt, "--title", contest, "--skipped", "two-end",
                        "--write-in", "candidate"], check=True)
    ours, error = count(jar, blt)
    if error:
        return False, f"{contest}: count failed: {error}"
    exact, gaps = match_rounds(totals, ours, key)
    same = [key(name) for name in ours["electedInOrder"]] == [winner(totals)]
    line = summary(contest, exact, gaps, same, ours["electedInOrder"])
    if contest not in STATE_2026 and contest not in SCANNED and causes:
        line += exhausted_line(fetch(os.path.join(data, contest + ".csv"), DATAVERSE.format(id=CONTESTS[contest][0])),
                               totals, causes)
    if contest in STATE_2026:
        places, moved = first_choices_compared(data, contest, ballots_2026(data, contest))
        line += ("; the exports' first rankings are the State's tally of first choices in every place" if not places
                 else f"; the exports' first rankings differ from the State's tally of first choices in "
                      f"{len(places)} places ({', '.join(p.title() if p != 'UOCAVA' else p for p in places[:4])}"
                      f"{', ...' if len(places) > 4 else ''}), {moved} ballots in all")
    return None not in gaps and same, line + ("; report transcribed from a scan" if contest in SCANNED else "")


if __name__ == "__main__":
    main(__doc__.split("\n\n")[0], list(CONTESTS) + list(STATE_2026), check)

"""Check the irv ruleset against New York City's official ranked-choice counts of its 2021, 2023 and 2025 primaries.

usage: check.py DATA_DIR [--jar PATH] [CONTEST ...]

CONTEST is one of CONTESTS below (all, by default): 124 primaries, from mayor to city council, whose
certified round-by-round pages ("Official Ranked Choice Rounds", under
https://www.vote.nyc/sites/default/files/pdf/election_results/) the Board of Elections publishes. The
ballots are the Board's own cast vote record of each primary (EXPORTS), a zip of Excel workbooks, one per
borough and way of voting, with a column per contest and ranking and the candidates by id: each year's is
fetched into DATA_DIR/<year>/, its workbooks converted to CSV with LibreOffice's soffice, and every
contest's rankings written out in one pass to DATA_DIR/<contest>.csv with the candidates' names (the
ballots that rank no one in it are left out). Each page is fetched into DATA_DIR, each record converted
with ../cvr_to_blt.py (an overvote ends the ballot; a skipped ranking or a write-in is passed over; a
candidate ranked again counts once) and counted with `irv`.

The Board counts write-ins as one candidate in round 1 and eliminates them first, in round 2, whatever
their number; passing them over is the same, so this count's first round is the Board's second, and the
Board's first is compared with the record's first choices, write-ins included. After that the Board
eliminates the candidate with the fewest votes round by round until two are left, but defeats together
the lowest candidates whose votes, all put together, are fewer than the next one up's, and prints a
round that changes nothing where a candidate first passes 50%. Neither changes the rounds passed
through, so every later official round must be one of this count's rounds, in order, with the same
candidates continuing, and the same candidate must win; the votes are compared, and where they differ,
each round's largest difference is reported.
"""
import collections
import csv
import os
import re
import shutil
import subprocess
import sys
import zipfile

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, ".."))
from rcvcheck import count, fetch, main, match_rounds, name_key, sheets, summary, table_rows, winner  # noqa: E402

CONVERTER = os.path.join(HERE, "..", "cvr_to_blt.py")
ROUNDS = "https://www.vote.nyc/sites/default/files/pdf/election_results/{year}/{date}Primary%20Election/rcv/{page}"
DATES = {"2021": "20210622", "2023": "20230627", "2025": "20250624"}
EXPORTS = {"2021": "2021/20210622Primary%20Election/cvr/PE2021_CVR_Final.zip",
           "2023": "2023/20230627Primary%20Election/cvr/2023P_CVR_Final.zip",
           "2025": "2025/20250624Primary%20Election/rcv/2025_Primary_CVR_2025-07-17.zip"}
EXPORT = "https://www.vote.nyc/sites/default/files/pdf/election_results/{path}"
# A ranking's column: "DEM Mayor Choice 2 of 5 Citywide (224306)", the choice's digit and the contest's code.
CHOICE = re.compile(r"Choice (\d+) of \d+ .*\(\d(\d{5})\)\s*$")
# contest: the Board's page, named for the contest's code in its ballot export.
CONTESTS = {
    "2021-dem-mayor": "024306_1.html",
    "2021-dem-public-advocate": "024408_1.html",
    "2021-dem-comptroller": "024314_1.html",
    "2021-dem-borough-president-bronx": "024339_1.html",
    "2021-dem-borough-president-brooklyn": "024336_1.html",
    "2021-dem-borough-president-manhattan": "024307_1.html",
    "2021-dem-borough-president-queens": "024415_1.html",
    "2021-dem-borough-president-richmond": "024458_1.html",
    "2021-dem-council-1": "024340_1.html",
    "2021-dem-council-2": "024463_1.html",
    "2021-dem-council-3": "024321_1.html",
    "2021-dem-council-5": "024332_1.html",
    "2021-dem-council-6": "024361_1.html",
    "2021-dem-council-7": "024318_1.html",
    "2021-dem-council-8": "024470_1.html",
    "2021-dem-council-9": "24347_1.html",
    "2021-dem-council-10": "024370_1.html",
    "2021-dem-council-11": "024329_1.html",
    "2021-dem-council-12": "024328_1.html",
    "2021-dem-council-13": "024341_1.html",
    "2021-dem-council-14": "024327_1.html",
    "2021-dem-council-15": "024357_1.html",
    "2021-dem-council-16": "024330_1.html",
    "2021-dem-council-17": "024481_1.html",
    "2021-dem-council-18": "024355_1.html",
    "2021-dem-council-19": "024350_1.html",
    "2021-dem-council-20": "024346_1.html",
    "2021-dem-council-21": "024358_1.html",
    "2021-dem-council-22": "024349_1.html",
    "2021-dem-council-23": "024351_1.html",
    "2021-dem-council-24": "024399_1.html",
    "2021-dem-council-25": "024310_1.html",
    "2021-dem-council-26": "024311_1.html",
    "2021-dem-council-27": "024312_1.html",
    "2021-dem-council-28": "024406_1.html",
    "2021-dem-council-29": "024324_1.html",
    "2021-dem-council-30": "024414_1.html",
    "2021-dem-council-31": "024462_1.html",
    "2021-dem-council-32": "024320_1.html",
    "2021-dem-council-33": "024309_1.html",
    "2021-dem-council-34": "024319_1.html",
    "2021-dem-council-35": "024335_1.html",
    "2021-dem-council-36": "024379_1.html",
    "2021-dem-council-37": "024389_1.html",
    "2021-dem-council-38": "024352_1.html",
    "2021-dem-council-39": "024353_1.html",
    "2021-dem-council-40": "024348_1.html",
    "2021-dem-council-41": "024424_1.html",
    "2021-dem-council-42": "024323_1.html",
    "2021-dem-council-45": "024375_1.html",
    "2021-dem-council-46": "024338_1.html",
    "2021-dem-council-47": "024394_1.html",
    "2021-dem-council-48": "024337_1.html",
    "2021-dem-council-49": "024326_1.html",
    "2021-con-council-19": "024500_1.html",
    "2021-rep-mayor": "024425_1.html",
    "2021-rep-borough-president-richmond": "024496_1.html",
    "2021-rep-council-15": "024524_1.html",
    "2021-rep-council-19": "024410_1.html",
    "2021-rep-council-23": "024546_1.html",
    "2021-rep-council-24": "024554_1.html",
    "2021-rep-council-32": "024550_1.html",
    "2021-rep-council-50": "24367_1.html",
    "2023-dem-council-1": "025715_1.html",
    "2023-dem-council-2": "025980_1.html",
    "2023-dem-council-9": "025756_1.html",
    "2023-dem-council-10": "026031_1.html",
    "2023-dem-council-12": "025797_1.html",
    "2023-dem-council-13": "025908_1.html",
    "2023-dem-council-14": "025919_1.html",
    "2023-dem-council-19": "025767_1.html",
    "2023-dem-council-22": "026015_1.html",
    "2023-dem-council-23": "026013_1.html",
    "2023-dem-council-25": "025985_1.html",
    "2023-dem-council-26": "025983_1.html",
    "2023-dem-council-29": "025804_1.html",
    "2023-dem-council-34": "026026_1.html",
    "2023-dem-council-41": "025822_1.html",
    "2023-dem-council-42": "025760_1.html",
    "2023-dem-council-43": "025755_1.html",
    "2023-con-council-13": "025929_1.html",
    "2023-rep-council-13": "025927_1.html",
    "2023-rep-council-20": "025934_1.html",
    "2023-rep-council-43": "026119_1.html",
    "2023-rep-council-44": "025933_1.html",
    "2023-rep-council-47": "025913_1.html",
    "2023-rep-council-48": "026030_1.html",
    "2025-dem-mayor": "026916_1.html",
    "2025-dem-public-advocate": "026915_1.html",
    "2025-dem-comptroller": "026914_1.html",
    "2025-dem-borough-president-bronx": "026929_1.html",
    "2025-dem-borough-president-brooklyn": "026913_1.html",
    "2025-dem-borough-president-manhattan": "026918_1.html",
    "2025-dem-council-1": "026942_1.html",
    "2025-dem-council-2": "026922_1.html",
    "2025-dem-council-3": "026955_1.html",
    "2025-dem-council-4": "026921_1.html",
    "2025-dem-council-5": "027025_1.html",
    "2025-dem-council-7": "026947_1.html",
    "2025-dem-council-8": "026927_1.html",
    "2025-dem-council-10": "027101_1.html",
    "2025-dem-council-11": "026989_1.html",
    "2025-dem-council-12": "026928_1.html",
    "2025-dem-council-13": "026988_1.html",
    "2025-dem-council-14": "026933_1.html",
    "2025-dem-council-16": "027058_1.html",
    "2025-dem-council-17": "026938_1.html",
    "2025-dem-council-19": "026957_1.html",
    "2025-dem-council-21": "026932_1.html",
    "2025-dem-council-25": "027075_1.html",
    "2025-dem-council-28": "026939_1.html",
    "2025-dem-council-30": "026943_1.html",
    "2025-dem-council-33": "027078_1.html",
    "2025-dem-council-35": "026919_1.html",
    "2025-dem-council-36": "026924_1.html",
    "2025-dem-council-38": "027007_1.html",
    "2025-dem-council-39": "026925_1.html",
    "2025-dem-council-41": "026952_1.html",
    "2025-dem-council-46": "026936_1.html",
    "2025-dem-council-47": "026999_1.html",
    "2025-dem-council-49": "026956_1.html",
    "2025-rep-comptroller": "026935_1.html",
    "2025-rep-council-48": "027131_1.html",
    "2025-rep-council-51": "026937_1.html",
}
NUMBER = re.compile(r"^[\d,]+$")


def key(name):
    """A name's letters, initials left out, so the record's spelling and the page's meet ("Selvena N.
    Brooks-Powers" and "Selvena Brooks-Powers"); write-ins are one candidate."""
    return name_key(name, initials=False, one_write_in=True)


def official_rounds(path):
    """{candidate: [votes in round 1, 2, ...]}: under "Votes in this round", a candidate's row is the name, the
    first round's votes and percentage, then each later round's transfer, votes and percentage, until the
    candidate is eliminated ("-XXX")."""
    totals, reading = {}, False
    for cells in table_rows(path):
        if cells and cells[0] == "Votes in this round":
            reading = True
        elif reading and (not cells or cells[0] == "Inactive ballots" or cells[0].startswith("*")):
            break
        elif reading and len(cells) > 1:
            votes = [cells[1]] + cells[4::3]
            totals[cells[0]] = [int(v.replace(",", "")) for v in votes if NUMBER.match(v)]
    return totals


def first_choices(cvr):
    """The record's first choices per candidate (by key), read as the converter reads the ballots, but with
    write-ins as one candidate."""
    reader = csv.DictReader(open(cvr, newline="", encoding="utf-8-sig"))
    columns = sorted((c for c in reader.fieldnames if c.startswith("rank")), key=lambda c: int(c[4:]))
    counts = collections.Counter()
    for row in reader:
        for column in columns:
            mark = row[column].strip()
            if mark.lower() == "overvote":
                break
            if mark and mark.lower() not in ("skipped", "undervote"):
                counts[key("Write-in" if mark.lower() in ("writein", "write-in", "write in") else mark)] += 1
                break
    return counts


def code(contest):
    """The contest's code in the Board's export, from its page's name ("024306_1.html": 24306)."""
    return CONTESTS[contest].split("_")[0].zfill(6)[1:]


def extract(data, year):
    """Writes DATA/<contest>.csv for each of the year's contests from the Board's export, in one pass: the ballots
    that rank anyone in it, with the precinct and the candidates' names (the export has their ids)."""
    folder = os.path.join(data, year)
    os.makedirs(folder, exist_ok=True)
    archive = fetch(os.path.join(folder, os.path.basename(EXPORTS[year])), EXPORT.format(path=EXPORTS[year]))
    books = []
    with zipfile.ZipFile(archive) as z:
        for name in z.namelist():
            # The workbooks, at the top or in one folder (2021's), unpacked into FOLDER.
            if name.endswith(".xlsx"):
                books.append(os.path.basename(name))
                if not os.path.exists(os.path.join(folder, books[-1])):
                    with z.open(name) as packed, open(os.path.join(folder, books[-1]), "wb") as out:
                        shutil.copyfileobj(packed, out)
    names = {}
    for book in books:
        if "CandidacyID" in book:
            for row in csv.reader(open(sheets(os.path.join(folder, book))[0], encoding="utf-8")):
                names[row[0].strip()] = row[1].strip()
    wanted = {code(contest): contest for contest in CONTESTS if contest.startswith(year)}
    files, writers, widths = {}, {}, {}
    for book in sorted(books):
        if "CandidacyID" in book:
            continue
        for sheet in sheets(os.path.join(folder, book)):
            reader = csv.reader(open(sheet, encoding="utf-8"))
            columns = {}
            for i, header in enumerate(next(reader)):
                match = CHOICE.search(header)
                if match and match.group(2) in wanted:
                    columns.setdefault(wanted[match.group(2)], []).append((int(match.group(1)), i))
            columns = {contest: [i for _, i in sorted(cells)] for contest, cells in columns.items()}
            for contest, cells in columns.items():
                if contest not in writers:
                    files[contest] = open(os.path.join(data, contest + ".csv.part"), "w", newline="", encoding="utf-8")
                    writers[contest], widths[contest] = csv.writer(files[contest]), len(cells)
                    writers[contest].writerow(["Precinct"] + [f"rank{n}" for n in range(1, len(cells) + 1)])
            for row in reader:
                for contest, cells in columns.items():
                    marks = [row[i].strip() for i in cells]
                    if any(mark and mark.lower() != "undervote" for mark in marks):
                        marks += [""] * (widths[contest] - len(marks))
                        writers[contest].writerow([row[1]] + [names.get(mark, mark) for mark in marks])
    for contest, out in files.items():
        out.close()
        os.replace(out.name, os.path.join(data, contest + ".csv"))


def check(data, contest, jar):
    page = CONTESTS[contest]
    year = contest[:4]
    local = fetch(os.path.join(data, contest + ".html"), ROUNDS.format(year=year, date=DATES[year], page=page))
    # A row with no figures (2021's District 16, a candidate removed from the count) has no votes.
    totals = {key(name): votes for name, votes in official_rounds(local).items() if votes}
    blt = os.path.join(data, contest + ".blt")
    cvr = os.path.join(data, contest + ".csv")
    if not os.path.exists(cvr):
        extract(data, year)
    if not os.path.exists(cvr):
        return False, f"{contest}: no columns for contest {code(contest)} in the Board's {year} export"
    if not os.path.exists(blt):
        subprocess.run([sys.executable, CONVERTER, cvr, blt, "--title", contest], check=True)
    ours, error = count(jar, blt)
    if error:
        return False, f"{contest}: count failed: {error}"
    # Round 1, with the write-ins, against the record's first choices; the rest against this count's rounds.
    first = first_choices(cvr)
    gap = max(abs(first[name] - votes[0]) for name, votes in totals.items())
    exact, gaps = match_rounds(totals, ours, key, first=1)
    exact, gaps = exact + (gap == 0), [gap] + gaps
    same = [key(name) for name in ours["electedInOrder"]] == [winner(totals)]
    return None not in gaps and same, summary(contest, exact, gaps, same, ours["electedInOrder"])


if __name__ == "__main__":
    main(__doc__.split("\n\n")[0], CONTESTS, check)

"""Check the irv ruleset against New York City's official ranked-choice counts of its 2021, 2023 and 2025 primaries.

usage: check.py DATA_DIR [--jar PATH] [CONTEST ...]

CONTEST is one of CONTESTS below (all, by default): the 123 primaries, from mayor to city council, that
both FairVote's collection of single-winner RCV cast vote records (https://doi.org/10.7910/DVN/AMK8PJ)
and the Board of Elections' certified round-by-round pages ("Official Ranked Choice Rounds", under
https://www.vote.nyc/sites/default/files/pdf/election_results/) have. Each record and page is fetched
into DATA_DIR, the record converted with ../cvr_to_blt.py (an overvote ends the ballot; a skipped
ranking or a write-in is passed over; a candidate ranked again counts once) and counted with `irv`.

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
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, ".."))
from rcvcheck import DATAVERSE, count, fetch, main, match_rounds, name_key, summary, table_rows, winner  # noqa: E402

CONVERTER = os.path.join(HERE, "..", "cvr_to_blt.py")
ROUNDS = "https://www.vote.nyc/sites/default/files/pdf/election_results/{year}/{date}Primary%20Election/rcv/{page}"
DATES = {"2021": "20210622", "2023": "20230627", "2025": "20250624"}
# contest: (Dataverse file id, the Board's page). Left out: the 2025 Republican comptroller primary, whose record
# in the collection holds 5,402 ballots, most of them from Queens, of about 30,400 counted citywide.
CONTESTS = {
    "2021-dem-mayor": (11593393, "024306_1.html"),
    "2021-dem-public-advocate": (11593384, "024408_1.html"),
    "2021-dem-comptroller": (11593360, "024314_1.html"),
    "2021-dem-borough-president-bronx": (11593412, "024339_1.html"),
    "2021-dem-borough-president-brooklyn": (11593359, "024336_1.html"),
    "2021-dem-borough-president-manhattan": (11593403, "024307_1.html"),
    "2021-dem-borough-president-queens": (11593397, "024415_1.html"),
    "2021-dem-borough-president-richmond": (11593415, "024458_1.html"),
    "2021-dem-council-1": (11593361, "024340_1.html"),
    "2021-dem-council-2": (11593396, "024463_1.html"),
    "2021-dem-council-3": (11593381, "024321_1.html"),
    "2021-dem-council-5": (11593402, "024332_1.html"),
    "2021-dem-council-6": (11593377, "024361_1.html"),
    "2021-dem-council-7": (11593395, "024318_1.html"),
    "2021-dem-council-8": (11593364, "024470_1.html"),
    "2021-dem-council-9": (11593434, "24347_1.html"),
    "2021-dem-council-10": (11593417, "024370_1.html"),
    "2021-dem-council-11": (11593390, "024329_1.html"),
    "2021-dem-council-12": (11593408, "024328_1.html"),
    "2021-dem-council-13": (11593405, "024341_1.html"),
    "2021-dem-council-14": (11593442, "024327_1.html"),
    "2021-dem-council-15": (11593388, "024357_1.html"),
    "2021-dem-council-16": (11593407, "024330_1.html"),
    "2021-dem-council-17": (11593368, "024481_1.html"),
    "2021-dem-council-18": (11593366, "024355_1.html"),
    "2021-dem-council-19": (11593391, "024350_1.html"),
    "2021-dem-council-20": (11593431, "024346_1.html"),
    "2021-dem-council-21": (11593411, "024358_1.html"),
    "2021-dem-council-22": (11593440, "024349_1.html"),
    "2021-dem-council-23": (11593430, "024351_1.html"),
    "2021-dem-council-24": (11593383, "024399_1.html"),
    "2021-dem-council-25": (11593423, "024310_1.html"),
    "2021-dem-council-26": (11593400, "024311_1.html"),
    "2021-dem-council-27": (11593427, "024312_1.html"),
    "2021-dem-council-28": (11593441, "024406_1.html"),
    "2021-dem-council-29": (11593367, "024324_1.html"),
    "2021-dem-council-30": (11593433, "024414_1.html"),
    "2021-dem-council-31": (11593439, "024462_1.html"),
    "2021-dem-council-32": (11593382, "024320_1.html"),
    "2021-dem-council-33": (11593438, "024309_1.html"),
    "2021-dem-council-34": (11593409, "024319_1.html"),
    "2021-dem-council-35": (11593375, "024335_1.html"),
    "2021-dem-council-36": (11593419, "024379_1.html"),
    "2021-dem-council-37": (11593406, "024389_1.html"),
    "2021-dem-council-38": (11593387, "024352_1.html"),
    "2021-dem-council-39": (11593443, "024353_1.html"),
    "2021-dem-council-40": (11593380, "024348_1.html"),
    "2021-dem-council-41": (11593394, "024424_1.html"),
    "2021-dem-council-42": (11593376, "024323_1.html"),
    "2021-dem-council-45": (11593429, "024375_1.html"),
    "2021-dem-council-46": (11593420, "024338_1.html"),
    "2021-dem-council-47": (11593358, "024394_1.html"),
    "2021-dem-council-48": (11593392, "024337_1.html"),
    "2021-dem-council-49": (11593357, "024326_1.html"),
    "2021-con-council-19": (11593365, "024500_1.html"),
    "2021-rep-mayor": (11593372, "024425_1.html"),
    "2021-rep-borough-president-richmond": (11593426, "024496_1.html"),
    "2021-rep-council-15": (11593428, "024524_1.html"),
    "2021-rep-council-19": (11593424, "024410_1.html"),
    "2021-rep-council-23": (11593369, "024546_1.html"),
    "2021-rep-council-24": (11593425, "024554_1.html"),
    "2021-rep-council-32": (11593374, "024550_1.html"),
    "2021-rep-council-50": (11593401, "24367_1.html"),
    "2023-dem-council-1": (11593379, "025715_1.html"),
    "2023-dem-council-2": (11593421, "025980_1.html"),
    "2023-dem-council-9": (11593413, "025756_1.html"),
    "2023-dem-council-10": (11593410, "026031_1.html"),
    "2023-dem-council-12": (11593362, "025797_1.html"),
    "2023-dem-council-13": (11593437, "025908_1.html"),
    "2023-dem-council-14": (11593432, "025919_1.html"),
    "2023-dem-council-19": (11593363, "025767_1.html"),
    "2023-dem-council-22": (11593386, "026015_1.html"),
    "2023-dem-council-23": (11593398, "026013_1.html"),
    "2023-dem-council-25": (11593422, "025985_1.html"),
    "2023-dem-council-26": (11593371, "025983_1.html"),
    "2023-dem-council-29": (11593416, "025804_1.html"),
    "2023-dem-council-34": (11593370, "026026_1.html"),
    "2023-dem-council-41": (11593414, "025822_1.html"),
    "2023-dem-council-42": (11593378, "025760_1.html"),
    "2023-dem-council-43": (11593436, "025755_1.html"),
    "2023-con-council-13": (11593373, "025929_1.html"),
    "2023-rep-council-13": (11593404, "025927_1.html"),
    "2023-rep-council-20": (11593435, "025934_1.html"),
    "2023-rep-council-43": (11593399, "026119_1.html"),
    "2023-rep-council-44": (11593385, "025933_1.html"),
    "2023-rep-council-47": (11593418, "025913_1.html"),
    "2023-rep-council-48": (11593389, "026030_1.html"),
    "2025-dem-mayor": (11845395, "026916_1.html"),
    "2025-dem-public-advocate": (11845405, "026915_1.html"),
    "2025-dem-comptroller": (11845413, "026914_1.html"),
    "2025-dem-borough-president-bronx": (11845411, "026929_1.html"),
    "2025-dem-borough-president-brooklyn": (11845401, "026913_1.html"),
    "2025-dem-borough-president-manhattan": (11845417, "026918_1.html"),
    "2025-dem-council-1": (11845404, "026942_1.html"),
    "2025-dem-council-2": (11845394, "026922_1.html"),
    "2025-dem-council-3": (11845427, "026955_1.html"),
    "2025-dem-council-4": (11845418, "026921_1.html"),
    "2025-dem-council-5": (11845392, "027025_1.html"),
    "2025-dem-council-7": (11845407, "026947_1.html"),
    "2025-dem-council-8": (11845398, "026927_1.html"),
    "2025-dem-council-10": (11845393, "027101_1.html"),
    "2025-dem-council-11": (11845415, "026989_1.html"),
    "2025-dem-council-12": (11845408, "026928_1.html"),
    "2025-dem-council-13": (11845416, "026988_1.html"),
    "2025-dem-council-14": (11845429, "026933_1.html"),
    "2025-dem-council-16": (11845400, "027058_1.html"),
    "2025-dem-council-17": (11845402, "026938_1.html"),
    "2025-dem-council-19": (11845426, "026957_1.html"),
    "2025-dem-council-21": (11845422, "026932_1.html"),
    "2025-dem-council-25": (11845409, "027075_1.html"),
    "2025-dem-council-28": (11845397, "026939_1.html"),
    "2025-dem-council-30": (11845423, "026943_1.html"),
    "2025-dem-council-33": (11845410, "027078_1.html"),
    "2025-dem-council-35": (11845419, "026919_1.html"),
    "2025-dem-council-36": (11845425, "026924_1.html"),
    "2025-dem-council-38": (11845414, "027007_1.html"),
    "2025-dem-council-39": (11845396, "026925_1.html"),
    "2025-dem-council-41": (11845420, "026952_1.html"),
    "2025-dem-council-46": (11845428, "026936_1.html"),
    "2025-dem-council-47": (11845424, "026999_1.html"),
    "2025-dem-council-49": (11845399, "026956_1.html"),
    "2025-rep-council-48": (11845403, "027131_1.html"),
    "2025-rep-council-51": (11845406, "026937_1.html"),
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
            if mark and mark.lower() != "skipped":
                counts[key("Write-in" if mark.lower() in ("writein", "write-in", "write in") else mark)] += 1
                break
    return counts


def check(data, contest, jar):
    file_id, page = CONTESTS[contest]
    year = contest[:4]
    local = fetch(os.path.join(data, contest + ".html"), ROUNDS.format(year=year, date=DATES[year], page=page))
    # A row with no figures (2021's District 16, a candidate removed from the count) has no votes.
    totals = {key(name): votes for name, votes in official_rounds(local).items() if votes}
    blt = os.path.join(data, contest + ".blt")
    cvr = fetch(os.path.join(data, contest + ".csv"), DATAVERSE.format(id=file_id))
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

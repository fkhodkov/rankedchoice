"""Convert an NSW Electoral Commission councillor count to BLT.

usage: nsw_to_blt.py PREFERENCES.zip DOP.xlsx OUT.blt

PREFERENCES.zip is the count's finalpreferencedatafile.zip: a tab-separated row per mark on
each ballot paper (a paper is its vote type, venue and VCBallotPaperID), with the
Commission's reading of the mark as PreferenceNumber and its decision on the paper's
formality. DOP.xlsx is the count's dopfulldetails.xlsx, whose column headings give the
candidates in ballot order with their groups ("Group A - SURNAME Given", "Ungrouped - ...", or
just the name where there are no groups) and whose header gives the vacancies. Needs openpyxl.

A candidate the official first count gives no ballot papers, though the papers rank them first,
is marked withdrawn: a councillor candidate elected mayor leaves the councillor count, and their
papers go to their next preference.

Informal papers are left out. Below the line (BTL), a paper ranks candidates; above it (SATL,
one group, or RATL, several), groups, each standing for its candidates in ballot order. Marks
the Commission gave no preference number are ignored, and a ranking stops at the first missing
or repeated number. Identical rankings are merged.
"""
import collections
import csv
import io
import pathlib
import re
import sys
import zipfile

import openpyxl


def sheet_of(dop_path):
    """The distribution of preferences' worksheet."""
    return openpyxl.load_workbook(dop_path, read_only=True).worksheets[0]


def candidate_columns(header):
    """[(column, group, name)] for the candidates' columns, in ballot order."""
    first, last = header.index("Votes To Be Transferred") + 1, header.index("Exhausted")
    columns = []
    for i in range(first, last):
        if header[i]:
            match = re.match(r"^(?:Group (\S+) - |Ungrouped - )?(.+)$", str(header[i]).strip())
            columns.append((i, match.group(1) or "UG", match.group(2).strip()))
    return columns


def layout(dop_path):
    """(title, seats, [(group, name)] in ballot order, {name: first-count papers})."""
    sheet = sheet_of(dop_path)
    rows = [list(row) for row in sheet.iter_rows(values_only=True, max_row=12)]
    title = next(cell for cell in rows[0] if cell)
    seats = next(row[row.index("Vacancies") + 1] for row in rows if "Vacancies" in row)
    header = next(row for row in rows if "Count" in row)
    columns = candidate_columns(header)
    first_count = next((row for row in rows if str(row[1]) == "1" and row[3] == "Progressive Total"), None)
    if first_count is None:
        raise ValueError("the distribution of preferences has no counts")
    papers = {name: first_count[i] or 0 for i, _, name in columns}
    return title, int(seats), [(group, name) for _, group, name in columns], papers


def papers(zip_path):
    """Each formal paper's (type, [(preference number, group, candidate name)])."""
    with zipfile.ZipFile(zip_path) as z:
        name = next(n for n in z.namelist() if n.lower().endswith(".txt"))
        reader = csv.DictReader(io.TextIOWrapper(z.open(name), encoding="utf-8-sig"), delimiter="\t")
        grouped = collections.OrderedDict()
        for row in reader:
            if row["Formality"] != "Formal":
                continue
            key = (row["VoteType"], row["Venue/VoteSubType"], row["VCBallotPaperID"])
            entry = grouped.setdefault(key, [row["Type"], []])
            if row["PreferenceNumber"].strip():
                entry[1].append((int(row["PreferenceNumber"]), row["GroupCode"].strip(), row["CandidateName"].strip()))
    return grouped.values()


def in_sequence(marks):
    """Marks in preference order, to the first missing or repeated number."""
    by_number = collections.defaultdict(list)
    for mark in marks:
        by_number[mark[0]].append(mark)
    order, n = [], 1
    while len(by_number.get(n, [])) == 1:
        order.append(by_number[n][0])
        n += 1
    return order


def convert(zip_path, dop_path):
    title, seats, candidates, first_count_papers = layout(dop_path)
    index = {name: i for i, (_, name) in enumerate(candidates)}
    members = collections.defaultdict(list)
    for i, (group, _) in enumerate(candidates):
        members[group].append(i)
    counts = collections.Counter()
    for kind, marks in papers(zip_path):
        marks = in_sequence(marks)
        if kind == "BTL":
            ranking = [index[name] for _, _, name in marks]
        else:
            ranking = [i for _, group, _ in marks for i in members[group]]
        if ranking:
            counts[tuple(ranking)] += 1
    first = collections.Counter(ranking[0] for ranking in counts.elements())
    withdrawn = [i + 1 for i, (_, name) in enumerate(candidates) if first_count_papers[name] == 0 and first[i]]
    lines = [f"{len(candidates)} {seats}"] + ([" ".join(f"-{i}" for i in withdrawn)] if withdrawn else [])
    lines += [f"{n} {' '.join(str(c + 1) for c in ranking)} 0"
              for ranking, n in sorted(counts.items(), key=lambda item: (-item[1], item[0]))]
    lines += ["0"] + [f'"{name}"' for _, name in candidates] + [f'"{title}"']
    return "\n".join(lines) + "\n"


if __name__ == "__main__":
    pathlib.Path(sys.argv[3]).write_text(convert(sys.argv[1], sys.argv[2]))

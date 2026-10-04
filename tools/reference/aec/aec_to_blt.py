"""Convert the AEC's Senate formal preference data to BLT.

usage: aec_to_blt.py FORMALPREFERENCES.zip OUT.blt [--seats N]

The AEC publishes, for every Senate election, each state's formal ballot papers
(aec-senate-formalpreferences-<event>-<state>.zip): a row per paper, with a column for each
group's box above the line, then one for each candidate below it. A paper's preferences are:

- below the line, if it numbers at least 6 candidates in sequence (1, 2, 3, ..., to the first
  missing or repeated number); otherwise
- above the line: the groups in sequence (to the first missing or repeated number), each group's
  candidates in ballot order.

A tick or cross ("*" or "/") counts as a 1. Identical rankings are merged. Seats default to 6
(a half-Senate election in a state; the territories elect 2).
"""
import argparse
import collections
import csv
import io
import re
import zipfile


def sequence(marks):
    """{position: number} -> positions in preference order, to the first missing or repeated number."""
    by_number = collections.defaultdict(list)
    for position, mark in marks.items():
        mark = mark.strip()
        if mark in ("*", "/"):
            mark = "1"
        if mark.isdigit():
            by_number[int(mark)].append(position)
    order = []
    n = 1
    while len(by_number.get(n, [])) == 1:
        order.append(by_number[n][0])
        n += 1
    return order


def main():
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("zip")
    parser.add_argument("out")
    parser.add_argument("--seats", type=int)
    args = parser.parse_args()

    with zipfile.ZipFile(args.zip) as z:
        name = next(n for n in z.namelist() if n.endswith(".csv"))
        reader = csv.reader(io.TextIOWrapper(z.open(name), encoding="utf-8-sig"))
        header = next(reader)
        rows = [row for row in reader if row and not row[0].startswith("-")]

    # After the fixed columns: one per group above the line, then one per candidate below it.
    columns = [(i, h) for i, h in enumerate(header) if re.match(r"^[A-Z]{1,2}:", h)]
    first_candidate = next(k for k in range(1, len(columns))
                           if columns[k][1].split(":")[0] <= columns[k - 1][1].split(":")[0]
                           and len(columns[k][1].split(":")[0]) <= len(columns[k - 1][1].split(":")[0]))
    groups = columns[:first_candidate]
    candidates = columns[first_candidate:]
    members = collections.defaultdict(list)
    for index, (column, title) in enumerate(candidates):
        members[title.split(":")[0]].append(index)

    counts = collections.Counter()
    for row in rows:
        below = sequence({k: row[c] for k, (c, _) in enumerate(candidates) if c < len(row)})
        if len(below) >= 6:
            ranking = below
        else:
            above = sequence({k: row[c] for k, (c, _) in enumerate(groups) if c < len(row)})
            ranking = [m for g in above for m in members[groups[g][1].split(":")[0]]]
        if ranking:
            counts[tuple(ranking)] += 1

    state = rows[0][0] if rows else "?"
    seats = args.seats or (2 if state in ("ACT", "NT") else 6)
    with open(args.out, "w") as out:
        out.write(f"{len(candidates)} {seats}\n")
        for ranking, count in sorted(counts.items(), key=lambda item: (-item[1], item[0])):
            out.write(f"{count} {' '.join(str(c + 1) for c in ranking)} 0\n")
        out.write("0\n")
        for _, title in candidates:
            out.write(f'"{title.split(":", 1)[1]}"\n')
        out.write(f'"{name[:-4]}"\n')


if __name__ == "__main__":
    main()

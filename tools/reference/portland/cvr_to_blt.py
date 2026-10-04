"""Convert a Multnomah County cast vote record for a Portland Council contest to BLT.

usage: cvr_to_blt.py CVR.csv OUT.blt [--seats N]

The County's cast vote record has a row per ballot and a column per candidate per ranking
("Choice_..:City of Portland, Councilor, District 1:<ranking>:Number of Winners 3:<candidate>:NON"),
1 where the voter marked it. Every row counts, as the County's first round shows (those with
Status -1, flagged as remade, included). Read as the County reads them, which reproduces its
first round exactly: a ranking with more than one mark is an overvote and skipped (City Code
2.08.030 A.4), the numbered write-in lines ("Write-in-120") counting as marks; a write-in line
marked on its own is skipped too, only "Uncertified Write In" being counted as a candidate; an
empty ranking is skipped (A.3); and a candidate ranked again counts only where first ranked.
Ballots with no ranking left are left out; identical rankings are merged.
"""
import argparse
import collections
import csv
import re

WRITE_IN_LINE = re.compile(r"(?i)write-in-\d+")


def main():
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("cvr")
    parser.add_argument("out")
    parser.add_argument("--seats", type=int)
    args = parser.parse_args()

    reader = csv.reader(open(args.cvr, newline="", encoding="utf-8-sig"))
    header = next(reader)
    columns, candidates, title, seats = [], [], None, args.seats
    for i, heading in enumerate(header):
        if heading.startswith("Choice_"):
            parts = heading.split(":")
            name = parts[4]
            if WRITE_IN_LINE.fullmatch(name):
                columns.append((i, int(parts[2]), None))
                continue
            if name not in candidates:
                candidates.append(name)
            columns.append((i, int(parts[2]), candidates.index(name)))
            title = parts[1]
            winners = re.search(r"Number of Winners (\d+)", parts[3])
            seats = seats or (int(winners.group(1)) if winners else None)
    rankings = max(rank for _, rank, _ in columns)

    counts = collections.Counter()
    for row in reader:
        marks = [[] for _ in range(rankings)]
        for i, rank, candidate in columns:
            if row[i] == "1":
                marks[rank - 1].append(candidate)
        ranking, seen = [], set()
        for group in marks:
            if len(group) == 1 and group[0] is not None and group[0] not in seen:
                ranking.append(group[0])
                seen.add(group[0])
        if ranking:
            counts[tuple(ranking)] += 1

    with open(args.out, "w") as out:
        out.write(f"{len(candidates)} {seats}\n")
        for ranking, n in sorted(counts.items(), key=lambda item: (-item[1], item[0])):
            out.write(f"{n} " + " ".join(str(c + 1) for c in ranking) + " 0\n")
        out.write("0\n")
        for name in candidates:
            out.write(f'"{name}"\n')
        out.write(f'"{title}"\n')


if __name__ == "__main__":
    main()

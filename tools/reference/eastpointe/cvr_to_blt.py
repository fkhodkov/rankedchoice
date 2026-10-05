"""Convert an Eastpointe, Michigan cast vote record from FairVote's proportional RCV collection to BLT.

usage: cvr_to_blt.py CVR.csv OUT.blt --seats N

FairVote's collection (https://doi.org/10.7910/DVN/STVUET) has a row per ballot, with columns
rank1, rank2, ...: a candidate, "skipped", "overvote" or "writein". Read as Macomb County's RCTab
count reads Eastpointe's ballots, which reproduces its first rounds exactly: a skipped ranking or
a write-in is passed over, but two in a row end the ballot; an overvote ends it, and so does a
candidate ranked again (RCTab's "exhaust on duplicate candidate"). Ballots ranking no one are left out; identical
rankings are merged.
"""
import argparse
import collections
import csv

PASSED_OVER = {"skipped", "writein"}


def main():
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("cvr")
    parser.add_argument("out")
    parser.add_argument("--seats", type=int, required=True)
    args = parser.parse_args()

    reader = csv.DictReader(open(args.cvr, newline="", encoding="utf-8-sig"))
    columns = sorted((c for c in reader.fieldnames if c.startswith("rank")), key=lambda c: int(c[4:]))
    candidates, counts = [], collections.Counter()
    for row in reader:
        ranking, passed = [], 0
        for column in columns:
            mark = row[column].strip()
            if mark.lower() in PASSED_OVER or not mark:
                passed += 1
                if passed >= 2:
                    break
                continue
            passed = 0
            if mark.lower() == "overvote":
                break
            if mark not in candidates:
                candidates.append(mark)
            if candidates.index(mark) in ranking:
                break
            ranking.append(candidates.index(mark))
        if ranking:
            counts[tuple(ranking)] += 1

    with open(args.out, "w") as out:
        out.write(f"{len(candidates)} {args.seats}\n")
        for ranking, n in sorted(counts.items(), key=lambda item: (-item[1], item[0])):
            out.write(f"{n} " + " ".join(str(c + 1) for c in ranking) + " 0\n")
        out.write("0\n")
        for name in candidates:
            out.write(f'"{name}"\n')
        out.write('"Eastpointe"\n')


if __name__ == "__main__":
    main()

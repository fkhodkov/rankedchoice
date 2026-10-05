"""Convert an Alaska cast vote record from FairVote's single-winner RCV collection to BLT.

usage: cvr_to_blt.py CVR.csv OUT.blt

FairVote's collection (https://doi.org/10.7910/DVN/AMK8PJ) has a row per ballot, with columns
rank1, rank2, ...: a candidate, "skipped", "overvote" or "Write-in". Read as the Division of
Elections' Dominion count reads them, which reproduces its first rounds: an unresolved write-in
is excluded, passed over like an eliminated candidate; two skipped rankings in a row end the
ballot, those before the first choice included ("Exhausted on Two or More Ranks Skipped"); an
overvote ends it ("Skip overvoted rankings: False"); a candidate ranked again counts only where
first ranked. Ballots ranking no one are left out; identical rankings are merged.
"""
import argparse
import collections
import csv


def main():
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("cvr")
    parser.add_argument("out")
    args = parser.parse_args()

    reader = csv.DictReader(open(args.cvr, newline="", encoding="utf-8-sig"))
    columns = sorted((c for c in reader.fieldnames if c.startswith("rank")), key=lambda c: int(c[4:]))
    candidates, counts = [], collections.Counter()
    for row in reader:
        ranking, skipped = [], 0
        for column in columns:
            mark = row[column].strip()
            if mark.lower() in ("write-in", "write in"):
                continue
            if mark.lower() in ("skipped", ""):
                skipped += 1
                if skipped >= 2:
                    break
                continue
            skipped = 0
            if mark.lower() == "overvote":
                break
            if mark not in candidates:
                candidates.append(mark)
            if candidates.index(mark) not in ranking:
                ranking.append(candidates.index(mark))
        if ranking:
            counts[tuple(ranking)] += 1

    with open(args.out, "w") as out:
        out.write(f"{len(candidates)} 1\n")
        for ranking, n in sorted(counts.items(), key=lambda item: (-item[1], item[0])):
            out.write(f"{n} " + " ".join(str(c + 1) for c in ranking) + " 0\n")
        out.write("0\n")
        for name in candidates:
            out.write(f'"{name}"\n')
        out.write('"Alaska"\n')


if __name__ == "__main__":
    main()

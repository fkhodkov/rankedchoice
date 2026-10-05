"""Convert a cast vote record from FairVote's proportional RCV collection to BLT.

usage: cvr_to_blt.py CVR OUT.blt --seats N [--write-in-candidate]

FairVote's collection (https://doi.org/10.7910/DVN/STVUET) has a row per ballot (CSV in the
original files, tab-separated in Dataverse's ingested ones), with columns
rank1, rank2, ...: a candidate, "skipped", "overvote" or a write-in ("Write-in", "Write in",
"writein").
Read as Alameda County's Dominion tabulation reads Albany's ballots, which reproduces its first
rounds: a skipped ranking is passed over, as is a write-in (no write-in candidate qualified); an
overvote ends the ballot there (the County doesn't skip overvoted rankings); a candidate ranked
again counts only where first ranked. Ballots ranking no one are left out; identical rankings
are merged. With --write-in-candidate, a write-in is a vote for a candidate named WRITE-IN instead
(San Francisco's counts before 2015).
"""
import argparse
import collections
import csv

WRITE_IN = {"write-in", "write in", "writein"}


def main():
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("cvr")
    parser.add_argument("out")
    parser.add_argument("--seats", type=int, required=True)
    parser.add_argument("--write-in-candidate", action="store_true")
    args = parser.parse_args()

    source = open(args.cvr, newline="", encoding="utf-8-sig")
    delimiter = "\t" if "\t" in source.readline() else ","    # the collection's originals are CSV
    source.seek(0)
    reader = csv.DictReader(source, delimiter=delimiter)
    columns = sorted((c for c in reader.fieldnames if c.startswith("rank")), key=lambda c: int(c[4:]))
    candidates, counts = [], collections.Counter()
    for row in reader:
        ranking = []
        for column in columns:
            mark = row[column].strip()
            if mark.lower() in WRITE_IN and args.write_in_candidate:
                mark = "WRITE-IN"
            elif not mark or mark.lower() == "skipped" or mark.lower() in WRITE_IN:
                continue
            if mark.lower() == "overvote":
                break
            if mark not in candidates:
                candidates.append(mark)
            if candidates.index(mark) not in ranking:
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
        out.write('"Albany"\n')


if __name__ == "__main__":
    main()

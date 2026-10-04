"""Convert a Minneapolis cast vote record for a multi-seat ranked-choice contest to BLT.

usage: cvr_to_blt.py CVR.csv OUT.blt --seats N [--overvote skip|exhaust]

The City's cast vote record has a row per distinct ballot: precinct, the 1st, 2nd and 3rd
choices (a candidate, "UWI" for an undeclared write-in, "overvote" or "undervote") and a count.
An undervote (a skipped choice) is passed over, and a candidate ranked again counts only where
first ranked; an overvote is passed over too, or, with --overvote exhaust, ends the ballot there.
"UWI" counts as a candidate, "Undeclared Write-ins", as the City's results report it. Ballots
ranking no one are left out; identical rankings are merged.
"""
import argparse
import collections
import csv

WRITE_IN = "Undeclared Write-ins"


def main():
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("cvr")
    parser.add_argument("out")
    parser.add_argument("--seats", type=int, required=True)
    parser.add_argument("--overvote", choices=["skip", "exhaust"], default="skip")
    args = parser.parse_args()

    reader = csv.reader(open(args.cvr, newline="", encoding="utf-8-sig"))
    header = next(reader)
    choices = [i for i, h in enumerate(header) if "choice" in h.lower()]
    count = header.index("Count")
    candidates, counts = [], collections.Counter()
    for row in reader:
        ranking = []
        for i in choices:
            mark = row[i].strip()
            if mark.lower() == "undervote" or not mark:
                continue
            if mark.lower() == "overvote":
                if args.overvote == "exhaust":
                    break
                continue
            name = WRITE_IN if mark == "UWI" else mark
            if name not in candidates:
                candidates.append(name)
            if candidates.index(name) not in ranking:
                ranking.append(candidates.index(name))
        if ranking:
            counts[tuple(ranking)] += int(row[count])

    with open(args.out, "w") as out:
        out.write(f"{len(candidates)} {args.seats}\n")
        for ranking, n in sorted(counts.items(), key=lambda item: (-item[1], item[0])):
            out.write(f"{n} " + " ".join(str(c + 1) for c in ranking) + " 0\n")
        out.write("0\n")
        for name in candidates:
            out.write(f'"{name}"\n')
        out.write('"Minneapolis"\n')


if __name__ == "__main__":
    main()

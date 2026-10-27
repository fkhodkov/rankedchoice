"""Convert a Cambridge cast vote record from FairVote's proportional RCV collection to BLT, in order.

usage: cvr_to_blt.py CVR.csv OUT.blt --seats N

FairVote's collection (https://doi.org/10.7910/DVN/STVUET) has a row per ballot, with columns
rank1, rank2, ...: a candidate, "skipped", "overvote" (some years) or a write-in ("Write-In 1",
"Write-In", "WI0"); from 2019 ballot ids come first. A skipped or overvoted ranking is passed
over (which gives the City's number of valid ballots exactly, where the record marks overvotes), a
candidate ranked again counts only where first ranked, and each
write-in label is a candidate of its own, as the City counts them. Ballots ranking no one are
invalid and left out.

The 2023 School Committee record has codes, not names, in its last two rankings: "C06[10]" (and
"WI01[10]" for a write-in, "C02=WI01=...[11]" for an overvote). Each code stands for one
candidate, found as the one the ballots using it most often haven't ranked yet; a code that
doesn't point clearly at one candidate stops the conversion.

A Cambridge count depends on the order of the ballots, so each ballot is a line of its own, in the
file's order, which is the City's: the 2001-2017 counts this reproduces depend on it.
"""
import argparse
import collections
import csv
import re
import sys

SKIPPED = {"skipped", ""}
CODE = re.compile(r"^(C|WI)(\d+)\[\d+\]$")
WRITE_IN = re.compile(r"(?i)^(write-in|wi\d)")


def code_names(rows, columns, names):
    """Each candidate code's candidate, of `names` (write-ins aside): the one most ballots using it
    haven't ranked before it."""
    votes = collections.defaultdict(collections.Counter)
    for row in rows:
        ranked = set()
        for column in columns:
            mark = row[column]
            match = CODE.match(mark)
            if match and match.group(1) == "C":
                for name in names - ranked:
                    votes[match.group(2)][name] += 1
            ranked.add(mark)
    mapping = {}
    for code, counter in votes.items():
        (best, n), (_, second) = (counter.most_common(2) + [(None, 0)])[:2]
        if n < 2 * second:
            sys.exit(f"code C{code} doesn't point clearly at one candidate: {counter.most_common(3)}")
        mapping[code] = best
    if len(set(mapping.values())) != len(mapping):
        sys.exit(f"two codes point at the same candidate: {mapping}")
    return mapping


def main():
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("cvr")
    parser.add_argument("out")
    parser.add_argument("--seats", type=int, required=True)
    args = parser.parse_args()

    with open(args.cvr, newline="", encoding="utf-8-sig") as source:
        rows = list(csv.DictReader(source))
    columns = sorted((c for c in rows[0] if c.startswith("rank")), key=lambda c: int(c[4:]))
    names = {row[c] for row in rows for c in columns
             if row[c].lower() not in SKIPPED and row[c].lower() != "overvote" and not CODE.match(row[c])
             and "=" not in row[c]}
    codes = code_names(rows, columns, {name for name in names if not WRITE_IN.match(name)})
    candidates, ballots = [], []
    for row in rows:
        ranking = []
        for column in columns:
            mark = row[column].strip()
            if mark.lower() in SKIPPED:
                continue
            if mark.lower() == "overvote" or "=" in mark:
                continue
            match = CODE.match(mark)
            if match:
                mark = codes[match.group(2)] if match.group(1) == "C" else f"Write-In {int(match.group(2))}"
            if mark not in candidates:
                candidates.append(mark)
            if candidates.index(mark) not in ranking:
                ranking.append(candidates.index(mark))
        if ranking:
            ballots.append(ranking)

    with open(args.out, "w") as out:
        out.write(f"{len(candidates)} {args.seats}\n")
        for ranking in ballots:
            out.write("1 " + " ".join(str(c + 1) for c in ranking) + " 0\n")
        out.write("0\n")
        for name in candidates:
            out.write(f'"{name}"\n')
        out.write('"Cambridge"\n')


if __name__ == "__main__":
    main()

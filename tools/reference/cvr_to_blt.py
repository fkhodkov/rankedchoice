"""Convert a ranked-choice cast vote record, a row per ballot or per distinct ballot, to BLT.

usage: cvr_to_blt.py CVR OUT.blt [--seats N] [--title TEXT] [--overvote end|skip] [--skipped pass|two-end]
                     [--repeat once|end|skip] [--write-in pass|skip|candidate] [--write-in-name NAME]

The record has a column per ranking: "rank1", "rank2", ... (FairVote's collections,
https://doi.org/10.7910/DVN/AMK8PJ and https://doi.org/10.7910/DVN/STVUET; CSV in the original
files, tab-separated in Dataverse's ingested ones), or "1st Choice", ... in the order given
(Minneapolis's), and, optionally, a "Count" of identical ballots. A ranking is a candidate, a
skipped ranking ("skipped", "undervote" or empty), an "overvote", or a write-in ("Write-in",
"Write in", "writein", or "UWI" for Minneapolis's undeclared write-ins).

How a jurisdiction reads its ballots is set by the options, one per rule that differs between
the counts checked (VALIDATION.md tabulates them):

  --overvote end      an overvote ends the ballot (the default); skip passes over it
  --skipped pass      a skipped ranking is passed over (the default); two-end passes over one, but
                      two in a row end the ballot, those before the first choice included
  --repeat once       a candidate ranked again counts only where first ranked (the default); end
                      ends the ballot there; skip counts it as a skipped ranking (so with
                      --skipped two-end, a repeat next to a skip, or two repeats, end the ballot),
                      a write-in after a write-in included
  --write-in pass     a write-in is passed over, neither a choice nor a skipped ranking (the
                      default); skip counts it as a skipped ranking; candidate makes it a vote for
                      one candidate, --write-in-name (default "Write-in")

Ballots ranking no one are left out; identical rankings are merged, in order of how many ballots
each has, most first; candidates are numbered in the order they first appear.
"""
import argparse
import collections
import csv

SKIPPED = {"", "skipped", "undervote"}
WRITE_IN = {"write-in", "write in", "writein", "uwi"}


def columns_of(header):
    """The ranking columns, in order: rank1, rank2, ... by number, or those naming a choice as they come."""
    ranks = [i for i, h in enumerate(header) if h.lower().startswith("rank") and h[4:].isdigit()]
    if ranks:
        return sorted(ranks, key=lambda i: int(header[i][4:]))
    return [i for i, h in enumerate(header) if "choice" in h.lower()]


def ranking_of(marks, args, candidates):
    ranking, skipped, write_ins = [], 0, 0
    for mark in marks:
        mark = mark.strip()
        lower = mark.lower()
        write_in = lower in WRITE_IN
        if write_in:
            write_ins += 1
            if args.write_in == "candidate":
                mark, lower = args.write_in_name, args.write_in_name.lower()
        repeated = (mark in candidates and candidates.index(mark) in ranking) or (write_in and write_ins > 1)
        if (lower in SKIPPED or (write_in and args.write_in == "skip")
                or (repeated and args.repeat == "skip")):
            skipped += 1
            if args.skipped == "two-end" and skipped >= 2:
                break
            continue
        if write_in and args.write_in == "pass":
            continue
        skipped = 0
        if lower == "overvote":
            if args.overvote == "end":
                break
            continue
        if mark not in candidates:
            candidates.append(mark)
        if repeated:
            if args.repeat == "end":
                break
            continue
        ranking.append(candidates.index(mark))
    return ranking


def main():
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("cvr")
    parser.add_argument("out")
    parser.add_argument("--seats", type=int, default=1)
    parser.add_argument("--title", default="Contest")
    parser.add_argument("--overvote", choices=["end", "skip"], default="end")
    parser.add_argument("--skipped", choices=["pass", "two-end"], default="pass")
    parser.add_argument("--repeat", choices=["once", "end", "skip"], default="once")
    parser.add_argument("--write-in", choices=["pass", "skip", "candidate"], default="pass")
    parser.add_argument("--write-in-name", default="Write-in")
    args = parser.parse_args()

    with open(args.cvr, newline="", encoding="utf-8-sig") as source:
        delimiter = "\t" if "\t" in source.readline() else ","    # the collections' originals are CSV
        source.seek(0)
        reader = csv.reader(source, delimiter=delimiter)
        header = next(reader)
        columns = columns_of(header)
        count = header.index("Count") if "Count" in header else None
        candidates, counts = [], collections.Counter()
        for row in reader:
            ranking = ranking_of([row[i] for i in columns], args, candidates)
            if ranking:
                counts[tuple(ranking)] += int(row[count]) if count is not None else 1

    with open(args.out, "w") as out:
        out.write(f"{len(candidates)} {args.seats}\n")
        for ranking, n in sorted(counts.items(), key=lambda item: (-item[1], item[0])):
            out.write(f"{n} " + " ".join(str(c + 1) for c in ranking) + " 0\n")
        out.write("0\n")
        for name in candidates:
            out.write(f'"{name}"\n')
        out.write(f'"{args.title}"\n')


if __name__ == "__main__":
    main()

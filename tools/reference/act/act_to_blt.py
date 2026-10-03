"""Convert Elections ACT's ballot-paper preference data to BLT.

usage: act_to_blt.py DATA_DIR ELECTORATE OUT.blt [--seats N]

DATA_DIR holds the files Elections ACT publishes for an election (for 2020: "Ballot paper
preference data", archived at web.archive.org): Electorates.txt, Groups.txt, Candidates.txt and
<Electorate>Total.txt, one row per preference: batch, pindex (the paper within its batch), pref,
pcode (group), ccode (candidate within the group), rcand (the candidate's rotated position on
that paper, unused here).

Candidates are listed in ballot-paper order, group by group ("BOISEN, Adriana" becomes "Adriana
Boisen"); identical papers are merged, the most common first. Seats default to 5, the number
every ACT electorate has returned since 2016.
"""
import argparse
import collections
import csv
import os


def rows(path):
    with open(path, newline="", encoding="latin-1") as f:
        reader = csv.reader(f)
        header = next(reader)
        for row in reader:
            if row:
                yield dict(zip(header, row))


def display_name(name):
    surname, _, given = name.partition(",")
    surname = "-".join("'".join(part.capitalize() for part in piece.split("'")) for piece in surname.strip().split("-"))
    return f"{given.strip()} {surname}"


def main():
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("data")
    parser.add_argument("electorate")
    parser.add_argument("out")
    parser.add_argument("--seats", type=int, default=5)
    args = parser.parse_args()

    ecode = next(r["ecode"] for r in rows(os.path.join(args.data, "Electorates.txt"))
                 if r["electorate"].lower() == args.electorate.lower())
    candidates = sorted(((int(r["pcode"]), int(r["ccode"])), r["cname"])
                        for r in rows(os.path.join(args.data, "Candidates.txt")) if r["ecode"] == ecode)
    index = {key: i + 1 for i, (key, _) in enumerate(candidates)}

    papers = collections.defaultdict(dict)
    for r in rows(os.path.join(args.data, args.electorate.capitalize() + "Total.txt")):
        papers[(r["batch"], r["pindex"])][int(r["pref"])] = index[(int(r["pcode"]), int(r["ccode"]))]
    counts = collections.Counter(tuple(prefs[p] for p in sorted(prefs)) for prefs in papers.values())

    with open(args.out, "w") as out:
        out.write(f"# {args.electorate.capitalize()}: {len(papers)} ballot papers, converted from Elections ACT's\n"
                  f"# ballot paper preference data by tools/reference/act/act_to_blt.py.\n")
        out.write(f"{len(candidates)} {args.seats}\n")
        for ranking, count in sorted(counts.items(), key=lambda item: (-item[1], item[0])):
            out.write(f"{count} {' '.join(map(str, ranking))} 0\n")
        out.write("0\n")
        for _, name in candidates:
            out.write(f'"{display_name(name)}"\n')
        out.write(f'"{args.electorate.capitalize()}"\n')


if __name__ == "__main__":
    main()

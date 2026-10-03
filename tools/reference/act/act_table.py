"""Extract Elections ACT's official distribution of preferences into a plain-text expected file.

usage: act_table.py TABLE2.xlsx ELECTION.blt OUT.txt   (needs openpyxl)

TABLE2.xlsx is the "Final Distribution of Preferences" table 2 (votes) for one electorate, as
Elections ACT publishes it. ELECTION.blt is the same electorate converted by act_to_blt.py: its
candidate names are used, matched to the table's ("Adriana BOISEN" is "Adriana Boisen").

The output has one block per numbered count: every candidate's votes at the end of that count
(elected candidates included), then the votes exhausted and the loss by fraction, exactly as the
table gives them to 6 decimal places, and the table's remarks. ActOfficialDistributionTest
matches each round of this project's count to one of these.
"""
import re
import sys

import openpyxl


def key(name):
    return re.sub(r"[^a-z]", "", name.lower())


def main():
    table, blt, out_path = sys.argv[1:4]
    lines = [line.rstrip("\n") for line in open(blt) if not line.startswith("#")]
    n = int(lines[0].split()[0])
    end = lines.index("0", 1)
    names = [lines[end + 1 + i].strip('"') for i in range(n)]
    by_key = {key(name): name for name in names}

    rows = list(openpyxl.load_workbook(table, data_only=True).active.iter_rows(values_only=True))
    header = [str(c).strip() if c else "" for c in rows[1]]
    exhausted = header.index("Votes Exhausted at Count")
    loss = header.index("Loss by fraction")
    remarks = header.index("Remarks")
    columns = {i: by_key[key(str(rows[2][i]))] for i in range(1, exhausted) if rows[2][i]}
    if sorted(columns.values()) != sorted(names):
        sys.exit("the table's candidates don't match the BLT's")

    with open(out_path, "w") as out:
        out.write(f"# Elections ACT's official distribution of preferences ({table.split('/')[-1]}), extracted\n"
                  f"# by tools/reference/act/act_table.py: votes at the end of each count.\n")
        for row in rows[3:]:
            if not isinstance(row[0], int):
                continue
            out.write(f"\ncount {row[0]}\n")
            for i, name in columns.items():
                out.write(f"{name}: {row[i] or 0}\n")
            out.write(f"exhausted: {row[exhausted] or 0}\n")
            out.write(f"loss: {format(row[loss] or 0, 'f')}\n")
            if row[remarks]:
                out.write(f"remarks: {' '.join(str(row[remarks]).split())}\n")


if __name__ == "__main__":
    main()

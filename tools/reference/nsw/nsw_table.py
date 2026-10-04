"""Turn an NSW Electoral Commission distribution of preferences into the CSV the test fixtures read.

usage: nsw_table.py DOP.xlsx OUT.csv

DOP.xlsx is a councillor count's dopfulldetails.xlsx. OUT.csv gets a row per count: the count,
every candidate's progressive total in votes, the votes lost (exhausted plus loss by fraction,
which the Commission reports together), and who that count elected, separated by ";". The
first row names the columns. Needs openpyxl.
"""
import csv
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import nsw_to_blt  # noqa: E402
from check import official  # noqa: E402


def main():
    dop, out = sys.argv[1], sys.argv[2]
    header = next(r for r in nsw_to_blt.sheet_of(dop).iter_rows(values_only=True, max_row=12) if "Count" in r)
    names = [name for _, _, name in nsw_to_blt.candidate_columns(list(header))]
    counts, _, _ = official(dop)
    rows = [list(r) for r in nsw_to_blt.sheet_of(dop).iter_rows(values_only=True)]
    results = [str(r[-1] or "") for r in rows if len(r) > 3 and r[3] == "Progressive Total"]
    with open(out, "w", newline="") as f:
        writer = csv.writer(f, lineterminator="\n")
        writer.writerow(["count", *names, "votes lost", "elected"])
        for (count, votes, lost), result in zip(counts, results):
            elected = [n for n in names if f"{n} Elected" in result]
            writer.writerow([count, *(votes[n] for n in names), lost, ";".join(elected)])


if __name__ == "__main__":
    main()

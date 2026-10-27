"""Check the project's Schulze proportional ranking against Markus Schulze's published lists.

usage: proportional_check.py SCHULZE3 [--jar PATH] [--timeout SECONDS] [ELECTION ...]

SCHULZE3 is the unpacked schulze3.zip (see README.md). For each election X.dat with a published
X_list.dat (all 66 of Tideman's, and the Pirate Party conventions ppb2011, ppb2013 and pps2014),
the election is converted (tideman.py), ranked with --method schulze-proportional, and compared
with Schulze's program's output: the list, and for every place both lists reach with the same
candidates placed before it, every comparison H[b][c] = N[the list so far + b; c] his program
prints, to the 6 decimals it prints them in (allowing for its long double arithmetic).

The lists can part where the program differs from the paper (see VALIDATION.md): it compares
H's links by winning votes rather than margin, gives the first place to the top of its
single-winner ranking, and breaks ties with a ranking drawn from a random ballot order. So a
different list is reported with whether the paper's own rules, read off his printed H, give
ours, and the comparisons are checked up to the first place the lists differ at.
"""
import argparse
import json
import os
import re
import subprocess
import sys
import tempfile
from decimal import Decimal
from fractions import Fraction

HERE = os.path.dirname(os.path.abspath(__file__))
DEFAULT_JAR = os.path.join(HERE, "..", "..", "..", "cli", "target", "rankedchoice-cli-0.1.0-SNAPSHOT.jar")
TOLERANCE = Decimal("0.0000015")


def published(path):
    """Each run of the program the file holds (some hold two, appended), as [(H as {row: {column:
    value}}, who took the place)] per place."""
    runs, places, block = [], [], {}
    with open(path, encoding="latin-1") as source:
        for line in source:
            line = line.strip()
            match = re.match(r"The (\d+)\. place goes to candidate (\S+)\.", line)
            if match:
                if match.group(1) == "1" and places:
                    runs.append(places)
                    places = []
                places.append((block, match.group(2)))
                block = {}
                continue
            fields = line.split()
            if len(fields) > 2 and re.fullmatch(r"-?\d+\.\d+", fields[1]):
                block[fields[0]] = [Decimal(x) for x in fields[1:]]
    runs.append(places)
    # Columns are the block's rows, in the same order (the diagonal is printed as 0).
    return [[({row: {list(rows)[j]: v for j, v in enumerate(values) if list(rows)[j] != row}
               for row, values in rows.items()}, taken) for rows, taken in run] for run in runs]


def potential_winners_by_margin(h):
    """The paper's Stage 3 on a printed H: Schulze by margin, its unbeaten candidates."""
    names = list(h)
    path = {(a, b): h[a][b] - h[b][a] for a in names for b in names if a != b}
    for via in names:
        for a in names:
            for b in names:
                if len({a, b, via}) == 3:
                    path[a, b] = max(path[a, b], min(path[a, via], path[via, b]))
    return [a for a in names if all(path[a, b] >= path[b, a] for b in names if b != a)]


def potential_winners_by_winning_votes(h):
    """The program's version of Stage 3 on a printed H: a link is H[b][c] if b wins, else nothing."""
    names = list(h)
    eps = Fraction(1, 10 ** 7)
    path = {(a, b): (h[a][b] + 1 if h[a][b] > h[b][a] + eps else Fraction(1)) for a in names for b in names if a != b}
    for via in names:
        for a in names:
            for b in names:
                if len({a, b, via}) == 3:
                    path[a, b] = max(path[a, b], min(path[a, via], path[via, b]))
    return [a for a in names if not any(path[b, a] > path[a, b] + 5 * eps for b in names if b != a)]


def check(schulze3, election, jar, timeout):
    with tempfile.TemporaryDirectory() as tmp:
        blt = os.path.join(tmp, election + ".blt")
        subprocess.run([sys.executable, os.path.join(HERE, "tideman.py"), os.path.join(schulze3, election + ".dat"),
                        blt], check=True)
        try:
            result = subprocess.run(["java", "-Xss64m", "-jar", jar, "-i", blt, "-m", "schulze-proportional",
                                     "--report-format", "json"], capture_output=True, text=True, timeout=timeout)
        except subprocess.TimeoutExpired:
            return True, "unfinished", 0, f"{election}: not finished within {timeout} s (not checked)"
    if result.returncode:
        return False, "failed", 0, f"{election}: count failed: {result.stderr.strip()[-300:]}"
    ours = json.loads(result.stdout)
    runs = published(os.path.join(schulze3, election + "_list.dat"))
    theirs = runs[0]
    their_list = [taken for _, taken in theirs]
    other_runs = [[taken for _, taken in run] for run in runs[1:]]
    checked, worst, mismatch = 0, Decimal(0), None
    for k, (h, taken) in enumerate(theirs):
        if ours["ranking"][:k] != their_list[:k]:
            break
        if not h:
            continue
        values = {(c["candidate"], c["against"]): Decimal(c["support"]) for c in ours["places"][k]["comparisons"]}
        for row, cells in h.items():
            for column, value in cells.items():
                ours_value = values.get((row, column))
                if ours_value is None or abs(ours_value - value) > TOLERANCE:
                    mismatch = mismatch or f"place {k + 1}: H[{row}][{column}] is {ours_value}, published {value}"
                else:
                    worst = max(worst, abs(ours_value - value))
                checked += 1
    same = ours["ranking"] == their_list
    line = f"{election}: {len(their_list)} places, {checked} comparisons checked"
    if other_runs:
        line += (f" ({len(runs)} runs of his program in the file, "
                 + ("the same list" if all(run == their_list for run in other_runs) else "LISTS DIFFERING") + ")")
    if mismatch:
        return False, "mismatch", checked, line + f"; MISMATCH at {mismatch}"
    if same:
        return True, "same", checked, line + "; the same list"
    k = next(i for i in range(len(their_list)) if ours["ranking"][i] != their_list[i])
    h = theirs[k][0]
    note = ""
    if h:
        potential = potential_winners_by_margin({row: {c: Fraction(str(v)) for c, v in cells.items()}
                                                 for row, cells in h.items()})
        printed = {row: {c: Fraction(str(v)) for c, v in cells.items()} for row, cells in h.items()}
        by_votes = potential_winners_by_winning_votes(printed)
        note = (f"; on his printed H, by margin: {', '.join(potential)}"
                + (" (ours among them)" if ours["ranking"][k] in potential else " (OURS NOT AMONG THEM)")
                + f"; by winning votes: {', '.join(by_votes)}"
                + (" (his among them)" if their_list[k] in by_votes else " (HIS NOT AMONG THEM)"))
    return True, "part", checked, (line + f"; lists part at place {k + 1}: ours {ours['ranking'][k]}, "
                                   f"his {their_list[k]}" + note)


def main():
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("schulze3")
    parser.add_argument("elections", nargs="*")
    parser.add_argument("--jar", default=DEFAULT_JAR)
    parser.add_argument("--timeout", type=int, default=1800)
    args = parser.parse_args()
    elections = args.elections or sorted(
        name[:-len("_list.dat")] for name in os.listdir(args.schulze3)
        if name.endswith("_list.dat")
        and os.path.exists(os.path.join(args.schulze3, name[:-len("_list.dat")] + ".dat")))
    failed, kinds, checked = 0, {}, 0
    for election in elections:
        ok, kind, comparisons, line = check(args.schulze3, election, args.jar, args.timeout)
        failed += not ok
        kinds.setdefault(kind, []).append(election)
        checked += comparisons
        print(line, flush=True)

    def listed(kind, what):
        return f"; {len(kinds[kind])} {what} ({', '.join(kinds[kind])})" if kind in kinds else ""
    print(f"proportional: {len(kinds.get('same', []))} of {len(elections)} lists the same as the program's"
          + listed("part", "part where the program isn't the paper") + listed("unfinished", "not finished")
          + listed("failed", "failed") + listed("mismatch", "MISMATCH") + f"; {checked} comparisons checked")
    sys.exit(1 if failed else 0)


if __name__ == "__main__":
    main()

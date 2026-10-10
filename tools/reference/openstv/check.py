"""Count random elections with this project and OpenSTV by Schulze or Borda, and compare every figure.

usage: check.py OPENSTV_DIR WORK_DIR --method schulze|borda [--elections N] [--jar PATH] [--python2 PATH]

OPENSTV_DIR is a checkout of OpenSTV 1.7 (https://github.com/Conservatory/openstv, commit 2a267a4),
run with Python 2. Writes N random elections (default 500, seeded, so the same every time) to
WORK_DIR, counts each with this project (`--method schulze` or `--method borda`, JSON report) and
with OpenSTV (`Condorcet` with Schwartz sequential dropping, or `Borda` without ballot completion,
text report), and compares:

- schulze: every pairwise figure and every strongest-path strength (OpenSTV's "Matrix of beatpath
  magnitudes"), and OpenSTV's winner, which must be alone in this project's first place, or in it
  when OpenSTV's dropping leaves several candidates (it then picks by candidate order, -t index).
- borda: every candidate's score.

The elections are small on purpose, so that pairwise cycles and ties are common: 3-8 candidates,
some withdrawn, 5-60 ballot lines with weights 1-5, rankings cut short at random, no tied ranks
(OpenSTV's ballot files have none). Prints the elections that differ, how many had a pairwise
cycle (a Smith set of more than one) and how many ended in a tie, and a total.
"""
import argparse
import concurrent.futures
import json
import os
import random
import re
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
DEFAULT_JAR = os.path.join(HERE, "..", "..", "..", "target", "rankedchoice-0.1.0-SNAPSHOT.jar")
THEIRS = {"schulze": "Condorcet", "borda": "Borda"}


def election(seed):
    """A random election, as BLT text."""
    rng = random.Random(seed)
    candidates = rng.randint(3, 8)
    withdrawn = rng.sample(range(1, candidates + 1), rng.choice([0, 0, 0, 1]))
    weights = [rng.uniform(0.2, 1) for _ in range(candidates)]
    lines = [f"{candidates} 1"]
    if withdrawn:
        lines.append(" ".join(f"-{c}" for c in withdrawn))
    for _ in range(rng.randint(5, 60)):
        pool = list(range(1, candidates + 1))
        ranking = []
        while pool and (not ranking or rng.random() < 0.75):
            pick = rng.choices(pool, [weights[c - 1] for c in pool])[0]
            pool.remove(pick)
            ranking.append(pick)
        lines.append(f"{rng.randint(1, 5)} " + " ".join(map(str, ranking)) + " 0")
    lines.append("0")
    lines += [f'"C{i + 1}"' for i in range(candidates)] + [f'"random {seed}"']
    return "\n".join(lines) + "\n"


def matrix(report, title):
    """The matrix printed after `title` in OpenSTV's text report, as {row: {column: value}}."""
    lines = report[report.index(title):].splitlines()[2:]
    names = [name.strip() for name in lines[0].split("|")[1:]]
    rows = {}
    for line in lines[2:]:
        if "|" not in line:
            break
        cells = [cell.strip() for cell in line.split("|")]
        rows[cells[0]] = {name: int(value) for name, value in zip(names, cells[1:]) if name != cells[0]}
    return rows


def names(text):
    """OpenSTV's list of names: "A", "A and B", "A, B, and C"."""
    return set(re.split(r", and |, | and ", text.strip()))


def schulze(ours, report):
    """Why this project's Schulze count differs from OpenSTV's, or None; and whether there was a cycle, a tie."""
    smith = names(re.search(r"^Smith Set: (.*)$", report, re.M).group(1))
    winner = re.search(r"^Winner is (.*)\.$", report, re.M).group(1)
    first = set(ours["ranking"][0])
    pairwise = matrix(report, "Pairwise Comparison Matrix:")
    if pairwise != ours["pairwise"]:
        return f"pairwise figures differ: ours {ours['pairwise']}, OpenSTV {pairwise}", False, False
    if len(smith) == 1:
        # No dropping: OpenSTV prints no beatpaths, and the Condorcet winner must be ours alone.
        return (None if first == smith else f"winner: ours {sorted(first)}, OpenSTV {winner}"), False, False
    paths = matrix(report, "Matrix of beatpath magnitudes:")
    if paths != ours["strongestPaths"]:
        return f"strongest paths differ: ours {ours['strongestPaths']}, OpenSTV {paths}", True, False
    remaining = re.search(r"^Candidates remaining after SSD: (.*)$", report, re.M)
    expected = names(remaining.group(1)) if remaining else {winner}
    why = None if first == expected and winner in first else \
        f"winners: ours {sorted(first)}, OpenSTV {sorted(expected)} (picked {winner})"
    return why, True, len(expected) > 1


def borda(ours, report):
    """Why this project's Borda scores differ from OpenSTV's, or None."""
    table = report[report.index("Candidate | Count"):].splitlines()[2:]
    theirs = {}
    for line in table:
        if "|" not in line:
            break
        name, count = (cell.strip() for cell in line.split("|"))
        if name != "Exhausted":
            theirs[name] = int(count)
    mine = {row["candidate"]: int(row["score"]) for row in ours["ranking"]}
    return (None if mine == theirs else f"scores: ours {mine}, OpenSTV {theirs}"), False, False


def check(n, args):
    blt = os.path.join(args.work, f"e{n}.blt")
    if not os.path.exists(blt):
        open(blt, "w").write(election(n))
    result = subprocess.run(["java", "-jar", args.jar, "-i", blt, "--method", args.method, "--report-format", "json"],
                            capture_output=True, text=True)
    if result.returncode:
        return n, f"this project failed: {result.stderr.strip()[-200:]}", False, False
    ours = json.loads(result.stdout)
    theirs = subprocess.run([args.python2, "runElection.py", "-r", "TextReport", "-t", "index",
                             THEIRS[args.method], os.path.abspath(blt)],
                            cwd=os.path.join(args.openstv, "openstv"), capture_output=True, text=True)
    if theirs.returncode:
        return n, f"OpenSTV failed: {theirs.stderr.strip()[-200:]}", False, False
    return (n, *(schulze if args.method == "schulze" else borda)(ours, theirs.stdout))


def main():
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("openstv")
    parser.add_argument("work")
    parser.add_argument("--method", required=True, choices=sorted(THEIRS))
    parser.add_argument("--elections", type=int, default=500)
    parser.add_argument("--jar", default=DEFAULT_JAR)
    parser.add_argument("--python2", default="python2")
    args = parser.parse_args()
    os.makedirs(args.work, exist_ok=True)
    with concurrent.futures.ThreadPoolExecutor(os.cpu_count()) as pool:
        results = sorted(pool.map(lambda n: check(n, args), range(1, args.elections + 1)))
    differ = [(n, why) for n, why, _, _ in results if why]
    for n, why in differ:
        print(f"e{n}: {why}")
    cycles = sum(cycle for _, _, cycle, _ in results)
    ties = sum(tie for _, _, _, tie in results)
    print(f"{args.method}: {args.elections - len(differ)} of {args.elections} elections match OpenSTV"
          + (f" ({cycles} with a pairwise cycle, {ties} of them ending in a tie)" if args.method == "schulze" else "")
          + f"; {len(differ)} differ")
    sys.exit(1 if differ else 0)


if __name__ == "__main__":
    main()

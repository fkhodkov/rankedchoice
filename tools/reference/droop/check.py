"""Count random elections by Meek's and Warren's methods with this project and droop, and compare every round.

usage: check.py DROOP_DIR WORK_DIR [--elections N] [--precisions 9,6,12,13] [--jar PATH]

DROOP_DIR is a checkout of droop (https://github.com/jklundell/droop, commit cf58293), run with this
Python. Writes N random elections (default 300, seeded, so the same every time) to WORK_DIR and
counts each by Meek and by Warren, at every precision given (decimal places, default 9, 6, 12 and
13), with this project (`--method meek|warren --precision N --tiebreak declared --rounding
truncate:N`: figures to the count's own places, JSON report) and with droop (`meek|warren
arithmetic=fixed defeat_batch=none precision=N`), and compares them as MeekDroopReferenceTest does:
each of this project's rounds is droop's next Elect or Defeat action (droop's closing "Defeat
remaining" isn't a round here), the same kind, and for an exclusion the same candidate; every tally
is droop's, exactly; the residual is this project's exhausted votes, less the ballots that rank only
withdrawn candidates (droop leaves those out of the count, this project counts them as exhausted
from the start: either way they're out of the quota, which Meek's method takes over the active
votes); an election's surplus is droop's vote for the candidate less the quota; and the same
candidates are elected.

A count droop stops on is reported apart: where the elected candidates' votes less the quota sum
below zero (rounding can leave one a hair under it), droop finds no hopeful within that surplus of
the lowest, not even the lowest, and stops on an empty tie. This project excludes the lowest there;
a copy of droop with that one line patched to do the same matches it.

Elections: 3-10 candidates, 1-5 seats, a withdrawn candidate in a quarter of them, 5-40 ballot lines
of 1-20 voters (larger, up to 2,000, in a fifth of them), rankings cut short at random, no tied
ranks (a tie under `split` is where this project and droop deliberately differ: see VALIDATION.md).
"""
import argparse
import concurrent.futures
import json
import os
import pathlib
import random
import re
import subprocess
import sys
from decimal import Decimal

HERE = os.path.dirname(os.path.abspath(__file__))
DEFAULT_JAR = os.path.join(HERE, "..", "..", "..", "cli", "target", "rankedchoice-cli-0.1.0-SNAPSHOT.jar")
STANDING = re.compile(r"^\t(?:Elected|Hopeful|Defeated|Pending):\s+(.+) \((-?[\d.]+)\)$", re.M)


def election(seed):
    """A random election, as BLT text."""
    rng = random.Random(seed)
    candidates = rng.randint(3, 10)
    seats = rng.randint(1, min(5, candidates - 1))
    withdrawn = rng.sample(range(1, candidates + 1), rng.choice([0, 0, 0, 1]))
    if candidates - len(withdrawn) <= seats:
        withdrawn = []
    weights = [rng.uniform(0.1, 1) for _ in range(candidates)]
    largest = 2000 if rng.random() < 0.2 else 20
    lines = [f"{candidates} {seats}"]
    if withdrawn:
        lines.append(" ".join(f"-{c}" for c in withdrawn))
    for _ in range(rng.randint(5, 40)):
        pool = list(range(1, candidates + 1))
        ranking = []
        while pool and (not ranking or rng.random() < 0.75):
            pick = rng.choices(pool, [weights[c - 1] for c in pool])[0]
            pool.remove(pick)
            ranking.append(pick)
        lines.append(f"{rng.randint(1, largest)} " + " ".join(map(str, ranking)) + " 0")
    lines.append("0")
    lines += [f'"C{i + 1}"' for i in range(candidates)] + [f'"random {seed}"']
    return "\n".join(lines) + "\n"


def droop(args, blt, method, precision):
    """droop's actions: [(elected, name, votes, quota, residual)]."""
    report = subprocess.run([sys.executable, "Droop.py", method, "arithmetic=fixed", "defeat_batch=none",
                             f"precision={precision}", os.path.abspath(blt)], cwd=args.droop,
                            capture_output=True, text=True, check=True).stdout
    actions = []
    for part in report.split("\nAction: ")[1:]:
        head = part.splitlines()[0]
        if head.startswith("Defeat remaining") or not head.startswith(("Elect", "Defeat")):
            continue
        votes = {}
        for names, value in STANDING.findall(part):
            for name in names.split(", "):  # droop lists candidates on equal votes together
                votes[name] = Decimal(value)
        quota = Decimal(re.search(r"\tQuota: (-?[\d.]+)", part).group(1))
        residual = Decimal(re.search(r"\tResidual: (-?[\d.]+)", part).group(1))
        actions.append((head.startswith("Elect"), head[head.index(": ") + 2:].strip(), votes, quota, residual))
    return actions


def ours(args, blt, method, precision):
    # Reported to the count's own places: an exact report rounds figures to 10.
    result = subprocess.run(["java", "-jar", args.jar, "-i", blt, "--method", method, "--precision", str(precision),
                             "--tiebreak", "declared", "--rounding", f"truncate:{precision}",
                             "--report-format", "json"], capture_output=True, text=True, check=True)
    return json.loads(result.stdout)


def withdrawn_only(text):
    """The voters whose ballots rank only withdrawn candidates."""
    lines = [line for line in text.splitlines() if line.strip()]
    withdrawn = {-int(token) for token in lines[1].split()} if lines[1].startswith("-") else set()
    total = 0
    for line in lines[2 if withdrawn else 1:]:
        tokens = line.split()
        if tokens == ["0"]:
            break
        if all(int(token) in withdrawn for token in tokens[1:-1]):
            total += int(tokens[0])
    return Decimal(total)


def compare(report, actions, left_out):
    """Why this project's count differs from droop's, or None; `left_out` voters droop doesn't count."""
    rounds = report["rounds"]
    if len(rounds) != len(actions):
        return f"{len(rounds)} rounds here, {len(actions)} Elect or Defeat actions in droop"
    for round_, (elected, name, votes, quota, residual) in zip(rounds, actions):
        number = round_["roundNumber"]
        action = round_["action"]
        if (action["type"] == "elected") != elected:
            theirs = "Elect" if elected else "Defeat"
            return f"round {number}: ours {action['type']} {action['candidate']}, droop {theirs}"
        if not elected and action["candidate"] != name:
            return f"round {number}: ours excludes {action['candidate']}, droop {name}"
        for candidate, tally in round_["tallies"].items():
            if Decimal(tally) != votes.get(candidate):
                return f"round {number}: {candidate} {tally} here, {votes.get(candidate)} in droop"
        if Decimal(round_["exhaustedVotes"]) - left_out != residual:
            return (f"round {number}: exhausted {round_['exhaustedVotes']} here (less {left_out} for withdrawn "
                    f"candidates alone), residual {residual} in droop")
        surplus = Decimal(action.get("surplusTransferred", "0"))
        if elected and surplus > 0 and surplus != votes[action["candidate"]] - quota:
            return f"round {number}: surplus {surplus} here, {votes[action['candidate']] - quota} in droop"
    theirs = {name for elected, name, *_ in actions if elected}
    if set(report["electedInOrder"]) != theirs:
        return f"elected: ours {sorted(report['electedInOrder'])}, droop {sorted(theirs)}"
    return None


def check(n, args):
    """(n, problems, rounds compared, whether Warren's figures differ from Meek's, whether its winners do,
    the counts droop stops on)."""
    blt = os.path.join(args.work, f"e{n}.blt")
    text = election(n)
    pathlib.Path(blt).write_text(text)
    left_out = withdrawn_only(text)
    problems, rounds, droop_stops = [], 0, []
    figures = {}
    try:
        for precision in args.precisions:
            for method in ("meek", "warren"):
                report = ours(args, blt, method, precision)
                try:
                    actions = droop(args, blt, method, precision)
                except subprocess.CalledProcessError as e:
                    # droop stops where a surplus rounded below zero leaves it no candidate to exclude.
                    if "IndexError" not in e.stderr:
                        raise
                    droop_stops.append(f"e{n} {method} at {precision} places")
                    continue
                why = compare(report, actions, left_out)
                if why:
                    problems.append(f"{method} at {precision} places: {why}")
                rounds += len(report["rounds"])
                if precision == args.precisions[0]:
                    figures[method] = report
    except subprocess.CalledProcessError as e:
        return n, [f"a count failed: {' '.join(e.cmd[:3])}: {e.stderr.strip()[-200:]}"], rounds, False, False, []
    warren_differs = figures["meek"]["rounds"] != figures["warren"]["rounds"]
    winners_differ = set(figures["meek"]["electedInOrder"]) != set(figures["warren"]["electedInOrder"])
    return n, problems, rounds, warren_differs, winners_differ, droop_stops


def main():
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("droop")
    parser.add_argument("work")
    parser.add_argument("--elections", type=int, default=300)
    parser.add_argument("--precisions", default="9,6,12,13")
    parser.add_argument("--jar", default=DEFAULT_JAR)
    args = parser.parse_args()
    args.precisions = [int(p) for p in args.precisions.split(",")]
    os.makedirs(args.work, exist_ok=True)
    with concurrent.futures.ThreadPoolExecutor(os.cpu_count()) as pool:
        results = sorted(pool.map(lambda n: check(n, args), range(1, args.elections + 1)))
    differ = [(n, problems) for n, problems, *_ in results if problems]
    for n, problems in differ:
        print(f"e{n}: " + "\n    ".join(problems))
    rounds = sum(r[2] for r in results)
    warren = sum(r[3] for r in results)
    winners = sum(r[4] for r in results)
    stops = [stop for r in results for stop in r[5]]
    print(f"meek and warren: {args.elections - len(differ)} of {args.elections} elections match droop in every round "
          f"at {', '.join(map(str, args.precisions))} places ({rounds} rounds in all); Warren's figures differ from "
          f"Meek's in {warren} of them, its winners in {winners}; droop stops on {len(stops)} count(s)"
          + (f" ({', '.join(stops)})" if stops else "") + f"; {len(differ)} differ")
    sys.exit(1 if differ else 0)


if __name__ == "__main__":
    main()

"""Count random elections by QPQ with this project, droop and OpenSTV, and compare every decision.

usage: check.py DROOP_DIR OPENSTV_DIR WORK_DIR [--elections N] [--jar PATH] [--python2 PATH]

DROOP_DIR is a checkout of droop (https://github.com/jlundell/droop, commit cf58293), run with this
Python; OPENSTV_DIR one of OpenSTV 1.7 (commit 2a267a4), run with Python 2 through openstv_qpq.py.
Writes N random elections (default 1000, seeded, so the same every time) to WORK_DIR and counts each
by Woodall's Quota Preferential by Quotient three ways: this project (`--method qpq --tiebreak
declared`, JSON report), droop's `qpq` rule (its report's actions) and OpenSTV's `QPQ` (ties by
candidate index, no look back). Every decision this project makes, electing the candidate with the
highest quotient over the quota or excluding the one with the lowest, must be the next one each of
the others makes, the same candidate, at the same quota and quotient (to 10^-8: droop gives 9
places, the others 10), and a stage this project marks restarted (but the first) must be one OpenSTV restarts at.
The count ends differently, which isn't compared: this project and droop elect the hopefuls left
once there are no more than seats, OpenSTV counts on until they reach the quota. The winners must
be the same three ways.

A difference at a decision where two candidates tie for it is counted apart: this project breaks
ties by candidate order, as droop ("by lot", in its tie order) and OpenSTV (-t index) both do, but a
tie may lie within the others' rounding of a figure. Elections: 3-9 candidates, 1-5 seats, a
withdrawn candidate in a quarter of them, 5-60 ballot lines of weight 1-9, rankings cut short at
random, no tied ranks.
"""
import argparse
import concurrent.futures
import json
import os
import random
import re
import subprocess
import sys
from decimal import Decimal

HERE = os.path.dirname(os.path.abspath(__file__))
DEFAULT_JAR = os.path.join(HERE, "..", "..", "..", "cli", "target", "rankedchoice-cli-0.1.0-SNAPSHOT.jar")
TOLERANCE = Decimal("1e-8")


def election(seed):
    """A random election, as BLT text."""
    rng = random.Random(seed)
    candidates = rng.randint(3, 9)
    seats = rng.randint(1, min(5, candidates - 1))
    withdrawn = rng.sample(range(1, candidates + 1), rng.choice([0, 0, 0, 1]))
    if candidates - len(withdrawn) <= seats:
        withdrawn = []
    weights = [rng.uniform(0.2, 1) for _ in range(candidates)]
    lines = [f"{candidates} {seats}"]
    if withdrawn:
        lines.append(" ".join(f"-{c}" for c in withdrawn))
    for _ in range(rng.randint(5, 60)):
        pool = list(range(1, candidates + 1))
        ranking = []
        while pool and (not ranking or rng.random() < 0.7):
            pick = rng.choices(pool, [weights[c - 1] for c in pool])[0]
            pool.remove(pick)
            ranking.append(pick)
        lines.append(f"{rng.randint(1, 9)} " + " ".join(map(str, ranking)) + " 0")
    lines.append("0")
    lines += [f'"C{i + 1}"' for i in range(candidates)] + [f'"random {seed}"']
    return "\n".join(lines) + "\n"


def ours(jar, blt):
    """[(kind, candidate, quota, quotient, restarted, tied)], winners."""
    result = subprocess.run(["java", "-jar", jar, "-i", blt, "--method", "qpq", "--tiebreak", "declared",
                             "--report-format", "json"], capture_output=True, text=True, check=True)
    report = json.loads(result.stdout)
    decisions = []
    for stage in report["stages"]:
        kind = {"elected": "elect", "excluded": "exclude"}[stage["action"]["type"]]
        chosen = stage["action"]["candidate"]
        quotients = {name: Decimal(value) for name, value in stage["quotients"].items()}
        others = [value for name, value in quotients.items() if name != chosen]
        tied = any(abs(value - quotients[chosen]) <= TOLERANCE for value in others)
        decisions.append((kind, chosen, Decimal(stage["quota"]), quotients[chosen], stage["restarted"], tied))
    return decisions, sorted(report["electedInOrder"])


def droop(droop_dir, blt):
    """[(kind, candidate, quota, quotient)], winners, from droop's report."""
    result = subprocess.run([sys.executable, "Droop.py", "qpq", os.path.abspath(blt)], cwd=droop_dir,
                            capture_output=True, text=True, check=True)
    decisions = []
    winners = []
    blocks = result.stdout.split("Action: ")[1:]
    for block in blocks:
        head = block.splitlines()[0]
        figures = dict(re.findall(r"^\t(?:Elected|Hopeful|Defeated):\s+(.+?) \((-?[\d.]+)\)$", block, re.M))
        quota = re.search(r"^\tQuota: (-?[\d.]+)$", block, re.M)
        quota = Decimal(quota.group(1)) if quota else None
        for kind, prefix in (("elect", "Elect high quotient: "), ("exclude", "Defeat low quotient: ")):
            if head.startswith(prefix):
                name = head[len(prefix):].strip()
                decisions.append((kind, name, quota, Decimal(figures[name])))
        if head.startswith("Count Complete"):
            winners = sorted(re.findall(r"^\tElected:\s+(.+?) \(", block, re.M))
    return decisions, winners


def openstv(python2, openstv_dir, blt):
    """[(kind, candidate, quota, quotient, restarted)], winners."""
    result = subprocess.run([python2, os.path.join(HERE, "openstv_qpq.py"), openstv_dir, os.path.abspath(blt)],
                            capture_output=True, text=True, check=True)
    count = json.loads(result.stdout)
    decisions = [(r["action"][0], r["action"][1], Decimal(r["threshold"]), Decimal(r["quotient"]), r["restart"])
                 for r in count["rounds"]]
    return decisions, sorted(count["winners"])


def compare(name, mine, theirs, restarts):
    """The first decision at which `theirs` parts from `mine`: (index, why), or None."""
    for i, decision in enumerate(mine):
        kind, chosen, quota, quotient, restarted, _ = decision
        if i >= len(theirs):
            return i, f"{name} has no decision {i + 1} ({kind} {chosen})"
        other = theirs[i]
        if (other[0], other[1]) != (kind, chosen):
            return i, f"decision {i + 1}: ours {kind} {chosen}, {name} {other[0]} {other[1]}"
        if abs(other[2] - quota) > TOLERANCE or abs(other[3] - quotient) > TOLERANCE:
            return i, (f"decision {i + 1} ({kind} {chosen}): ours quota {quota} quotient {quotient}, "
                       f"{name} {other[2]} and {other[3]}")
        # This project marks the first count restarted too; OpenSTV calls it the count of first choices.
        if restarts and i > 0 and restarted != other[4]:
            return i, f"decision {i + 1} ({kind} {chosen}): restarted here {restarted}, in {name} {other[4]}"
    return None


def check(n, args):
    """(n, problems, differences at a tie, decisions compared, of them at a tie, restarts after the first count)."""
    blt = os.path.join(args.work, f"e{n}.blt")
    open(blt, "w").write(election(n))
    try:
        mine, my_winners = ours(args.jar, blt)
        by_droop, droop_winners = droop(args.droop, blt)
        by_openstv, openstv_winners = openstv(args.python2, args.openstv, blt)
    except subprocess.CalledProcessError as e:
        return n, [f"a count failed: {e.cmd[:3]} {e.stderr.strip()[-200:]}"], [], 0, 0, 0
    problems, at_ties = [], []
    for name, theirs, restarts in (("droop", by_droop, False), ("OpenSTV", by_openstv, True)):
        parted = compare(name, mine, theirs, restarts)
        if parted:
            index, why = parted
            (at_ties if mine[index][5] else problems).append(why)
    tie_free = not at_ties
    if tie_free and not (my_winners == droop_winners == openstv_winners):
        problems.append(f"winners: ours {my_winners}, droop {droop_winners}, OpenSTV {openstv_winners}")
    tied = sum(1 for decision in mine if decision[5])
    restarts = sum(1 for decision in mine[1:] if decision[4])
    return n, problems, at_ties, len(mine), tied, restarts


def main():
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("droop")
    parser.add_argument("openstv")
    parser.add_argument("work")
    parser.add_argument("--elections", type=int, default=1000)
    parser.add_argument("--jar", default=DEFAULT_JAR)
    parser.add_argument("--python2", default="python2")
    args = parser.parse_args()
    os.makedirs(args.work, exist_ok=True)
    with concurrent.futures.ThreadPoolExecutor(os.cpu_count()) as pool:
        results = sorted(pool.map(lambda n: check(n, args), range(1, args.elections + 1)))
    differ = [(n, problems) for n, problems, *_ in results if problems]
    at_ties = [n for n, problems, ties, *_ in results if ties and not problems]
    for n, problems in differ:
        print(f"e{n}: " + "\n    ".join(problems))
    decisions, tied, restarts = (sum(result[i] for result in results) for i in (3, 4, 5))
    print(f"qpq: {args.elections - len(differ) - len(at_ties)} of {args.elections} elections match droop and "
          f"OpenSTV in every decision ({decisions} decisions in all, {tied} of them at a tie, {restarts} "
          f"after a restart) and the winners; {len(at_ties)} part only at "
          f"a tie" + (f" ({', '.join(f'e{n}' for n in at_ties[:20])}{', ...' if len(at_ties) > 20 else ''})"
                      if at_ties else "") + f"; {len(differ)} differ otherwise")
    sys.exit(1 if differ else 0)


if __name__ == "__main__":
    main()

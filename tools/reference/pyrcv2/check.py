"""Count random elections with this project and pyRCV2 under one preset, and compare every round.

usage: check.py PYRCV2_DIR WORK_DIR --preset NAME [--elections N] [--jar PATH]

Writes N random elections (default 100, 200 for senate; seeded, so the same every time) to
WORK_DIR, counts each with this project's ruleset matching the pyRCV2 preset (see count.py) and
with pyRCV2, and compares them with compare.py as the README's table says: every round must be a
pyRCV2 stage, with every candidate's votes, the votes exhausted and the loss by fraction, and the
same candidates elected. Prints the elections that differ and a total, counting apart those that
differ only in the loss by fraction, those within the preset's tolerance (scottish: a hundredth of
a vote) and those pyRCV2 crashes on. pyRCV2 breaks ties backwards and then by declared order, as
this project does, so no difference is down to a random tie.
"""
import argparse
import concurrent.futures
import os
import pathlib
import random
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
DEFAULT_JAR = os.path.join(HERE, "..", "..", "..", "cli", "target", "rankedchoice-cli-0.1.0-SNAPSHOT.jar")

OURS = {
    "ers97": ["--ruleset", "ers97"],
    "gregory": ["--quota", "droop", "--transfer", "fractional", "--rounding", "exact",
                "--tiebreak", "previous-round,declared"],
    "scottish": ["--ruleset", "scottish"],
    "senate": ["--ruleset", "senate"],
}
COMPARE = {"ers97": [], "gregory": ["--exact"], "scottish": ["--combined"], "senate": []}
# A second, looser comparison: an election that fails the first but passes this is counted apart.
WITHIN = {"scottish": ("1/100", "within a hundredth of a vote")}
ELECTIONS = {"senate": 200}


def election(seed):
    """A random election: 4-13 candidates, 1-5 seats, 20-1,000 voters, skewed popularity."""
    rng = random.Random(f"pyrcv2 {seed}")
    candidates = rng.randint(4, 13)
    seats = rng.randint(1, min(5, candidates - 1))
    weights = [1 / (i + 1) ** rng.uniform(0.3, 1.2) for i in range(candidates)]
    lines = [f"{candidates} {seats}"]
    for _ in range(rng.randint(20, 1000)):
        pool = list(range(1, candidates + 1))
        ranking = []
        while pool and (not ranking or rng.random() < 0.8):
            pick = rng.choices(pool, [weights[c - 1] for c in pool])[0]
            pool.remove(pick)
            ranking.append(pick)
        lines.append("1 " + " ".join(map(str, ranking)) + " 0")
    lines.append("0")
    lines += [f'"C{i + 1}"' for i in range(candidates)] + [f'"random {seed}"']
    return "\n".join(lines) + "\n"


def compare(ours, theirs, flags):
    return subprocess.run([sys.executable, os.path.join(HERE, "compare.py"), ours, theirs] + flags,
                          capture_output=True, text=True)


def check(n, args):
    blt = os.path.join(args.work, f"r{n}.blt")
    if not os.path.exists(blt):
        pathlib.Path(blt).write_text(election(n))
    ours = os.path.join(args.work, f"r{n}.{args.preset}.ours.json")
    theirs = os.path.join(args.work, f"r{n}.{args.preset}.pyrcv2.json")
    result = subprocess.run(["java", "-jar", args.jar, "-i", blt, "--report-format", "json"] + OURS[args.preset],
                            capture_output=True, text=True)
    if result.returncode:
        return n, f"this project failed: {result.stderr.strip()[-200:]}"
    pathlib.Path(ours).write_text(result.stdout)
    counted = subprocess.run([sys.executable, os.path.join(HERE, "count.py"), args.pyrcv2, blt,
                              "--preset", args.preset], capture_output=True, text=True)
    if counted.returncode:
        return n, f"CRASH: {counted.stderr.strip().splitlines()[-1][-200:]}"
    pathlib.Path(theirs).write_text(counted.stdout)
    compared = compare(ours, theirs, COMPARE[args.preset])
    if compared.returncode == 0:
        return n, None
    output = compared.stdout.strip()
    problems = output.splitlines()[1:]
    if "winners differ" not in output and problems and all(": loss " in p for p in problems):
        return n, "LOSS: " + output
    if args.preset in WITHIN:
        tolerance = WITHIN[args.preset][0]
        if compare(ours, theirs, COMPARE[args.preset] + ["--tolerance", tolerance]).returncode == 0:
            return n, "WITHIN: " + output
    return n, output


def main():
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("pyrcv2")
    parser.add_argument("work")
    parser.add_argument("--preset", required=True, choices=sorted(OURS))
    parser.add_argument("--elections", type=int)
    parser.add_argument("--jar", default=DEFAULT_JAR)
    args = parser.parse_args()
    elections = args.elections or ELECTIONS.get(args.preset, 100)
    os.makedirs(args.work, exist_ok=True)
    with concurrent.futures.ThreadPoolExecutor(os.cpu_count()) as pool:
        results = sorted(pool.map(lambda n: check(n, args), range(1, elections + 1)))
    kinds = {kind: [n for n, why in results if why and why.startswith(kind + ": ")]
             for kind in ("LOSS", "WITHIN", "CRASH")}
    differ = [(n, why) for n, why in results if why and not why.startswith(tuple(k + ": " for k in kinds))]
    for n, why in differ:
        print(f"r{n}: " + why.replace("\n", "\n    "))

    def listed(numbers):
        return f" ({', '.join(f'r{n}' for n in numbers)})" if numbers else ""
    agree = elections - len(differ) - sum(map(len, kinds.values()))
    summary = f"{args.preset}: {agree} of {elections} elections match pyRCV2 in every round"
    if args.preset in WITHIN:
        summary += f"; {len(kinds['WITHIN'])} more {WITHIN[args.preset][1]}"
    if kinds["LOSS"]:
        summary += f"; {len(kinds['LOSS'])} differ only in the loss by fraction{listed(kinds['LOSS'])}"
    if kinds["CRASH"]:
        summary += f"; pyRCV2 crashes on {len(kinds['CRASH'])}{listed(kinds['CRASH'])}"
    print(summary + f"; {len(differ)} differ otherwise")
    sys.exit(1 if differ else 0)


if __name__ == "__main__":
    main()

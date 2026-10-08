"""Count random elections with this project and OpenTally under one preset, and compare every round.

usage: check.py OPENTALLY WORK_DIR --preset NAME [--elections N] [--jar PATH]

Writes N random elections (default 200, seeded, so the same every time) to WORK_DIR, counts each
with this project's ruleset matching the OpenTally preset (see count.py) and with OpenTally,
and compares them with tools/reference/pyrcv2/compare.py --combined: every round must be an
OpenTally stage, every candidate's votes and the votes set aside (exhausted plus lost) within
10^-9, and the same candidates elected (for Meek, only the winners: see WINNERS_ONLY). Prints the
elections that differ and a total. An election that
differs where OpenTally broke a tie at random (this project uses declared order) is counted
apart.

Under `tasmania`, ballots with fewer than 7 preferences are left out before OpenTally counts
them, since this project's hare-clark-qpq treats them as informal and OpenTally has no such rule.
"""
import argparse
import concurrent.futures
import json
import os
import random
import subprocess
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import count  # noqa: E402

HERE = os.path.dirname(os.path.abspath(__file__))
DEFAULT_JAR = os.path.join(HERE, "..", "..", "..", "target", "rankedchoice-0.1.0-SNAPSHOT.jar")
COMPARE = os.path.join(HERE, "..", "pyrcv2", "compare.py")

OURS = {
    "gregory": ["--quota", "droop", "--transfer", "fractional", "--rounding", "exact",
                "--tiebreak", "previous-round,declared"],
    "scottish": ["--ruleset", "scottish"],
    "ers97": ["--ruleset", "ers97"],
    "act": ["--ruleset", "act-hare-clark"],
    # Written to WORK_DIR: these rulesets less electAsTheyStand (see CORE).
    "senate": ["--config", "senate-core.yaml"],
    "viclc": ["--config", "vic-lc-core.yaml"],
    "wa": ["--config", "wa-lc-core.yaml"],
    "tasmania": ["--ruleset", "hare-clark-qpq"],
    "nswlg": ["--ruleset", "nsw-lg"],
    "meek06": ["--method", "meek"],
    "meeknz": ["--method", "meek"],
}
MINIMUM_PREFERENCES = {"tasmania": 7}
# Rulesets compared without their end of count, electAsTheyStand, against OpenTally without its
# early election, which differs from it (see count.py); the real counts check the ending.
CORE = ["senate", "vic-lc", "wa-lc"]
# Whole votes tie far more often in small elections, and OpenTally breaks those at random, so
# these presets get ten times the voters.
VOTERS = {"tasmania": 10}
# Meek's figures depend on when each implementation stops iterating (OpenTally's Meek presets also
# stop early when the surplus left can't change an exclusion), so only the winners are compared.
WINNERS_ONLY = {"meek06", "meeknz"}


def election(seed, scale=1):
    """A random election: 5-12 candidates, 1-5 seats, 30-600 voters (times scale), skewed popularity."""
    rng = random.Random(seed)
    candidates = rng.randint(5, 12)
    seats = rng.randint(1, min(5, candidates - 1))
    weights = [1 / (i + 1) ** rng.uniform(0.3, 1.2) for i in range(candidates)]
    lines = [f"{candidates} {seats}"]
    for _ in range(rng.randint(30, 600) * scale):
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


def without_short_ballots(text, minimum):
    lines = text.splitlines()
    candidates = int(lines[0].split()[0])
    minimum = min(minimum, candidates - 1)
    end = lines.index("0")
    kept = [line for line in lines[1:end] if len(line.split()) - 2 >= minimum]
    return "\n".join([lines[0]] + kept + lines[end:]) + "\n"


def check(n, args):
    scale = VOTERS.get(args.preset, 1)
    blt = os.path.join(args.work, f"e{n}.blt" if scale == 1 else f"e{n}x{scale}.blt")
    if not os.path.exists(blt):
        open(blt, "w").write(election(n, scale))
    theirs_blt = blt
    if args.preset in MINIMUM_PREFERENCES:
        theirs_blt = os.path.join(args.work, f"e{n}.{args.preset}.blt")
        open(theirs_blt, "w").write(without_short_ballots(open(blt).read(), MINIMUM_PREFERENCES[args.preset]))
    ours = os.path.join(args.work, f"e{n}.{args.preset}.ours.json")
    flags = [os.path.join(args.work, flag) if flag.endswith(".yaml") else flag for flag in OURS[args.preset]]
    theirs = os.path.join(args.work, f"e{n}.{args.preset}.opentally.json")
    result = subprocess.run(["java", "-jar", args.jar, "-i", blt, "--report-format", "json"] + flags,
                            capture_output=True, text=True)
    if result.returncode:
        return n, f"this project failed: {result.stderr.strip()[-200:]}"
    open(ours, "w").write(result.stdout)
    try:
        open(theirs, "w").write(json.dumps(count.count(args.opentally, theirs_blt, args.preset)))
    except subprocess.CalledProcessError as e:
        return n, f"CRASH: OpenTally failed: {e.stderr.strip()[-200:]}"
    if args.preset in WINNERS_ONLY:
        stages = json.load(open(theirs))
        won = set(json.load(open(ours))["electedInOrder"])
        theirs_won = {name for name, state in stages[-1]["states"].items() if state == 40}
        return n, None if won == theirs_won else f"winners differ: ours {sorted(won)}, OpenTally {sorted(theirs_won)}"
    compared = subprocess.run([sys.executable, COMPARE, ours, theirs, "--combined", "--tolerance", "1/1000000000"],
                              capture_output=True, text=True)
    if compared.returncode == 0:
        return n, None
    if count.random_tie(args.opentally, theirs_blt, args.preset):
        return n, "TIE: OpenTally broke a tie at random"
    return n, compared.stdout.strip()


def main():
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("opentally")
    parser.add_argument("work")
    parser.add_argument("--preset", required=True, choices=sorted(OURS))
    parser.add_argument("--elections", type=int, default=200)
    parser.add_argument("--jar", default=DEFAULT_JAR)
    args = parser.parse_args()
    os.makedirs(args.work, exist_ok=True)
    for ruleset in CORE:
        text = open(os.path.join(HERE, "..", "..", "..", "src", "main", "resources", "rulesets",
                                 ruleset + ".yaml")).read()
        open(os.path.join(args.work, ruleset + "-core.yaml"), "w").write(
            text.replace("electAsTheyStand: true", "electAsTheyStand: false"))
    with concurrent.futures.ThreadPoolExecutor(os.cpu_count()) as pool:
        results = sorted(pool.map(lambda n: check(n, args), range(1, args.elections + 1)))
    ties = [n for n, why in results if why and why.startswith("TIE")]
    crashes = [n for n, why in results if why and why.startswith("CRASH")]
    differ = [(n, why) for n, why in results if why and not why.startswith(("TIE", "CRASH"))]
    for n, why in differ:
        print(f"e{n}: " + why.replace("\n", "\n    "))
    agreement = "elect the same candidates" if args.preset in WINNERS_ONLY else "match in every round"
    print(f"{args.preset}: {args.elections - len(differ) - len(ties) - len(crashes)} of {args.elections} elections "
          f"{agreement}; {len(ties)} differ after a tie OpenTally broke at random"
          + (f" ({', '.join(f'e{n}' for n in ties)})" if ties else "")
          + (f"; OpenTally crashes on {len(crashes)} ({', '.join(f'e{n}' for n in crashes)})" if crashes else "")
          + f"; {len(differ)} differ otherwise")
    sys.exit(1 if differ else 0)


if __name__ == "__main__":
    main()

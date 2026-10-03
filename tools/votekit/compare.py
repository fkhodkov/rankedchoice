"""Generate mock elections from a scenario and compare how this project's methods fill the seats.

usage: compare.py SCENARIO.json [--seeds N] [--voters N] [--jar PATH] [--methods a,b,...]
                                [--keep DIR]

For each seed 1..N, generates an election with generate.py's model and counts it with every
method through the project's CLI jar (build it first: mvn package -DskipTests). Reports, per
method, how the seats split between the scenario's slates across all seeds -- the natural
measure for bloc/slate scenarios, where a proportional method should give each bloc's slate
roughly its share of the seats.

Schulze and Borda only rank candidates; their "committee" is the top-ranked candidates, a
common non-proportional baseline. A tie across the seat cutoff is reported as a tie.
"""
import argparse
import collections
import concurrent.futures
import json
import math
import os
import re
import subprocess
import sys
import tempfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import generate  # noqa: E402

METHODS = {
    "stv-scottish": ("STV, Scottish rules (WIGM)", ["--ruleset", "scottish"]),
    "stv-gregory": ("STV, fractional (Gregory)", ["--rounding", "truncate:9"]),
    "stv-ers97": ("STV, ERS97 (last parcel)", ["--ruleset", "ers97"]),
    "meek": ("Meek STV", ["--method", "meek"]),
    "warren": ("Warren STV", ["--method", "warren"]),
    "qpq": ("QPQ", ["--method", "qpq"]),
    "cpo-stv": ("CPO-STV", ["--method", "cpo-stv"]),
    "schulze": ("Schulze, top-ranked", ["--method", "schulze"]),
    "borda": ("Borda, top-ranked", ["--method", "borda"]),
}
DEFAULT_JAR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "target",
                           "rankedchoice-0.1.0-SNAPSHOT.jar")


def winners(method, report, seats):
    """The winning committee from a CLI report, or the string 'tie' if it's ambiguous."""
    if method == "cpo-stv":
        found = re.search(r"^Winning outcome: \{(.*)\}( \(tied)?", report, re.M)
        return "tie" if found.group(2) else found.group(1).split(", ")
    if method == "schulze":
        groups = re.findall(r"^  \d+\. (.*)$", report.split("\nRanking:\n", 1)[1], re.M)
        groups = [g.split(" = ") for g in groups]
    elif method == "borda":
        scores = re.findall(r"^(.*): (\S+)$", report, re.M)
        groups = []
        for name, score in scores:  # already sorted best first; equal scores tie
            if groups and groups[-1][0] == score:
                groups[-1][1].append(name)
            else:
                groups.append((score, [name]))
        groups = [names for _, names in groups]
    else:
        return re.search(r"^Elected: (.*)$", report, re.M).group(1).split(", ")
    committee = []
    for group in groups:
        if len(committee) + len(group) > seats:
            return "tie" if len(committee) < seats else committee
        committee += group
    return committee


def run(jar, blt, method):
    result = subprocess.run(["java", "-jar", jar, "--input", blt, *METHODS[method][1]],
                            capture_output=True, text=True)
    if result.returncode != 0:
        return f"error: {result.stderr.strip().splitlines()[-1] if result.stderr.strip() else result.returncode}"
    return result.stdout


def main():
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("scenario")
    parser.add_argument("--seeds", type=int, default=20)
    parser.add_argument("--voters", type=int, help="override the scenario's voter count")
    parser.add_argument("--jar", default=DEFAULT_JAR)
    parser.add_argument("--methods", default=",".join(METHODS), help="comma-separated subset of: " + ", ".join(METHODS))
    parser.add_argument("--keep", help="directory to keep the generated .blt files in")
    args = parser.parse_args()

    with open(args.scenario) as f:
        scenario = json.load(f)
    seats = scenario["seats"]
    voters = args.voters or scenario["voters"]
    methods = args.methods.split(",")
    candidates = generate.candidates_of(scenario)
    if "cpo-stv" in methods and math.comb(len(candidates), seats) > 500:
        print(f"skipping cpo-stv: C({len(candidates)}, {seats}) outcomes exceeds its cap of 500")
        methods.remove("cpo-stv")
    slate_of = {c: slate for slate, members in scenario["slates"].items() for c in members}
    slates = list(scenario["slates"])
    if not os.path.exists(args.jar):
        sys.exit(f"jar not found: {args.jar} (run: mvn package -DskipTests)")

    directory = args.keep or tempfile.mkdtemp(prefix="votekit-")
    os.makedirs(directory, exist_ok=True)
    files = {}
    for seed in range(1, args.seeds + 1):
        files[seed] = os.path.join(directory, f"seed{seed}.blt")
        generate.write_blt(files[seed], scenario, generate.generate(scenario, seed, voters), seed)

    jobs = [(seed, method) for seed in files for method in methods]
    with concurrent.futures.ThreadPoolExecutor(max_workers=os.cpu_count()) as pool:
        reports = dict(zip(jobs, pool.map(lambda job: run(args.jar, files[job[0]], job[1]), jobs)))

    print(f"{scenario.get('name', args.scenario)}: {seats} seats, {voters} voters, "
          f"model {scenario.get('model', 'slate-pl')}, {args.seeds} seeds")
    if set(scenario["blocs"]) == set(slates):
        target = ", ".join(f"{s} {scenario['blocs'][s] * seats:.2f}" for s in slates)
        print(f"proportional share of seats by bloc: {target}")
    print()
    for method in methods:
        splits = collections.Counter()
        problems = collections.Counter()
        for seed in files:
            report = reports[(seed, method)]
            if report.startswith("error:"):
                problems[report] += 1
                continue
            committee = winners(method, report, seats)
            if committee == "tie":
                problems["tied at the seat cutoff"] += 1
                continue
            counts = collections.Counter(slate_of[c] for c in committee)
            splits[" / ".join(f"{s} {counts[s]}" for s in slates)] += 1
        counted = sum(splits.values())
        mean = {s: 0.0 for s in slates}
        for split, n in splits.items():
            for part in split.split(" / "):
                slate, seats_won = part.rsplit(" ", 1)
                mean[slate] += int(seats_won) * n / max(counted, 1)
        print(f"{METHODS[method][0]}")
        print(f"  mean seats: " + ", ".join(f"{s} {mean[s]:.2f}" for s in slates))
        for split, n in splits.most_common():
            print(f"  {split}: {n}/{args.seeds}")
        for problem, n in problems.items():
            print(f"  {problem}: {n}/{args.seeds}")
    if args.keep:
        print(f"\nelections kept in {directory}")


if __name__ == "__main__":
    main()

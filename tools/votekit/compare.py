"""Generate mock elections from a scenario and compare how this project's methods fill the seats.

usage: compare.py SCENARIO.json [--seeds N] [--voters N] [--jar PATH] [--methods a,b,...]
                                [--keep DIR]

For each seed 1..N, generates an election with generate.py's model and counts it with every
method through the project's CLI jar (build it first: mvn package -DskipTests). Reports, per
method, how the seats split between the scenario's slates across all seeds -- the natural
measure for bloc/slate scenarios, where a proportional method should give each bloc's slate
roughly its share of the seats. Two measures sum it up: seats misallocated, i.e. half the sum
over slates of |seats won - seats deserved by first-preference share| (0 is perfectly
proportional; a whole seat in the wrong party's hands counts 1), and a table of how often each
pair of methods elected exactly the same committee.

Schulze and Borda only rank candidates; their "committee" is the top-ranked candidates, a
common non-proportional baseline. A tie across the seat cutoff is reported as a tie.
"""
import argparse
import collections
import concurrent.futures
import json
import math
import os
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
    "cambridge": ("Cambridge STV (Cincinnati, whole ballots)", ["--method", "cambridge"]),
    "qpq": ("QPQ", ["--method", "qpq"]),
    "cpo-stv": ("CPO-STV", ["--method", "cpo-stv"]),
    "schulze": ("Schulze, top-ranked", ["--method", "schulze"]),
    "borda": ("Borda, top-ranked", ["--method", "borda"]),
}
DEFAULT_JAR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "target",
                           "rankedchoice-0.1.0-SNAPSHOT.jar")


def winners(method, report, seats):
    """The winning committee from a method's JSON report, or the string 'tie' if it's ambiguous."""
    data = json.loads(report)
    if method == "cpo-stv":
        return "tie" if len(data["winners"]) > 1 else data["winners"][0]
    if method == "schulze":
        groups = data["ranking"]
    elif method == "borda":
        groups = []
        for entry in data["ranking"]:  # best first; candidates sharing a place are tied
            if groups and groups[-1][0] == entry["place"]:
                groups[-1][1].append(entry["candidate"])
            else:
                groups.append((entry["place"], [entry["candidate"]]))
        groups = [names for _, names in groups]
    else:
        return data["electedInOrder"]
    committee = []
    for group in groups:
        if len(committee) + len(group) > seats:
            return "tie" if len(committee) < seats else committee
        committee += group
    return committee


def run(jar, blt, method):
    result = subprocess.run(["java", "-jar", jar, "--input", blt, *METHODS[method][1], "--report-format", "json"],
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
    parser.add_argument("--json", help="also write the results (committees per seed, measures) to this file")
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

    # Each election's first-preference share by slate: the vote share a proportional result
    # should mirror (it differs from the bloc sizes whenever voters cross party lines).
    deserved = {seed: deserved_seats(files[seed], candidates, slate_of, slates, seats) for seed in files}
    committees = {}
    for (seed, method), report in reports.items():
        committees[(seed, method)] = report if report.startswith("error:") else winners(method, report, seats)

    print(f"{scenario.get('name', args.scenario)}: {seats} seats, {voters} voters, "
          f"model {scenario.get('model', 'slate-pl')}, {args.seeds} seeds")
    mean_deserved = {s: sum(d[s] for d in deserved.values()) / len(deserved) for s in slates}
    print("seats deserved by first-preference share (mean): "
          + ", ".join(f"{s} {mean_deserved[s]:.2f}" for s in slates))
    print()
    summary = []
    for method in methods:
        splits = collections.Counter()
        problems = collections.Counter()
        misallocated = []
        for seed in files:
            committee = committees[(seed, method)]
            if isinstance(committee, str):
                problems["tied at the seat cutoff" if committee == "tie" else committee] += 1
                continue
            counts = collections.Counter(slate_of[c] for c in committee)
            splits[" / ".join(f"{s} {counts[s]}" for s in slates)] += 1
            misallocated.append(sum(abs(counts[s] - deserved[seed][s]) for s in slates) / 2)
        counted = sum(splits.values())
        mean = {s: 0.0 for s in slates}
        for split, n in splits.items():
            for part in split.split(" / "):
                slate, seats_won = part.rsplit(" ", 1)
                mean[slate] += int(seats_won) * n / max(counted, 1)
        print(f"{METHODS[method][0]}")
        print(f"  mean seats: " + ", ".join(f"{s} {mean[s]:.2f}" for s in slates))
        if misallocated:
            print(f"  seats misallocated vs first preferences: mean {sum(misallocated) / len(misallocated):.2f},"
                  f" worst {max(misallocated):.2f}")
            summary.append((method, sum(misallocated) / len(misallocated), max(misallocated)))
        for split, n in splits.most_common():
            print(f"  {split}: {n}/{args.seeds}")
        for problem, n in problems.items():
            print(f"  {problem}: {n}/{args.seeds}")

    print("\nSame committee (seeds out of " + str(args.seeds) + "):")
    width = max(len(m) for m in methods)
    print(" " * (width + 2) + " ".join(f"{m[:7]:>7}" for m in methods))
    for a in methods:
        row = []
        for b in methods:
            same = sum(1 for seed in files
                       if not isinstance(committees[(seed, a)], str)
                       and not isinstance(committees[(seed, b)], str)
                       and set(committees[(seed, a)]) == set(committees[(seed, b)]))
            row.append(f"{same:>7}")
        print(f"{a:>{width}}  " + " ".join(row))
    if args.keep:
        print(f"\nelections kept in {directory}")
    if args.json:
        with open(args.json, "w") as out:
            json.dump({
                "scenario": scenario.get("name", args.scenario), "seats": seats, "voters": voters,
                "seeds": args.seeds, "deserved": mean_deserved,
                "methods": {m: {"mean_misallocated": mean_mis, "worst_misallocated": worst}
                            for m, mean_mis, worst in summary},
                "committees": {f"{seed}:{m}": (c if isinstance(c, str) else sorted(c))
                               for (seed, m), c in committees.items()},
            }, out, indent=1)


def deserved_seats(blt, candidates, slate_of, slates, seats):
    """Each slate's share of first preferences in a BLT file, times the number of seats."""
    first = collections.Counter()
    with open(blt) as f:
        next(f)
        for line in f:
            parts = line.split()
            if parts == ["0"]:
                break
            if len(parts) > 2:
                first[slate_of[candidates[int(parts[1]) - 1]]] += int(parts[0])
    total = sum(first.values())
    return {s: first[s] / total * seats for s in slates}


if __name__ == "__main__":
    main()

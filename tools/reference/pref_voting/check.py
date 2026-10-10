"""Count random elections with tied rankings by Schulze or Borda, with this project and pref_voting, and compare.

usage: check.py WORK_DIR --method schulze|borda [--elections N] [--jar PATH]

Run it with a Python that has pref_voting 1.18.2 installed (it supports Python 3.10-3.13). Writes
N random elections (default 500, seeded, so the same every time) to WORK_DIR, counts each with
this project (`--method schulze` or `--method borda`, JSON report) and with pref_voting's
ProfileWithTies, and compares:

- schulze: every pairwise figure with pref_voting's supports, using its extended strict
  preference (a ranked candidate is preferred to an unranked one; unranked candidates tie, as
  this project counts a ballot cut short); the whole defeat relation of its beat path on winning
  votes (`beat_path_defeat` with `strength_function=support`) with the one this project's
  strongest paths give (a defeats b when p[a][b] > p[b][a]); and its winners (`beat_path`) with
  this project's first place.
- borda: every candidate's score, on ballots that rank every candidate (ties allowed). On such a
  ballot a candidate tied with k-1 others after p candidates shares the points of positions p to
  p+k-1, n-1-p-(k-1)/2; pref_voting's symmetric Borda score is the candidates below minus those
  above, n-p-k-p. So this project's score must be (symmetric + N(n-1))/2, N the ballots counted
  and n the candidates standing. pref_voting gives an unranked candidate a share of the last
  places where this project, like OpenSTV, gives none, so cut-short ballots aren't compared here
  (tools/reference/openstv compares them).

Elections have 3-8 candidates, a withdrawn one in a quarter of them, 5-60 ballot lines of weight
1-5, and each rank a tie with the next candidate's three times in ten.
"""
import argparse
import concurrent.futures
import json
import os
import random
import subprocess
import sys
from fractions import Fraction

from pref_voting.margin_based_methods import beat_path, beat_path_defeat
from pref_voting.profiles_with_ties import ProfileWithTies
from pref_voting.scoring_methods import symmetric_borda_scores

HERE = os.path.dirname(os.path.abspath(__file__))
DEFAULT_JAR = os.path.join(HERE, "..", "..", "..", "cli", "target", "rankedchoice-cli-0.1.0-SNAPSHOT.jar")


def election(seed, complete):
    """A random election: (BLT text, standing candidates' names, [(weight, {name: rank})])."""
    rng = random.Random(seed)
    candidates = rng.randint(3, 8)
    withdrawn = rng.sample(range(1, candidates + 1), rng.choice([0, 0, 0, 1]))
    weights = [rng.uniform(0.2, 1) for _ in range(candidates)]
    lines = [f"{candidates} 1"]
    if withdrawn:
        lines.append(" ".join(f"-{c}" for c in withdrawn))
    ballots = []
    for _ in range(rng.randint(5, 60)):
        pool = list(range(1, candidates + 1))
        order = []
        while pool and (complete or not order or rng.random() < 0.75):
            pick = rng.choices(pool, [weights[c - 1] for c in pool])[0]
            pool.remove(pick)
            order.append(pick)
        groups = [[order[0]]]
        for c in order[1:]:
            if rng.random() < 0.3:
                groups[-1].append(c)
            else:
                groups.append([c])
        weight = rng.randint(1, 5)
        lines.append(f"{weight} " + " ".join("=".join(map(str, group)) for group in groups) + " 0")
        # pref_voting's ranking of the standing candidates: a withdrawn one is passed over.
        ranks = {}
        for group in groups:
            standing = [f"C{c}" for c in group if c not in withdrawn]
            if standing:
                rank = len(set(ranks.values())) + 1
                ranks.update({name: rank for name in standing})
        ballots.append((weight, ranks))
    lines.append("0")
    lines += [f'"C{i + 1}"' for i in range(candidates)] + [f'"random {seed}"']
    standing = [f"C{c}" for c in range(1, candidates + 1) if c not in withdrawn]
    return "\n".join(lines) + "\n", standing, ballots


def profile(standing, ballots):
    nonempty = [(weight, ranks) for weight, ranks in ballots if ranks]
    prof = ProfileWithTies([ranks for _, ranks in nonempty], rcounts=[weight for weight, _ in nonempty],
                           candidates=standing)
    prof.use_extended_strict_preference()
    return prof


def schulze(ours, standing, ballots):
    """Why this project's Schulze count differs from pref_voting's, or None; and whether ballots had ties."""
    prof = profile(standing, ballots)
    supports = {a: {b: prof.support(a, b) for b in standing if b != a} for a in standing}
    if supports != ours["pairwise"]:
        return f"pairwise figures differ: ours {ours['pairwise']}, pref_voting {supports}"
    theirs = set(beat_path_defeat(prof, strength_function=prof.support).edges())
    p = ours["strongestPaths"]
    mine = {(a, b) for a in standing for b in standing if a != b and p[a][b] > p[b][a]}
    if mine != theirs:
        return f"defeats differ: only ours {sorted(mine - theirs)}, only pref_voting's {sorted(theirs - mine)}"
    winners = set(beat_path(prof, strength_function=prof.support))
    if winners != set(ours["ranking"][0]):
        return f"winners: ours {sorted(ours['ranking'][0])}, pref_voting {sorted(winners)}"
    return None


def borda(ours, standing, ballots):
    """Why this project's Borda scores differ from what pref_voting's symmetric scores give, or None."""
    prof = profile(standing, ballots)
    n = len(standing)
    counted = sum(weight for weight, ranks in ballots if ranks)
    expected = {c: Fraction(score + counted * (n - 1), 2) for c, score in symmetric_borda_scores(prof).items()}
    mine = {row["candidate"]: Fraction(row["score"]) for row in ours["ranking"]}
    return None if mine == expected else f"scores: ours {mine}, from pref_voting {expected}"


def check(n, args):
    text, standing, ballots = election(n, complete=args.method == "borda")
    blt = os.path.join(args.work, f"{args.method}-e{n}.blt")
    open(blt, "w").write(text)
    result = subprocess.run(["java", "-jar", args.jar, "-i", blt, "--method", args.method, "--report-format", "json"],
                            capture_output=True, text=True)
    if result.returncode:
        return n, f"this project failed: {result.stderr.strip()[-200:]}", False
    tied = any(len(set(ranks.values())) < len(ranks) for _, ranks in ballots)
    return n, (schulze if args.method == "schulze" else borda)(json.loads(result.stdout), standing, ballots), tied


def main():
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("work")
    parser.add_argument("--method", required=True, choices=["schulze", "borda"])
    parser.add_argument("--elections", type=int, default=500)
    parser.add_argument("--jar", default=DEFAULT_JAR)
    args = parser.parse_args()
    os.makedirs(args.work, exist_ok=True)
    with concurrent.futures.ThreadPoolExecutor(os.cpu_count()) as pool:
        results = sorted(pool.map(lambda n: check(n, args), range(1, args.elections + 1)))
    differ = [(n, why) for n, why, _ in results if why]
    for n, why in differ:
        print(f"e{n}: {why}")
    tied = sum(tie for _, _, tie in results)
    print(f"{args.method}: {args.elections - len(differ)} of {args.elections} elections match pref_voting "
          f"({tied} with tied ranks); {len(differ)} differ")
    sys.exit(1 if differ else 0)


if __name__ == "__main__":
    main()

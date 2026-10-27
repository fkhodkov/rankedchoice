"""Count random elections by CPO-STV with this project and STVPoll, and compare every comparison of outcomes.

usage: check.py WORK_DIR [--elections N] [--jar PATH]

Run it with a Python that has STVPoll 0.5.6 installed (https://github.com/VoteIT/STVPoll, commit
d6b68a7; `pip install` its checkout). Its order of shared surpluses is a set's, so the figures
depend on string hashing: unless hashing is already fixed (PYTHONHASHSEED=0), the check runs itself
again with it fixed, without Python's own options (-I would ignore PYTHONHASHSEED). Writes N
random elections (default 300, seeded, so the same every time) to WORK_DIR, counts each with
this project (`--method cpo-stv --quota droop --transfer fractional --rounding exact`, JSON
report) and compares, for every pair of outcomes, the
difference between their scores with STVPoll's `outcomes_duel` on the same ballots and quota.
STVPoll rounds each ballot's value to 5 places as it transfers; here it's given exact rounding, so
that what's compared is the rule, not the rounding.

The two count a comparison the same way while the outcomes share at most one candidate: everyone
in neither is excluded, and the shared candidate's surplus, if any, moves to the next preferences
at its fraction. With two or more shared, they differ: this project transfers the largest
surplus still pending until none is left, STVPoll gives each shared candidate one turn, in its
set's order, and then removes them from the count. So a comparison with at most one shared
candidate must match (to 10^-9, the report's precision), and the others are counted apart.
Where STVPoll finds an outcome that wins every comparison, it must be this project's winner, or
else lose or tie, in this project's count, only comparisons with two or more shared candidates,
where the rules differ (such an election is counted apart as explained). Where STVPoll finds no
such outcome, their completions differ (STVPoll's is minimax over the Smith set, this project's
the beatpath), and the winners aren't compared.

Elections: 4-7 candidates, 2-4 seats with at most 35 outcomes, 5-40 ballot lines of weight 1-5,
rankings cut short at random, no tied ranks.
"""
import argparse
import concurrent.futures
import itertools
import json
import os
import pathlib
import random
import subprocess
import sys
from decimal import Decimal
from fractions import Fraction

if sys.flags.hash_randomization:
    os.execve(sys.executable, [sys.executable, os.path.abspath(sys.argv[0])] + sys.argv[1:],
              {**os.environ, "PYTHONHASHSEED": "0"})

from stvpoll.abcs import PreferenceBallot  # noqa: E402
from stvpoll.cpo_stv import get_duels_winner, outcomes_duel  # noqa: E402

HERE = os.path.dirname(os.path.abspath(__file__))
DEFAULT_JAR = os.path.join(HERE, "..", "..", "..", "cli", "target", "rankedchoice-cli-0.1.0-SNAPSHOT.jar")
TOLERANCE = Fraction(1, 10 ** 9)


def election(seed):
    """A random election: (BLT text, candidates' names, seats, [(weight, [name, ...])])."""
    rng = random.Random(seed)
    while True:
        candidates = rng.randint(4, 7)
        seats = rng.randint(2, min(4, candidates - 1))
        outcomes = len(list(itertools.combinations(range(candidates), seats)))
        if outcomes <= 35:
            break
    weights = [rng.uniform(0.2, 1) for _ in range(candidates)]
    names = [f"C{i + 1}" for i in range(candidates)]
    lines = [f"{candidates} {seats}"]
    ballots = []
    for _ in range(rng.randint(5, 40)):
        pool = list(range(candidates))
        order = []
        while pool and (not order or rng.random() < 0.75):
            pick = rng.choices(pool, [weights[c] for c in pool])[0]
            pool.remove(pick)
            order.append(pick)
        weight = rng.randint(1, 5)
        lines.append(f"{weight} " + " ".join(str(c + 1) for c in order) + " 0")
        ballots.append((weight, [names[c] for c in order]))
    lines.append("0")
    lines += [f'"{name}"' for name in names] + [f'"random {seed}"']
    return "\n".join(lines) + "\n", names, seats, ballots


def check(n, args):
    """(n, problems, comparisons with at most one shared, with more, of those differing, whether winners compared,
    whether the winners differ only where the rules do)."""
    text, names, seats, ballots = election(n)
    blt = os.path.join(args.work, f"e{n}.blt")
    pathlib.Path(blt).write_text(text)
    result = subprocess.run(["java", "-jar", args.jar, "-i", blt, "--method", "cpo-stv", "--quota", "droop",
                             "--transfer", "fractional", "--rounding", "exact", "--report-format", "json"],
                            capture_output=True, text=True)
    if result.returncode:
        return n, [f"this project failed: {result.stderr.strip()[-200:]}"], 0, 0, 0, False, False
    ours = json.loads(result.stdout)
    score = {(frozenset(s["outcome"]), frozenset(s["against"])): Fraction(Decimal(s["score"])) for s in ours["scores"]}
    total = sum(weight for weight, _ in ballots)
    quota = total // (seats + 1) + 1
    problems = []
    one_shared = more_shared = more_differing = 0
    duels = []
    for a, b in itertools.combinations(itertools.combinations(names, seats), 2):
        exact = tuple(PreferenceBallot(tuple(ranking), weight, rounding=lambda value: value)
                      for weight, ranking in ballots)
        duel = outcomes_duel(exact, (a, b), quota)
        duels.append(duel)
        theirs = Fraction(duel.difference) * (1 if set(duel.winner) == set(a) else -1)
        mine = score[(frozenset(a), frozenset(b))] - score[(frozenset(b), frozenset(a))]
        agree = abs(theirs - mine) <= TOLERANCE
        if len(set(a) & set(b)) <= 1:
            one_shared += 1
            if not agree:
                problems.append(f"{sorted(a)} against {sorted(b)}: ours {float(mine):+.9f}, "
                                f"STVPoll {float(theirs):+.9f}")
        else:
            more_shared += 1
            more_differing += not agree
    best = get_duels_winner(tuple(duels))
    explained = False
    if best is not None and [sorted(best)] != [sorted(w) for w in ours["winners"]]:
        theirs = frozenset(best)
        # The outcomes STVPoll's winner doesn't beat in this project's count.
        unbeaten_by = [b for b in {key[0] for key in score}
                       if b != theirs and score[(b, theirs)] >= score[(theirs, b)]]
        if unbeaten_by and all(len(theirs & b) >= 2 for b in unbeaten_by):
            explained = True
        else:
            problems.append(f"winner: ours {ours['winners']}, STVPoll {sorted(best)}")
    return n, problems, one_shared, more_shared, more_differing, best is not None, explained


def main():
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("work")
    parser.add_argument("--elections", type=int, default=300)
    parser.add_argument("--jar", default=DEFAULT_JAR)
    args = parser.parse_args()
    os.makedirs(args.work, exist_ok=True)
    with concurrent.futures.ThreadPoolExecutor(os.cpu_count()) as pool:
        results = sorted(pool.map(lambda n: check(n, args), range(1, args.elections + 1)))
    differ = [(n, problems) for n, problems, *_ in results if problems]
    for n, problems in differ:
        rest = f"\n    ... {len(problems) - 5} more" if len(problems) > 5 else ""
        print(f"e{n}: " + "\n    ".join(problems[:5]) + rest)
    one = sum(r[2] for r in results)
    more = sum(r[3] for r in results)
    more_differing = sum(r[4] for r in results)
    winners = sum(r[5] for r in results)
    explained = [n for n, *rest in results if rest[-1]]
    print(f"cpo-stv: {args.elections - len(differ)} of {args.elections} elections agree with STVPoll; "
          f"{one} comparisons with at most one shared candidate, {more} with more ({more_differing} of those "
          f"differ, as the rules there do); winners compared in {winners} elections with an outcome that beats "
          f"every other, {len(explained)} of them different only where the rules are"
          + (f" ({', '.join(f'e{n}' for n in explained)})" if explained else "") + f"; {len(differ)} differ")
    sys.exit(1 if differ else 0)


if __name__ == "__main__":
    main()

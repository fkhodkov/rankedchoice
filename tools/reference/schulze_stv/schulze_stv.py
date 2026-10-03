"""Independent Schulze STV count (exact Fractions), written from the paper alone: Markus Schulze,
"The Schulze Method of Voting", arXiv:1804.02973, section 8.1.

usage: schulze_stv.py ELECTION.blt [REPORT.json]

Prints the potential winners and the winning committee. Given this project's JSON report for
the same election (--method schulze-stv --report-format json), also checks every support
N[committee; outsider] in it (reported to 10 decimals) and its winners against this count.

Deliberately simple rather than fast: the strongest path between every pair of committees,
Floyd-Warshall style, so it's practical up to a few hundred committees.

Definitions used:
- Proportional completion (8.1.1), per contest of M+1 candidates (as Schulze's program does):
  a voter ranking g level with some members T of the committee is replaced by the original
  voters not level with g on all of T, in proportion to their numbers, each copying what they
  say about T; a copy still level with g on part of T is completed the same way. With no such
  voters, every order of T and g is equally likely.
- N[{a1..aM}; g] (8.1.2): the largest t such that the voters can be split into M groups, group
  j preferring aj to g, each weighing at least t. By the supply/demand theorem for bipartite
  transportation problems, that's the minimum over non-empty J of (weight preferring some aj,
  j in J, to g) / |J|.
- Links (8.1.3): committee X = Z+b to Y = Z+c has strength (N[X; c], N[Y; b]), compared by
  margin, N[X; c] - N[Y; b]. A path is as strong as its weakest link; X beats Y if the
  strongest path from X to Y is stronger than the one from Y to X. Potential winners are the
  committees nobody beats.
- Stage 4: A disqualifies B if some candidate in A but not B is ranked above every candidate
  in B but not A in the single-winner Schulze ranking (winning votes, as this project's
  --method schulze). The winners are the potential winners nobody disqualifies.
"""
import itertools
import json
import math
import sys
from fractions import Fraction


def read_blt(path):
    """Candidate names, seats, and ballots as (count, {candidate index: rank level})."""
    lines = [line.strip() for line in open(path) if line.strip() and not line.startswith("#")]
    n, seats = map(int, lines[0].split())
    ballots, i = [], 1
    while lines[i] != "0":
        fields = lines[i].split()
        levels = {}
        for level, group in enumerate(fields[1:-1]):
            for c in group.split("="):
                levels[int(c) - 1] = level
        ballots.append((int(fields[0]), levels))
        i += 1
    names = [lines[i + 1 + k].strip('"') for k in range(n)]
    return names, seats, ballots


def rank(levels, candidate):
    return levels.get(candidate, math.inf)  # unranked: level with each other, below everyone ranked


def patterns(ballots, others, g):
    """How many voters say what about each of `others` against g: 1 above, 2 level, 3 below."""
    counts = {}
    for count, levels in ballots:
        pattern = tuple(1 if rank(levels, a) < rank(levels, g) else 2 if rank(levels, a) == rank(levels, g) else 3
                        for a in others)
        counts[pattern] = counts.get(pattern, 0) + count
    return counts


def complete(original):
    """Proportional completion: the weight of each set of positions placed above g."""
    memo = {}

    def shares(tied):
        """How a voter level with g on positions `tied` is completed: {positions above g: share}."""
        if tied in memo:
            return memo[tied]
        donors = {}
        for pattern, count in original.items():
            if any(pattern[i] != 2 for i in tied):
                key = tuple(pattern[i] for i in tied)
                donors[key] = donors.get(key, 0) + count
        total = sum(donors.values())
        result = {}
        if total:
            for key, count in donors.items():
                above = frozenset(i for i, x in zip(tied, key) if x == 1)
                still = tuple(i for i, x in zip(tied, key) if x == 2)
                for inner, share in (shares(still) if still else {frozenset(): Fraction(1)}).items():
                    result[above | inner] = result.get(above | inner, 0) + Fraction(count, total) * share
        else:
            k = len(tied)
            for size in range(k + 1):
                for above in itertools.combinations(tied, size):
                    result[frozenset(above)] = Fraction(math.factorial(size) * math.factorial(k - size),
                                                        math.factorial(k + 1))
        memo[tied] = result
        return result

    weights = {}
    for pattern, count in original.items():
        above = frozenset(i for i, x in enumerate(pattern) if x == 1)
        tied = tuple(i for i, x in enumerate(pattern) if x == 2)
        for inner, share in (shares(tied) if tied else {frozenset(): Fraction(1)}).items():
            weights[above | inner] = weights.get(above | inner, 0) + count * share
    return weights


def support(weights, m):
    best = None
    for size in range(1, m + 1):
        for subset in itertools.combinations(range(m), size):
            value = Fraction(sum(w for above, w in weights.items() if above & set(subset)), size)
            best = value if best is None or value < best else best
    return best


def single_winner_above(ballots, n):
    d = [[sum(count for count, levels in ballots if rank(levels, a) < rank(levels, b)) for b in range(n)]
         for a in range(n)]
    p = [[d[a][b] if a != b and d[a][b] > d[b][a] else 0 for b in range(n)] for a in range(n)]
    for k in range(n):
        for i in range(n):
            for j in range(n):
                if len({i, j, k}) == 3:
                    p[i][j] = max(p[i][j], min(p[i][k], p[k][j]))
    return lambda a, b: p[a][b] > p[b][a]


def count(names, seats, ballots):
    n = len(names)
    supports = {}  # (committee, outsider) -> N
    for contest in itertools.combinations(range(n), seats + 1):
        for g in contest:
            others = tuple(a for a in contest if a != g)
            supports[(frozenset(others), g)] = support(complete(patterns(ballots, others, g)), seats)

    committees = [frozenset(c) for c in itertools.combinations(range(n), seats)]
    index = {c: i for i, c in enumerate(committees)}
    size = len(committees)
    strongest = [[None] * size for _ in range(size)]
    for x in committees:
        for b in x:
            for c in range(n):
                if c not in x:
                    y = x - {b} | {c}
                    strongest[index[x]][index[y]] = supports[(x, c)] - supports[(y, b)]
    for k in range(size):
        for i in range(size):
            if i == k or strongest[i][k] is None:
                continue
            for j in range(size):
                if j in (i, k) or strongest[k][j] is None:
                    continue
                through = min(strongest[i][k], strongest[k][j])
                if strongest[i][j] is None or through > strongest[i][j]:
                    strongest[i][j] = through
    potential = [a for a in range(size)
                 if not any(b != a and strongest[b][a] > strongest[a][b] for b in range(size))]

    above = single_winner_above(ballots, n)

    def disqualifies(a, b):
        return any(all(above(x, y) for y in b - a) for x in a - b)

    winners = [a for a in potential
               if not any(b != a and disqualifies(committees[b], committees[a]) for b in potential)]
    return supports, [committees[a] for a in potential], [committees[a] for a in winners]


def main():
    names, seats, ballots = read_blt(sys.argv[1])
    if seats >= len(names):
        sys.exit("every candidate is elected")
    supports, potential, winners = count(names, seats, ballots)

    def label(committee):
        return "{" + ", ".join(sorted(names[c] for c in committee)) + "}"

    print("potential winners:", ", ".join(label(c) for c in potential))
    print("winners:", ", ".join(label(c) for c in winners))
    if len(sys.argv) < 3:
        return
    report = json.load(open(sys.argv[2]))
    position = {name: i for i, name in enumerate(names)}
    mismatches, checked = 0, 0
    for entry in report["supports"]:
        key = (frozenset(position[c] for c in entry["committee"]), position[entry["against"]])
        if abs(supports[key] - Fraction(entry["support"])) > Fraction(1, 2 * 10 ** 10):
            mismatches += 1
            if mismatches <= 5:
                print(f"support differs: N[{label(key[0])}; {entry['against']}] report {entry['support']}, "
                      f"here {float(supports[key]):.10f}")
        checked += 1
    same_count = checked == len(supports)
    same_winners = sorted(map(sorted, report["winners"])) == sorted(sorted(names[c] for c in w) for w in winners)
    same_potential = (sorted(map(sorted, report["potentialWinners"]))
                      == sorted(sorted(names[c] for c in p) for p in potential))
    print(f"report: {checked} supports checked, {mismatches} differ"
          + ("" if same_count else f", but this count has {len(supports)}")
          + f"; potential winners {'match' if same_potential else 'DIFFER'}"
          + f"; winners {'match' if same_winners else 'DIFFER'}")
    if mismatches or not (same_count and same_winners and same_potential):
        sys.exit(1)


if __name__ == "__main__":
    main()

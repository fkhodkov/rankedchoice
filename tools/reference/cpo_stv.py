"""Independent CPO-STV pairwise scorer (exact Fractions), written from the method's rules only.

usage: cpo_stv.py ELECTION.blt SCORES.txt

SCORES.txt holds the scores to check, one "A|B|score" per line (outcomes as comma-separated,
sorted candidate names; score an exact fraction "n/d"), e.g. dumped from this project's
CpoStvResult.outcomeScores(). Prints how many match, any mismatches, and the outcome (if any)
that beats every other head to head.

Used to check src/test/resources/testdata/cpostv/near-tie-cycle.blt: all 4,830 pairwise scores
match CpoStvCounter exactly.

Rules (Tideman's CPO-STV, as this project documents them): Droop quota floor(N/(s+1))+1 over
non-blank ballots. For outcomes A and B, candidates in neither are excluded (a ballot counts for
its first preference among A|B). Only candidates in both A and B transfer surpluses, the largest
surplus first (equal surpluses: earliest declared candidate first), each once, by the Gregory
fraction surplus / total value held, applied to every ballot it holds, which then moves to its
next candidate in A|B. A transferring candidate's tally is fixed at the quota. An outcome's score
is the sum of its members' tallies.
"""
import itertools
import sys
from fractions import Fraction


def read_blt(path):
    lines = [line.strip() for line in open(path) if line.strip() and not line.startswith("#")]
    n, seats = map(int, lines[0].split())
    ballots, i = [], 1
    while lines[i] != "0":
        parts = list(map(int, lines[i].split()))
        ballots.append((parts[0], parts[1:-1]))
        i += 1
    names = [lines[i + 1 + k].strip('"') for k in range(n)]
    return names, seats, [(count, [names[c - 1] for c in ranking]) for count, ranking in ballots]


def score_pair(a, b, ballots, quota, order):
    standing = a | b
    shared = [c for c in order if c in a and c in b]
    merged = {}
    for count, ranking in ballots:
        projected = tuple(c for c in ranking if c in standing)
        if projected:
            merged[projected] = merged.get(projected, 0) + count
    states = [[count, ranking, 0, Fraction(1)] for ranking, count in merged.items()]
    fixed = {}
    while True:
        tallies = {c: Fraction(0) for c in standing}
        for count, ranking, pos, value in states:
            if pos < len(ranking):
                tallies[ranking[pos]] += count * value
        best, best_surplus = None, Fraction(0)
        for c in shared:
            if c not in fixed and tallies[c] - quota > best_surplus:
                best, best_surplus = c, tallies[c] - quota
        if best is None:
            break
        ratio = best_surplus / tallies[best]
        fixed[best] = Fraction(quota)
        for state in states:
            if state[2] < len(state[1]) and state[1][state[2]] == best:
                state[3] *= ratio
                state[2] += 1
    final = {c: fixed.get(c, tallies[c]) for c in standing}
    return sum(final[c] for c in a), sum(final[c] for c in b)


def main():
    names, seats, ballots = read_blt(sys.argv[1])
    quota = sum(count for count, ranking in ballots if ranking) // (seats + 1) + 1
    outcomes = [frozenset(o) for o in itertools.combinations(names, seats)]
    key = lambda o: ",".join(sorted(o))
    ours = {}
    for a, b in itertools.combinations(outcomes, 2):
        sa, sb = score_pair(a, b, ballots, quota, names)
        ours[(key(a), key(b))] = sa
        ours[(key(b), key(a))] = sb
    theirs = {}
    for line in open(sys.argv[2]):
        a, b, score = line.strip().split("|")
        theirs[(a, b)] = Fraction(score)
    mismatches = [(k, ours.get(k), theirs[k]) for k in theirs if ours.get(k) != theirs[k]]
    print(f"{len(theirs)} pairwise scores compared, {len(mismatches)} mismatches, quota {quota}")
    for k, mine, java in mismatches[:5]:
        print("  MISMATCH", k, mine, java)
    # Condorcet winner among outcomes: beats every other outcome head to head.
    winners = [o for o in map(key, outcomes)
               if all(ours[(o, p)] > ours[(p, o)] for p in map(key, outcomes) if p != o)]
    print("outcome beating every other:", winners)


if __name__ == "__main__":
    main()

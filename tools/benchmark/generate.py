"""Generate a large synthetic election as BLT, for timing the counts.

usage: generate.py CANDIDATES SEATS VOTERS OUT.blt [--mean-length 5] [--seed 1]

Every voter casts their own ballot line (count 1), the worst case for an engine that merges
identical ballots. Candidate popularity is skewed (candidate i weighs 1/i^0.8), and each voter
ranks a geometric number of candidates (mean --mean-length, at least 1, at most all), drawn
without replacement in proportion to popularity. So ballots are mostly distinct, as in real
large elections. The same arguments always give the same file.
"""
import argparse
import heapq
import math
import os
import random


def ballots(candidates, voters, mean_length, seed):
    """Each voter's ranking, as candidate numbers from 1."""
    rng = random.Random(seed)
    weights = [1 / (i + 1) ** 0.8 for i in range(candidates)]
    keep_going = 1 - 1 / mean_length
    for _ in range(voters):
        length = 1
        while length < candidates and rng.random() < keep_going:
            length += 1
        # Weighted sampling without replacement: the k largest of u^(1/w) (Efraimidis and Spirakis).
        keys = ((math.log(rng.random()) / weights[c], c) for c in range(candidates))
        yield [c + 1 for _, c in heapq.nlargest(length, keys)]


def main():
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("candidates", type=int)
    parser.add_argument("seats", type=int)
    parser.add_argument("voters", type=int)
    parser.add_argument("out")
    parser.add_argument("--mean-length", type=float, default=5)
    parser.add_argument("--seed", type=int, default=1)
    args = parser.parse_args()
    if args.mean_length < 1:
        parser.error("--mean-length must be at least 1")
    partial = args.out + ".part"
    with open(partial, "w") as out:
        out.write(f"{args.candidates} {args.seats}\n")
        for ranking in ballots(args.candidates, args.voters, args.mean_length, args.seed):
            out.write("1 " + " ".join(map(str, ranking)) + " 0\n")
        out.write("0\n")
        for i in range(args.candidates):
            out.write(f'"C{i + 1}"\n')
        out.write(f'"{args.candidates} candidates, {args.seats} seats, {args.voters} voters, '
                  f'mean length {args.mean_length:g}"\n')
    os.replace(partial, args.out)


if __name__ == "__main__":
    main()

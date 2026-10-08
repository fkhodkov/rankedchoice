"""Read an election in the format of Tideman's database, as Markus Schulze distributes it, and
optionally write it as BLT.

usage: tideman.py ELECTION.dat OUT.blt [--names]

The header gives M (seats), C (candidates), N (voters) and F (format), then BEGIN, one line per
voter, END. Format 1: a voter id, then each candidate's rank (99 = unranked). Format 2: a voter
id, then candidates in order of preference, as letters (or numbers, from 1, past 26). In format
2, a candidate named a second time ties with them everyone ranked since their first mention;
that's how Schulze's own program (singl01g.cpp) reads it, and what reproduces his published
figures.

Candidates are named A, B, ... when there are at most 26, else 1, 2, ..., as Schulze's program
prints them; with --names, by the "# 1 Name" comment lines the Pirate Party files have. The
files are Latin-1.
"""
import re
import sys

UNRANKED = 99


def read(path):
    """(seats, number of candidates, voters), each voter a list of ranks, UNRANKED if unranked."""
    params, voters, inside = {}, [], False
    for line in open(path, encoding="latin-1"):
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        if line == "BEGIN":
            inside = True
            continue
        if line == "END":
            break
        if not inside:
            key, value = line.split()
            params[key] = int(value)
            continue
        fields = line.split()[1:]
        if params.get("F", 1) == 1:
            voters.append([int(x) for x in fields])
            continue
        ranks = [UNRANKED] * params["C"]
        for position, letter in enumerate(fields, 1):
            i = int(letter) - 1 if letter.isdigit() else ord(letter) - ord("A")
            if ranks[i] == UNRANKED:
                ranks[i] = position
            else:
                ranks = [ranks[i] if r != UNRANKED and r > ranks[i] else r for r in ranks]
        voters.append(ranks)
    return params["M"], params["C"], voters


def names(candidates):
    return [chr(ord("A") + i) if candidates <= 26 else str(i + 1) for i in range(candidates)]


def comment_names(path, candidates):
    """The names "# 1 Name" comment lines give, if every candidate has one."""
    found = {}
    for line in open(path, encoding="latin-1"):
        match = re.match(r"#\s*(\d+)\s+(.+?)\s*$", line)
        if match:
            found[int(match.group(1))] = match.group(2)
    return [found[i + 1] for i in range(candidates)] if len(found) >= candidates else None


def write_blt(path, seats, candidates, voters, title, given=None):
    with open(path, "w") as out:
        out.write(f"{candidates} {seats}\n")
        for ranks in voters:
            levels = sorted(set(r for r in ranks if r != UNRANKED))
            groups = ["=".join(str(i + 1) for i in range(candidates) if ranks[i] == level) for level in levels]
            out.write(" ".join(["1", *groups, "0"]) + "\n")
        out.write("0\n")
        for name in given or names(candidates):
            out.write(f'"{name}"\n')
        out.write(f'"{title}"\n')


if __name__ == "__main__":
    seats, candidates, voters = read(sys.argv[1])
    given = comment_names(sys.argv[1], candidates) if "--names" in sys.argv[3:] else None
    write_blt(sys.argv[2], seats, candidates, voters, sys.argv[1].rsplit("/", 1)[-1].removesuffix(".dat"), given)

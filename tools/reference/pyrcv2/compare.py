"""Compare this project's count of an election with pyRCV2's, round by round.

usage: compare.py OURS.json PYRCV2.json [-v]

OURS.json is the project's report (--report-format json), PYRCV2.json the output
of count.py. Each round must match a pyRCV2 stage, no earlier than the last round's: every
candidate's votes, the votes exhausted and the loss by fraction. Prints how many rounds match and
whether the same candidates are elected; exits non-zero if not.
"""
import json
import sys
from fractions import Fraction

ELECTED = {20, 30, 40}  # pyRCV2's provisionally elected, distributing surplus, elected


def main():
    combined = "--combined" in sys.argv
    # This project reports exact values to 10 places; with --exact, pyRCV2's exact fractions
    # match within that rounding.
    tolerance = Fraction(1, 10 ** 9) if "--exact" in sys.argv else Fraction(0)
    if "--tolerance" in sys.argv:
        tolerance = Fraction(sys.argv[sys.argv.index("--tolerance") + 1])

    def equal(a, b):
        return abs(Fraction(a) - Fraction(b)) <= tolerance
    ours = json.load(open(sys.argv[1]))
    stages = json.load(open(sys.argv[2]))
    start, matched, problems = 0, 0, []
    for rnd in ours["rounds"]:
        tallies = {name: Fraction(v) for name, v in rnd["tallies"].items()}
        same = [i for i in range(start, len(stages))
                if all(equal(stages[i]["votes"][name], v) for name, v in tallies.items())
                and (combined or equal(stages[i]["exhausted"], rnd["exhaustedVotes"]))]
        if combined:
            # Only the total set aside: pyRCV2's Gregory values the exhausted share of a surplus
            # at the unrounded ratio, so it splits the same total differently.
            exact = [i for i in same if equal(Fraction(stages[i]["exhausted"]) + Fraction(stages[i]["loss"]),
                                              Fraction(rnd["exhaustedVotes"]) + Fraction(rnd["roundingLoss"]))]
        else:
            exact = [i for i in same if equal(stages[i]["loss"], rnd["roundingLoss"])]
        if not same:
            problems.append(f"round {rnd['roundNumber']} ({rnd['action']['type']} {rnd['action']['candidate']}): "
                            "no pyRCV2 stage matches")
            continue
        hit = (exact or same)[0]
        if not exact:
            problems.append(f"round {rnd['roundNumber']}: loss {rnd['roundingLoss']} against {stages[hit]['loss']}")
        matched += 1
        start = hit
        if "-v" in sys.argv:
            print(f"round {rnd['roundNumber']} = stage {hit + 1} ({stages[hit]['comment']})")
    won = sorted(ours["electedInOrder"])
    theirs = sorted(name for name, state in stages[-1]["states"].items() if int(state) in ELECTED)
    print(f"{matched}/{len(ours['rounds'])} rounds match"
          + ("" if won == theirs else f"; winners differ: ours {won}, pyRCV2 {theirs}"))
    for problem in problems:
        print("  " + problem)
    sys.exit(0 if matched == len(ours["rounds"]) and not problems and won == theirs else 1)


if __name__ == "__main__":
    main()

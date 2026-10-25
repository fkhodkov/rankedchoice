"""Count random elections by Cambridge STV with this project and OpenSTV, and compare every stage.

usage: cambridge.py OPENSTV_DIR WORK_DIR [--elections N] [--jar PATH] [--python2 PATH]

OPENSTV_DIR is a checkout of OpenSTV 1.7 (https://github.com/Conservatory/openstv, commit 2a267a4),
run with Python 2. Writes N random elections (default 1000, seeded, so the same every time) to
WORK_DIR, counts each with this project (`--method cambridge --tiebreak declared`, JSON report) and
with OpenSTV's CambridgeSTV (`-t index`, its CSV report), and compares them as
CambridgeOpenStvReferenceTest does: this project's rounds, a surplus transfer each and the
exclusions decided on the same counts as one batch, must be OpenSTV's stages in order, the same
kind and the same candidates; at the start of each, every candidate's count and the
non-transferable total must equal OpenSTV's; and the winners must be the same.

The elections are made to exercise what Cambridge STV turns on: party slates, so that surpluses
move to running mates (the Cincinnati method's every n-th ballot, from the n-th), a long tail of
minor candidates for the batch exclusion of everyone under 50 votes, ballots in a fixed order,
some lines of weight 2 or 3, rankings of any length. 8-20 candidates, 3-9 seats, 600-3,000
ballots, at least 50 a seat plus a margin, as Cambridge STV requires.
"""
import argparse
import concurrent.futures
import csv
import io
import json
import os
import random
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
DEFAULT_JAR = os.path.join(HERE, "..", "..", "..", "cli", "target", "rankedchoice-cli-0.1.0-SNAPSHOT.jar")


def election(seed):
    """A random election, as BLT text."""
    rng = random.Random(seed)
    candidates = rng.randint(8, 20)
    seats = rng.randint(3, min(9, candidates - 3))
    voters = rng.randint(max(600, 60 * seats + 100), 3000)
    parties = rng.randint(2, 4)
    party_of = [rng.randrange(parties) for _ in range(candidates)]
    support = [rng.uniform(0.2, 1) for _ in range(parties)]
    # Within a party a few candidates draw most votes; many candidates draw almost none.
    pull = [rng.choice([rng.uniform(1, 6), rng.uniform(0.05, 0.4)]) for _ in range(candidates)]
    lines = [f"{candidates} {seats}"]
    cast = 0
    while cast < voters:
        party = rng.choices(range(parties), support)[0]
        pool = list(range(candidates))
        ranking = []
        length = rng.choice([1, 2, 3, 3, 4, 5, 6, candidates])
        while pool and len(ranking) < length:
            loyal = rng.random() < 0.8
            weights = [pull[c] * (4 if loyal and party_of[c] == party else 1) for c in pool]
            pick = rng.choices(pool, weights)[0]
            pool.remove(pick)
            ranking.append(pick + 1)
        weight = rng.choice([1, 1, 1, 1, 1, 1, 2, 3])
        cast += weight
        lines.append(f"{weight} " + " ".join(map(str, ranking)) + " 0")
    lines.append("0")
    lines += [f'"C{i + 1}"' for i in range(candidates)] + [f'"random {seed}"']
    return "\n".join(lines) + "\n"


def openstv(args, blt):
    """OpenSTV's stages: (kinds, names, counts per stage, non-transferable per stage, winners, ties broken)."""
    result = subprocess.run([args.python2, "runElection.py", "-r", "CsvReport", "-t", "index", "CambridgeSTV",
                             os.path.abspath(blt)], cwd=os.path.join(args.openstv, "openstv"),
                            capture_output=True, text=True, check=True)
    rows = [[cell.strip() for cell in row] for row in csv.reader(io.StringIO(result.stdout))]
    header = next(i for i, row in enumerate(rows) if row and row[0] == "Candidates")
    kinds, names = rows[header - 1], rows[header]
    stages = (len(names) - 2) // 2
    counts = [{} for _ in range(stages + 1)]
    exhausted = [0] * (stages + 1)
    winners = set()
    for row in rows[header + 1:]:
        if len(row) < 2 or row[0] == "Totals":
            continue
        for s in range(stages + 1):
            value = row[1] if s == 0 else row[1 + 2 * s]
            if row[0] == "Non-transferable":
                exhausted[s] = int(value) if value else 0
            elif value != "-":
                counts[s][row[0]] = int(value)
        if "Elected" in row:
            winners.add(row[0])
    stage_kinds = [kinds[2 + 2 * k] for k in range(stages)]
    stage_names = [names[2 + 2 * k].split("+") for k in range(stages)]
    # The CSV report doesn't say where a tie was broken; the text report does, once a tie.
    text = subprocess.run([args.python2, "runElection.py", "-r", "TextReport", "-t", "index", "CambridgeSTV",
                           os.path.abspath(blt)], cwd=os.path.join(args.openstv, "openstv"),
                          capture_output=True, text=True, check=True).stdout
    ties = " ".join(text.split()).count("were tied")
    return stage_kinds, stage_names, counts, exhausted, winners, ties


def ours(args, blt):
    """This project's steps: [(kind, [names], tallies at the start, exhausted at the start)], winners."""
    result = subprocess.run(["java", "-jar", args.jar, "-i", blt, "--method", "cambridge", "--tiebreak", "declared",
                             "--report-format", "json"], capture_output=True, text=True, check=True)
    report = json.loads(result.stdout)
    steps = []
    for round_ in report["rounds"]:
        action = round_["action"]
        surplus = action["type"] == "elected" and action.get("surplusTransferred", "0") != "0"
        exclusion = action["type"] == "eliminated"
        if exclusion and steps and steps[-1][0] == "exclusion" and steps[-1][2] == round_["tallies"]:
            steps[-1][1].append(action["candidate"])  # same decision: one batch
        elif surplus or exclusion:
            steps.append(("surplus" if surplus else "exclusion", [action["candidate"]], round_["tallies"],
                          round_["exhaustedVotes"]))
    return steps, set(report["electedInOrder"])


def check(n, args):
    """(n, problems, surpluses, batch exclusions of several, ties OpenSTV broke)."""
    blt = os.path.join(args.work, f"e{n}.blt")
    open(blt, "w").write(election(n))
    try:
        steps, winners = ours(args, blt)
        kinds, names, counts, exhausted, their_winners, ties = openstv(args, blt)
    except subprocess.CalledProcessError as e:
        return n, [f"a count failed: {' '.join(e.cmd[:4])}: {e.stderr.strip()[-200:]}"], 0, 0, 0
    problems = []
    if len(steps) != len(kinds):
        problems.append(f"{len(steps)} stages here, {len(kinds)} in OpenSTV")
    for k, (kind, chosen, tallies, gone) in enumerate(steps[:len(kinds)]):
        stage = f"stage {k + 2}"
        expected = "Surplus of" if kind == "surplus" else "Exclusion of"
        if not kinds[k].startswith(expected) or sorted(chosen) != sorted(names[k]):
            problems.append(f"{stage}: ours {kind} of {sorted(chosen)}, OpenSTV {kinds[k]} {sorted(names[k])}")
            break
        mine = {name: value for name, value in tallies.items()}
        theirs = {name: str(value) for name, value in counts[k].items()}
        differing = [name for name in mine if theirs.get(name) != mine[name]]
        if differing or gone != str(exhausted[k]):
            problems.append(f"{stage}: counts differ for {differing[:5]}; non-transferable ours {gone}, "
                            f"OpenSTV {exhausted[k]}")
            break
    if winners != their_winners:
        problems.append(f"winners: ours {sorted(winners)}, OpenSTV {sorted(their_winners)}")
    surpluses = sum(1 for kind, *_ in steps if kind == "surplus")
    batches = sum(1 for kind, chosen, *_ in steps if kind == "exclusion" and len(chosen) > 1)
    return n, problems, surpluses, batches, ties


def main():
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("openstv")
    parser.add_argument("work")
    parser.add_argument("--elections", type=int, default=1000)
    parser.add_argument("--jar", default=DEFAULT_JAR)
    parser.add_argument("--python2", default="python2")
    args = parser.parse_args()
    os.makedirs(args.work, exist_ok=True)
    with concurrent.futures.ThreadPoolExecutor(os.cpu_count()) as pool:
        results = sorted(pool.map(lambda n: check(n, args), range(1, args.elections + 1)))
    differ = [(n, problems) for n, problems, *_ in results if problems]
    for n, problems in differ:
        print(f"e{n}: " + "\n    ".join(problems))
    surpluses = sum(r[2] for r in results)
    batches = sum(r[3] for r in results)
    ties = sum(r[4] for r in results)
    tied = sum(1 for r in results if r[4])
    print(f"cambridge: {args.elections - len(differ)} of {args.elections} elections match OpenSTV in every stage "
          f"({surpluses} surplus transfers, {batches} exclusions of several candidates at once, {ties} ties broken "
          f"in {tied} elections) and the winners; {len(differ)} differ")
    sys.exit(1 if differ else 0)


if __name__ == "__main__":
    main()

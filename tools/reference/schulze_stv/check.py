"""Check this project's Schulze STV against the figures Markus Schulze publishes for Tideman's
elections (see README.md).

usage: check.py SCHULZE3_DIR [--jar PATH] [--program PATH] [--timeout SECONDS] [ELECTION ...]

For every election A*.dat in SCHULZE3_DIR with a published A*_stv.dat (or just the ELECTIONs
named, e.g. A53), converts it to BLT (tideman.py), counts it with the project's CLI jar, and
compares every support N[committee; outsider] with the published value, which is given to 6
decimals. With --program, also runs that build of Schulze's program (singl01g.cpp) on the
election and compares the winning committees.

Prints one line per election and a summary; exits non-zero if any support differs.
"""
import argparse
import glob
import json
import os
import pathlib
import subprocess
import sys
import tempfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import tideman  # noqa: E402

DEFAULT_JAR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "..", "cli", "target",
                           "rankedchoice-cli-0.1.0-SNAPSHOT.jar")
TOLERANCE = 5e-7 + 1e-9  # half the published 6th decimal, plus slack for float parsing


def published_supports(path, seats):
    """{(sorted committee, outsider): value} from an A*_stv.dat file."""
    supports = {}
    with open(path) as source:
        for line in source:
            fields = line.split()
            if len(fields) == 2 * (seats + 1) + 1 and fields[0].isdigit():
                members, values = fields[1:seats + 2], fields[seats + 2:]
                for member, value in zip(members, values):
                    supports[(tuple(sorted(m for m in members if m != member)), member)] = float(value)
    return supports


def program_winners(program, dat, timeout):
    """Schulze's program's winning committees for one election, or a string saying why not."""
    with tempfile.TemporaryDirectory() as directory:
        name = os.path.basename(dat)
        os.symlink(os.path.abspath(dat), os.path.join(directory, name))
        try:
            subprocess.run([os.path.abspath(program)], input=name + "\n", cwd=directory, capture_output=True,
                           text=True, timeout=timeout)
        except subprocess.TimeoutExpired:
            return f"timed out after {timeout} s"
        output = os.path.join(directory, "output.txt")
        if not os.path.exists(output):
            return "no output"
        lines = pathlib.Path(output).read_text().splitlines()
    committees = [sorted(lines[i + 1].split()) for i, line in enumerate(lines) if line.startswith("winning set:")]
    return committees or "no winning set"


def main():
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("directory")
    parser.add_argument("elections", nargs="*")
    parser.add_argument("--jar", default=DEFAULT_JAR)
    parser.add_argument("--program", help="a build of Schulze's singl01g.cpp, to compare winners with")
    parser.add_argument("--timeout", type=int, default=600, help="per election and program run")
    args = parser.parse_args()

    names = args.elections or sorted(os.path.basename(p)[:-len("_stv.dat")]
                                     for p in glob.glob(os.path.join(args.directory, "A*_stv.dat")))
    totals = {"supports": 0, "differ": 0, "agree": 0, "disagree": [], "skipped": []}
    with tempfile.TemporaryDirectory() as work:
        for name in names:
            dat = os.path.join(args.directory, name + ".dat")
            seats, candidates, voters = tideman.read(dat)
            blt = os.path.join(work, name + ".blt")
            tideman.write_blt(blt, seats, candidates, voters, name)
            try:
                result = subprocess.run(["java", "-jar", args.jar, "--input", blt, "--method", "schulze-stv",
                                         "--report-format", "json"], capture_output=True, text=True,
                                        timeout=args.timeout)
            except subprocess.TimeoutExpired:
                print(f"{name}: timed out after {args.timeout} s")
                totals["skipped"].append(name)
                continue
            if result.returncode != 0:
                print(f"{name}: {result.stderr.strip().splitlines()[-1]}")
                totals["skipped"].append(name)
                continue
            report = json.loads(result.stdout)
            ours = {(tuple(sorted(s["committee"])), s["against"]): float(s["support"]) for s in report["supports"]}
            published = published_supports(os.path.join(args.directory, name + "_stv.dat"), seats)
            differ = [key for key, value in published.items() if key not in ours or abs(ours[key] - value) > TOLERANCE]
            worst = max((abs(ours[k] - v) for k, v in published.items() if k in ours), default=0.0)
            totals["supports"] += len(published)
            totals["differ"] += len(differ)
            winners = [sorted(w) for w in report["winners"]]
            line = (f"{name}: {len(published)} supports, {len(differ)} differ (largest difference {worst:.1e}); "
                    f"winners {' | '.join(' '.join(w) for w in winners)}")
            if len(report["potentialWinners"]) > 1:
                line += f" (from {len(report['potentialWinners'])} potential)"
            if args.program:
                theirs = program_winners(args.program, dat, args.timeout)
                if isinstance(theirs, str):
                    line += f"; program: {theirs}"
                elif sorted(theirs) == sorted(winners):
                    line += "; program agrees"
                    totals["agree"] += 1
                else:
                    line += f"; program elects {' | '.join(' '.join(w) for w in theirs)}"
                    totals["disagree"].append(name)
            print(line, flush=True)

    print(f"\n{totals['supports']} supports compared, {totals['differ']} differ")
    if totals["skipped"]:
        print(f"not counted: {', '.join(totals['skipped'])}")
    if args.program:
        print(f"winners: program agrees on {totals['agree']}, elects a different committee on "
              f"{len(totals['disagree'])}" + (f" ({', '.join(totals['disagree'])})" if totals["disagree"] else ""))
    sys.exit(1 if totals["differ"] else 0)


if __name__ == "__main__":
    main()

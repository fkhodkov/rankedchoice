"""Refresh every checker's results, then the results page and VALIDATION.md's Official counts table.

usage: refresh_results.py DATA_ROOT [--only NAME ...] [--no-java] [--dry-run] [--compare] [--jar PATH]

DATA_ROOT holds a directory per checker, named as its results file is (alameda, sanfrancisco, nyc, alaska, maine,
minneapolis, portland, eastpointe, albany, aec, nsw, cambridge, scotland). A checker runs only if its directory is
there, and is reported as skipped otherwise; most fetch what they need into an empty one, while aec, nsw and portland
need their data saved there first (see each tool's README), and scotland needs scot-elex/, official.json,
official-stages.json and reports/ in it. NSW needs openpyxl, and is skipped without it: the checkers run with the
Python running this, so run it with one that has openpyxl (a virtual environment's, say) to include NSW.

Each checker writes results/<name>.json with --results; a checker that finds differences still writes its results,
and the refresh goes on. Then, unless --no-java or --only, the ACT's and Ireland's fixture tests write act.json and
ireland.json (mvn test -Dresults.dir=...), and last the page (results_page.py) and the table (results_page.py
--validation write) are rebuilt. --dry-run prints what would run, and runs nothing.

--compare checks that a change leaves every result as it was, and changes nothing: the checkers run on a copy of
DATA_ROOT made of links to its files, less the BLT files the checkers cache next to their records (so the records are
converted afresh, with the converter as it is now), write their results to a temporary directory, and each is compared
contest by contest with the committed one. The page and the table aren't rebuilt. It exits 1 if any result differs or
a checker that ran wrote none.
"""
import argparse
import importlib.util
import json
import os
import subprocess
import sys
import tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.join(HERE, "..", "..")
RESULTS = os.path.join(HERE, "results")
DEFAULT_JAR = os.path.join(ROOT, "target", "rankedchoice-0.1.0-SNAPSHOT.jar")
SINGLE = ["alameda", "sanfrancisco", "nyc", "alaska", "maine", "minneapolis", "portland", "eastpointe", "albany",
          "aec", "nsw", "cambridge"]
JAVA_TESTS = "ActOfficialDistributionTest,Irish2002GeneralElectionFixtureTest"


def steps(data_root, only=None, java=True, jar=DEFAULT_JAR, python=sys.executable, results_dir=RESULTS, page=True):
    """[(name, command, why it's skipped or None)], in the order they run: each checker writes results/<name>.json; with
    page, the results page and VALIDATION.md's table follow."""
    plan = []
    for name in SINGLE + ["scotland"]:
        if only and name not in only:
            continue
        directory = os.path.join(data_root, name)
        skip = None if os.path.isdir(directory) else f"no {directory}"
        if name == "nsw" and not skip and importlib.util.find_spec("openpyxl") is None:
            skip = "openpyxl isn't installed for this Python"
        if name == "scotland":
            scot_elex = os.path.join(directory, "scot-elex")
            for script, official, results in (("check.py", "official.json", "scotland"),
                                              ("stage_report.py", "reports", "scotland-stage-reports"),
                                              ("stages.py", "official-stages.json", "scotland-wikipedia")):
                needed = os.path.join(directory, official)
                why = skip or (None if os.path.exists(needed) and os.path.isdir(scot_elex)
                               else f"no {scot_elex} or {needed}")
                plan.append((results, [python, os.path.join(HERE, "scotland", script), scot_elex, needed, "--jar", jar,
                                       "--results", os.path.join(results_dir, results + ".json")], why))
            continue
        plan.append((name, [python, os.path.join(HERE, name, "check.py"), directory, "--jar", jar,
                            "--results", os.path.join(results_dir, name + ".json")], skip))
    if java and not only:
        plan.append(("act, ireland", ["mvn", "-q", "test", f"-Dtest={JAVA_TESTS}", "-DskipPythonTests",
                                      f"-Dresults.dir={results_dir}"], None))
    if page:
        plan.append(("results page", [python, os.path.join(HERE, "results_page.py")], None))
        plan.append(("VALIDATION.md table", [python, os.path.join(HERE, "results_page.py"), "--validation", "write"],
                     None))
    return plan


def shadow(data_root, copy):
    """Fills COPY with a directory per checker directory of DATA_ROOT, linking to everything in it but its BLT files
    (each checker's cache of its converted records; Scotland's ballots are in scot-elex/, linked whole)."""
    for name in os.listdir(data_root):
        source = os.path.join(data_root, name)
        if not os.path.isdir(source):
            continue
        os.makedirs(os.path.join(copy, name))
        for entry in os.listdir(source):
            if not entry.endswith(".blt"):
                os.symlink(os.path.realpath(os.path.join(source, entry)), os.path.join(copy, name, entry))


def differences(committed, fresh):
    """The lines saying how a checker's fresh results differ from its committed ones, contest by contest: none if the
    same contests have the same results (the date aside)."""
    old = {c["contest"]: c for c in committed["contests"]}
    new = {c["contest"]: c for c in fresh["contests"]}
    lines = []
    for contest in list(old) + [c for c in new if c not in old]:
        if contest not in new:
            lines.append(f"  {contest}: no longer checked (was: {old[contest].get('line', old[contest])})")
        elif contest not in old:
            lines.append(f"  {contest}: new: {new[contest].get('line', new[contest])}")
        elif old[contest] != new[contest]:
            was, now = old[contest], new[contest]
            changed = sorted(k for k in set(was) | set(now) if was.get(k) != now.get(k))
            lines.append(f"  {contest}: {', '.join(changed)} changed")
            if "line" in changed:
                lines += [f"    was: {old[contest]['line']}", f"    now: {new[contest]['line']}"]
    return lines


def compare(results_dir):
    """[(results name, how it differs from the committed one)] for every results file in RESULTS_DIR."""
    found = []
    for file in sorted(os.listdir(results_dir)):
        committed = os.path.join(RESULTS, file)
        fresh = json.load(open(os.path.join(results_dir, file), encoding="utf-8"))
        if not os.path.exists(committed):
            found.append((file[:-5], [f"  no committed {file}"]))
            continue
        found.append((file[:-5], differences(json.load(open(committed, encoding="utf-8")), fresh)))
    return found


def run(plan, dry_run, results_dir=None):
    """Runs the plan and prints a summary; with RESULTS_DIR, compares the results written there with the committed
    ones, and returns 1 if any differs or a checker that ran wrote none."""
    summary = []
    for name, command, skip in plan:
        if skip:
            summary.append(f"{name}: skipped ({skip})")
            continue
        print(f"== {name}: {' '.join(command)}", flush=True)
        if dry_run:
            continue
        try:
            code = subprocess.run(command, cwd=ROOT).returncode
        except FileNotFoundError:
            summary.append(f"{name}: not run ({command[0]} isn't installed)")
            continue
        summary.append(f"{name}: {'done' if code == 0 else f'exit {code} (its results are still written)'}")
    print("\n".join(summary))
    if results_dir is None or dry_run:
        return 0
    ran = [name for name, _, skip in plan if not skip]
    written = {file[:-5] for file in os.listdir(results_dir)}
    status = 0
    print("\nCompared with the committed results:")
    for name, lines in compare(results_dir):
        contests = sum(not line.startswith("    ") for line in lines)
        print(f"{name}: {f'{contests} contests DIFFER' if lines else 'identical'}")
        if lines:
            print("\n".join(lines))
            status = 1
    for name in ran:
        if name == "act, ireland":
            missing = [n for n in ("act", "ireland") if n not in written]
        else:
            missing = [name] if name not in written else []
        for n in missing:
            print(f"{n}: NO RESULTS written")
            status = 1
    return status



def main():
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("data_root")
    parser.add_argument("--only", nargs="+", help="refresh only these checkers' results (then the page and table)")
    parser.add_argument("--no-java", action="store_true", help="leave act.json and ireland.json as they are")
    parser.add_argument("--dry-run", action="store_true", help="print what would run")
    parser.add_argument("--compare", action="store_true",
                        help="rerun on freshly converted records, compare with the committed results, change nothing")
    parser.add_argument("--jar", default=DEFAULT_JAR)
    args = parser.parse_args()
    if args.compare:
        with tempfile.TemporaryDirectory(prefix="refresh-compare-") as work:
            copy, results_dir = os.path.join(work, "data"), os.path.join(work, "results")
            os.makedirs(results_dir)
            shadow(args.data_root, copy)
            plan = steps(copy, args.only, not args.no_java, args.jar, results_dir=results_dir, page=False)
            sys.exit(run(plan, args.dry_run, results_dir))
    run(steps(args.data_root, args.only, not args.no_java, args.jar), args.dry_run)

if __name__ == "__main__":
    main()

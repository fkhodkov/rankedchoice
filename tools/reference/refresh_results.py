"""Refresh every checker's results, then the results page and VALIDATION.md's Official counts table.

usage: refresh_results.py DATA_ROOT [--only NAME ...] [--no-java] [--dry-run] [--jar PATH]

DATA_ROOT holds a directory per checker, named as its results file is (alameda, sanfrancisco, nyc, alaska, maine,
minneapolis, portland, eastpointe, albany, aec, nsw, cambridge, scotland). A checker runs only if its directory is
there, and is reported as skipped otherwise; most fetch what they need into an empty one, while aec, nsw and portland
need their data saved there first (see each tool's README), and scotland needs scot-elex/, official.json,
official-stages.json and reports/ in it. NSW needs openpyxl, and is skipped without it.

Each checker writes results/<name>.json with --results; a checker that finds differences still writes its results,
and the refresh goes on. Then, unless --no-java or --only, the ACT's and Ireland's fixture tests write act.json and
ireland.json (mvn test -Dresults.dir=...), and last the page (results_page.py) and the table (results_page.py
--validation write) are rebuilt. --dry-run prints what would run, and runs nothing.
"""
import argparse
import importlib.util
import os
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.join(HERE, "..", "..")
RESULTS = os.path.join(HERE, "results")
DEFAULT_JAR = os.path.join(ROOT, "target", "rankedchoice-0.1.0-SNAPSHOT.jar")
SINGLE = ["alameda", "sanfrancisco", "nyc", "alaska", "maine", "minneapolis", "portland", "eastpointe", "albany",
          "aec", "nsw", "cambridge"]
JAVA_TESTS = "ActOfficialDistributionTest,Irish2002GeneralElectionFixtureTest"


def steps(data_root, only=None, java=True, jar=DEFAULT_JAR, python=sys.executable):
    """[(name, command, why it's skipped or None)], in the order they run."""
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
                                       "--results", os.path.join(RESULTS, results + ".json")], why))
            continue
        plan.append((name, [python, os.path.join(HERE, name, "check.py"), directory, "--jar", jar,
                            "--results", os.path.join(RESULTS, name + ".json")], skip))
    if java and not only:
        plan.append(("act, ireland", ["mvn", "-q", "test", f"-Dtest={JAVA_TESTS}", "-DskipPythonTests",
                                      f"-Dresults.dir={RESULTS}"], None))
    plan.append(("results page", [python, os.path.join(HERE, "results_page.py")], None))
    plan.append(("VALIDATION.md table", [python, os.path.join(HERE, "results_page.py"), "--validation", "write"], None))
    return plan


def main():
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("data_root")
    parser.add_argument("--only", nargs="+", help="refresh only these checkers' results (then the page and table)")
    parser.add_argument("--no-java", action="store_true", help="leave act.json and ireland.json as they are")
    parser.add_argument("--dry-run", action="store_true", help="print what would run")
    parser.add_argument("--jar", default=DEFAULT_JAR)
    args = parser.parse_args()
    summary = []
    for name, command, skip in steps(args.data_root, args.only, not args.no_java, args.jar):
        if skip:
            summary.append(f"{name}: skipped ({skip})")
            continue
        print(f"== {name}: {' '.join(command)}", flush=True)
        if args.dry_run:
            continue
        try:
            code = subprocess.run(command, cwd=ROOT).returncode
        except FileNotFoundError:
            summary.append(f"{name}: not run ({command[0]} isn't installed)")
            continue
        summary.append(f"{name}: {'done' if code == 0 else f'exit {code} (its results are still written)'}")
    print("\n".join(summary))


if __name__ == "__main__":
    main()

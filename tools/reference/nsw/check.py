"""Check the nsw-lg ruleset against the NSW Electoral Commission's official distributions of preferences.

usage: check.py DATA_DIR [--jar PATH] [--workers N] [NAME ...]

DATA_DIR holds download.py's files: NAME.zip (the ballot papers) and NAME.xlsx (the full
distribution of preferences) for each councillor count. Each is converted (nsw_to_blt.py) and
counted with the project's `nsw-lg` ruleset, and every round matched to an official count, in
order: every candidate's votes, and the votes no longer with a candidate (exhausted plus lost,
which the Commission reports together as "Votes Lost"), to the vote. The councillors elected are
compared too. A count that reaches a tie for exclusion decided by lot, where the Commission's
draw and this project's declared order part ways, is counted apart. Prints a line for each count
that differs and a total. Needs openpyxl.
"""
import argparse
import concurrent.futures
import glob
import json
import os
import re
import subprocess
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import nsw_to_blt  # noqa: E402
sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
from rcvcheck import record, write_results  # noqa: E402

DEFAULT_JAR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "..", "target",
                           "rankedchoice-0.1.0-SNAPSHOT.jar")

def official(dop_path):
    """[(count, {candidate: votes}, votes lost)], the councillors elected, and who was excluded, in order."""
    rows = [list(r) for r in nsw_to_blt.sheet_of(dop_path).iter_rows(values_only=True)]
    header = next(r for r in rows if "Count" in r)
    # Each candidate's columns are papers, then votes.
    columns = {name: i + 1 for i, _, name in nsw_to_blt.candidate_columns(header)}
    lost = header.index("Votes Lost")
    counts, elected, excluded = [], set(), []
    for row in rows:
        if len(row) > 3 and row[3] == "E" and row[2] in columns:
            excluded.append(row[2])
        if len(row) > 3 and row[3] == "Progressive Total":
            counts.append((row[1], {name: row[i] or 0 for name, i in columns.items()}, row[lost] or 0))
            for name in columns:
                if re.search(re.escape(name) + r" Elected", str(row[-1] or "")):
                    elected.add(name)
    return counts, elected, excluded

def check(name, data, jar):
    """(name, what's wrong or None, its result for the results page, None where there are no counts)."""
    blt = os.path.join(data, name + ".blt")
    try:
        if not os.path.exists(blt):
            text = nsw_to_blt.convert(os.path.join(data, name + ".zip"), os.path.join(data, name + ".xlsx"))
            open(blt, "w").write(text)
        counts, elected, excluded = official(os.path.join(data, name + ".xlsx"))
    except ValueError as e:
        return name, f"NODATA: {e}", None
    except Exception as e:  # noqa: BLE001 -- report and carry on
        return name, f"can't read: {e!r}", record(name, False, f"{name}: can't read: {e!r}", winners_same=False)
    result = subprocess.run(["java", "-jar", jar, "-i", blt, "--ruleset", "nsw-lg", "--report-format", "json"],
                            capture_output=True, text=True)
    if result.returncode:
        why = f"count failed: {result.stderr.strip()[-200:]}"
        return name, why, record(name, False, f"{name}: {why}", winners_same=False)
    report = json.loads(result.stdout)
    start, unmatched = 0, []
    for rnd in report["rounds"]:
        tallies = {n: float(v) for n, v in rnd["tallies"].items()}
        set_aside = float(rnd["exhaustedVotes"]) + float(rnd["roundingLoss"])
        hit = next((i for i in range(start, len(counts))
                    if all(counts[i][1].get(n) == v for n, v in tallies.items()) and counts[i][2] == set_aside), None)
        if hit is None:
            unmatched.append(rnd["roundNumber"])
        else:
            start = hit
    ours = set(report["electedInOrder"])
    rounds = len(report["rounds"])

    def result_of(ok, why):
        return record(name, ok, f"{name}: {why or 'every round matches an official count; the same elected'}",
                      matched=rounds - len(unmatched), rounds=rounds, winners_same=ours == elected)
    # A tie for exclusion that previous counts don't settle is decided by lot; this project uses
    # declared order instead, so a count that diverges there is counted apart.
    exclusions = [r for r in report["rounds"] if r["action"]["type"] == "eliminated"]
    for ours_excluded, theirs in zip(exclusions, excluded):
        candidate = ours_excluded["action"]["candidate"]
        if candidate != theirs:
            tallies = ours_excluded["tallies"]
            if theirs in tallies and tallies[theirs] == tallies[candidate]:
                why = f"LOT: {candidate} and {theirs} tied on {tallies[candidate]} votes, officially decided by lot"
                return name, why, result_of(ours == elected, why)
            break
    problems = []
    if unmatched:
        problems.append(f"{len(report['rounds']) - len(unmatched)} of {len(report['rounds'])} rounds match an "
                        f"official count; first unmatched round {unmatched[0]}")
    if ours != elected:
        problems.append(f"elects {sorted(ours - elected)}, officially {sorted(elected - ours)}")
    why = "; ".join(problems) or None
    return name, why, result_of(why is None, why)

def main():
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("data")
    parser.add_argument("names", nargs="*")
    parser.add_argument("--jar", default=DEFAULT_JAR)
    parser.add_argument("--workers", type=int, default=os.cpu_count())
    parser.add_argument("--results", help="write each count's result to this JSON file, for the results page")
    args = parser.parse_args()
    names = args.names or sorted(os.path.basename(p)[:-5] for p in glob.glob(os.path.join(args.data, "*.xlsx"))
                                 if os.path.exists(p[:-5] + ".zip"))
    with concurrent.futures.ThreadPoolExecutor(args.workers) as pool:
        checked = sorted(pool.map(lambda n: check(n, args.data, args.jar), names), key=lambda r: r[0])
    results = [(n, why) for n, why, _ in checked]
    lots = [(n, why) for n, why in results if why and why.startswith("LOT")]
    missing = [(n, why) for n, why in results if why and why.startswith("NODATA")]
    differ = [(n, why) for n, why in results if why and not why.startswith(("LOT", "NODATA"))]
    if args.results:
        write_results(args.results, "nsw", [r for _, _, r in checked if r is not None])
    for n, why in differ + lots + missing:
        print(f"{n}: {why}")
    checked = len(results) - len(missing)
    print(f"{checked - len(differ) - len(lots)} of {checked} counts match every official count and elect the same "
          f"councillors; {len(lots)} reach a tie the Commission decided by lot; {len(differ)} differ"
          + (f" ({len(missing)} published without counts)" if missing else ""))
    sys.exit(1 if differ else 0)

if __name__ == "__main__":
    main()

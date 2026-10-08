"""Check the senate ruleset against the AEC's official Senate distributions of preferences.

usage: check.py DATA_DIR [--jar PATH] [EVENT-STATE ...]

DATA_DIR holds the AEC's downloads for each Senate election (event 20499 is 2016, 24310 is 2019,
27966 is 2022, 31496 is 2025): aec-senate-formalpreferences-<event>-<state>.zip and
SenateDopDownload-<event>.zip. For each state, converts the ballots (aec_to_blt.py), counts them
with the project's `senate` ruleset, and matches every round to an official count, in order:
every candidate's votes, the exhausted votes and the loss by fraction ("Gain/Loss"), to the
vote. Also compares the senators elected, in order. Prints a line per state, with the time the
count took, and exits non-zero if any differs.
"""
import argparse
import collections
import csv
import glob
import io
import json
import os
import re
import subprocess
import sys
import time
import zipfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
from rcvcheck import record, stale, write_results  # noqa: E402

DEFAULT_JAR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "..", "target",
                           "rankedchoice-0.1.0-SNAPSHOT.jar")


def key(name):
    return re.sub(r"[^a-z]", "", name.lower())


def official(dop_zip, state):
    """[(count, {candidate key: votes}, exhausted, loss)], seats, [elected keys in order]."""
    with zipfile.ZipFile(dop_zip) as z:
        name = next(n for n in z.namelist() if n.endswith(f"-{state}.csv"))
        rows = list(csv.DictReader(io.TextIOWrapper(z.open(name), encoding="utf-8-sig")))
    counts = collections.OrderedDict()
    elected = {}
    for row in rows:
        row = {k.strip(): (v or "").strip() for k, v in row.items()}
        count = counts.setdefault(int(row["Count"]), {"votes": {}, "exhausted": 0, "loss": 0})
        votes = int(row["ProgressiveVoteTotal"])
        if row["Surname"] == "Exhausted":
            count["exhausted"] = votes
        elif row["Surname"] == "Gain/Loss":
            count["loss"] = votes            # votes lost to rounding, as a positive total
        else:
            candidate = key(row["Surname"] + row["GivenNm"])
            count["votes"][candidate] = votes
            if row["Order Elected"] not in ("", "0"):
                elected[candidate] = int(row["Order Elected"])
    seats = int(rows[0]["No Of Vacancies"])
    return list(counts.items()), seats, [c for c, _ in sorted(elected.items(), key=lambda item: item[1])]


def check(data, event, state, jar):
    prefs = os.path.join(data, f"aec-senate-formalpreferences-{event}-{state}.zip")
    blt = os.path.join(data, f"{event}-{state}.blt")
    counts, seats, elected = official(os.path.join(data, f"SenateDopDownload-{event}.zip"), state)
    if stale(blt, prefs, os.path.join(data, f"SenateDopDownload-{event}.zip")):
        subprocess.run([sys.executable, os.path.join(os.path.dirname(os.path.abspath(__file__)), "aec_to_blt.py"),
                        prefs, blt, "--seats", str(seats), "--dop",
                        os.path.join(data, f"SenateDopDownload-{event}.zip")],
                       check=True)
    started = time.monotonic()
    result = subprocess.run(["java", "-Xmx6g", "-jar", jar, "-i", blt, "--ruleset", "senate", "--report-format",
                             "json"], capture_output=True, text=True)
    seconds = time.monotonic() - started
    if result.returncode:
        return record(f"{event}-{state}", False, f"{event}-{state}: count failed: {result.stderr.strip()[-200:]}")
    report = json.loads(result.stdout)
    start, unmatched = 0, []
    for rnd in report["rounds"]:
        tallies = {key(n): float(v) for n, v in rnd["tallies"].items()}
        hit = None
        for i in range(start, len(counts)):
            number, figures = counts[i]
            if (all(figures["votes"].get(k) == v for k, v in tallies.items())
                    and figures["exhausted"] == float(rnd["exhaustedVotes"])
                    and figures["loss"] == float(rnd["roundingLoss"])):
                hit = i
                break
        if hit is None:
            unmatched.append(rnd["roundNumber"])
        else:
            start = hit
    ours = [key(n) for n in report["electedInOrder"]]
    ok = not unmatched and ours == elected
    line = (f"{event}-{state}: {len(report['rounds'])} rounds, {len(report['rounds']) - len(unmatched)} match an "
            f"official count (of {len(counts)}); elected {'the same, in order' if ours == elected else 'DIFFERENTLY'}")
    if unmatched:
        line += f"; first unmatched round {unmatched[0]}"
    result = record(f"{event}-{state}", ok, line, matched=len(report["rounds"]) - len(unmatched),
                    rounds=len(report["rounds"]), winners_same=ours == elected)
    result["seconds"] = seconds    # printed, not written to the results file: it changes from run to run
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("data")
    parser.add_argument("elections", nargs="*")
    parser.add_argument("--jar", default=DEFAULT_JAR)
    parser.add_argument("--results", help="write each election's result to this JSON file, for the results page")
    args = parser.parse_args()
    elections = args.elections or sorted(
        re.search(r"(\d+-[A-Z]+)\.zip$", p).group(1)
        for p in glob.glob(os.path.join(args.data, "aec-senate-formalpreferences-*.zip")))
    failed, results = 0, []
    for election in elections:
        event, state = election.split("-")
        result = check(args.data, event, state, args.jar)
        failed += not result["ok"]
        print(f'{result["line"]} ({result.pop("seconds", 0):.0f}s to count)', flush=True)
        results.append(result)
    if args.results:
        write_results(args.results, "aec", results)
    sys.exit(1 if failed else 0)


if __name__ == "__main__":
    main()

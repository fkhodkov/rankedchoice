"""Count every Scottish council election in scot-elex and compare the winners with the official ones.

usage: check.py SCOT_ELEX_DIR OFFICIAL.json [--jar PATH] [--workers N]

SCOT_ELEX_DIR is a checkout of https://github.com/mggg/scot-elex; OFFICIAL.json comes from
official.py. Every ward's ballots (blt_files/*/*.blt) are converted (scot_elex.py) and counted
with the project's `scottish` ruleset. Each is then matched to the official result for its year
with the same candidates (allowing one missing from a source, and names spelled differently:
Dave/David, typos, truncated surnames), preferring EMB's official 2022 record, and the winners
are compared. Prints the totals and every ward that disagrees or finds no match.
"""
import argparse
import concurrent.futures
import difflib
import glob
import json
import os
import pathlib
import re
import subprocess
import sys
import tempfile
import unicodedata

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import scot_elex  # noqa: E402
sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
from rcvcheck import record, write_results  # noqa: E402

DEFAULT_JAR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "..", "cli", "target",
                           "rankedchoice-cli-0.1.0-SNAPSHOT.jar")


def count(path, jar, work):
    key = os.path.basename(path)[:-4]
    try:
        text, names, seats, ward = scot_elex.convert(pathlib.Path(path).read_text(encoding="utf-8", errors="replace"))
    except (ValueError, StopIteration) as e:
        return key, {"error": f"can't read the file: {e}"}
    blt = os.path.join(work, key + ".blt")
    pathlib.Path(blt).write_text(text)
    result = subprocess.run(["java", "-jar", jar, "-i", blt, "--ruleset", "scottish", "--report-format", "json"],
                            capture_output=True, text=True)
    if result.returncode:
        return key, {"error": result.stderr.strip()[-200:]}
    return key, {"names": names, "seats": seats, "ward": ward, "elected": json.loads(result.stdout)["electedInOrder"]}


def plain(name):
    name = re.sub(r"[(].*?[)]", " ", name)
    name = unicodedata.normalize("NFKD", name).encode("ascii", "ignore").decode().lower()
    return " ".join(re.findall(r"[a-z]+", name))


def similar(a, b):
    a, b = plain(a), plain(b)
    return bool(a and b) and (a.split()[-1] == b.split()[-1] or difflib.SequenceMatcher(None, a, b).ratio() >= 0.8)


def official_ward(ours, official, year):
    """The official result with the same candidates, or a string saying why there's none."""
    def overlap(entry):
        return sum(1 for a in ours["names"] if any(similar(a, c["name"]) for c in entry["candidates"]))
    scored = [(overlap(e), e) for e in official
              if e["year"] == year and abs(len(e["candidates"]) - len(ours["names"])) <= 1]
    top = max((score for score, _ in scored), default=0)
    best = [e for score, e in scored if score == top] if top >= len(ours["names"]) - 1 else []
    if not best:
        return "no official result with these candidates"
    if len(best) > len({e["source"] for e in best}):
        return "several official results match: " + ", ".join(e["ward"] for e in best)
    return min(best, key=lambda e: e["source"] != "emb")   # EMB's official record first


def officially_elected(ours, entry):
    """Our names for the officially elected: each paired with the most similar official name."""
    pairs = sorted(((difflib.SequenceMatcher(None, plain(a), plain(c["name"])).ratio(), a, i)
                    for a in ours["names"] for i, c in enumerate(entry["candidates"])), reverse=True)
    paired, used = {}, set()
    for _, a, i in pairs:
        if a not in paired and i not in used:
            paired[a] = i
            used.add(i)
    return {a for a, i in paired.items() if entry["candidates"][i]["elected"]}


def main():
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("scot_elex")
    parser.add_argument("official")
    parser.add_argument("--jar", default=DEFAULT_JAR)
    parser.add_argument("--workers", type=int, default=os.cpu_count())
    parser.add_argument("--results", help="write each ward's result to this JSON file, for the results page")
    args = parser.parse_args()

    official = json.loads(pathlib.Path(args.official).read_text())
    files = sorted(glob.glob(os.path.join(args.scot_elex, "blt_files", "*", "*.blt")))
    with tempfile.TemporaryDirectory() as work, concurrent.futures.ThreadPoolExecutor(args.workers) as pool:
        counts = dict(pool.map(lambda path: count(path, args.jar, work), files))

    agree, disagree, unmatched, results = 0, [], [], []
    for key, ours in sorted(counts.items()):
        if "error" in ours:
            unmatched.append((key, ours["error"]))
            results.append(record(key, False, f"{key}: not compared: {ours['error']}", winners_same=False))
            continue
        entry = official_ward(ours, official, re.search(r"(20\d\d)", key).group(1))
        if isinstance(entry, str):
            unmatched.append((key, entry))
            results.append(record(key, False, f"{key}: not compared: {entry}", winners_same=False))
            continue
        theirs = officially_elected(ours, entry)
        if len(theirs) != ours["seats"]:
            unmatched.append((key, f"{entry['ward']} ({entry['source']}) elects {len(theirs)}, "
                                   f"the ballot file has {ours['seats']} seats"))
            results.append(record(key, False, f"{key}: not compared: {unmatched[-1][1]}", winners_same=False))
        elif theirs == set(ours["elected"]):
            agree += 1
            results.append(record(key, True, f"{key} ({entry['ward']}, {entry['source']}): the same elected",
                                  winners_same=True))
        else:
            disagree.append((key, entry["ward"], entry["source"], sorted(set(ours["elected"]) - theirs),
                             sorted(theirs - set(ours["elected"]))))
            results.append(record(key, False, f"{key} ({entry['ward']}, {entry['source']}): we elect "
                                              f"{disagree[-1][3]}, officially {disagree[-1][4]}", winners_same=False))
    if args.results:
        write_results(args.results, "scotland", results)

    print(f"{len(counts)} wards counted: {agree} elect the official winners, {len(disagree)} don't, "
          f"{len(unmatched)} not compared")
    for key, ward, source, ours_only, theirs_only in disagree:
        print(f"  DIFFERS {key} ({ward}, {source}): we elect {ours_only}, officially {theirs_only}")
    for key, why in unmatched:
        print(f"  not compared: {key}: {why}")


if __name__ == "__main__":
    main()

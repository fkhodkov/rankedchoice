"""Check every stage of the Scottish wards' counts against the stages Wikipedia's result boxes give.

usage: stages.py SCOT_ELEX_DIR OFFICIAL.json [--jar PATH] [--workers N]

OFFICIAL.json comes from official.py, whose Wikipedia entries carry each candidate's figure at
every count, as transcribed from the councils' declarations. Each ward in scot-elex is counted
with the `scottish` ruleset and matched to its Wikipedia result box as check.py matches wards;
then every count in the box must be one of this count's rounds, in order: every candidate the
box gives a figure for, equal to this project's to the precision the box shows (whole votes,
or 1 or 2 decimal places), rounded or truncated, since editors do both. A ward whose counts are
each off by at most one figure is put down to transcription (a counting difference carries on
into later counts and moves several figures); one with a count further off is listed.
"""
import argparse
import concurrent.futures
import difflib
import glob
import json
import os
import re
import subprocess
import sys
import tempfile
from decimal import ROUND_DOWN, ROUND_HALF_UP, Decimal

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import check  # noqa: E402
import scot_elex  # noqa: E402


def count(path, jar, work):
    key = os.path.basename(path)[:-4]
    try:
        text, names, seats, ward = scot_elex.convert(open(path, encoding="utf-8", errors="replace").read())
    except (ValueError, StopIteration) as e:
        return key, {"error": f"can't read the file: {e}"}
    blt = os.path.join(work, key + ".blt")
    open(blt, "w").write(text)
    result = subprocess.run(["java", "-jar", jar, "-i", blt, "--ruleset", "scottish", "--report-format", "json"],
                            capture_output=True, text=True)
    if result.returncode:
        return key, {"error": result.stderr.strip()[-200:]}
    return key, {"names": names, "seats": seats, "ward": ward, "report": json.loads(result.stdout)}


def agrees(ours, figure):
    """Whether this project's exact figure is the box's: rounded or truncated to its places, or
    rounded first to 2 places (as editors working from a 2-place sheet do)."""
    places = Decimal(1).scaleb(-len(figure.split(".")[1])) if "." in figure else Decimal(1)
    value = Decimal(ours)
    twice = value.quantize(Decimal("0.01"), ROUND_HALF_UP).quantize(places, ROUND_HALF_UP)
    return Decimal(figure) in (value.quantize(places, ROUND_HALF_UP), value.quantize(places, ROUND_DOWN), twice)


def pairing(names, entry):
    """Our name -> the box's candidate, pairing the most similar first."""
    pairs = sorted(((difflib.SequenceMatcher(None, check.plain(a), check.plain(c["name"])).ratio(), a, i)
                    for a in names for i, c in enumerate(entry["candidates"])), reverse=True)
    paired, used = {}, set()
    for _, a, i in pairs:
        if a not in paired and i not in used:
            paired[a] = entry["candidates"][i]
            used.add(i)
    return paired


def compare(ours, entry):
    """For each count in the box, how many of its figures disagree with the round that fits best
    (the first with fewest, no earlier than the last count's)."""
    paired = pairing(ours["names"], entry)
    rounds = ours["report"]["rounds"]
    numcounts = max(len(c["counts"]) for c in entry["candidates"])
    start, misses = 0, []
    for k in range(numcounts):
        figures = {name: c["counts"][k] for name, c in paired.items() if k < len(c["counts"]) and c["counts"][k]}
        best, fewest = None, None
        for i in range(start, len(rounds)):
            tallies = rounds[i]["tallies"]
            shared = [name for name in figures if name in tallies]
            if not shared:
                continue
            wrong = sum(1 for name in shared if not agrees(tallies[name], figures[name]))
            if fewest is None or wrong < fewest:
                best, fewest = i, wrong
            if wrong == 0:
                break
        misses.append(len(figures) if best is None else fewest)
        if best is not None:
            start = best
    return misses


def main():
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("scot_elex")
    parser.add_argument("official")
    parser.add_argument("--jar", default=check.DEFAULT_JAR)
    parser.add_argument("--workers", type=int, default=os.cpu_count())
    args = parser.parse_args()

    boxes = [e for e in json.load(open(args.official))
             if e["source"] == "wikipedia" and any(len([x for x in c.get("counts", []) if x]) > 1
                                                    for c in e["candidates"])]
    files = sorted(glob.glob(os.path.join(args.scot_elex, "blt_files", "*", "*.blt")))
    with tempfile.TemporaryDirectory() as work, concurrent.futures.ThreadPoolExecutor(args.workers) as pool:
        counts = dict(pool.map(lambda path: count(path, args.jar, work), files))

    exact, slips, differ, unmatched = 0, [], [], 0
    for key, ours in sorted(counts.items()):
        if "error" in ours:
            unmatched += 1
            continue
        entry = check.official_ward(ours, boxes, re.search(r"(20\d\d)", key).group(1))
        if isinstance(entry, str):
            unmatched += 1
            continue
        misses = compare(ours, entry)
        wrong = [(k + 1, n) for k, n in enumerate(misses) if n]
        if not wrong:
            exact += 1
        elif max(n for _, n in wrong) == 1:
            slips.append((key, entry["ward"], wrong))
        else:
            differ.append((key, entry["ward"], wrong))
    print(f"{exact + len(slips) + len(differ)} wards with stages on Wikipedia: {exact} match every count; {len(slips)} "
          f"have counts with one figure off (transcription); {len(differ)} have a count with more "
          f"({unmatched} with no stages to compare)")
    for key, ward, wrong in differ + slips:
        print(f"  {key} ({ward}): figures off at count " + ", ".join(f"{k} ({n})" for k, n in wrong))


if __name__ == "__main__":
    main()

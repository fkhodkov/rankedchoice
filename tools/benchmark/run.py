"""Time every method end to end through the command line, as README's Performance section reports.

usage: run.py DATA_DIR [--jar JAR] [--heap 256m] [--only small|large] [--limit SECONDS]

Generates the elections into DATA_DIR with generate.py (once; later runs reuse them), then runs
each method on each with `java -jar`, JVM start-up and parsing included, and prints the times as
Markdown. "small" is README's table (20 candidates and 5 seats at 100k and 1M voters, 100 and 20
at 100k); "large" is its 300-candidate, 33-seat, 1M-voter line, at mean ranking lengths 5 and 40.
--heap caps the JVM heap, to check that every count fits in it. --limit stops a count after that
many seconds (default 300) and shows it as "over" them: exact STV never finishes the 300-candidate
election at 40 preferences (README's Limitations says why). Single runs vary by up to a third,
more on a busy machine.
"""
import argparse
import os
import subprocess
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))
JAR = os.path.join(HERE, "..", "..", "target", "rankedchoice-0.1.0-SNAPSHOT.jar")

METHODS = {
    "Borda": ["--method", "borda"],
    "Schulze": ["--method", "schulze"],
    "STV (fractional, exact)": ["--method", "stv"],
    "STV (fractional, `truncate:9`)": ["--method", "stv", "--rounding", "truncate:9"],
    "STV (`scottish`, WIGM)": ["--ruleset", "scottish"],
    "STV (`ers97`, last parcel)": ["--ruleset", "ers97"],
    "QPQ": ["--method", "qpq"],
    "Cambridge STV": ["--method", "cambridge"],
    "Meek": ["--method", "meek"],
    "Warren": ["--method", "warren"],
}

# (candidates, seats, voters, mean ranking length)
SMALL = [(20, 5, 100_000, 5), (20, 5, 1_000_000, 5), (100, 20, 100_000, 5)]
LARGE = [(300, 33, 1_000_000, 5), (300, 33, 1_000_000, 40)]
SMALL_METHODS = [name for name in METHODS if "truncate" not in name]
LARGE_METHODS = ["Borda", "Schulze", "STV (fractional, exact)", "STV (fractional, `truncate:9`)",
                 "STV (`scottish`, WIGM)", "STV (`ers97`, last parcel)", "QPQ", "Cambridge STV", "Meek",
                 "Warren"]


def election(data_dir, candidates, seats, voters, mean_length):
    path = os.path.join(data_dir, f"{candidates}c-{seats}s-{voters}v-len{mean_length}.blt")
    if not os.path.exists(path):
        print(f"generating {os.path.basename(path)}", file=sys.stderr)
        subprocess.run([sys.executable, os.path.join(HERE, "generate.py"), str(candidates), str(seats),
                        str(voters), path, "--mean-length", str(mean_length)], check=True)
    return path


def timed(jar, heap, limit, path, method_args):
    """Seconds the count took, or why it failed."""
    command = ["java", *([f"-Xmx{heap}"] if heap else []), "-jar", jar, "--input", path, *method_args]
    start = time.perf_counter()
    try:
        result = subprocess.run(command, stdout=subprocess.DEVNULL, stderr=subprocess.PIPE, text=True,
                                timeout=limit)
    except subprocess.TimeoutExpired:
        return f"over {limit} s"
    seconds = time.perf_counter() - start
    if result.returncode != 0:
        lines = result.stderr.strip().splitlines()
        return "failed: " + (lines[0] if lines else f"exit {result.returncode}")
    return f"{seconds:.1f} s"


def table(jar, heap, limit, data_dir, sizes, methods):
    header = [f"{c} candidates, {s} seats, {v // 1000:,}k voters, length {m}" for c, s, v, m in sizes]
    rows = ["| Method | " + " | ".join(header) + " |", "|---" * (len(sizes) + 1) + "|"]
    paths = [election(data_dir, *size) for size in sizes]
    for name in methods:
        times = []
        for path in paths:
            times.append(timed(jar, heap, limit, path, METHODS[name]))
            print(f"{name}, {os.path.basename(path)}: {times[-1]}", file=sys.stderr)
        rows.append(f"| {name} | " + " | ".join(times) + " |")
    return "\n".join(rows)


def main():
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("data_dir")
    parser.add_argument("--jar", default=JAR)
    parser.add_argument("--heap", help="the JVM's -Xmx, e.g. 256m")
    parser.add_argument("--only", choices=["small", "large"])
    parser.add_argument("--limit", type=int, default=300, help="seconds a count may take (default 300)")
    args = parser.parse_args()
    if not os.path.exists(args.jar):
        parser.error(f"no jar at {args.jar}: build it with mvn -o -q package -DskipTests")
    os.makedirs(args.data_dir, exist_ok=True)
    if args.only != "large":
        print(table(args.jar, args.heap, args.limit, args.data_dir, SMALL, SMALL_METHODS) + "\n")
    if args.only != "small":
        print(table(args.jar, args.heap, args.limit, args.data_dir, LARGE, LARGE_METHODS) + "\n")


if __name__ == "__main__":
    main()

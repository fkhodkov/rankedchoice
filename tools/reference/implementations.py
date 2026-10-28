"""Rerun every check against another implementation, and compare what each reports with what's recorded.

usage: implementations.py TOOLS_ROOT [--only NAME ...] [--dry-run] [--record] [--jar PATH] [--python2 PATH]

TOOLS_ROOT holds the other implementations, as each tool's README sets them up:

    opentally/          a checkout of OpenTally, built (target/release/opentally)
    pyrcv2/             a checkout of pyRCV2
    droop/              a checkout of droop
    openstv/            a checkout of OpenSTV, run with --python2 (default python2)
    STVPoll-venv/       a Python virtual environment with STVPoll installed
    pref_voting-venv/   a Python virtual environment with pref_voting installed
    schulze3/           Schulze's unpacked schulze3.zip, with his program built as singl01p

A check runs only if what it needs is there, and is reported as skipped otherwise. Each one writes its
random elections to a temporary directory, so they're made afresh. What a check reports is its lines that
aren't indented: a line per election that differs (or, for Schulze's elections, per election) and its
summary. Those lines are compared with implementations.json, line by line, and every change is listed;
--record writes them there instead, for checks whose figures are meant to move (then update VALIDATION.md
to match). It exits 1 if any check's lines differ from the record or a check printed nothing.

A full run takes about 50 minutes; --only runs some checks (by name, or a prefix: opentally runs
all eleven presets), --dry-run lists what would run.
"""
import argparse
import json
import os
import pathlib
import subprocess
import sys
import tempfile
import time

HERE = os.path.dirname(os.path.abspath(__file__))
DEFAULT_JAR = os.path.join(HERE, "..", "..", "cli", "target", "rankedchoice-cli-0.1.0-SNAPSHOT.jar")
RECORD = os.path.join(HERE, "implementations.json")
OPENTALLY_PRESETS = ["gregory", "scottish", "act", "senate", "tasmania", "nswlg", "viclc", "wa", "ers97",
                     "meek06", "meeknz"]
PYRCV2_PRESETS = ["ers97", "gregory", "scottish", "senate"]


def steps(root, work, python2="python2", jar=DEFAULT_JAR, only=None):
    """[(name, command, why it's skipped or None)], in the order they run."""
    def tool(*parts):
        return os.path.join(root, *parts)

    def needs(*paths):
        missing = [path for path in paths if not os.path.exists(path)]
        return f"no {missing[0]}" if missing else None

    def script(*parts):
        return os.path.join(HERE, *parts)
    # OpenSTV's checks need a Python 2 that runs (pyenv's shim is found even where it can't run one).
    no_python2 = None if runs([python2, "-c", "pass"]) else f"{python2} doesn't run (give --python2 PATH)"
    python, jar_flag = sys.executable, ["--jar", jar]
    opentally, pyrcv2, droop, openstv = tool("opentally", "target", "release", "opentally"), tool("pyrcv2"), \
        tool("droop"), tool("openstv")
    stvpoll, pref_voting = tool("STVPoll-venv", "bin", "python"), tool("pref_voting-venv", "bin", "python")
    schulze3 = tool("schulze3")
    plan = []
    for preset in OPENTALLY_PRESETS:
        plan.append((f"opentally-{preset}", [python, script("opentally", "check.py"), opentally,
                                             os.path.join(work, "opentally"), "--preset", preset] + jar_flag,
                     needs(opentally)))
    for preset in PYRCV2_PRESETS:
        plan.append((f"pyrcv2-{preset}", [python, script("pyrcv2", "check.py"), pyrcv2, os.path.join(work, "pyrcv2"),
                                          "--preset", preset] + jar_flag, needs(pyrcv2)))
    plan.append(("droop", [python, script("droop", "check.py"), droop, os.path.join(work, "droop")] + jar_flag,
                 needs(droop)))
    for method in ("schulze", "borda"):
        plan.append((f"openstv-{method}", [python, script("openstv", "check.py"), openstv,
                                           os.path.join(work, f"openstv-{method}"), "--method", method,
                                           "--python2", python2] + jar_flag, needs(openstv) or no_python2))
    plan.append(("openstv-cambridge", [python, script("openstv", "cambridge.py"), openstv,
                                       os.path.join(work, "cambridge"), "--python2", python2] + jar_flag,
                 needs(openstv) or no_python2))
    plan.append(("qpq", [python, script("qpq", "check.py"), droop, openstv, os.path.join(work, "qpq"),
                         "--python2", python2] + jar_flag, needs(droop, openstv) or no_python2))
    for method in ("schulze", "borda"):
        plan.append((f"pref_voting-{method}", [pref_voting, script("pref_voting", "check.py"),
                                               os.path.join(work, f"pref_voting-{method}"), "--method", method]
                     + jar_flag, needs(pref_voting)))
    plan.append(("stvpoll", [stvpoll, script("stvpoll", "check.py"), os.path.join(work, "stvpoll")] + jar_flag,
                 needs(stvpoll)))
    plan.append(("schulze-stv", [python, script("schulze_stv", "check.py"), schulze3, "--program",
                                 os.path.join(schulze3, "singl01p")] + jar_flag,
                 needs(os.path.join(schulze3, "singl01p"))))
    plan.append(("schulze-proportional", [python, script("schulze_stv", "proportional_check.py"), schulze3]
                 + jar_flag, needs(schulze3)))
    return [step for step in plan if not only or any(step[0] == name or step[0].startswith(name + "-")
                                                       for name in only)]


def runs(command):
    try:
        return subprocess.run(command, capture_output=True).returncode == 0
    except OSError:
        return False


def reported(output):
    """What a check reports: its lines that aren't indented or blank."""
    return [line for line in output.splitlines() if line.strip() and not line[0].isspace()]


def changes(recorded, now):
    """The lines that differ between a check's record and its run, as '-' and '+' lines."""
    return [f"  - {line}" for line in recorded if line not in now] + [f"  + {line}" for line in now
                                                                       if line not in recorded]


def main():
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("root")
    parser.add_argument("--only", nargs="+")
    parser.add_argument("--dry-run", action="store_true")
    parser.add_argument("--record", action="store_true")
    parser.add_argument("--jar", default=DEFAULT_JAR)
    parser.add_argument("--python2", default="python2")
    args = parser.parse_args()
    record = json.loads(pathlib.Path(RECORD).read_text()) if os.path.exists(RECORD) else {}
    with tempfile.TemporaryDirectory(prefix="implementations-") as work:
        plan = steps(args.root, work, args.python2, args.jar, args.only)
        if args.dry_run:
            for name, command, skip in plan:
                print(f"{name}: skipped, {skip}" if skip else f"{name}: {' '.join(command)}")
            return
        failed = []
        for name, command, skip in plan:
            if skip:
                print(f"{name}: skipped, {skip}", flush=True)
                continue
            started = time.monotonic()
            result = subprocess.run(command, capture_output=True, text=True)
            lines = reported(result.stdout)
            took = f"{time.monotonic() - started:.0f} s"
            if not lines:
                failed.append(name)
                print(f"{name}: printed nothing ({took}, exit {result.returncode}): {result.stderr.strip()[-300:]}",
                      flush=True)
                continue
            if args.record:
                record[name] = lines
                print(f"{name}: recorded ({took}): {lines[-1]}", flush=True)
                continue
            if name not in record:
                failed.append(name)
                print(f"{name}: nothing recorded ({took}); now: {lines[-1]}", flush=True)
                continue
            differ = changes(record[name], lines)
            if differ:
                failed.append(name)
            print(f"{name}: " + ("as recorded" if not differ else "CHANGED") + f" ({took})", flush=True)
            for line in differ:
                print(line, flush=True)
    if args.record:
        pathlib.Path(RECORD).write_text(json.dumps(record, indent=1, ensure_ascii=False) + "\n")
    print(f"{len(failed)} differ from the record or failed" + (f": {', '.join(failed)}" if failed else ""))
    sys.exit(1 if failed else 0)


if __name__ == "__main__":
    main()

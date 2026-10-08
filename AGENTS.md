# AGENTS.md

Notes for coding agents working on this repository: what the project is, how to build and check
it, and the conventions the existing work follows. `CLAUDE.md` is a link to this file.

## The project

A ranked-choice voting calculator in Java 25: classic STV with interchangeable quotas, transfer
methods, rounding rules and tie-breaks, and bundled rulesets for real jurisdictions (Scotland, the
ACT, the Australian Senate, NSW councils, Portland, Minneapolis, US instant-runoff, ...), plus
Meek and Warren, Cambridge STV, QPQ, CPO-STV, Schulze, Schulze STV, the Schulze proportional
ranking and Borda. Counts use exact rational arithmetic (`core/util/Fraction`); Meek and Warren use
droop's fixed-point arithmetic.

- [README.md](README.md): what's built, how to use it, the bundled rulesets, the tools, and the
  project layout (under "Project layout"; keep it current when adding packages or tools).
- [VALIDATION.md](VALIDATION.md): how every method and ruleset is checked, against what, and to
  what precision, with every known difference and its cause.
- [PLAN.md](PLAN.md): the original design, kept as a record; don't update it for new work.

## Build and test

```sh
mvn -o -q verify       # Checkstyle, Java tests, Python tests, jar; -o works with the cached dependencies
mvn -o -q verify -DskipTests            # the jar only
mvn -o -q verify -DskipPythonTests      # where python3 isn't installed
python3 -m unittest discover -s tools/reference/tests   # the Python tests alone, in a second
```

- The pre-commit hook (`.githooks/pre-commit`, on with `git config core.hooksPath .githooks`)
  runs the same `mvn -o -q verify` on the staged files in a temporary copy and refuses the commit
  if it fails. It stands in for CI, which this repository doesn't have: don't skip it with
  `--no-verify`; fix the build.
- Checkstyle runs in `validate` and fails the build on any violation: 120 columns, no tabs, no star
  or unused imports, imports grouped static, `java`, third-party, then this project's. Its messages
  may come in the system's locale. `.editorconfig` sets the same for editors; Python keeps to 120
  columns too, which `tools/reference/tests/test_line_length.py` checks under `tools/`.
- `tools/reference/tests/test_pyflakes.py` runs pyflakes over `tools/` (unused imports, undefined
  names, and the like) where it's installed, and is skipped where it isn't: install it once
  (`pip install --user pyflakes`, or the distribution's `python3-pyflakes`) for the build and the
  pre-commit hook to enforce it.
- The Python tests check `tools/reference/cvr_to_blt.py`, `rcvcheck.py`, the results page and
  `refresh_results.py`, and that VALIDATION.md's generated table matches the committed results.
- The reference checkers need `target/rankedchoice-0.1.0-SNAPSHOT.jar` (`mvn -o -q package
  -DskipTests`); some need LibreOffice (`soffice`), `pdftotext`, or `openpyxl` (NSW).
  `refresh_results.py` runs the checkers with its own Python and skips NSW where that lacks
  `openpyxl`: run it with a virtual environment's (`python3 -m venv ...; .../pip install
  openpyxl`) to include NSW.

## Validation is the point

Every rule a ruleset implements is meant to be checked against something independent of this
engine: an official count of a real election, another implementation (OpenTally, droop, pyRCV2,
OpenSTV, CRAN packages), or a hand-derived case. When adding or changing a method or ruleset:

- Find the independent check first, and record it in VALIDATION.md: what was checked, against
  what, to what precision, and every figure that doesn't match, with its cause.
- Say how a rule was established. "The counts show" is a finding; a reading the official rounds
  only fit one way is a finding; a guess is labelled as one. A difference may be put down to the
  ballot record ("looks like the record's") only after the other plausible readings of the
  ballots have been tried and named, and when it runs both ways (some candidates over, others
  under) with the same eliminations and winners, or the ballot totals show it (more or fewer
  ballots than the count's). Better still, show it: see below.
- Name the gaps. VALIDATION.md's "Not validated" section lists what has no independent check and
  why, dated when it was last rechecked.
- Real-election fixtures live in `src/test/resources/testdata/<place>/`, each directory with a
  README saying where the ballots and official figures come from and how they were converted.
  They're data: kept byte for byte (`.editorconfig` leaves them alone), never reformatted.

### When an official count doesn't match

What settled the differences that once looked like the record's (VALIDATION.md has each case):

- Look past the round totals. Reports list each elimination's transfers by destination, or
  each round's exhausted ballots, sometimes by cause; the checkers compare them
  (`rcvcheck.transfer_line`, `rcvcheck.exhausted_line`). A gap that moves ballots from one
  destination to another within an elimination is a rule read differently (Alaska's repeated
  rankings, found from one ballot ranked Bye four times); a gap that runs one way, every figure
  short or over, is ballots missing or extra (Oakland, counted in the reports' totals).
- Count the ballots. The record's rows against the report's total (candidates, blanks,
  exhausted, overvotes) show missing or extra ballots outright.
- Read the report's status. An archived report may be the unofficial one, or captured before
  certification (Alaska's August 2022 special, San Francisco's 2024 pages); look for a later
  capture, or for the certified figures elsewhere (San Francisco's statement of vote).
- Find the authority's own record. Where FairVote's copy is a conversion, the authority's export
  (New York's Board, Alaska's Division, Maine's State) shows whether the gap is the copy's; if
  they agree, the gap lies between the authority's published ballots and its count.
- Check the report's own arithmetic: a round whose figures don't add up to the ballots counted,
  or a candidate off their own transfers, is the report's slip (Eastpointe 2021).
- Narrow a gap to the ballots it is. A few votes is usually a few ballots of one shape; find
  them in the record and say what they are (Maine 2018's 3, Minneapolis 2009's one).
- Try a rule everywhere it could apply. A reading that fixes one jurisdiction may break another
  (repeats as skips fix Alaska and put Maine off); run `refresh_results.py --compare` and make
  sure every line it changes is one you expect.

### Official results, the results page and VALIDATION.md's table

- Each reference checker writes its results with `--results tools/reference/results/<name>.json`;
  the ACT and Ireland fixture tests do with `-Dresults.dir=tools/reference/results`.
- `tools/reference/results/jurisdictions.json` holds what no checker knows: names, which contests
  count, every known difference with its contests and cause, the rules table, and VALIDATION.md's
  "Official counts" table rows as templates (`{checker.field}`).
- `tools/reference/results_page.py` builds `results/real-elections.html`; with `--validation
  write` it rewrites VALIDATION.md's table between its markers. Never edit that table by hand: the
  build fails when it doesn't match the results.
- A contest short of every round that no difference names shows up as "unexplained", and the
  generator says so. Explain it in `jurisdictions.json` (with the evidence in VALIDATION.md); don't
  silence it.
- `tools/reference/refresh_results.py DATA_ROOT` reruns everything (`--dry-run` first);
  `--compare` reruns without writing anything and lists how the results would change.

### Reference tools

- `tools/reference/<place>/check.py` checks a jurisdiction; shared pieces are in
  `tools/reference/rcvcheck.py` (download, name keys, counting, round matching, summary lines,
  command line) and `tools/reference/cvr_to_blt.py` (rank-column ballot records, an option per
  ballot rule: `--overvote`, `--skipped`, `--repeat`, `--write-in`). Use them rather than copying.
- When refactoring a checker, a converter or the engine, `refresh_results.py DATA_ROOT --compare`
  is the check: it reruns the checkers on their records converted afresh (the cached BLT files
  left out), into a temporary directory, and diffs every contest with the committed results,
  changing nothing. Identical is the bar; where a change is meant to move results, every
  difference it lists should be one you expect. A full run takes about an hour (`--only` for the
  checkers a change touches); a checker whose committed results include a failure exits 1 and
  is still compared.
- Data (ballot records, official reports) isn't committed: checkers fetch it into a data directory
  given on the command line. Downloads go to a temporary name and are renamed when complete.
  Use `rcvcheck.fetch`: it records each file's address in the directory's `.sources.json` and
  fetches again when a checker asks for the file from another address, so pointing a contest at
  a corrected report needs no manual step. Make a derived file (a BLT from its record, CSVs
  from a spreadsheet) again when `rcvcheck.stale(target, *sources)` says a source is newer.
  A file saved before `.sources.json` was kept has no address on record, and is trusted to be
  what's asked for; `refresh_results.py --refetch` (or `RCVCHECK_REFETCH=1` for one checker)
  downloads such files again instead, once. That's why San Francisco's and Alaska's reports keep
  their capture's timestamp in their names: an old copy can't be taken for a new one.
- Where an authority's site turns scripted requests away (Alaska's, San Francisco's), use the
  Internet Archive's copies; don't work around bot protection.
- Dataverse refuses requests without a User-Agent header. The Internet Archive's index
  rate-limits and sometimes sends gzip regardless; `rcvcheck.fetch` handles both.

## Writing docs

README.md and VALIDATION.md are written plainly: specific figures, named contests and sources, short
sentences, and no claims beyond what was checked. Keep their summary tables in step with the
sections below them (VALIDATION.md's "Official counts" table is generated; the others aren't).

## Git

- Work happens on the `stv` branch. The owner updates `main` by hand: don't open pull requests,
  merge, or push to `main`.
- Commit only when asked. Messages start with `[llm] ` and an imperative summary, then a short
  body saying what changed and what was checked, then the attribution lines the session gives.
- Don't commit scratch data, downloaded records or local tool output; results files in
  `tools/reference/results/` are committed on purpose.

## Shell gotchas

- `pkill -f <pattern>` can match and kill the shell running it; kill by process ID instead.
- Long checker runs (New York, the Senate, Scotland) take tens of minutes: run them in the
  background and wait on their output.

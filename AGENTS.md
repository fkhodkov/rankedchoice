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

- Checkstyle runs in `validate` and fails the build on any violation: 120 columns, no tabs, no star
  or unused imports, imports grouped static, `java`, third-party, then this project's. Its messages
  may come in the system's locale. `.editorconfig` sets the same for editors; Python keeps to 120
  columns too.
- The Python tests check `tools/reference/cvr_to_blt.py`, `rcvcheck.py`, the results page and
  `refresh_results.py`, and that VALIDATION.md's generated table matches the committed results.
- The reference checkers need `target/rankedchoice-0.1.0-SNAPSHOT.jar` (`mvn -o -q package
  -DskipTests`); some need LibreOffice (`soffice`), `pdftotext`, or `openpyxl` (NSW).

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
  under) with the same eliminations and winners.
- Name the gaps. VALIDATION.md's "Not validated" section lists what has no independent check and
  why, dated when it was last rechecked.
- Real-election fixtures live in `src/test/resources/testdata/<place>/`, each directory with a
  README saying where the ballots and official figures come from and how they were converted.
  They're data: kept byte for byte (`.editorconfig` leaves them alone), never reformatted.

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
- `tools/reference/refresh_results.py DATA_ROOT` reruns everything (`--dry-run` first).

### Reference tools

- `tools/reference/<place>/check.py` checks a jurisdiction; shared pieces are in
  `tools/reference/rcvcheck.py` (download, name keys, counting, round matching, summary lines,
  command line) and `tools/reference/cvr_to_blt.py` (rank-column ballot records, an option per
  ballot rule: `--overvote`, `--skipped`, `--repeat`, `--write-in`). Use them rather than copying.
- When refactoring a checker or converter, record its full output (or BLT files) before, and diff
  after: identical is the bar.
- Data (ballot records, official reports) isn't committed: checkers fetch it into a data directory
  given on the command line. Downloads go to a temporary name and are renamed when complete.
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

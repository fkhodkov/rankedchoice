# Plan: Java Ranked-Choice Voting Calculator (STV + Condorcet + Positional methods)

This is the original design, kept as a record. For what's built and how to use it, see
[README.md](README.md); for how it's checked, [VALIDATION.md](VALIDATION.md).

## Status

Milestones 1–7 are done:

- **Built as planned:** the tie-capable ballot model, BLT/CSV parsers, exact `Fraction`
  arithmetic; classic STV with every planned quota, fractional and WIGM transfer, truncation,
  every planned tie-break; YAML config with CLI override precedence and bundled rulesets; Meek;
  Schulze; Borda (classic, Dowdall, generic modified); CPO-STV with a 500-outcome cap;
  Schulze-STV with a 50,000-committee cap; tied
  rankings throughout; text/CSV/JSON/HTML reports.
- **Built differently from the plan:**
  - Real rulesets needed more than the plan's axes: two **last-parcel** transfer methods (ERS97,
    Tasmania, ACT), ERS97's **bulk exclusion**, a tied-ranking **policy** (reject, split,
    truncate, skip — every real ruleset truncates rather than splits), and a
    **minimum-preferences** formality rule (Tasmania).
  - The §2.2 table was wrong in places: ERS97/ERS76 are last-parcel, not plain Gregory;
    "Hare-Clark QPQ" is classic last-parcel STV, while **QPQ** is a different method
    (Woodall's), built as its own engine; and **Cambridge, MA** moves whole ballots by the
    Cincinnati method, also its own engine (`cambridge`) rather than a fractional ruleset.
  - **Meek** runs in droop's fixed-point arithmetic, not exact fractions, so every figure
    reproduces droop's exactly; **Warren's method** came with it.
  - Engines live in their own packages (`stv`, `stv/meek`, `cambridge`, `qpq`, `cpostv`,
    `schulze`, `schulzestv`, `borda`) and share `StvResult`/`RoundRecord` where they can, instead of the
    planned single sealed `RoundEvent` model and `condorcet` package.
  - Not built: round-half-up rounding and whole-vote random transfer (no ruleset needed them;
    Cambridge's is the deterministic Cincinnati method); a JSON ballot parser.
  - **Schulze-STV** needed no optimisation solver after all: a committee's support against an
    outsider has a closed form, a minimum over subsets of the committee. Schulze's own program
    and published figures turned out to exist to check it against.
- **Open:** replaying an official Cambridge count (no public ballot data); validating `ers76` and
  `hare-clark-qpq` (no data).

What follows is the plan as first written.

## 1. Scope

Build a Java library + CLI that:
- Tabulates **STV** elections under multiple rule variants (quota, transfer method, rounding rule, tie-break rules), including real-world legislated rulesets (Scottish, ERS76/97, Cambridge MA, Australian Hare-Clark "quota preferential" QPQ)
- Tabulates other ranked-choice methods: **Schulze**, **Schulze-STV**, **CPO-STV**, and **Borda** (classic + modified variants), with IRV falling out of the STV engine at `seats = 1`
- Exports full round-by-round results in **CSV, JSON, HTML, and text**, format selectable via CLI flag or config file
- Accepts rules/config via **CLI arguments or a config file** (CLI overrides config, config overrides defaults)
- Is validated against **published, real-world results** and known synthetic test vectors, not just unit-tested against its own logic

Since the repo is currently empty, this is a from-scratch project.

## 2. Domain background

### 2.1 STV building blocks (the "basic rules")

STV isn't one algorithm — every real-world ruleset is a specific combination of these independent axes:

**Quota**
- Droop: `floor(votes / (seats+1)) + 1` (Ireland, Scotland, Australia)
- Hare: `votes / seats`
- Hagenbach-Bischoff (no floor/+1)
- Imperiali (rare, larger-council)

**Rounding rule** (applied to transfer-value fractions — independent of quota/transfer choice)
- Exact (no rounding, keep as rational fraction)
- Truncate to N decimal places (e.g. Scottish rule: truncate to 5 places)
- Round-half-up to N decimal places

**Surplus transfer method**
- Whole-vote / random transfer (historic, non-deterministic — included for historical fidelity only)
- Fractional (Senatorial/Gregory) transfer
- **Weighted Inclusive Gregory Method (WIGM)** — current Scottish standard, re-examines all of a candidate's ballots each transfer, not just the last parcel
- **Meek method** — iterative "keep value" recalculation; every ballot is re-counted every round until convergence, structurally different loop shape from the others

**Elimination / tie-break rule**
- Single vs. batch elimination
- Tie-break by: previous round votes, original first-preference count, seeded random draw (reproducible), defined candidate order

**Surplus order**
- Largest surplus first vs. order of election

### 2.2 Real-world rulesets = named combinations of the above

Rather than hardcoding "Scottish STV" or "ERS97" as separate engines, each is a
named preset of {quota, rounding rule, transfer method, tie-break, surplus
order}, expressed in the same config format a user's own custom rules use:

| Ruleset | Quota | Transfer | Rounding | Notes |
|---|---|---|---|---|
| Scottish STV (2007 Regs) | Droop | WIGM | Truncate 5dp | Local government standard |
| ERS97 | Droop | Fractional (Gregory) | Truncate | Electoral Reform Society rules |
| ERS76 | Droop | Fractional, last-parcel only | Truncate | Predecessor to ERS97 |
| Cambridge, MA | Droop | Fractional (with historical whole-vote precedent) | Round | US municipal STV since 1941 |
| Hare-Clark "quota preferential" (QPQ) | Droop (Hare-Clark specific rounding) | Fractional | Per Tasmanian/ACT Electoral Act | Legislated Australian STV variant |
| Meek-based (e.g. some NZ/local uses) | N/A (Meek recalculates directly) | Meek | Configurable precision | |

This directly drives the architecture: a `StvRuleset` is *data*, and the
bundled rulesets ship as config-file resources, not code.

### 2.3 Other ranked-choice methods (structurally distinct engines)

- **Schulze** (beatpath/Condorcet-Schulze): pairwise comparison matrix → strongest-path strengths → winner ordering. Single-winner or full ranking, deterministic.
- **Schulze-STV**: multi-winner extension — compares *completions* of ballots across candidate subsets using the Schulze method to pick the winning proportional completion. Combinatorial: cost grows with candidate-subset count, so needs a documented practical seat/candidate cap.
- **CPO-STV** (Comparison of Pairs of Outcomes): compares every pair of possible winning candidate-sets directly. Also combinatorial (all `C(candidates, seats)` outcome pairs) — same cap caveat as Schulze-STV.
- **Borda count, with modifications**: classic Borda (`n-1, n-2, ..., 0` points), Dowdall/Nauru modified Borda (`1, 1/2, 1/3, ...` points), and a generic `ModifiedBorda(startWeight, step)` for other positional-weight variants.
- IRV falls out of the STV engine at `seats = 1`.

Schulze/Schulze-STV/CPO-STV/Borda consume the *same* ballot model as STV — a
ranking is a ranking regardless of which tabulator reads it.

### 2.4 Ties within a ballot (nice-to-have, not a priority)

Some ballot formats allow equal rankings (e.g. `.blt` `3=4=5` meaning three
candidates tied at the 3rd preference). This changes the ballot model shape
(`List<Set<Candidate>>` instead of `List<Candidate>`) and requires a defined
splitting rule in every counting engine (STV transfer, Schulze pairwise
comparison, Borda scoring) for how a tied group is handled. Priority: low —
but the ballot model is designed with tie-groups from the start (see §3) so
this can be turned on later without a breaking model change.

## 3. Architecture

```
rankedchoice/
├── core/
│   ├── model/         Candidate, Ballot (ranks: List<Set<Candidate>>, tie support built-in),
│   │                  sealed RoundEvent (FirstCount, Elimination, SurplusTransfer, PairwiseTally, ...),
│   │                  ElectionResult
│   ├── ballot/        BltParser (incl. tie syntax), CsvBallotParser, JsonBallotParser
│   ├── config/        ElectionConfig model, YAML loader (Jackson YAML), CLI-arg merge/override logic,
│   │                  bundled ruleset presets as resources: rulesets/scottish.yaml, ers97.yaml, ers76.yaml,
│   │                  cambridge-ma.yaml, hare-clark-qpq.yaml
│   ├── export/        ResultExporter interface: CsvExporter, JsonExporter, HtmlExporter, TextExporter
│   └── util/          Fraction (BigInteger-backed rational), rounding utilities
├── stv/
│   ├── quota/         sealed Quota: Droop, Hare, HagenbachBischoff, Imperiali
│   ├── rounding/       sealed RoundingRule: Exact, TruncateDecimal, RoundHalfUp
│   ├── transfer/       sealed TransferMethod: Fractional, Wigm, Meek, WholeVote
│   ├── tiebreak/       TieBreakRule: PreviousRoundVotes, FirstPreferenceCount, SeededRandom, DefinedOrder
│   ├── StvRuleset.java record{quota, transferMethod, roundingRule, tieBreak, surplusOrder, batchElimination}
│   └── StvCounter.java runs an StvRuleset against ballots -> ElectionResult
├── condorcet/
│   ├── PairwiseMatrix.java
│   ├── SchulzeMethod.java      single-winner / ranked Schulze
│   ├── SchulzeStvMethod.java   multi-winner proportional completion (expensive — capped)
│   └── CpoStvMethod.java       pairwise outcome comparison (expensive — capped)
├── borda/
│   ├── BordaScoringRule.java   Classic, DowdallNauru, ModifiedBorda(start, step)
│   └── BordaMethod.java
├── cli/
│   └── Main.java        picocli: --method, --ruleset, --quota/--transfer/--rounding (custom overrides),
│                         --config, --format, --output; config/CLI precedence handled centrally
└── testdata/            known elections + rulesets + expected outputs, source citations
```

Key design decisions:
- **Rational arithmetic**: exact `BigInteger`-backed `Fraction`, with the `RoundingRule` applied only at the specific step a given ruleset's official rules define — this is what lets exact reproduction of a real count, not just "same winner."
- **Immutable round snapshots**: each round produces an immutable, sealed `RoundEvent` so the full count is auditable/printable/exportable in any format from one shared model.
- **Strategy pattern + sealed interfaces** for quota/transfer/rounding/tie-break, combined into a `StvRuleset` record — this is what "multiple variants" and "real rulesets as combinations" cash out to in code.
- **Rulesets and user config are the same mechanism**: bundled rulesets are just YAML resources loaded through the identical config path a user's custom YAML takes.
- **Shared `Ballot` model** (with built-in tie-group support) feeds every method — STV, Schulze family, Borda.
- **Java 25 usage**: `getFirst()/getLast()` on ballots and round-history lists; sealed interfaces + exhaustive pattern-matching `switch` over `Quota`/`TransferMethod`/`RoundingRule`/`RoundEvent`; record patterns when destructuring round events; unnamed variables (`_`) for intentionally-unused bindings. No feature is forced in where it doesn't fit — e.g. no virtual threads, nothing here is I/O-concurrent.

## 4. Config, CLI, and export design

**Config file**: YAML (via `jackson-dataformat-yaml`, reusing the same object
model as JSON export). Shape: `method`, `ruleset` (name of a bundled preset OR
inline `quota`/`transfer`/`rounding`/`tieBreak` overrides), `seats`, `input`
(ballot file + format), `output` (format + destination). (Open question: YAML
vs. TOML/HOCON — YAML recommended for familiarity and Jackson reuse; flag if
you'd rather use something else.)

**CLI** (picocli): every config field has a corresponding flag, e.g.
`--method=stv|schulze|schulze-stv|cpo-stv|borda`, `--ruleset=scottish|ers97|
ers76|cambridge|hare-clark-qpq|custom`, `--quota=droop|hare|...`,
`--transfer=...`, `--rounding=...`, `--seats=N`, `--config=path.yaml`,
`--format=csv|json|html|text`, `--output=path`. Precedence: CLI flag > config
file > built-in default. A ruleset name is just a shortcut for loading its
bundled config, so `--ruleset=scottish --transfer=fractional` is legal and
overrides just the transfer method on top of the Scottish preset.

**Export**: `ResultExporter` interface consuming the same `ElectionResult`/
`RoundEvent` model regardless of method. `TextExporter` mimics official
count-sheet layout; `CsvExporter` emits one row per candidate per round;
`JsonExporter` gives the full structured tree (good for test fixtures too);
`HtmlExporter` renders a per-round table with minimal inline CSS, no JS
dependency. Format is chosen by `--format` or inferred from `--output`'s file
extension if `--format` is omitted.

## 5. Testing strategy — validate against known examples

1. **Synthetic worked examples** (hand-verifiable, small N): Wikipedia's STV/
   Schulze worked examples; the Meek method's own reference paper (Hill/
   Wichmann/Woodall, *Algorithm 123*); Schulze's original paper's example
   matrix; a small hand-built CPO-STV/Schulze-STV example (published worked
   examples for these are rarer — may need to hand-derive one or cross-check
   against a reference implementation's output).

2. **Reference implementation cross-check**: **OpenSTV** ships a large `.blt`
   fixture suite with known outcomes across rule variants — support `.blt`
   directly so these drop straight in as golden vectors (cite, don't copy
   code).

3. **Real published official counts**: Scottish council elections (WIGM,
   exact stage-by-stage sheets); Cambridge, MA municipal elections; Australian
   Senate / Irish Dáil (Droop/fractional, also good Hare-Clark QPQ cross-check
   material); Debian project leader/TC elections (Schulze, full pairwise
   matrices published); single-winner US RCV (San Francisco, Minneapolis) for
   the IRV degenerate case.

4. **Test harness shape**: each fixture = ballot file + ruleset/config +
   expected result (elected set, ideally full round-by-round tallies) +
   source citation. JUnit5 parameterized tests iterate the fixture directory
   so adding a known election is "drop a file in `testdata/`". Fast hand-built
   unit tests stay separate from the slower golden-fixture suite.

5. **Edge cases**: exhausted ballots, ties at threshold, tied ballots (once
   implemented), more candidates eliminated than remaining seats, single-seat/
   single-candidate degenerate cases, malformed ballots, unanimous
   first-preference winner, and — for CPO-STV/Schulze-STV — a candidate/seat
   count large enough to actually exercise the combinatorial cap.

## 6. Tooling

- **Java 25**, Maven
- JUnit 5 + AssertJ
- picocli for the CLI
- Jackson (`jackson-databind` + `jackson-dataformat-yaml`) for config load and JSON export, reusing one object model for both
- No heavy election-specific dependency needed — this is small enough to build directly

## 7. Milestones

| Phase | Deliverable |
|---|---|
| 1 | Core model (tie-capable `Ballot` from the start) + `Fraction` util + BLT/CSV parsers, unit-tested standalone |
| 2 | STV engine: Droop + fractional transfer, no rounding rule yet; CLI-arg-only config; text exporter. Validate vs. Wikipedia + OpenSTV small fixtures |
| 3 | Remaining quotas, WIGM transfer, rounding rules, tie-break strategies; YAML config file + CLI-override precedence; bundled rulesets (Scottish, ERS97, ERS76, Cambridge, Hare-Clark QPQ). Validate vs. Scottish council + Cambridge fixtures |
| 4 | Meek method; CSV/JSON/HTML exporters. Validate vs. Meek reference paper cases |
| 5 | Schulze method; Borda (classic + Dowdall/Nauru + generic modified). Validate vs. Debian fixtures + Wikipedia example |
| 6 | CPO-STV and Schulze-STV, with documented/enforced candidate-seat cap given combinatorial cost. Validate against hand-derived or reference-cross-checked small examples |
| 7 (nice-to-have) | Full tied-ranking support threaded through STV/Schulze/Borda counting (defined splitting rule); final Java 25 idiom pass (sealed exhaustiveness, record patterns, unnamed vars) |

## 8. Open questions worth deciding before coding

- YAML vs. TOML/HOCON for config — recommend YAML (Jackson reuse), flag if you'd rather not.
- Multi-module (separate `stv`/`condorcet`/`borda` artifacts) vs. single module — only matters if you want to publish the core ballot model independently later.
- Practical candidate/seat cap for CPO-STV/Schulze-STV — needs a number once you know what elections you'll realistically run through it.

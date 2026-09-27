# Ranked Choice Voting Calculator

A Java library and CLI for tabulating ranked-choice elections: Single Transferable Vote (STV,
plus Meek's method), Schulze, Borda, CPO-STV, and QPQ are implemented; Schulze-STV is deferred (see
below), and the Cambridge, MA "Cincinnati method" ruleset needs its own separate engine. See
[PLAN.md](PLAN.md) for the full design and roadmap; this file covers what's built and how to
use it today.

## Status

Currently implemented (Phase 1–3 of the plan, in progress):

- Ballot model with built-in support for tied rankings (not yet consumed by the STV
  counter — see below)
- `.blt` and CSV ballot parsers, both streamed line-by-line rather than loaded into memory
- STV counting engine, pluggable per axis:
  - Quotas: Droop, Hare, Hagenbach-Bischoff, Imperiali, and a Droop-rounded-to-N-decimal-
    places variant (`droop-rounded:N`) used by ERS97
  - Transfer methods: fractional/inclusive Gregory, the Weighted Inclusive Gregory Method
    (WIGM), and two last-parcel-only variants (only a candidate's single most-recently-arrived
    batch of ballots is examined for a surplus — everything they held before that stays with
    them, unchanged, for good). Both last-parcel variants divide the transfer ratio by only
    the parcel's ballot papers that have a further preference — papers with none carry their
    existing value forward into non-transferability unscaled, rather than being counted in
    the denominator the way WIGM/fractional's inclusive denominator (every vote the candidate
    holds) does. They differ in *where* rounding is applied: `last-parcel` rounds the shared
    ratio itself once (ERS97/76, Tasmania's Hare-Clark), while `last-parcel-deferred-rounding`
    keeps the ratio exact and rounds only each destination candidate's own resulting gain (the
    ACT Legislative Assembly's Hare-Clark variant). Fractional and WIGM agree exactly until a
    candidate holds ballots that arrived at more than one distinct value, which is where
    WIGM's per-parcel rounding diverges from the simpler method's single rounded ratio;
    last-parcel methods can leave a just-elected candidate with more or less than exactly
    quota, unlike the two inclusive methods — a known, documented trade-off of that approach.
    Both last-parcel variants also transfer an eliminated candidate's ballots one value-group
    at a time (largest value first, per ERS97's "exclusion by value"), and a candidate who
    crosses quota partway through is frozen immediately and stops receiving that elimination's
    remaining, lower-valued groups — rather than deciding who's elected and running every
    transfer the instant a candidate qualifies, these rulesets defer each surplus transfer,
    processing the single largest still-pending one before ever considering an elimination
  - Rounding rules: exact (no rounding), truncate to N decimal places — real STV rulesets
    consistently round down rather than to nearest, so that's the only rounding rule kept
    here for now
  - Tie-break rules: fixed declared order, previous-round votes, first-preference count
    (each with a configurable fallback), seeded random
- A CLI that runs a count from a ballot file and prints a round-by-round report. Rules
  come from a bundled ruleset preset (`--ruleset`), a YAML config file (`--config`),
  individual override flags (`--quota`/`--transfer`/`--rounding`/`--tiebreak`), or defaults
  (Droop quota, fractional transfer, exact rounding, declared-order tie-break) — in that
  order of precedence, flag over config over default
- Four report formats — text, CSV, JSON, and a self-contained HTML page — selectable via
  `--report-format`, a config file's `reportFormat` field, or inferred from `--output`'s file
  extension. All four render the exact same figures (the same `RoundingRule`-formatted decimal
  strings), just reshaped per format; JSON also exposes the underlying structured tree
  (`StvJsonReport.toReport`) for callers that want objects rather than a serialized string
- A second counting method, Meek's method (`--method meek`), selectable alongside classic
  quota-based STV (`--method stv`, the default) — structurally different from every quota/
  transfer combination above: instead of moving discrete "parcels" of ballots, every ballot's
  value is recomputed from scratch each round by walking its full ranking against every
  candidate's current keep factor, and the quota itself is recalculated every round rather than
  fixed once at the start. Elected candidates' keep factors are converged iteratively (per the
  original algorithm's own 0.001% tolerance) using `double` arithmetic rather than this
  project's exact `Fraction` type — a deliberate exception, since Meek's own termination rule
  is a numeric tolerance and there is no exact value to converge to. `MeekCounter` still
  produces the same `StvResult`/`RoundRecord` shape classic STV does (each round's figures
  converted to `Fraction` only when the round's snapshot is recorded), so all four report
  formats above work for a Meek count unchanged. Only `roundingRule` and `tiebreak` are
  configurable for Meek — quota and transfer method are fixed by the method itself, so
  `--quota`/`--transfer` are rejected alongside `--method meek`. Implemented from the original
  algorithm specification (Hill, Wichmann & Woodall, "Algorithm 123: Single Transferable Vote
  by Meek's Method", *The Computer Journal* 30(3), 1987, rules 2.1-2.9), validated with
  hand-derived fixtures that simulate those same rules independently (`MeekCounterTest`), and
  validated against a real election: MapLibre's 2025 Governing Board election (6 candidates,
  5 seats, 92 ballots, run on OpaVote) — real ballot data plus OpaVote's own published
  per-round keep factors, both mirrored on GitHub by MapLibre itself
  (`testdata/meek/maplibre-governing-board-2025.blt`, `MeekMapLibreGoverningBoard2025FixtureTest`).
  This real fixture caught a genuine bug during development: the first implementation fully
  converged the currently-elected candidates' keep factors to tight tolerance before ever
  checking whether a hopeful had crossed quota, which happened to give the same eventual
  winners but produced visibly wrong intermediate keep-factor figures, caught by comparing this
  project's own iteration trace directly against OpaVote's published per-round numbers. Fixed
  by checking for newly-qualifying hopefuls on every convergence iteration instead of gating it
  behind full convergence (see `MeekCounter#runOneStage`'s javadoc for the detailed why); the
  corrected engine's final keep factors now agree with OpaVote's published figures to 5-6
  significant digits, the residual being ordinary double-precision implementation variance
  rather than a structural disagreement
- Five bundled ruleset presets, shipped as YAML resources rather than hardcoded — `scottish`,
  `ers97`, `ers76`, `hare-clark-qpq`, `act-hare-clark` — each a named combination of the
  building blocks above, with sourced rules and links under
  [Bundled rulesets](#bundled-rulesets) below
- Validated against **nine real elections**, not just hand-derived or adapted fixtures: the
  2012 Shetland South, 2012 Orkney North Isles, 2017 Shetland North, and 2022 Orkney Kirkwall
  East council elections (ballot-level data from
  [MGGG's `scot-elex` archive](https://github.com/mggg/scot-elex)),
  the larger 2022 Badenoch and Strathspey (Highland Council) election, converted directly
  from that council's own official raw preference-profile record, and the 2020 ACT
  Legislative Assembly election's Kurrajong electorate (28 candidates, 5 seats, 50,600
  ballots), converted from Elections ACT's own official ballot-paper preference data. Run
  through their respective rulesets, each reproduces its actual result exactly — quota,
  first-preference tallies, and who was elected — independently cross-checked against each
  election's published or officially declared result, not derived from our own engine
  (`src/test/resources/testdata/scottish/`, `src/test/resources/testdata/act/`). Badenoch and
  Strathspey goes further: its full 8-round count is replayed and checked stage-by-stage
  against Highland Council's official "Candidate Votes Per Stage Report", to 5-decimal
  precision, with the expected figures stored as a resource file rather than hardcoded into
  the test (`BadenochAndStrathspeyFullReplayTest`,
  `highland-2022-badenoch-and-strathspey.expected.txt`); Kurrajong's round 2 is checked
  against the official "Final Distribution of Preferences" report to the full 6 decimal
  places the ACT Electoral Act specifies; North Isles (Orkney, 2012) is the one case where
  the final seat is settled by elimination rather than any surplus transfer, and reproduces
  that too. When multiple candidates meet quota in the same tally snapshot, they're declared
  elected as a simultaneous batch (largest surplus transferred first), matching official STV
  rules and each real election's published stage-by-stage figures exactly
- The remaining three of those nine are a different kind of real-election validation: three
  constituencies from **Ireland's 2002 general election** (Dublin North, Dublin West, Meath —
  137,861 real ballots total), from
  [PrefLib's "Irish Election Data"](https://www.preflib.org/dataset/00001) (originally donated
  by OpenSTV's Jeffrey O'Neill). Unlike the six elections above, this isn't a replay of each
  constituency's own official count — real Irish Dáil counts historically transfer surplus
  votes by physically resampling a random subset of the surplus parcel at full value, not by
  any deterministic ratio, so no reproducible official round-by-round record exists at ballot
  level. What's independently verifiable, and checked here: every first-preference tally
  matches each constituency's published Wikipedia result exactly; the final elected set
  matches the TDs actually returned exactly (9 seats across the three); and running the
  bundled `scottish` ruleset (the closest widely-used deterministic equivalent) against the
  real ballot data reproduces, stage by stage, the exact same figures as an independent
  implementation of that same method (`jklundell/droop`), matched to droop's own
  5-decimal-place precision at every stage (`Irish2002GeneralElectionFixtureTest`,
  `src/test/resources/testdata/irish/`)
- Also validated against the Electoral Reform Society's own worked "model election" from the
  ERS97 rulebook (11 candidates, 6 seats, 753 votes) — not a real election, but not
  self-generated either: the ballot data and expected figures come from an independent,
  third-party STV implementation's own correctness test
  ([pyRCV2](https://yingtongli.me/git/pyRCV2)), not derived from our own engine
  (`src/test/resources/testdata/ers97/`, `Ers97ModelElectionFixtureTest`). This is what
  surfaced the value-group elimination behavior described above: a naive single-shot
  elimination credited a candidate with 108.89 votes at the point they crossed quota
  mid-elimination, where the rulebook's own count sheet shows 108.68. It also caught a second,
  independent bug, this time in which candidate(s) get excluded: ERS97 rule 5.2.5 allows
  excluding several of the lowest continuing candidates together in the same stage ("bulk
  exclusion") whenever their combined votes, plus any deferred surplus, still couldn't reach
  the candidate above them, rather than always eliminating just one at a time. The original
  implementation only ever excluded one candidate per stage; cross-checking against a real,
  independent implementation ([OpenSTV](http://www.openstv.org/)) reproducing this exact model
  election showed it batches two candidates (Wright and Glazier) together at the
  corresponding stage — and that eliminating them one at a time instead, as this project
  originally did, changes the final winner from the rulebook's own Vicar to a wrong Baron.
  Fixed by adding a `batchExclusion` ruleset flag (`StvRuleset`) that grows the excluded batch
  upward from the lowest continuing candidate while enough candidates remain to fill the
  remaining seats and the batch's combined tally, plus deferred surplus, doesn't exceed the
  tally of the next candidate above it, and by generalizing the existing value-group
  elimination machinery to pool every excluded candidate's held ballots together rather than
  just one's (`StvCounter#selectCandidatesToExclude`, `#eliminateByValueGroup`). Enabled only
  for the bundled `ers97`/`ers76` presets — confirmed unneeded for Scottish/WIGM (the statute,
  [SSI 2007/42 rule 50](https://www.legislation.gov.uk/ssi/2007/42), only ever excludes one
  candidate) and left unresolved for Hare-Clark/QPQ (no accessible primary source document one
  way or the other)
- Two structurally different, non-STV methods: **Schulze** (`--method schulze`) and **Borda**
  (`--method borda`). Both are Condorcet/positional methods, not quota-based, so they don't use
  `--seats`, `--quota`, `--transfer`, `--rounding`, or `--tiebreak` at all, and — since a
  pairwise comparison or a positional score has no transfer ratio that would need one — both
  are the first methods in this project to actually consume the `Ballot` model's built-in tied-
  ranking support (`info.fkhodkov.rankedchoice.schulze`/`.borda`, `SchulzeMethod`/
  `BordaMethod`). Schulze computes the pairwise preference matrix and the Floyd-Warshall-style
  strongest-path ("beatpath") closure over it (the "winning votes" variant, matching Schulze's
  own paper and the Debian project's devotee implementation), ranking candidates by how many
  others they beat this way; Borda supports classic scoring (`n-1, n-2, ..., 0`) and the
  Dowdall/Nauru system (`1, 1/2, 1/3, ...`, selected via `--borda-scoring dowdall`), with tied
  ranks on a ballot averaging the scores their span would otherwise be worth. Schulze is
  validated against Wikipedia's worked 5-candidate example, a hand-derived genuine pairwise
  cycle (A beats B beats C beats A in direct comparisons, resolved cleanly to A>B>C by
  strongest path), a hand-derived perfect dead-heat (a genuine, unresolvable tie for the win),
  a tied-ranking ballot, an unranked-candidate case, and a real Debian General Resolution's own
  published pairwise tally (`SchulzeMethodTest`, `SchulzeDebianGr2004002FixtureTest`) — ballot-
  level Debian data isn't public (GR ballots are secret), so that fixture checks the
  strongest-path/ranking computation directly against the real matrix rather than a full
  ballot replay. A real *cycle* specifically — where beatpath routing actually changes the
  outcome versus a plain Condorcet winner — appears to be very rare in practice: an analysis of
  Debian's own election history found all 7 of its early leader elections had a clean
  Condorcet winner, and no other documented real election with a published genuine cycle was
  found either, so the cycle case above is hand-derived rather than sourced from a real
  election. Borda is validated against two independent constituencies from the real 2019
  Nauruan parliamentary election, Anabar and Boe (`BordaNauru2019AnabarFixtureTest`,
  `BordaNauru2019BoeFixtureTest`), plus hand-derived cases for `ModifiedBorda`, a partial
  ballot mixed with full ones, a three-way tie, a single-candidate election, and a blank
  ballot (`BordaMethodTest`) — real per-voter ballots aren't public for Nauru either, but
  Dowdall scoring is linear in each candidate's own (rank, count) marginal alone, so a
  synthetic ballot set reconstructed to match each real published marginal table is
  mathematically guaranteed to reproduce the same official scores, which it does, exactly, for
  both constituencies. Only a text report exists for either method so far — `--report-format
  csv/json/html` isn't supported yet for `--method schulze`/`borda`
- **CPO-STV** (`--method cpo-stv`, Nicolaus Tideman's Comparison of Pairs of Outcomes by STV) —
  a fundamentally different shape again: instead of one count with a single sequence of
  elections/eliminations, it exhaustively enumerates every possible `seats`-sized candidate
  subset ("outcome"), scores every pair of them against each other via a small notional
  STV-style count (excluding candidates in neither outcome, transferring the surplus only of
  candidates *shared* by both — a candidate unique to just one outcome keeps whatever they end
  up with), and picks whichever outcome beats every other — falling back to the same
  beatpath-completion idea `SchulzeMethod` uses (reimplemented locally over `Fraction`-valued
  outcome scores rather than `SchulzeMethod`'s `BigInteger` vote counts) if no outcome wins
  outright. Reuses the existing `Quota`/`TransferMethod`/`RoundingRule` building blocks for the
  notional count (`--quota`/`--transfer`/`--rounding` all apply; `--tiebreak` doesn't, since
  there's no elimination to break a tie for). The number of outcomes is `C(candidates, seats)`,
  and comparisons scale quadratically in that, so `CpoStvCounter.MAX_OUTCOMES` caps it at 500
  outcomes (at most 124,750 comparisons) rather than letting a larger election silently hang —
  there's no real-world precedent to size this against, since CPO-STV sees virtually no
  practical use for exactly this reason — as of a 2021 analysis, CPO-STV has never actually been
  used for a public election, so there's no real-world data to validate against; every fixture
  here is hand-derived (some with the help of a small independent Python re-implementation of
  this exact algorithm, used to search for and verify tricky cases before transcribing them into
  Java) rather than sourced from a real count. Validated against the CPO-STV Wikipedia article's
  own worked example (5 candidates, 3 seats); a case where WIGM and fractional transfer agree
  (confirming `CpoStvRuleset`'s transfer method is genuinely threaded through the notional
  count); ballots with no continuing preference becoming exhausted rather than miscounted; a
  genuine tie between four different outcomes; a genuine three-outcome cycle in *direct*
  pairwise comparison that the beatpath completion still resolves to a full strict ranking; and
  the `seats = candidates - 1` boundary case, where no outcome pair ever excludes anyone from
  standing and a shared candidate's transferred surplus can itself push a second shared
  candidate over quota, cascading a further transfer (`CpoStvCounterTest`). Only a text report
  exists so far (`CpoStvTextReport`), and it doesn't print the full pairwise score matrix (grows
  quadratically, usually too large to read) — just the winning outcome and the full ranking.
- **QPQ** (`--method qpq`, Douglas Woodall's Quota Preferential by Quotient — see his paper
  "QPQ, a quota-preferential STV-like election rule", *Voting matters* Issue 17, 2003) — not to
  be confused with the bundled `hare-clark-qpq` *ruleset* above, an unrelated classic-STV preset
  that happens to share the same three letters in its name. Structurally unlike every
  parcel-based method here: a ballot's vote is never split or devalued, it always counts as one
  full vote for whichever hopeful candidate currently tops its ranking. What changes instead is
  a per-ballot bookkeeping number, its *elected-credit* — how many candidates, fractionally, it
  has "helped elect" so far — used only to compute each hopeful candidate's quotient
  (`votes / (1 + elected-credit)`) and the quota (`active votes / (1 + seats - exhausted
  ballots' banked credit)`), both recalculated every stage rather than fixed once. If the
  highest quotient beats quota, that candidate is elected and every ballot currently topping
  them has its credit *overwritten* with `1/quotient`; otherwise the lowest-quotient candidate
  is permanently excluded and — this is the rule's distinctive, easy-to-miss feature — **the
  whole count restarts**: every previously-elected candidate returns to hopeful, every ballot's
  credit resets to zero, and counting resumes from scratch with one fewer candidate in the
  running. This makes QPQ structurally closer to `MeekCounter`'s whole-count iteration than to
  classic STV's incremental transfers, but simpler than Meek's method in one respect: since it's
  exact quotient arithmetic rather than a converging keep-factor, there's no numeric-tolerance
  loop needed anywhere, just a bounded, finite sequence of exact `Fraction` stages. Only
  `--tiebreak` applies (`--quota`/`--transfer`/`--rounding` don't — both are derived formulas,
  not pluggable). Validated with hand-derived cases (`QpqCounterTest`) — including one
  specifically constructed to exercise the restart-and-re-election mechanic, which caught a
  real bug during development (a naive "elect every remaining hopeful once the loop ends"
  cleanup step didn't check whether every seat was already filled by explicit elections,
  wrongly adding an extra winner) — and cross-checked against **two independent, genuine
  open-source implementations of this exact algorithm**: `jklundell/droop`'s `qpq.py` and
  OpenSTV's `QPQ.py`, both confirmed to implement Woodall's rule directly (unlike the false
  leads chased for `hare-clark-qpq` below, where neither tool turned out to implement Tasmania's
  actual rules). Six synthetic ballot sets plus one purpose-built restart-and-re-election case
  matched both reference implementations' quota and every candidate's quotient, stage by stage,
  to 9 decimal places — OpenSTV's own transcript even independently labels the exact stage this
  project's `restarted` flag marks as "Restart count."
- The two least-common quotas, `HareQuota` and `ImperialiQuota`, were previously only
  unit-tested in isolation (`QuotaTest`), never run end-to-end through `StvCounter`. Neither
  droop nor OpenSTV could validate either: droop's WIGM rule only ever computes Droop, and
  while OpenSTV's abstract `STV.py` base class has Hare-quota code, no shipped method plugin
  actually selects it and its own CLI has no option to switch quotas at all — tracing that
  dead code also showed it silently adds a "+1" that isn't part of the textbook Hare quota
  (plain `votes / seats`) this project implements. `ImperialiQuota` is closed with a
  hand-derived, self-consistency-only fixture — it also exercises a real edge case Imperiali
  is uniquely prone to among the bundled quotas: its smaller quota doesn't mathematically
  guarantee no more candidates can reach it than there are seats, so the fixture has three
  candidates meet quota in the same round for only two seats, with the existing
  batch-election machinery correctly dropping the third. `HareQuota`, though, turned out to
  have a genuine, actively-maintained independent implementation after all: the CRAN package
  [`STV`](https://cran.r-project.org/package=STV) (Emerson et al., Yale) implements the exact
  same unrounded `votes / seats` formula (`quotaMethod = "Hare", quotaFloor = FALSE`) with a
  matching `Fractional` surplus option — two fixtures (an exact-quota match with zero surplus,
  and a genuine fractional surplus transfer) both reproduce that package's round-by-round
  output exactly (`StvCounterTest`).

Not yet implemented: the Cambridge MA ruleset (it uses the "Cincinnati method" — whole-ballot
systematic sampling by literal individual-ballot sequence order, not aggregate ranking counts —
so it doesn't fit either counting engine's current `Ballot(ranking, count)` model at all, and
would need a ballot model extension plus its own separate engine; even then, Cambridge's own
official round reports publish only aggregate totals, never individual ballot transfers, so an
exact historical replay likely isn't achievable even with real ballot-level data) and
Schulze-STV (the other multi-winner Condorcet-STV extension, alongside CPO-STV above — deferred
specifically because its "proportional completion" step requires solving a genuine optimization
problem per pairwise outcome comparison, i.e. an LP-style solver, which is a real dependency
this project doesn't currently have and which the plan's own tooling section explicitly avoided
pulling in). Tied ballot rankings are rejected by the STV, Meek, CPO-STV, and QPQ counters
(CPO-STV's notional count reuses STV's own ballot-position logic, so it inherits the same
restriction); Schulze and Borda accept them.

Two rulesets are validated only at the arithmetic/config level, not against a real or model
election, and after a real search, likely can't be: ERS76 (see the `ers76` ruleset note
above — no surviving copy of the 1976 rulebook itself, or any election published under it
specifically, was found) and `hare-clark-qpq` (the Tasmanian Electoral Commission publishes
genuine, detailed official round-by-round scrutiny reports per election, but — unlike the
AEC or Elections ACT — never ballot-level preference data itself, so there's nothing to feed
the engine and check it reproduces those reports from). A Cambridge MA validation is still
ahead, blocked on the Cincinnati-method implementation above.

One known, documented gap versus official reporting: when a candidate's surplus transfer
causes a rounding truncation loss (e.g. a transfer ratio truncated from 0.1359375 to 0.13593),
official reports track that tiny loss as its own "non-transferable votes due to rounding"
line so their running total always reconciles exactly to the total valid vote count. This
engine doesn't track that loss as a separate bucket — it isn't attributed to any candidate or
to "exhausted" — so summing a round's candidate tallies plus its exhausted-vote count will be
a hair short of the true total by the rounding loss accumulated so far. This doesn't affect
who gets elected or any individual candidate's tally, which always match official figures
exactly; it only affects a full grand-total reconciliation, which this engine doesn't attempt.

Another known gap, specific to the last-parcel-family rulesets' deferred surplus transfers:
real ERS97 counting only defers a surplus when a vote-gap safety check proves doing so can't
possibly affect which candidate is lowest (and additionally supports "bulk exclusion" of
multiple safely-doomed candidates at once) — this engine instead always defers, transferring
the single largest pending surplus once nothing else is pending. Confirmed against both the
ERS97 model election and the real 2020 Kurrajong election: every individual tally, once
transferred, and the final elected set, still match exactly — only *when* a deferred surplus
gets transferred (and so the exact round/stage numbering beyond that point) can differ from
an official count.

## Requirements

- Java 25
- Maven

## Building

```
mvn package
```

This produces an executable jar at `target/rankedchoice-<version>.jar`.

## Running

```
java -jar target/rankedchoice-<version>.jar --input path/to/election.blt
```

| Option | Description |
|---|---|
| `-i, --input` (required) | Ballot file (`.blt` or `.csv`) |
| `-m, --method` | Counting method: `stv\|meek\|schulze\|borda\|cpo-stv\|qpq`. Defaults to `stv`. `--quota`/`--transfer` don't apply to `meek`; `schulze`/`borda` don't use `--seats`, `--quota`, `--transfer`, `--rounding`, or `--tiebreak` at all; `cpo-stv` uses `--seats`/`--quota`/`--transfer`/`--rounding` but not `--tiebreak`; `qpq` uses `--seats`/`--tiebreak` but not `--quota`/`--transfer`/`--rounding` (both are derived formulas, not configurable). |
| `-f, --format` | Input format: `blt` or `csv`. Inferred from the file extension if omitted. Can also come from `--config`'s `format` field. |
| `-s, --seats` | Seats to fill. Required for CSV input under `stv`/`meek`/`cpo-stv`/`qpq` (can also come from `--config`'s `seats` field); for BLT input, overrides the file's own seat count if given. Unused by `schulze`/`borda`. |
| `-o, --output` | Output file. Defaults to standard output. |
| `--report-format` | Report output format: `text\|csv\|json\|html` for `stv`/`meek`; only `text` exists so far for `schulze`/`borda`/`cpo-stv`/`qpq`. Defaults to `text`; inferred from `--output`'s extension (`.csv`/`.json`/`.html`/`.htm`) if that's given and this isn't. Can also come from `--config`'s `reportFormat` field. |
| `-c, --config` | YAML config file (see below). Mutually exclusive with `--ruleset`. |
| `-r, --ruleset` | A bundled ruleset preset by name: `scottish`, `ers97`, `ers76`, `hare-clark-qpq`, `act-hare-clark`. Mutually exclusive with `--config`. |
| `--quota` | `droop\|hare\|hagenbach-bischoff\|imperiali\|droop-rounded:N`. Overrides `--config`/`--ruleset`. |
| `--transfer` | `fractional\|wigm\|last-parcel\|last-parcel-deferred-rounding`. Overrides `--config`/`--ruleset`. |
| `--rounding` | `exact\|truncate:N`. Overrides `--config`/`--ruleset`. |
| `--tiebreak` | A comma-separated fallback chain: `declared\|previous-round\|first-preference\|seeded-random:SEED`, e.g. `previous-round,declared`. Overrides `--config`/`--ruleset`. |
| `--borda-scoring` | `classic\|dowdall`. Only applies to `--method borda`; defaults to `classic`. `dowdall` is the Nauru system (`1, 1/2, 1/3, ...`). |

### Rules and config

A YAML config file can set any combination of `quota`, `transfer`, `rounding`, `tiebreak`,
`seats`, `format`, `reportFormat`, and `batchExclusion` — all optional:

```yaml
quota: hare
transfer: wigm
rounding: truncate:5
tiebreak: previous-round,declared
reportFormat: json
```

`batchExclusion` (`true`/`false`, default `false`) enables ERS97 rule 5.2.5's "exclude as many
candidates together as possible" provision — see [Bundled rulesets](#bundled-rulesets) below.
There's no `--batch-exclusion` CLI flag: unlike quota/transfer/rounding/tiebreak, it's treated
as an intrinsic ruleset characteristic rather than something to override per run.

`--ruleset <name>` is exactly the same mechanism, pointed at a bundled config shipped as a
classpath resource (`src/main/resources/rulesets/<name>.yaml`) instead of a file you supply.
Precedence when combining sources is always **CLI override flag > config/ruleset file >
built-in default**, resolved independently per field — so `--ruleset scottish --transfer
fractional` is legal and just swaps out the transfer method on top of the Scottish preset.
Passing an unrecognized name (e.g. `--ruleset scotish`) lists all the ones below.

#### Bundled rulesets

| Name | Rules | Used for | Source |
|---|---|---|---|
| `scottish` | Droop + WIGM + truncate:5 | Scottish local government elections | [The Scottish Local Government Elections Order 2007, rule 48(3)](https://www.legislation.gov.uk/ssi/2007/42) |
| `ers97` | Droop rounded up to 2dp + last-parcel + truncate:2 + batch exclusion | Many UK organisations' internal elections | [opavote's citation of ERS97 rules 5.2.5 and 5.3.7](https://opavote.com/methods/ers97-stv-rules) (*How to Conduct an Election by the Single Transferable Vote*, 3rd ed., Electoral Reform Society, 1997) |
| `ers76` | Same settings as `ers97` | ERS97's predecessor | No dedicated technical spec or real-election data found distinct from ERS97 — [OpenTally's docs](https://yingtongli.me/opentally/docs/options.html) note one rule delta (quota rounds to 0dp above 100, not implemented here) but that's a third-party reimplementation, not the rulebook itself; treat as "believed similar," not verified-accurate |
| `hare-clark-qpq` | Droop + last-parcel + truncate:2 | Tasmanian House of Assembly and local government elections | [Tasmanian Electoral Commission, "Hare-Clark Electoral System in Tasmania"](https://www.tec.tas.gov.au/info/Publications/HareClark.html) |
| `act-hare-clark` | Droop + last-parcel-deferred-rounding + truncate:6 | ACT Legislative Assembly elections | [Electoral Act 1992 (ACT), Schedule 4, as reproduced in Elections ACT's Scrutineers information handbook](https://www.elections.act.gov.au/__data/assets/pdf_file/0008/2474981/Scrutineers-information-handbook.pdf) |

`hare-clark-qpq` and `act-hare-clark` share the "Hare-Clark" name but aren't the same rules —
Tasmania rounds the shared transfer ratio to 2 decimal places, while the ACT rounds each
destination candidate's own resulting gain to 6 decimal places instead (see
`LastParcelDeferredRoundingTransfer`'s javadoc). See [Status](#status) above for which of
these have been checked against real election data, and to what precision.

A tie-break value is a fallback chain, evaluated left to right: `previous-round,declared`
tries `previous-round` first and only falls back to `declared` if the tied candidates were
tied in every prior round too (or there's no history yet, e.g. a tie in round 1 itself).
`declared` always resolves on its own and needs no fallback.

### Try it

A sample ballot file is included at `examples/wikipedia-andrea-brad-carter-delilah.blt`
(adapted from Wikipedia's "Counting single transferable votes" worked example):

```
java -jar target/rankedchoice-<version>.jar --input examples/wikipedia-andrea-brad-carter-delilah.blt
```

## Ballot formats

**`.blt`** — the de facto format used by tools like OpenSTV:

```
<num candidates> <num seats>
[<withdrawn candidate ids, negative, e.g. "-2 -5">]
<count> <ranked candidate ids, ties as "2=3"> 0
...
0
"Candidate 1 name"
"Candidate 2 name"
...
["Election title"]
```

Blank lines and lines starting with `#` are ignored anywhere in the file.

**CSV** — a header row starting with `count`, followed by one column per preference rank:

```
count,rank1,rank2,rank3
42,Alice,Bob,
7,Bob,Alice=Carol,
```

A blank cell ends that ballot's ranking. Tied candidates within a rank are written as
`A=B`. Cells may be double-quoted (RFC4180-style, `""` as an escaped quote) to contain a
literal comma.

## Testing

```
mvn test
```

## Project layout

```
src/main/java/info/fkhodkov/rankedchoice/
├── core/
│   ├── model/     Candidate, Ballot
│   ├── ballot/    BltParser, CsvBallotParser
│   ├── config/    ElectionConfig, ConfigLoader (YAML, file or classpath resource)
│   └── util/      Fraction (exact rational arithmetic)
├── stv/
│   ├── quota/     Quota, DroopQuota, HareQuota, HagenbachBischoffQuota, ImperialiQuota,
│   │              RoundedUpQuota
│   ├── transfer/  TransferMethod, FractionalTransfer, WigmTransfer, LastParcelTransfer,
│   │              LastParcelDeferredRoundingTransfer, HeldParcel (the parcel abstraction
│   │              transfer methods operate on)
│   ├── rounding/  RoundingRule, Exact, TruncateDecimal
│   ├── tiebreak/  TieBreakRule, DeclaredOrderTieBreak, PreviousRoundVotesTieBreak,
│   │              FirstPreferenceCountTieBreak, SeededRandomTieBreak
│   ├── config/    StvRulesetParser (the quota/transfer/rounding/tiebreak string DSL)
│   ├── report/    StvTextReport, StvCsvReport, StvJsonReport, StvHtmlReport
│   ├── meek/      MeekCounter, MeekRuleset -- Meek's method, producing the same StvResult
│   └── StvCounter, StvRuleset, StvResult, RoundRecord, RoundAction
├── schulze/       PairwiseMatrix, SchulzeMethod, SchulzeResult, SchulzeTextReport
├── borda/         BordaMethod, BordaScoringRule (ClassicBorda, DowdallScoring, ModifiedBorda),
│                  BordaResult, BordaTextReport
├── cpostv/        CpoStvCounter, CpoStvRuleset, CpoStvResult, CpoStvTextReport
├── qpq/           QpqCounter, QpqRuleset, QpqResult, QpqRoundRecord, QpqAction, QpqTextReport
└── cli/
    └── Main

src/main/resources/rulesets/   bundled ruleset presets (scottish.yaml, ers97.yaml, ...)
```

## Roadmap

See [PLAN.md](PLAN.md).

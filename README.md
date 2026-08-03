# Ranked Choice Voting Calculator

A Java library and CLI for tabulating ranked-choice elections — starting with Single
Transferable Vote (STV), with Schulze, CPO-STV, and Borda planned. See [PLAN.md](PLAN.md)
for the full design and roadmap; this file covers what's built and how to use it today.

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
- A CLI that runs a count from a ballot file and prints a round-by-round text report. Rules
  come from a bundled ruleset preset (`--ruleset`), a YAML config file (`--config`),
  individual override flags (`--quota`/`--transfer`/`--rounding`/`--tiebreak`), or defaults
  (Droop quota, fractional transfer, exact rounding, declared-order tie-break) — in that
  order of precedence, flag over config over default
- Bundled ruleset presets, shipped as YAML resources rather than hardcoded: `scottish`
  (Droop + WIGM + truncate:5), `ers97` (Droop rounded up to 2dp + last-parcel + truncate:2),
  `ers76` (same settings as `ers97` — I couldn't find a technical spec distinguishing ERS76
  from ERS97, so treat this one as "believed similar," not verified-accurate), `hare-clark-qpq`
  (Droop + last-parcel + truncate:2, Tasmanian House of Assembly and local government
  elections specifically), and `act-hare-clark` (Droop + last-parcel-deferred-rounding +
  truncate:6 — the ACT Legislative Assembly's own Hare-Clark variant, which despite the
  shared name rounds each destination candidate's gain to 6 decimal places rather than
  rounding Tasmania's shared ratio to 2, per the ACT Electoral Act's Schedule 4)
- Validated against **five real elections**, not just hand-derived or adapted fixtures: the
  2012 Shetland South, 2017 Shetland North, and 2022 Orkney Kirkwall East council elections
  (ballot-level data from [MGGG's `scot-elex` archive](https://github.com/mggg/scot-elex)),
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
  places the ACT Electoral Act specifies. When multiple candidates meet quota in the same
  tally snapshot, they're declared elected as a simultaneous batch (largest surplus
  transferred first), matching official STV rules and each real election's published
  stage-by-stage figures exactly
- Also validated against the Electoral Reform Society's own worked "model election" from the
  ERS97 rulebook (11 candidates, 6 seats, 753 votes) — not a real election, but not
  self-generated either: the ballot data and expected figures come from an independent,
  third-party STV implementation's own correctness test
  ([pyRCV2](https://yingtongli.me/git/pyRCV2)), not derived from our own engine
  (`src/test/resources/testdata/ers97/`, `Ers97ModelElectionFixtureTest`). This is what
  surfaced the value-group elimination behavior described above: a naive single-shot
  elimination credited a candidate with 108.89 votes at the point they crossed quota
  mid-elimination, where the rulebook's own count sheet shows 108.68

Not yet implemented: the Meek transfer method, the Cambridge MA ruleset (it uses the
"Cincinnati method" — whole-ballot systematic sampling rather than fractional values — which
doesn't fit this engine's current value-based model and would need its own separate
implementation), Schulze/CPO-STV/Borda, and CSV/JSON/HTML export. Tied ballot rankings are
rejected by the counter for now. ERS76 still has no verified-accurate technical spec (see the
`ers76` ruleset note above) or real-election data of its own. More Scottish wards, an ERS76
validation source, and a Cambridge MA validation are still ahead.

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
| `-f, --format` | Input format: `blt` or `csv`. Inferred from the file extension if omitted. Can also come from `--config`'s `format` field. |
| `-s, --seats` | Seats to fill. Required for CSV input (can also come from `--config`'s `seats` field); for BLT input, overrides the file's own seat count if given. |
| `-o, --output` | Output file. Defaults to standard output. |
| `-c, --config` | YAML config file (see below). Mutually exclusive with `--ruleset`. |
| `-r, --ruleset` | A bundled ruleset preset by name: `scottish`, `ers97`, `ers76`, `hare-clark-qpq`, `act-hare-clark`. Mutually exclusive with `--config`. |
| `--quota` | `droop\|hare\|hagenbach-bischoff\|imperiali\|droop-rounded:N`. Overrides `--config`/`--ruleset`. |
| `--transfer` | `fractional\|wigm\|last-parcel\|last-parcel-deferred-rounding`. Overrides `--config`/`--ruleset`. |
| `--rounding` | `exact\|truncate:N`. Overrides `--config`/`--ruleset`. |
| `--tiebreak` | A comma-separated fallback chain: `declared\|previous-round\|first-preference\|seeded-random:SEED`, e.g. `previous-round,declared`. Overrides `--config`/`--ruleset`. |

### Rules and config

A YAML config file can set any combination of `quota`, `transfer`, `rounding`, `tiebreak`,
`seats`, and `format` — all optional:

```yaml
quota: hare
transfer: wigm
rounding: truncate:5
tiebreak: previous-round,declared
```

`--ruleset <name>` is exactly the same mechanism, pointed at a bundled config shipped as a
classpath resource (`src/main/resources/rulesets/<name>.yaml`) instead of a file you supply.
Precedence when combining sources is always **CLI override flag > config/ruleset file >
built-in default**, resolved independently per field — so `--ruleset scottish --transfer
fractional` is legal and just swaps out the transfer method on top of the Scottish preset.

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
│   ├── report/    StvTextReport
│   └── StvCounter, StvRuleset, StvResult, RoundRecord, RoundAction
└── cli/
    └── Main

src/main/resources/rulesets/   bundled ruleset presets (scottish.yaml, ers97.yaml, ...)
```

## Roadmap

See [PLAN.md](PLAN.md).

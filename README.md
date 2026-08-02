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
    (WIGM), and last-parcel-only (only a candidate's single most-recently-arrived batch of
    ballots is examined for a surplus — everything they held before that stays with them,
    unchanged, for good; used by ERS97/76 and Hare-Clark). Fractional and WIGM agree exactly
    until a candidate holds ballots that arrived at more than one distinct value, which is
    where WIGM's per-parcel rounding diverges from the simpler method's single rounded ratio;
    last-parcel methods can leave a just-elected candidate with more or less than exactly
    quota, unlike the two inclusive methods — a known, documented trade-off of that approach
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
  from ERS97, so treat this one as "believed similar," not verified-accurate), and
  `hare-clark-qpq` (Droop + last-parcel + truncate:2)

Not yet implemented: the Meek transfer method, the Cambridge MA ruleset (it uses the
"Cincinnati method" — whole-ballot systematic sampling rather than fractional values — which
doesn't fit this engine's current value-based model and would need its own separate
implementation), Schulze/CPO-STV/Borda, and CSV/JSON/HTML export. Tied ballot rankings are
rejected by the counter for now. Nothing has been validated yet against real published
Scottish council or Cambridge election results — that's still ahead.

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
| `-r, --ruleset` | A bundled ruleset preset by name: `scottish`, `ers97`, `ers76`, `hare-clark-qpq`. Mutually exclusive with `--config`. |
| `--quota` | `droop\|hare\|hagenbach-bischoff\|imperiali\|droop-rounded:N`. Overrides `--config`/`--ruleset`. |
| `--transfer` | `fractional\|wigm\|last-parcel`. Overrides `--config`/`--ruleset`. |
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
│   │              HeldParcel (the parcel abstraction transfer methods operate on)
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

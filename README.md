# Ranked Choice Voting Calculator

A Java library and CLI for tabulating ranked-choice elections — starting with Single
Transferable Vote (STV), with Schulze, CPO-STV, and Borda planned. See [PLAN.md](PLAN.md)
for the full design and roadmap; this file covers what's built and how to use it today.

## Status

Currently implemented (Phase 1–2 of the plan):

- Ballot model with built-in support for tied rankings (not yet consumed by the STV
  counter — see below)
- `.blt` and CSV ballot parsers, both streamed line-by-line rather than loaded into memory
- STV counting engine: **Droop quota + fractional (Gregory) transfer** only so far
- A CLI that runs a count from a ballot file and prints a round-by-round text report

Not yet implemented: other quotas (Hare, Hagenbach-Bischoff, Imperiali), other transfer
methods (WIGM, Meek), rounding rules, richer tie-break strategies, real-world ruleset
presets (Scottish, ERS97/76, Cambridge MA, Hare-Clark), Schulze/CPO-STV/Borda, YAML config,
and CSV/JSON/HTML export. Tied ballot rankings are rejected by the counter for now.

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
| `-f, --format` | Input format: `blt` or `csv`. Inferred from the file extension if omitted. |
| `-s, --seats` | Seats to fill. Required for CSV input; for BLT input, overrides the file's own seat count if given. |
| `-o, --output` | Output file. Defaults to standard output. |

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
│   └── util/      Fraction (exact rational arithmetic)
├── stv/
│   ├── quota/     Quota, DroopQuota
│   ├── transfer/  TransferMethod, FractionalTransfer
│   ├── tiebreak/  TieBreakRule, DeclaredOrderTieBreak
│   ├── report/    StvTextReport
│   └── StvCounter, StvRuleset, StvResult, RoundRecord, RoundAction
└── cli/
    └── Main
```

## Roadmap

See [PLAN.md](PLAN.md).

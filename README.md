# Ranked Choice Voting Calculator

A Java library and command-line tool for counting ranked-choice elections, under the rules
real elections use. It counts the Single Transferable Vote in its legislated variants (Scottish,
ERS97, Tasmanian and ACT Hare-Clark, Cambridge MA), Meek's and Warren's methods, Schulze,
Borda, CPO-STV and QPQ, and reports every round in text, CSV, JSON or HTML.

Every method is checked against something independent of this code: official counts of real
elections, independent implementations of the same method, or hand-worked cases. See
[VALIDATION.md](VALIDATION.md) for what was checked and how; [PLAN.md](PLAN.md) is the
original design.

## Methods

| Method | `--method` | What it is | Checked against |
|---|---|---|---|
| STV | `stv` (default) | Quota-based STV, assembled from a quota, transfer method, rounding rule and tie-break — or a [bundled ruleset](#bundled-rulesets) | 9 real elections (Scottish councils, ACT Kurrajong 2020, Irish Dáil 2002), the ERS97 model election, CRAN `STV`/`vote`, OpenSTV |
| Meek's method | `meek` | Every ballot recounted each iteration against converging keep factors; droop's fixed-point arithmetic | droop, exactly, on 144 elections incl. MapLibre's real 2025 election |
| Warren's method | `warren` | Meek's count, but an elected candidate takes a flat amount from each ballot | droop, exactly, on the same 144 |
| Cambridge STV | `cambridge` | Cambridge, MA: whole ballots, surpluses by the Cincinnati method (every n-th ballot) | OpenSTV, exactly, on 60 elections |
| QPQ | `qpq` | Woodall's Quota Preferential by Quotient: whole votes, quotients, a restart after each exclusion | droop and OpenSTV, to 9 decimal places |
| CPO-STV | `cpo-stv` | Tideman's: every possible committee compared with every other | hand-worked cases; an independent implementation (4,830 scores) |
| Schulze STV | `schulze-stv` | Schulze's: committees one swap apart compared by how well each could split the voters into separate quotas, then a beatpath | Schulze's own program and published figures on Tideman's 65 elections; the paper's worked example |
| Schulze | `schulze` | Condorcet beatpath ranking (winning votes) | Wikipedia's example, a Debian vote's published tally |
| Borda | `borda` | Positional scoring: classic, or Dowdall (Nauru) via `--borda-scoring`; tied ranks share their positions' points | Nauru 2019, two constituencies |

IRV is STV with one seat.

## Quick start

Requires Java 25 and Maven.

```sh
mvn package
java -jar target/rankedchoice-0.1.0-SNAPSHOT.jar --input examples/wikipedia-andrea-brad-carter-delilah.blt
java -jar target/rankedchoice-0.1.0-SNAPSHOT.jar --input election.blt --ruleset scottish
java -jar target/rankedchoice-0.1.0-SNAPSHOT.jar --input election.blt --method meek --report-format html --output result.html
```

`mvn test` runs the test suite, including the real-election fixtures.

## STV building blocks

A classic STV ruleset is a combination of independent choices:

- **Quota** (`--quota`): `droop` (whole votes, `floor(votes / (seats+1)) + 1`), `hare`
  (`votes / seats`), `hagenbach-bischoff` (`votes / (seats+1)`), `imperiali`, and
  `droop-rounded:N` (Droop rounded up to N decimal places, as ERS97 uses).
- **Transfer method** (`--transfer`):
  - `fractional` — inclusive Gregory: a surplus moves as one ratio across everything the
    candidate holds.
  - `wigm` — the Weighted Inclusive Gregory Method (Scottish local elections): the same, but
    rounded per parcel of ballots received at the same value.
  - `last-parcel` — only the candidate's most recently received parcel moves (ERS97, Tasmania),
    the ratio taken over ballots that have a next preference, then rounded.
  - `last-parcel-deferred-rounding` — the same, but each recipient's gain is rounded instead of
    the ratio (ACT).

  Under the two last-parcel methods an excluded candidate's ballots move one value group at a
  time, largest first, and surpluses wait until they're the largest pending, as in ERS97.
- **Rounding** (`--rounding`): `exact`, or `truncate:N` (round down to N decimal places, as real
  rulesets do).
- **Tie-break** (`--tiebreak`): a fallback chain of `declared` (candidate order),
  `previous-round`, `first-preference` and `seeded-random:SEED`, e.g. `previous-round,declared`.
- **Batch exclusion** (config `batchExclusion`): ERS97 rule 5.2.5 — exclude several of the
  lowest candidates at once when together they still couldn't overtake the next.

### Tied rankings and short ballots

These apply to every method that transfers votes (`stv`, `meek`, `warren`, `cambridge`, `qpq`,
`cpo-stv`); Schulze, Schulze STV and Borda count tied rankings natively (Schulze STV by the
method's own "proportional completion").

- **`--tied-rankings`** — what to do with a ballot ranking several candidates equally:
  - `reject` refuses to count the election;
  - `split` shares the ballot evenly among the tied candidates still standing — defined for
    fractional-transfer STV, Meek and Warren, which use it by default;
  - `truncate` ends the ballot at the tie, so a tie at the first preference voids it (the
    Scottish rule; every bundled preset uses this);
  - `skip` drops the tied rank and carries on.
  
  Everything else defaults to `reject`.
- **`--minimum-preferences N`** — a ballot ranking fewer than N candidates (after tied rankings
  are dealt with) is informal: not counted and not part of the valid vote. Capped at one fewer
  than the number of candidates. Tasmania's 7-preference rule is the use case.

Ballots set aside this way, and blank ones, are reported as informal ballots, with the reason.

## Bundled rulesets

`--ruleset NAME` loads a preset; any flag still overrides its fields
(`--ruleset scottish --transfer fractional` is legal).

| Name | Rules | Used for | Source |
|---|---|---|---|
| `scottish` | Droop + WIGM + truncate:5, tied rankings truncated | Scottish local government elections | [The Scottish Local Government Elections Order 2007, rules 43(1)(c) and 48(3)](https://www.legislation.gov.uk/ssi/2007/42) |
| `ers97` | Droop rounded up to 2dp + last-parcel + truncate:2 + batch exclusion, tied rankings truncated | Many UK organisations' internal elections | [opavote's citation of ERS97 rules 5.2.5, 5.3.7, and glossary 6.8–6.12](https://opavote.com/methods/ers97-stv-rules) (*How to Conduct an Election by the Single Transferable Vote*, 3rd ed., Electoral Reform Society, 1997) |
| `ers76` | Same settings as `ers97` | ERS97's predecessor | No technical spec or election data found distinct from ERS97 — [OpenTally's docs](https://yingtongli.me/opentally/docs/options.html) note one rule difference (quota rounded to 0dp above 100, not implemented here); treat as "believed similar", not verified |
| `hare-clark-qpq` | Droop + last-parcel + truncate:2, tied rankings truncated, minimum 7 preferences | Tasmanian House of Assembly elections (council elections: add `--minimum-preferences 3`) | [Tasmanian Electoral Commission, "Hare-Clark Electoral System in Tasmania"](https://www.tec.tas.gov.au/info/Publications/HareClark.html); formality from the [Electoral Act 2004 (Tas), ss.102–103](https://www.legislation.tas.gov.au/view/whole/html/inforce/current/act-2004-051) |
| `act-hare-clark` | Droop + last-parcel-deferred-rounding + truncate:6, tied rankings truncated | ACT Legislative Assembly elections | [Electoral Act 1992 (ACT), Schedule 4 and s.180](https://www.legislation.act.gov.au/a/1992-71/current/pdf/1992-71.pdf), as reproduced in [Elections ACT's Scrutineers information handbook](https://www.elections.act.gov.au/__data/assets/pdf_file/0008/2474981/Scrutineers-information-handbook.pdf) |

Each preset's tied-ranking rule comes from its rulebook: the Scottish order's rule 43(1)(c) (a
"1" against more than one candidate voids the paper), ERS97 glossary 6.8–6.12, the ACT Act's
Schedule 4 clause 2(2) with s.180(2)(c), and the Tasmanian Act's ss.102–103 (a duplicate above
the 7th preference ends the ballot there). `hare-clark-qpq` and `act-hare-clark` share the
"Hare-Clark" name but differ: Tasmania rounds the shared transfer ratio to 2 places, the ACT
each recipient's gain to 6. Despite its name, `hare-clark-qpq` is classic STV, unrelated to the
QPQ method.

## Command line

| Option | Description |
|---|---|
| `-i, --input` (required) | Ballot file (`.blt` or `.csv`) |
| `-m, --method` | `stv\|meek\|warren\|cambridge\|schulze\|borda\|cpo-stv\|schulze-stv\|qpq`. Defaults to `stv`. |
| `-f, --format` | Input format, `blt` or `csv`; inferred from the file extension if omitted. |
| `-s, --seats` | Seats to fill. Required for CSV input; for BLT, overrides the file's seat count. Not for `schulze`/`borda`, which rank every candidate. |
| `-o, --output` | Output file. Defaults to standard output. |
| `--report-format` | `text\|csv\|json\|html`, for every method. Otherwise taken from the config file's `reportFormat`, then `--output`'s extension, then `text`. |
| `-c, --config` | YAML config file (below). Not with `--ruleset`. |
| `-r, --ruleset` | A [bundled ruleset](#bundled-rulesets). Not with `--config`. |
| `--quota` | `droop\|hare\|hagenbach-bischoff\|imperiali\|droop-rounded:N` — `stv` and `cpo-stv`. |
| `--transfer` | `fractional\|wigm\|last-parcel\|last-parcel-deferred-rounding` — `stv` and `cpo-stv`. |
| `--rounding` | `exact\|truncate:N`. Defaults to `exact` — but see [Limitations](#limitations) for large counts. Not used by `cambridge`, `qpq`, `schulze`, `borda`; for `meek`/`warren` it only shapes the report. |
| `--tiebreak` | Fallback chain, e.g. `previous-round,declared`. Not used by `cpo-stv`, `schulze`, `borda`. |
| `--tied-rankings` | `reject\|split\|truncate\|skip` — see [above](#tied-rankings-and-short-ballots). |
| `--minimum-preferences` | Minimum preferences for a ballot to count — see [above](#tied-rankings-and-short-ballots). |
| `--precision` | Decimal places a `meek`/`warren` count works to; default 9 (droop's default). Changes the count itself, not just the report. An electorate too large for the chosen precision (about 4.6 billion votes at 9 places, 4.6 million at 12) is refused, naming the highest precision that fits. |
| `--borda-scoring` | `classic\|dowdall` for `--method borda`; default `classic`. |

Every flag overrides the config file or ruleset, field by field. A flag the chosen method has
no use for is refused with the reason, rather than silently ignored — e.g. `--quota` for
`meek`, `warren`, `cambridge` or `qpq` (the method fixes its own), `--tiebreak` for `cpo-stv`,
every STV option, `--seats` and `--ruleset` for `schulze`/`borda`, and everything but `--seats`
for `schulze-stv`. A config file may still
carry fields a method doesn't use.

### Config file

A YAML file (`--config`) can set any of `quota`, `transfer`, `rounding`, `tiebreak`, `seats`,
`format`, `reportFormat`, `batchExclusion`, `tiedRankings`, `minimumPreferences` and
`precision`:

```yaml
quota: hare
transfer: wigm
rounding: truncate:5
tiebreak: previous-round,declared
reportFormat: json
```

Bundled rulesets are the same thing, shipped in `src/main/resources/rulesets/`. Precedence is
always flag, then config or ruleset, then built-in default. `batchExclusion` has no flag: it's
treated as a fixed property of a ruleset.

## Reports

Every method reports as text, CSV, JSON or a self-contained HTML page, with the same figures in
each format:

- **STV, Meek, Warren, Cambridge**: every round's tallies, the action (elected with the surplus
  transferred, or excluded), exhausted votes and votes lost to rounding, the line official
  sheets call "non-transferable (due to rounding)" or "loss by fraction", so each round adds up
  to the total. (Meek and Warren fold it into their residual, as droop does.)
- **QPQ**: every stage's quota and quotients, whether it restarted, and the action.
- **CPO-STV**: the winning committee and the full ranking; JSON and CSV add every pairwise
  score, HTML a score table when there are at most 20 committees.
- **Schulze**: the pairwise and strongest-path matrices and the ranking.
- **Schulze STV**: the winning committee and, when several committees go unbeaten, those and the
  single-winner ranking that chose between them; JSON and CSV add every support `N[committee;
  outsider]`, HTML a support table when there are at most 200.
- **Borda**: every candidate's score and place.

Informal ballots are reported with their reasons — a leading line in text and HTML, an
`informalBallots` object in JSON, an `informal_ballots` column in CSV. Each method's JSON
structure is also available as objects (`StvJsonReport.toReport`, `QpqReports.toReport`, and
so on).

## Ballot formats

**BLT** — the format OpenSTV and most STV tools use:

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

Blank lines and lines starting with `#` are ignored. Ballot order is kept, which matters for
`cambridge`: a line with count k is k consecutive identical ballots.

**CSV** — a header starting with `count`, then one column per preference:

```
count,rank1,rank2,rank3
42,Alice,Bob,
7,Bob,Alice=Carol,
```

A blank cell ends the ranking; `A=B` is a tie; cells may be double-quoted.

## Limitations

- **Exact arithmetic can be slow.** With `--rounding exact` (the default), fractional transfers
  compound into ever larger fractions when ballots pass through many elected candidates — 300
  candidates, 33 seats and 40 preferences per ballot stalls even at 20,000 voters. The count
  warns on stderr when this starts; `--rounding truncate:9` keeps it bounded, and every bundled
  preset rounds anyway.
- **ERS97 surplus deferral.** Real ERS97 counts may put off a surplus and exclude first when it
  can't change who is excluded; this engine always transfers pending surpluses first. Tallies
  and winners still match the ERS97 model election and Kurrajong; only stage numbering can differ.
- **Not validated:** `ers76` and `hare-clark-qpq` (no data to check against); Cambridge STV
  against official Cambridge counts (no public ballot data; ballot order matters). See
  [VALIDATION.md](VALIDATION.md#not-validated).
- **CPO-STV** is capped at 500 possible committees (`C(candidates, seats)`), **Schulze STV** at
  50,000 committees and 20 seats; near that cap it needs about 1 GB of heap.
- **Schulze STV compares links by margin**, as the paper's worked example does. Schulze's own
  program ranks them differently, and its tie-break uses an older single-winner ranking, so on 2
  of Tideman's 65 elections it elects a different committee (see
  [VALIDATION.md](VALIDATION.md#schulze-stv)).
- **Cambridge STV depends on ballot order**: rarely at realistic sizes, but in small
  electorates with many seats another order can elect different people
  (`tools/order-sensitivity` measures it).

## Performance

End to end through the command line (JVM startup and parsing included) on a 14-core machine,
with every voter casting a distinct ballot ranking about 5 candidates:

| Method | 20 candidates, 5 seats, 100k voters | 20 / 5, 1M voters | 100 / 20, 100k voters |
|---|---|---|---|
| Borda | 0.7 s | 4.4 s | — |
| Schulze | 0.5 s | 1.4 s | 0.7 s |
| STV (fractional, exact) | 1.1 s | 3.5 s | 1.8 s |
| STV (`scottish`, WIGM) | 1.4 s | 3.7 s | 1.6 s |
| STV (`ers97`, last parcel) | 1.6 s | 3.7 s | 1.5 s |
| QPQ | 1.3 s | 3.8 s | 2.1 s |
| Cambridge STV | 1.3 s | 3.7 s | — |
| Meek / Warren | 1.6 / 1.7 s | 4.8 / 5.7 s | 6.4 / 7.2 s |

At 300 candidates, 33 seats and 1M voters ranking about 5 (or 40) candidates: Borda 3.8 s
(27 s), Schulze 1.7 s (11 s), `scottish` 6.9 s (16 s), `ers97` 8.9 s (26 s), QPQ 37 s (68 s), Meek
63 s (135 s), Warren 75 s (193 s). CPO-STV grows with the number of committee pairs instead:
10 candidates / 3 seats takes about 9 s at 100k voters, 12 / 4 about 34 s at 10k. Every method
counts 1M ballots within a 256 MB heap.

Schulze STV's cost grows with the number of contests of seats + 1 candidates, and with how many
distinct orders the voters give each contest's candidates. Of Tideman's 65 elections, 60 take
under 2 s and the slowest (16 candidates, 7 seats) about 15 s. Synthetic elections of 10,000
voters ranking 1–8 candidates: 20 candidates / 5 seats about 8 s, 24 / 5 (42,504 committees)
about 45 s, 16 / 8 about a minute.

## Tools

- [`tools/votekit`](tools/votekit/README.md) — generate realistic mock elections with
  [VoteKit](https://github.com/mggg/VoteKit) and compare how every method fills the seats.
- [`tools/order-sensitivity`](tools/order-sensitivity/README.md) — measure how much ballot
  order changes a Cambridge STV count.
- `tools/reference/cpo_stv.py` — an independent CPO-STV scorer used to check this one.
- [`tools/reference/schulze_stv`](tools/reference/schulze_stv/README.md) — check Schulze STV
  against Schulze's own program and published figures, and against an independent count.

## Project layout

```
src/main/java/info/fkhodkov/rankedchoice/
├── core/
│   ├── model/     Candidate, Ballot
│   ├── ballot/    BltParser, CsvBallotParser
│   ├── config/    ElectionConfig, ConfigLoader (YAML, file or classpath resource)
│   ├── report/    ReportMarkup (CSV quoting, HTML escaping and page, JSON writer)
│   └── util/      Fraction (exact rational arithmetic)
├── stv/
│   ├── quota/     Quota, DroopQuota, HareQuota, HagenbachBischoffQuota, ImperialiQuota,
│   │              RoundedUpQuota
│   ├── transfer/  TransferMethod, FractionalTransfer, WigmTransfer, LastParcelTransfer,
│   │              LastParcelDeferredRoundingTransfer, HeldParcel
│   ├── rounding/  RoundingRule, Exact, TruncateDecimal
│   ├── tiebreak/  TieBreakRule, DeclaredOrderTieBreak, PreviousRoundVotesTieBreak,
│   │              FirstPreferenceCountTieBreak, SeededRandomTieBreak
│   ├── config/    StvRulesetParser (the quota/transfer/rounding/tiebreak strings)
│   ├── report/    StvTextReport, StvCsvReport, StvJsonReport, StvHtmlReport
│   ├── meek/      MeekCounter, MeekRuleset, KeepRule, FixedPoint (Meek and Warren)
│   └── StvCounter, StvRuleset, StvResult, RoundRecord, RoundAction, TiedRankingPolicy,
│       BallotFormality, InformalBallots (tied rankings and short ballots, for every engine)
├── cambridge/     CambridgeCounter, CambridgeRuleset
├── qpq/           QpqCounter, QpqRuleset, QpqResult, QpqRoundRecord, QpqAction, QpqTextReport,
│                  QpqReports
├── cpostv/        CpoStvCounter, CpoStvRuleset, CpoStvResult, CpoStvTextReport, CpoStvReports
├── schulze/       PairwiseMatrix, SchulzeMethod, SchulzeResult, SchulzeTextReport, SchulzeReports
├── schulzestv/    SchulzeStvCounter, SchulzeStvResult, SchulzeStvTextReport, SchulzeStvReports
├── borda/         BordaMethod, BordaScoringRule (ClassicBorda, DowdallScoring, ModifiedBorda),
│                  BordaResult, BordaTextReport, BordaReports
└── cli/           Main

src/main/resources/rulesets/   bundled rulesets (scottish.yaml, ers97.yaml, ...)
src/test/resources/testdata/   real and reference elections (see VALIDATION.md)
tools/                         VoteKit mock elections, order sensitivity, reference scorer
```

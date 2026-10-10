# Ranked Choice Voting Calculator

A Java library and command-line tool for counting ranked-choice elections, under the rules real
elections use. It counts the Single Transferable Vote in its legislated variants (Scottish,
ERS97, Tasmanian and ACT Hare-Clark, Cambridge MA), Meek's and Warren's methods, Schulze, Borda,
CPO-STV, QPQ, Schulze STV and the Schulze proportional ranking, and reports every round in text,
CSV, JSON or HTML.

Every method is checked against something independent of this code: official counts of real
elections, independent implementations of the same method, or hand-worked cases. See
[VALIDATION.md](VALIDATION.md) for what was checked and how (its
[overview](VALIDATION.md#at-a-glance) puts it in two tables); [PLAN.md](PLAN.md) is the original
design.

## Methods

| Method | `--method` | What it is | Checked against |
|---|---|---|---|
| STV | `stv` (default) | Quota-based STV, assembled from a quota, transfer method, rounding rule and tie-break — or a [bundled ruleset](#bundled-rulesets) | every Scottish council ward with published ballots (1,070), all five ACT electorates in 2020 to the last decimal, every Senate count from 2016 to 2025, NSW's 2024 council elections (227 counts), Portland's 2024 City Council, single-seat contests in Alameda County (93, 2010–2025), San Francisco (53, 2010–2024) and Alaska (22, 2022–2024) New York City's primaries (124, 2021–2025) and Maine (16, 2018–2026) as `irv`, Minneapolis's multi-seat contests 2009–2025 and 29 of its single-seat races 2017–2025, Eastpointe, Michigan's 2019 and 2021 councils, Albany, California's 2022 and 2024 contests, Irish Dáil 2002, the ERS97 model election, OpenTally, CRAN `STV`/`vote`, OpenSTV |
| Meek's method | `meek` | Every ballot recounted each iteration against converging keep factors; droop's fixed-point arithmetic | droop, exactly, on 144 elections incl. MapLibre's real 2025 election |
| Warren's method | `warren` | Meek's count, but an elected candidate takes a flat amount from each ballot | droop, exactly, on the same 144 |
| Cambridge STV | `cambridge` | Cambridge, MA: whole ballots, surpluses by the Cincinnati method (every n-th ballot) | every count of Cambridge's 16 elections of 2001–2017 and the winners of 21 of its 22 of 2001–2023; OpenSTV, exactly, on 60 elections |
| QPQ | `qpq` | Woodall's Quota Preferential by Quotient: whole votes, quotients, a restart after each exclusion | droop and OpenSTV, to 9 decimal places |
| CPO-STV | `cpo-stv` | Tideman's: every possible committee compared with every other | hand-worked cases; an independent implementation (4,830 scores) |
| Schulze STV | `schulze-stv` | Schulze's: committees one swap apart compared by how well each could split the voters into separate quotas, then a beatpath | Schulze's own program and published figures on Tideman's 65 elections; the paper's worked example |
| Schulze proportional ranking | `schulze-proportional` | Schulze's: a party list built place by place, each candidate compared by how strongly the list so far plus them would hold against each other candidate, then a beatpath | every comparison behind Schulze's own program's published lists for Tideman's elections, to 6 decimals; the paper's worked example |
| Schulze | `schulze` | Condorcet beatpath ranking (winning votes) | OpenSTV on 500 elections, every pairwise figure and strongest path; pref_voting on 500 with tied rankings; Wikipedia's example, a Debian vote's published tally |
| Borda | `borda` | Positional scoring: classic, or Dowdall (Nauru) via `--borda-scoring`; tied ranks share their positions' points | Nauru 2019, two constituencies; OpenSTV on 500 elections, every score; pref_voting on 500 with tied rankings |

IRV is STV with one seat.

## Quick start

Requires Java 25 and Maven, and Python 3 for the tests of the reference tools.

```sh
mvn package
java -jar cli/target/rankedchoice-cli-0.1.0-SNAPSHOT.jar \
    --input library/src/test/resources/testdata/wikipedia/wikipedia-andrea-brad-carter-delilah.blt
java -jar cli/target/rankedchoice-cli-0.1.0-SNAPSHOT.jar --input election.blt --ruleset scottish
java -jar cli/target/rankedchoice-cli-0.1.0-SNAPSHOT.jar --input election.blt --method meek --report-format html --output result.html
```

`mvn test` runs the test suite, including the real-election fixtures, and then the Python tests of
the reference tools (`tools/reference/tests`); `-DskipTests` skips both, `-DskipPythonTests` the
Python ones alone, where Python isn't installed. Every build also runs
Checkstyle (`checkstyle.xml`: 4-space indentation, 120 columns, imports grouped static, `java`,
third-party, then this project's), and fails on any violation; `.editorconfig` sets the same
for editors.

There's no hosted CI. Instead, `git config core.hooksPath .githooks` turns on a pre-commit hook
that runs `mvn -o -q verify` on the staged files alone, in a temporary copy, and refuses the
commit if the build fails (about 30 seconds; `git commit --no-verify` skips it once).

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
  - `last-parcel` — only the candidate's most recently received parcel moves (ERS97), the ratio
    taken over ballots that have a next preference, then rounded.
  - `last-parcel-deferred-rounding` — the same, but each recipient's gain is rounded instead of
    the ratio (ACT).
  - `unweighted-inclusive` — every paper the candidate holds moves at surplus ÷ papers,
    whatever value it arrived at; each recipient's gain is rounded (Australian Senate).
  - `unweighted-exclusive:N` — the papers of the last parcel move at surplus ÷ papers, those
    with no next preference counted too, truncated to N places; each recipient's gain is
    rounded (Tasmania, `:6`).
  - `weighted-transferable` — every paper with a next preference moves at its value times the
    surplus over the transferable votes; each recipient's gain from each parcel is rounded
    (NSW councils).
  - `weighted-inclusive` — every paper moves at its value times the surplus over the votes the
    candidate holds, those with no next preference exhausting; each recipient's gain from each
    parcel is rounded (Western Australia's Legislative Council).
  - `fractional-truncated` — `fractional`, but each ballot's new value is rounded too, after the
    ratio is (Portland, Oregon).

  Under the last-parcel, unweighted and `weighted-inclusive` methods an excluded candidate's ballots move
  one value group at a time, largest first, and surpluses wait until they're the largest
  pending, as in ERS97 (or the earliest elected, with `surplusOrder: election`).
- **Rounding** (`--rounding`): `exact`, or `truncate:N` (round down to N decimal places, as real
  rulesets do).
- **Tie-break** (`--tiebreak`): a fallback chain of `declared` (candidate order),
  `previous-round`, `first-preference` and `seeded-random:SEED`, e.g. `previous-round,declared`.
- **Batch exclusion** (config `batchExclusion`): ERS97 rule 5.2.5 — exclude several of the
  lowest candidates at once when together they still couldn't overtake the next.
- **Surplus deferral** (config `deferSurpluses`, last-parcel only): ERS97 — let pending surpluses
  wait while exclusions go ahead, unless they could change who is excluded.
- **Votes required** (config `votesRequired`): ERS97 — also elect a candidate on reaching the total
  active vote divided by one more than the seats left, which falls below the quota as votes are
  lost.
- **Surplus order** (config `surplusOrder`): `size` (largest first, the default) or `election`
  (in order of election, as the Australian Senate, the ACT, Tasmania and NSW councils do).
- **Elect as they stand** (config `electAsTheyStand`): the Australian Senate's end of count —
  when an exclusion leaves as many candidates as seats, they're elected without its papers
  being transferred, and the last seat between two candidates goes to the one ahead.
- **Exclusion order** (config `exclusionOrder`), for the methods that exclude in steps: `value`
  (by transfer value, highest first, the default), `first-preferences-then-value` (first
  preferences, then the rest by value: Victoria) or `parcel` (each parcel in the order received:
  Western Australia). A tie for exclusion looks back through each of those steps.
- **Simultaneous surpluses** (config `simultaneousSurpluses`): everyone elected in a round has
  their surplus transferred together, and a candidate those transfers take to quota is elected
  in the next round, not at once (Portland, Oregon).
- **Mathematical elimination** (config `mathematicalElimination`, for the methods that move a
  surplus at once): Minneapolis — each round, unless enough candidates have reached quota to fill
  the seats, every candidate who can no longer win is defeated together before any surplus
  moves: the lowest whose votes, with every surplus still to move, are less than the next
  candidate's, and, with `rankingsElimination` too, anyone ranked on too few ballots still in
  play to reach the last of the seats left. Otherwise the largest surplus moves, one a round, its
  candidate receiving ballots until it does.
- **Quota each round** (config `quotaEachRound`, for the methods that move a surplus at once): the
  quota is recomputed before every round and every surplus on the whole votes the candidates then
  hold, so it falls as ballots exhaust (Alameda County's Dominion count of Albany in 2022). The
  report shows the first quota.
- **Elect sure winners** (config `electSureWinners`): NSW councils' end of count — the leaders
  for the seats left are elected as soon as the others together, with any surplus still to
  transfer, can't overtake the last of them.

### Tied rankings and short ballots

These apply to every method that transfers votes (`stv`, `meek`, `warren`, `cambridge`, `qpq`,
`cpo-stv`); Schulze, Schulze STV, the Schulze proportional ranking and Borda count tied rankings
natively (the last two of Schulze's by his "proportional completion").

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
| `ers97` | Droop rounded up to 2dp + last-parcel + truncate:2 + batch exclusion, surplus deferral and votes required for election, tied rankings truncated | Many UK organisations' internal elections | [opavote's citation of ERS97 rules 5.2.5, 5.3.7, and glossary 6.8–6.12](https://opavote.com/methods/ers97-stv-rules) (*How to Conduct an Election by the Single Transferable Vote*, 3rd ed., Electoral Reform Society, 1997) |
| `ers76` | Same settings as `ers97` | ERS97's predecessor | No technical spec or election data found distinct from ERS97 — [OpenTally's docs](https://yingtongli.me/opentally/docs/options.html) note one rule difference (quota rounded to 0dp above 100, not implemented here); treat as "believed similar", not verified |
| `hare-clark-qpq` | Droop + unweighted-exclusive:6 + truncate:0 (whole votes), surpluses in order of election, tied rankings truncated, minimum 7 preferences | Tasmanian House of Assembly elections | [Electoral Act 2004 (Tas), Schedule 4, and ss.102–103 for formality](https://www.legislation.tas.gov.au/view/whole/html/inforce/current/act-2004-051); the transfer value's 6 places from the [Tasmanian Electoral Commission](https://www.tec.tas.gov.au/info/Publications/HareClark.html) |
| `senate` | Droop + unweighted-inclusive + truncate:0 (whole votes), surpluses in order of election, last candidates elected as they stand | Australian Senate elections | [Commonwealth Electoral Act 1918, s.273](https://www.legislation.gov.au/C1918A00027/latest/text) |
| `nsw-lg` | Droop + weighted-transferable + truncate:0 (whole votes per parcel), surpluses in order of election, sure winners elected | NSW local government councillor elections | [Local Government (General) Regulation 2021 (NSW), Schedule 5](https://legislation.nsw.gov.au/view/html/inforce/current/sl-2021-0460#sch.5), as the NSW Electoral Commission counts |
| `vic-lc` | Droop + unweighted-inclusive + truncate:0 (whole votes), surpluses in order of election, exclusions first preferences first, last candidates elected as they stand | Victorian Legislative Council | [Electoral Act 2002 (Vic), s114A](https://content.legislation.vic.gov.au/sites/default/files/2022-06/02-23aa064%20authorised.pdf); checked against OpenTally only |
| `wa-lc` | Droop + weighted-inclusive + truncate:0 (whole votes per parcel), surpluses in order of election, exclusions parcel by parcel, last candidates elected as they stand | Western Australian Legislative Council | Electoral Act 1907 (WA), Schedule 1; checked against OpenTally only |
| `portland` | Droop + fractional-truncated + truncate:4, surpluses transferred together, overvotes skipped | Portland, Oregon City Council | [Portland City Code 2.08.030](https://www.portland.gov/code/2/08/030) |
| `irv` | Hare quota (so nobody is elected by threshold) + fractional: the fewest votes defeated round by round until one candidate is left | Single-seat instant-runoff, as Alameda County (Oakland, Berkeley), San Francisco, Alaska, New York City and Maine count it | Dominion's settings there ("IRV", no winners by threshold); checked against 93 of Alameda's official counts of 2010–2025 (to 2017, stopping at a majority), 53 of San Francisco's of 2010–2024 (before 2019, defeating several at once and stopping at a majority, which changes neither the rounds passed through nor the winner) 22 of Alaska's of 2022–2024 124 of New York City's primaries of 2021–2025 (write-ins eliminated first, the hopeless defeated together) and 16 of Maine's of 2018–2026 |
| `albany` | Droop + fractional-truncated + truncate:4, overvotes end the ballot | Albany, California City Council and Board of Education | Alameda County's Dominion settings, as its RCV Detailed Reports give them; checked against the County's 2022 and 2024 counts |
| `minneapolis` | Droop + fractional-truncated + truncate:4, mathematical elimination, overvotes skipped | Minneapolis Park Board at large and Board of Estimate and Taxation | Code of Ordinances 167.70 as enacted in 2006 ([FairVote's copy](https://archive.fairvote.org/media/implementation/Minneapolis%20IRV_Ordinance.pdf)); the rankings test and the surplus handling inferred from the City's 2017–2025 counts |
| `act-hare-clark` | Droop + last-parcel-deferred-rounding + truncate:6, surpluses in order of election, tied rankings truncated | ACT Legislative Assembly elections | [Electoral Act 1992 (ACT), Schedule 4 and s.180](https://www.legislation.act.gov.au/a/1992-71/current/pdf/1992-71.pdf), as reproduced in [Elections ACT's Scrutineers information handbook](https://www.elections.act.gov.au/__data/assets/pdf_file/0008/2474981/Scrutineers-information-handbook.pdf) |

Each preset's tied-ranking rule comes from its rulebook: the Scottish order's rule 43(1)(c) (a
"1" against more than one candidate voids the paper), ERS97 glossary 6.8–6.12, the ACT Act's
Schedule 4 clause 2(2) with s.180(2)(c), and the Tasmanian Act's ss.102–103 (a duplicate above
the 7th preference ends the ballot there). `hare-clark-qpq` and `act-hare-clark` share the
"Hare-Clark" name but differ: Tasmania divides a surplus over every paper of the last parcel,
truncates that value to 6 places and counts whole votes; the ACT divides it over the papers with
a next preference, keeps the value exact, and rounds each recipient's gain to 6 places. Despite
its name, `hare-clark-qpq` is classic STV, unrelated to the QPQ method.

## Command line

| Option | Description |
|---|---|
| `-i, --input` (required) | Ballot file (`.blt` or `.csv`) |
| `-m, --method` | `stv\|meek\|warren\|cambridge\|schulze\|borda\|cpo-stv\|schulze-stv\|schulze-proportional\|qpq`. Defaults to `stv`. |
| `-f, --format` | Input format, `blt` or `csv`; inferred from the file extension if omitted. |
| `-s, --seats` | Seats to fill. Required for CSV input; for BLT, overrides the file's seat count. Not for `schulze`/`borda`, which rank every candidate. |
| `-o, --output` | Output file. Defaults to standard output. |
| `--report-format` | `text\|csv\|json\|html`, for every method. Otherwise taken from the config file's `reportFormat`, then `--output`'s extension, then `text`. |
| `-c, --config` | YAML config file (below). Not with `--ruleset`. |
| `-r, --ruleset` | A [bundled ruleset](#bundled-rulesets). Not with `--config`. |
| `--quota` | `droop\|hare\|hagenbach-bischoff\|imperiali\|droop-rounded:N` — `stv` and `cpo-stv`. |
| `--transfer` | `fractional\|wigm\|last-parcel\|last-parcel-deferred-rounding\|unweighted-inclusive\|unweighted-exclusive:N\|weighted-transferable\|weighted-inclusive\|fractional-truncated` — `stv` and `cpo-stv`. |
| `--rounding` | `exact\|truncate:N`. Defaults to `exact` — but see [Limitations](#limitations) for large counts. Not used by `cambridge`, `qpq`, `schulze`, `borda`; for `meek`/`warren` it only shapes the report. |
| `--tiebreak` | Fallback chain, e.g. `previous-round,declared`. Not used by `cpo-stv`, `schulze`, `borda`. |
| `--tied-rankings` | `reject\|split\|truncate\|skip` — see [above](#tied-rankings-and-short-ballots). |
| `--minimum-preferences` | Minimum preferences for a ballot to count — see [above](#tied-rankings-and-short-ballots). |
| `--precision` | Decimal places a `meek`/`warren` count works to; default 9 (droop's default). Changes the count itself, not just the report. An electorate too large for the chosen precision (about 4.6 billion votes at 9 places, 4.6 million at 12) is refused, naming the highest precision that fits; past about 4.6 × 10^17 votes (the limit at 1 place), none does. |
| `--borda-scoring` | `classic\|dowdall` for `--method borda`; default `classic`. |

Every flag overrides the config file or ruleset, field by field. A flag the chosen method has
no use for is refused with the reason, rather than silently ignored — e.g. `--quota` for
`meek`, `warren`, `cambridge` or `qpq` (the method fixes its own), `--tiebreak` for `cpo-stv`,
every STV option, `--seats` and `--ruleset` for `schulze`/`borda`, everything but `--seats`
for `schulze-stv`, and everything for `schulze-proportional`. A config file may still
carry fields a method doesn't use.

### Config file

A YAML file (`--config`) can set any of `quota`, `transfer`, `rounding`, `tiebreak`, `seats`,
`format`, `reportFormat`, `batchExclusion`, `deferSurpluses`, `votesRequired`, `surplusOrder`,
`electAsTheyStand`, `electSureWinners`, `exclusionOrder`, `simultaneousSurpluses`, `mathematicalElimination`,
`rankingsElimination`, `quotaEachRound`, `tiedRankings`, `minimumPreferences` and `precision`:

```yaml
quota: hare
transfer: wigm
rounding: truncate:5
tiebreak: previous-round,declared
reportFormat: json
```

Bundled rulesets are the same thing, shipped in `library/src/main/resources/rulesets/`. Precedence is
always flag, then config or ruleset, then built-in default. `batchExclusion`, `deferSurpluses`,
`votesRequired`, `surplusOrder`, `electAsTheyStand`, `electSureWinners`, `exclusionOrder`,
`simultaneousSurpluses`, `mathematicalElimination`, `rankingsElimination` and `quotaEachRound`
have no flags: they're treated as fixed properties of a ruleset.

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
- **Schulze proportional ranking**: the list, and for any place with several potential winners,
  those and the single-winner ranking that chose; JSON and CSV add every comparison `H[b][c] =
  N[the list so far + b; c]` behind every place.
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

A blank cell ends the ranking; `A=B` is a tie; a double-quoted cell or name may hold a `,` or
`=` (`"Smith, Jr."`, `"X=Y"=Z` is X=Y tied with Z). Both formats may start with a UTF-8 byte order
mark, as spreadsheets' "CSV UTF-8" files do.

## Limitations

- **Exact arithmetic can be slow.** With `--rounding exact` (the default), each surplus roughly
  doubles the size of the fractions on the ballots it moves: the transfer ratio's denominator is
  the elected candidate's exact tally, as large as everything they hold. In the 300-candidate,
  33-seat, 1M-voter count at 40 preferences, ballot values' denominators reach 91, 239, 532,
  1,048, 2,226 and 4,577 bits as the 5th to 10th candidates are elected, and the 11th's surplus
  didn't finish in 5 minutes; the same count stalls even at 20,000 voters. At 5 preferences fewer
  ballots go that deep and it finishes in under 30 s. The count warns on stderr when this starts;
  `--rounding truncate:9` keeps it bounded, and every bundled preset rounds anyway.
- **Not validated:** `ers76` (no data to check against); `hare-clark-qpq`, `vic-lc` and `wa-lc`
  against real counts (no ballot data is published; they're checked against OpenTally instead); Cambridge STV's
  rounds for 2019–2023 (those years' ballot records don't mark overvotes). See
  [VALIDATION.md](VALIDATION.md#not-validated).
- **Meek and Warren** work in 64-bit fixed point: about 4.6 billion votes at the default 9
  places, fewer at more (see `--precision`). **Cambridge STV** transfers ballots one by one and
  takes at most 1,073,741,823. Larger counts are refused with the limit. Every other method
  counts in exact integers of any size.
- **CPO-STV** is capped at 500 possible committees (`C(candidates, seats)`), **Schulze STV** at
  50,000 committees and 20 seats; near that cap it needs about 320 MB of heap.
- **Schulze STV compares links by margin**, as the paper's worked example does. Schulze's own
  program ranks them differently, and its tie-break uses an older single-winner ranking, so on 2
  of Tideman's 65 elections it elects a different committee (see
  [VALIDATION.md](VALIDATION.md#schulze-stv)).
- **The Schulze proportional ranking** is capped at 64 candidates, and slows sharply with many
  candidates and truncated ballots: for committees of over 20, the minimising set is searched
  for in double precision (then its support computed exactly), and proportional completion is
  listed out, which can run to millions of orders; of Tideman's elections, the two with 26 and
  29 candidates (and 91–104 voters, most ranking few) didn't finish within 15–30 minutes. It compares
  links by margin, as the paper does, where Schulze's own program uses winning votes; see
  [VALIDATION.md](VALIDATION.md#schulze-proportional-ranking).
- **Cambridge STV depends on ballot order**: rarely at realistic sizes, but in small
  electorates with many seats another order can elect different people
  (`tools/order-sensitivity` measures it).

## Performance

End to end through the command line (JVM startup and parsing included) on a 14-core machine,
with every voter casting a distinct ballot ranking about 5 candidates (`tools/benchmark` makes
the elections and reruns every figure here; single runs vary by up to a third, more on a busy machine):

| Method | 20 candidates, 5 seats, 100k voters | 20 / 5, 1M voters | 100 / 20, 100k voters |
|---|---|---|---|
| Borda | 0.5 s | 1.9 s | 0.8 s |
| Schulze | 0.5 s | 1.1 s | 0.4 s |
| STV (fractional, exact) | 0.6 s | 2.0 s | 1.2 s |
| STV (`scottish`, WIGM) | 0.7 s | 2.1 s | 1.0 s |
| STV (`ers97`, last parcel) | 0.7 s | 2.1 s | 1.2 s |
| QPQ | 0.7 s | 1.7 s | 1.0 s |
| Cambridge STV | 0.5 s | 1.4 s | 0.5 s |
| Meek / Warren | 0.8 / 0.9 s | 2.9 / 2.9 s | 1.8 / 1.9 s |

At 300 candidates, 33 seats and 1M voters ranking about 5 (or 40) candidates: Borda 1.4 s (5.4 s),
Schulze 1.7 s (7.9 s), fractional STV 25 s exact (never finishes at 40: see
[Limitations](#limitations)) and 7.1 s with `--rounding truncate:9` (11 s), `scottish` 6.7 s (9.8 s),
`ers97` 8.1 s (10 s), QPQ 15 s (25 s), Cambridge STV 1.9 s (5.0 s), Meek 29 s (55 s), Warren 30 s
(60 s). CPO-STV grows with the number of committee pairs instead: 10 candidates / 3 seats takes
about 9 s at 100k voters, 12 / 4 about 34 s at 10k.

Memory: every method counts the 20-candidate, 1M-voter election above, and the 300-candidate one at
5 preferences, within a 256 MB heap. At 40 preferences the 300-candidate count needs 256 MB for
Borda and Schulze, 384 MB for fractional STV at `truncate:9`, `scottish`, `ers97` and Cambridge STV,
and 512 MB for QPQ, Meek and Warren (the smallest of 256, 384, 512, 768 MB, 1 GB, ... that worked).
About 150 MB of that is the ballots as read: a ballot with no tied ranks keeps its candidates as
two-byte indices. Java's default heap is a quarter of the machine's memory; a count that runs out
says so and suggests `java -Xmx`.

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
- [`tools/benchmark`](tools/benchmark/README.md) — generate large elections and time every
  method on them: the figures under [Performance](#performance).
- `tools/reference/cpo_stv.py` — an independent CPO-STV scorer used to check this one.
- [`tools/reference/schulze_stv`](tools/reference/schulze_stv/README.md) — check Schulze STV
  and the Schulze proportional ranking against Schulze's own program and published figures, and
  Schulze STV against an independent count.
- [`tools/reference/aec`](tools/reference/aec/README.md) — count every state's Senate election
  from 2016 to 2025 from the AEC's ballot data and check each round against the official
  distribution of preferences.
- [`tools/reference/nsw`](tools/reference/nsw/README.md) — download the NSW Electoral
  Commission's 2024 councillor counts and check each round against the official distribution
  of preferences.
- [`tools/reference/cvr_to_blt.py`](tools/reference/cvr_to_blt.py) — convert a ranked-choice cast
  vote record with a column per ranking (FairVote's collections, Minneapolis's records) to BLT,
  reading the ballots by each jurisdiction's rules: what an overvote, a skipped or repeated
  ranking, and a write-in do. The checkers below for Alameda, San Francisco, Alaska, New York,
  Maine, Minneapolis, Eastpointe and Albany use it.
- [`tools/reference/rcvcheck.py`](tools/reference/rcvcheck.py) — what the checkers of US
  ranked-choice counts share: downloading (each file's address kept in `.sources.json`, so a file
  asked for from another address is fetched again, and what's made from it remade), matching
  names, counting, comparing transfers, and matching each official
  round with one of this count's, in order, to the vote for the single-seat ones (Alameda, San
  Francisco, Alaska, New York, Maine) and to the official decimal places for the multi-seat ones
  (Minneapolis, Portland, Eastpointe, Albany).
- [`tools/reference/results_page.py`](tools/reference/results_page.py) — build the results page,
  [`tools/reference/results/real-elections.html`](tools/reference/results/real-elections.html):
  every jurisdiction's official-count checks at a glance, with every known difference and its
  cause. Every checker it draws on (the US ones, `aec`, `nsw`, `cambridge`, and Scotland's
  `check.py`, `stage_report.py` and `stages.py`) writes its results with `--results
  tools/reference/results/<name>.json`, and so do the ACT's and Ireland's fixture tests, asked to
  (`mvn -pl library -am test -Dtest='ActOfficialDistributionTest,Irish2002GeneralElectionFixtureTest'
  -Dsurefire.failIfNoSpecifiedTests=false -Dresults.dir=tools/reference/results`); the causes and the rules table are in
  `results/jurisdictions.json`. A difference no cause names shows on the page as unexplained,
  and the script reports it. VALIDATION.md's Official counts table is generated the same way
  (`--validation write`), and the Python tests fail when it no longer matches the results.
- [`tools/reference/refresh_results.py`](tools/reference/refresh_results.py) — refresh all of it in
  one go ([`tools/reference/README.md`](tools/reference/README.md) lays out the data it needs):
  given a data root with a directory per checker (`alameda/`, `nyc/`, `aec/`,
  `scotland/`, …), it runs every checker whose directory is there, then the ACT's and Ireland's
  fixture tests, then rebuilds the page and VALIDATION.md's table, and says which it skipped and
  why (`--only` for some checkers, `--dry-run` to see the plan). `--compare` changes nothing:
  it reruns the checkers on their records converted afresh and lists every contest whose result
  differs from the committed one, to check that a refactor of the engine, a checker or the
  converter leaves the results as they were. `--refetch` downloads again every file saved before
  its address was recorded, which is otherwise trusted.
- [`tools/reference/tests`](tools/reference/tests) — tests of those, on small made-up ballots
  and counts (every converter option; round matching with several defeated at once, repeated
  rounds and rounds out of order; the summary lines; the pass-report parser; the results page's
  figures and its unexplained-difference check), with nothing to
  download; `mvn test` runs them, or `python3 -m unittest discover -s tools/reference/tests`.
- [`tools/reference/portland`](tools/reference/portland/README.md) — convert Multnomah
  County's cast vote records for Portland's 2024 Council contests and check each round against
  the County's official results.
- [`tools/reference/minneapolis`](tools/reference/minneapolis/README.md) — convert the City of
  Minneapolis's cast vote records for its multi-seat contests of 2009–2025 and its single-seat
  races of 2017–2025, and check each round against the City's summary statements.
- [`tools/reference/alameda`](tools/reference/alameda/README.md) — check the `irv` ruleset
  against Alameda County's official reports for 93 Oakland, Berkeley and San Leandro contests of
  2010–2025, from FairVote's collection of single-winner cast vote records.
- [`tools/reference/sanfrancisco`](tools/reference/sanfrancisco/README.md) — check the `irv`
  ruleset against San Francisco's final results (as the Internet Archive kept them) for 53
  contests of 2010–2024, from FairVote's collection.
- [`tools/reference/alaska`](tools/reference/alaska/README.md) — check the `irv` ruleset
  against the Alaska Division of Elections' reports (as the Internet Archive kept them) for the 22
  contests of 2022–2024 that went past a first round, from FairVote's collection.
- [`tools/reference/nyc`](tools/reference/nyc/README.md) — check the `irv` ruleset against the
  New York City Board of Elections' certified round-by-round results for 124 primaries of
  2021–2025, from the Board's own cast vote records.
- [`tools/reference/maine`](tools/reference/maine/README.md) — check the `irv` ruleset against the
  Maine Secretary of State's RCV summary reports for 16 contests of 2018–2026, from FairVote's
  collection and, for 2026, the State's own exports of the ballots.
- [`tools/reference/eastpointe`](tools/reference/eastpointe/README.md) — convert Macomb County's
  cast vote records for Eastpointe, Michigan's 2019 and 2021 Council elections (from FairVote's
  collection) and check each round against the County's RCTab summaries.
- [`tools/reference/cambridge`](tools/reference/cambridge/README.md) — convert Cambridge's
  ballots from FairVote's collection, in the City's order, and check each election from 2001 to
  2023 against the official count sheets.
- [`tools/reference/albany`](tools/reference/albany/README.md) — convert Alameda County's cast
  vote records for Albany's 2022 and 2024 contests (from FairVote's collection) and check each
  round against the County's RCV Detailed Reports.
- [`tools/reference/act`](tools/reference/act/README.md) — convert Elections ACT's ballot data
  and official distributions of preferences into the ACT test fixtures.
- [`tools/reference/opentally`](tools/reference/opentally/README.md) — count random elections with
  OpenTally under each matching preset and compare every round with this project's.
- [`tools/reference/openstv`](tools/reference/openstv/README.md) — count random elections by
  Schulze and Borda with OpenSTV and compare every pairwise figure, strongest path, winner and
  score with this project's.
- [`tools/reference/pref_voting`](tools/reference/pref_voting/README.md) — count random elections
  with tied rankings by Schulze and Borda with pref_voting and compare the pairwise figures,
  defeats, winners and scores with this project's.
- [`tools/reference/pyrcv2`](tools/reference/pyrcv2/README.md) — count an election with pyRCV2
  (ERS97, exact Gregory, Scottish or Senate) and compare it with this one, round by round.
- [`tools/reference/scotland`](tools/reference/scotland/README.md) — count every Scottish council
  election in scot-elex and compare the winners with the declared ones.

## Project layout

Three Maven modules, built together from the root: `common` (what every method shares),
`library` (the counting methods, which depend on `common` alone) and `cli` (the command line,
packaged with both and its own dependencies into one runnable jar,
`cli/target/rankedchoice-cli-0.1.0-SNAPSHOT.jar`). Each module's tests sit beside its code; the
real-election fixtures are the library's.

```
common/src/main/java/info/fkhodkov/rankedchoice/
└── core/
    ├── model/     Candidate, Ballot, SingletonRanking (an untied ranking as candidate indices)
    ├── ballot/    BltParser, CsvBallotParser, ParsedElection, ParsedBallots, BallotParseException,
    │              NumberedLine (line numbers for errors; a leading byte order mark dropped)
    ├── config/    ElectionConfig, ConfigLoader (YAML, file or classpath resource)
    ├── report/    ReportMarkup (CSV quoting, HTML escaping and page, JSON writer)
    └── util/      Fraction (exact rational arithmetic)

library/src/main/java/info/fkhodkov/rankedchoice/
├── count/         Tabulator (a count by any method, from CountOptions and a config or ruleset),
│                  Tabulation, ReportFormat, Election (a ballot file read), Rulesets (the bundled
│                  presets): what the command line counts through
├── stv/
│   ├── quota/     Quota, DroopQuota, HareQuota, HagenbachBischoffQuota, ImperialiQuota,
│   │              RoundedUpQuota
│   ├── transfer/  TransferMethod, HeldParcel; FractionalTransfer, TruncatedFractionalTransfer
│   │              (Portland), WigmTransfer, LastParcelTransfer, LastParcelDeferredRoundingTransfer,
│   │              LastParcelSelection, UnweightedInclusiveTransfer (Senate),
│   │              UnweightedExclusiveTransfer (Tasmania), WeightedInclusiveTransfer (WA),
│   │              WeightedTransferableTransfer (NSW councils)
│   ├── rounding/  RoundingRule, Exact, TruncateDecimal
│   ├── tiebreak/  TieBreakRule, DeclaredOrderTieBreak, PreviousRoundVotesTieBreak,
│   │              FirstPreferenceCountTieBreak, SeededRandomTieBreak
│   ├── config/    StvRulesetParser (the quota/transfer/rounding/tiebreak strings)
│   ├── report/    StvTextReport, StvCsvReport, StvJsonReport, StvHtmlReport, ReportFormatting
│   ├── meek/      MeekCounter, MeekRuleset, KeepRule, FixedPoint (Meek and Warren)
│   └── StvCounter, StvCounterValidation, StvRuleset, StvResult, RoundRecord, RoundAction,
│       ExclusionOrder (the order an exclusion's papers move in), TiedRankingPolicy,
│       BallotFormality, InformalBallots (tied rankings and short ballots, for every engine)
├── cambridge/     CambridgeCounter, CambridgeRuleset
├── qpq/           QpqCounter, QpqRuleset, QpqResult, QpqRoundRecord, QpqAction, QpqTextReport,
│                  QpqReports
├── cpostv/        CpoStvCounter, CpoStvRuleset, CpoStvResult, CpoStvTextReport, CpoStvReports
├── schulze/       PairwiseMatrix, SchulzeMethod, SchulzeResult, SchulzeTextReport, SchulzeReports
├── schulzestv/    SchulzeStvCounter, SchulzeStvResult, SchulzeStvTextReport, SchulzeStvReports,
│                  SchulzeProportionalRanking, SchulzeProportionalResult, SchulzeProportionalReports,
│                  LargeCommitteeSupport (supports for committees past the closed form),
│                  Shares, LongDoubleMap (a completion's shares, dense or sparse)
└── borda/         BordaMethod, BordaScoringRule (ClassicBorda, DowdallScoring, ModifiedBorda),
                   BordaResult, BordaTextReport, BordaReports
library/src/main/resources/rulesets/   bundled rulesets (scottish.yaml, ers97.yaml, ...)
library/src/test/resources/testdata/   real and reference elections (see VALIDATION.md)

cli/src/main/java/info/fkhodkov/rankedchoice/
└── cli/           Main (picocli: the flags, files and exit codes, over count/)

tools/
├── votekit/                   VoteKit mock elections, compared across every method
├── order-sensitivity/         how much ballot order changes a Cambridge STV count
├── benchmark/                 large synthetic elections and the Performance figures
└── reference/                 the independent checks (see VALIDATION.md)
    ├── <place>/check.py       one jurisdiction's official counts (README.md lays out their data)
    ├── opentally/, openstv/, pref_voting/, pyrcv2/, schulze_stv/, cpo_stv.py   checks against others
    ├── rcvcheck.py, cvr_to_blt.py, refresh_results.py, results_page.py   shared pieces
    ├── results/               every checker's results, the results page, jurisdictions.json
    └── tests/                 the Python tests, line length and pyflakes included
.githooks/pre-commit           the build, run on every commit (see Quick start)
```

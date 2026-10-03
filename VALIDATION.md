# Validation

How each counting method and building block has been checked, against what, and to what
precision. The aim throughout is to check against something independent of this project's
own engine: official counts of real elections, independent implementations of the same
method (OpenSTV, droop, CRAN packages, pyRCV2), or, where neither exists, hand-derived cases.
Tests named below are under `src/test/java`, fixtures under `src/test/resources/testdata/`.

## Real elections

Nine real elections, run through their own rulesets, reproduce their actual results:

| Election | Ruleset | Checked | Source of ballots |
|---|---|---|---|
| Shetland South 2012, Orkney North Isles 2012, Shetland North 2017, Orkney Kirkwall East 2022 | `scottish` | quota, first preferences, winners | [MGGG `scot-elex`](https://github.com/mggg/scot-elex) |
| Badenoch and Strathspey 2022 (Highland Council) | `scottish` | every stage of the 8-round count, to 5 decimal places, against the council's "Candidate Votes Per Stage Report" | the council's official raw preference profile |
| Kurrajong 2020 (ACT Legislative Assembly; 28 candidates, 5 seats, 50,600 ballots) | `act-hare-clark` | quota, first preferences, winners; round 2 to the 6 decimal places the Act specifies, against the official "Final Distribution of Preferences" | Elections ACT's official ballot-paper data |
| Dublin North, Dublin West, Meath — Irish general election 2002 (137,861 ballots) | see below | first preferences and the TDs returned | [PrefLib "Irish Election Data"](https://www.preflib.org/dataset/00001) |

Every one is cross-checked against the election's published or officially declared result,
not against figures derived from this engine (`ScottishRealElectionFixtureTest`,
`OrkneyNorthIslesFixtureTest`, `BadenochAndStrathspeyFullReplayTest` with its expected figures
in `highland-2022-badenoch-and-strathspey.expected.txt`, `ActKurrajongFixtureTest`,
`Irish2002GeneralElectionFixtureTest`). North Isles is the one where the final seat is settled
by elimination rather than a surplus transfer. When several candidates reach quota in the same
round they're elected together, the largest surplus transferred first, as each official count
does.

The Irish elections are a different kind of check. Dáil counts transfer surpluses by physically
drawing a random sample of the last parcel at full value, so there is no reproducible official
round-by-round record at ballot level. What is checked: every first-preference tally matches
the published result, the elected set matches the TDs actually returned, and the `scottish`
ruleset (the closest common deterministic method) run on the real ballots matches an independent
implementation of that method, droop, at every stage to droop's 5 decimal places.

Also checked against real data:

- **Meek's method**: MapLibre's 2025 Governing Board election (6 candidates, 5 seats, 92
  ballots, run on OpaVote), from the real ballots and OpaVote's published per-round keep
  factors — see [Meek and Warren](#meek-and-warren).
- **Schulze**: a real Debian General Resolution's published pairwise tally — see
  [Schulze](#schulze).
- **Borda (Dowdall)**: two constituencies of Nauru's 2019 election — see [Borda](#borda).

## The ERS97 model election

The Electoral Reform Society's worked "model election" from the ERS97 rulebook (11 candidates,
6 seats, 753 votes), with ballots and expected figures from an independent STV
implementation's own correctness test ([pyRCV2](https://yingtongli.me/git/pyRCV2))
(`Ers97ModelElectionFixtureTest`). It caught two real bugs:

- **Exclusion by value.** A single-shot elimination credited a candidate with 108.89 votes at
  the point they crossed quota partway through an elimination, where the rulebook's count
  sheet shows 108.68. Last-parcel rulesets now transfer an excluded candidate's ballots one
  value group at a time, largest first, and a candidate who reaches quota partway through
  stops receiving the rest.
- **Bulk exclusion.** ERS97 rule 5.2.5 excludes several of the lowest candidates together
  whenever their combined votes, plus any pending surplus, still couldn't reach the candidate
  above them. Excluding Wright and Glazier one at a time instead turned the rulebook's winner,
  Vicar, into a wrong Baron; [OpenSTV](http://www.openstv.org/) batches the two, as the rulebook
  does. The `batchExclusion` ruleset flag now grows the batch upward from the lowest candidate
  while enough candidates remain for the seats and the batch's total plus pending surplus
  stays below the next candidate. Enabled for the `ers97`/`ers76` presets only: the Scottish
  statute ([SSI 2007/42 rule 50](https://www.legislation.gov.uk/ssi/2007/42)) only ever
  excludes one candidate, and for Hare-Clark no primary source settles it either way.

## Classic STV building blocks

- **Quotas.** Droop is exercised by every real election above. Hare is checked against the CRAN
  package [`STV`](https://cran.r-project.org/package=STV) (Emerson et al.), which implements
  the same unrounded `votes / seats` (`quotaMethod = "Hare", quotaFloor = FALSE`): two counts,
  one landing exactly on quota and one with a fractional surplus transfer, match its output
  round by round (`StvCounterTest`). OpenSTV's Hare quota is dead code (no method plugin
  selects it) and adds a "+1" that isn't part of the textbook formula. Hagenbach-Bischoff runs
  Wikipedia's "Counting single transferable votes" worked example, whose own working uses the
  unrounded 57/(2+1) = 19 (`HagenbachBischoffQuotaFixtureTest`). Imperiali has no
  independent implementation; a hand-derived case covers the edge it's prone to — three
  candidates reaching its smaller quota in the same round for two seats.
- **Transfer methods and rounding.** WIGM and truncation are exercised by the Scottish real
  elections and Badenoch and Strathspey's full replay; last-parcel by the ERS97 model election;
  last-parcel with deferred rounding by Kurrajong.
- **Split tied rankings** (fractional transfer). Checked against CRAN's
  [`vote`](https://cran.r-project.org/package=vote) package (Sevcikova, Silverman, Raftery),
  read at source level: when one of several tied candidates is elected only their own share is
  scaled by the surplus ratio, and when one is eliminated whoever remains at that rank inherits
  the *whole* pool next round, not just their own share. `StvCounterTest` has a hand-derived
  case for that inheritance.
- **Truncate and skip tied rankings.** Cross-checked against OpenSTV's own overvote cleaning
  ("San Francisco" for `truncate`, "Cambridge" for `skip`) run through its `ScottishSTV`
  method, on four synthetic ballot sets with hundreds of tied rankings: quota, winners and every
  round's tallies match exactly (`TiedRankingPolicyTest`, plus a `StvCounterTest` case where
  the two policies elect different winners). The bundled presets' choice of `truncate` comes
  from each rulebook's text — see the [README](README.md#bundled-rulesets).

## Meek and Warren

`MeekCounter` follows droop's Meek rule ([jklundell/droop](https://github.com/jklundell/droop),
`meek arithmetic=fixed defeat_batch=none`) in the same fixed-point arithmetic, and droop's
`warren` rule for Warren's method.

- **Against droop.** 144 elections (MapLibre plus 143 synthetic: the four tied-ballot sets
  above as truncated, skipped and split, 120 random, 7 hand-built covering exclusions decided by
  tie-break, iteration stopped by a stalled surplus, and counts ending by electing the
  remaining candidates): every round's tallies, residual and surplus match droop's report to
  the last decimal place, for Meek and for Warren, at the default 9 places and at 6, 12 and 13
  (`--precision`). Warren's figures differ from Meek's in 73 of the 120 random elections and its
  winners in 7, so the Warren check isn't vacuous. `MeekDroopReferenceTest` replays six stored
  droop reports; `MeekCounterTest` has small droop-confirmed cases, including one where Warren
  and Meek differ.
- **Tied rankings under `split`** deliberately differ from droop in one place: droop sends any
  ballot containing a tie down a separate path that stops at the first rank with no standing
  candidate — tied or not — losing the rest of the ballot, while its path for untied ballots
  carries on. A copy of droop with just that path patched to carry on matches every round of
  the four tied-ballot elections and of 60 random ones.
- **MapLibre 2025** (`MeekMapLibreGoverningBoard2025FixtureTest`): the winners, the election
  order, and the round-1 tallies match OpaVote's published results PDF exactly, and every
  figure matches droop. This fixture caught a real bug: the first implementation converged
  the elected candidates' keep factors fully before checking whether anyone else had reached
  quota, giving the right winners but visibly wrong intermediate keep factors against OpaVote's
  published figures. Checking for new winners on every iteration, as droop also does, fixed it.
  OpaVote's own arithmetic isn't published, so its keep factors agree only to 5–6 significant
  digits.
- **OpenSTV's `MeekSTV`** agrees on winners, but its round-by-round figures aren't comparable:
  it batch-eliminates and reports single keep-factor iterations as rounds.

## Schulze

Wikipedia's worked 5-candidate example; hand-derived cases for a genuine pairwise cycle (A
beats B beats C beats A, resolved to A>B>C by strongest paths), a perfect dead heat, a tied
ranking and an unranked candidate (`SchulzeMethodTest`); and a real Debian General Resolution's
published pairwise tally (`SchulzeDebianGr2004002FixtureTest`). Debian ballots are secret, so
that fixture checks the strongest-path computation and ranking against the real matrix rather
than replaying ballots. No real election with a published genuine cycle was found, so the cycle
case is hand-derived.

## Borda

Two constituencies of Nauru's 2019 parliamentary election, Anabar and Boe
(`BordaNauru2019AnabarFixtureTest`, `BordaNauru2019BoeFixtureTest`): per-voter ballots aren't
public, but Dowdall scoring depends only on each candidate's own (rank, count) totals, so a
ballot set rebuilt to match each published table must reproduce the official scores — and does,
exactly. Plus hand-derived cases for modified Borda, partial ballots, a three-way tie, a single
candidate and a blank ballot (`BordaMethodTest`).

## CPO-STV

CPO-STV has never been used in a public election, so there is no real data. Checked against:

- **Hand-derived cases** (`CpoStvCounterTest`), some found with a small independent Python
  implementation: Wikipedia's worked example (5 candidates, 3 seats); WIGM and fractional
  transfer agreeing; exhausted ballots; a four-way tie between outcomes; a three-outcome cycle
  that the beatpath completion still resolves; the `seats = candidates - 1` case where one shared
  candidate's surplus pushes another over quota; and equal surpluses transferring in declared
  order (this last one fixed a real bug: the order used to come from the outcome sets'
  iteration order, which varies between JVM runs — the same election scored differently on 5
  of 10 runs).
- **An independent implementation** (`tools/reference/cpo_stv.py`, written from the method's
  rules alone) on a realistic-size synthetic count: 8 candidates, 4 seats, 10,000 voters
  (`CpoStvNearTieCycleFixtureTest`). All 4,830 pairwise scores match exactly, and an
  independent Schulze computation over them picks the same committee. Committees differing by
  one equally popular candidate form a cycle there, and the beatpath completion elects a
  committee that loses head to head to the one every classic STV variant elects — a genuine
  difference between the methods, correct under CPO-STV's rules.

## Schulze STV

Schulze STV has never been used in a public election either, but its author published a
program and its output for 66 elections from Nicolaus Tideman's database (`schulze3.zip`,
http://m-schulze.9mail.de/schulze3.zip: `singl01g.cpp` and an `A*_stv.dat` file of supports per
election). Checked against them:

- **Every support.** In all 65 elections within the committee cap, all 315,112 supports `N[committee; outsider]`
  match the published values to the 6 decimals they're given in. That covers proportional
  completion (most of these ballots are truncated, and a few rank candidates equally) and the
  closed-form support. A90 (20 candidates, 12 seats, 125,970 committees) is over the cap;
  Schulze's program didn't finish it in 10 minutes either.
- **The paper's worked example**, A53 (section 8.2): every support, and the winning committee {a,
  d, g, j} (`SchulzeStvTideman53FixtureTest`, which checks all 1,260 supports from the published
  file).
- **Winners.** The count follows the paper (arXiv:1804.02973, section 8.1.3). The 2017 program
  differs from it in two places, so it isn't a reference for every winner: it compares links by
  how a candidate ranks within its contest rather than by margin, and its Stage 4 tie-break
  uses an older single-winner ranking ("TBRL"). The committees agree on 63 of the 65 elections.
  On A04 the link definitions disagree. On A34, five committees go unbeaten and the two
  single-winner rankings break the tie differently. An independent count written from the paper alone
  (`tools/reference/schulze_stv/schulze_stv.py`) agrees with this one on every election with
  at most 500 committees (58, including A04 and A34): all 48,233 supports, the potential
  winners and the winners. Five of the elections need Stage 4.
- **Hand-derived cases** (`SchulzeStvCounterTest`): the closed form; two opposed factions
  winning a seat each rather than the compromise candidate; the paper's own proportional
  completion example (56/44); completion with nobody to copy, where every order of the tied
  candidates is equally likely (a deliberately wrong equal split over above/below patterns
  fails it); and a Stage 4 tie-break.

`tools/reference/schulze_stv` reruns all of this: the conversion, the comparison with the
published supports and with Schulze's program (with the one fix it needs to build), and the
independent count.

Two conventions had to be settled from the program, not the paper. Completion is done per
contest, over its M+1 candidates only. In the database's letter format, a candidate named twice
on a ballot ties everyone ranked since their first mention with them. Reading those ballots any
other way changes the supports of the 7 elections that have them.

## QPQ

Checked against two independent implementations of Woodall's rule, droop's `qpq.py` and
OpenSTV's `QPQ.py`: six synthetic ballot sets and a purpose-built restart-and-re-election case
match both, quota and every quotient, stage by stage, to 9 decimal places (OpenSTV's transcript
even labels the stage this project marks `restarted` as "Restart count"). Truncated and skipped
tied rankings match both as well. Hand-derived cases (`QpqCounterTest`) include the
restart-and-re-election mechanic, which caught a real bug: a final "elect every remaining
hopeful" step didn't check whether the seats were already filled.

## Cambridge STV

`CambridgeCounter` follows OpenSTV's `CambridgeSTV` (OpenSTV 1.7,
[Conservatory/openstv](https://github.com/Conservatory/openstv) at commit 2a267a4).

- **Against OpenSTV**, on 60 generated elections (114 surplus transfers, batch exclusions in
  all of them, 19 tie-breaks): every stage's counts, the non-transferable total and the winners
  match exactly. Two deliberate rule mistakes — starting the every-n-th run one ballot late,
  and not closing a candidate who reaches quota mid-transfer — fail 50 and 44 of the 60.
  `CambridgeOpenStvReferenceTest` replays a stored OpenSTV count stage by stage
  (`testdata/cambridge/README.md` says how it was made); `CambridgeCounterTest` has a
  hand-worked case, confirmed by OpenSTV, where the Cincinnati method gives a whole surplus of
  29 to one candidate that fractional STV would split evenly.
- **Official counts**: not checked. Cambridge doesn't currently publish ballot-level data
  (DemoChoice once hosted 1997–2013; FairVote analysed a 2023 cast vote record), counting order
  matters, and the city's settings say "Random, every nth ballot", which may mean a random
  starting point where OpenSTV always starts at the n-th.
- **Ballot order** (`tools/order-sensitivity`): at 10,000 voters, 200 random orders of each of
  30 generated elections never changed the result. In smaller electorates with many seats it
  sometimes does, and at 1,000 ballots for 9 seats — a quota near the 50-vote cutoff — it can
  decide whether every seat is filled (53, 131 and 193 of 200 orders in three such elections,
  counted the same way by OpenSTV).

## Changes checked for identical results

Performance work replaced several engines' internals. Each change was checked against the
previous implementation on random elections — every result identical — with a deliberate
mistake confirming each check could fail: classic STV (2,000 elections, every quota, transfer,
rounding, batch exclusion, tied-ranking policy and minimum-preferences setting), QPQ (2,000),
CPO-STV (400, every pairwise score), Schulze (1,500, both matrices), the BLT parser (164 files),
Meek/Warren (byte-identical output at 100,000–1,000,000 voters, and droop on a
100,000-voter count), and Schulze STV (every exact support, the potential winners and winners
on Tideman's 65 elections and 300 random ones with ties and short ballots, plus byte-identical
JSON on three 10,000-voter synthetic counts). A full JVM-level audit of iteration-order dependence (66 method/election
pairs, 10 fresh JVM runs each) found the CPO-STV bug above and nothing else.

## Not validated

- **ERS76** — no surviving copy of the 1976 rulebook or any election published under it was
  found; the preset is ERS97's settings ("believed similar", not verified).
- **`hare-clark-qpq`** — the Tasmanian Electoral Commission publishes detailed round-by-round
  scrutiny reports but never ballot-level data, so there's nothing to replay.
- **Imperiali quota** — hand-derived only (see above).
- **Cambridge STV against official counts** — see above.

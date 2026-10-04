# Validation

How each counting method and building block has been checked, against what, and to what
precision. The aim throughout is to check against something independent of this project's
own engine: official counts of real elections, independent implementations of the same
method (OpenSTV, droop, CRAN packages, pyRCV2), or, where neither exists, hand-derived cases.
Tests named below are under `src/test/java`, fixtures under `src/test/resources/testdata/`.

## Real elections

Every Scottish council election with published ballots, all five ACT electorates in 2020, every
state's and territory's Senate count from 2016 to 2025, every NSW councillor count of 2024, and
three Irish constituencies, run through their own rulesets, reproduce their actual results:

| Election | Ruleset | Checked | Source of ballots |
|---|---|---|---|
| All 1,070 Scottish council ward elections with published ballots (Glasgow 2007; every council 2012, 2017, 2022) | `scottish` | the winners, against the declared results (the Electoral Management Board's returns for 2022, each council's election article for the rest); see below | [MGGG `scot-elex`](https://github.com/mggg/scot-elex) |
| Shetland South 2012, Orkney North Isles 2012, Shetland North 2017, Orkney Kirkwall East 2022, Coatbridge South 2017 | `scottish` | quota, first preferences, winners, and key stages | [MGGG `scot-elex`](https://github.com/mggg/scot-elex) |
| Badenoch and Strathspey 2022 (Highland Council) | `scottish` | every stage of the 8-round count, to 5 decimal places, against the council's "Candidate Votes Per Stage Report" | the council's official raw preference profile |
| All five electorates of the ACT Legislative Assembly 2020 (Brindabella, Ginninderra, Kurrajong, Murrumbidgee, Yerrabi; 23–32 candidates, 5 seats each, 269,251 ballots) | `act-hare-clark` | every round's tallies, votes exhausted and loss by fraction, exactly to the 6 decimal places the Act specifies, and the winners, against the official "Final Distribution of Preferences" | Elections ACT's official ballot-paper data |
| Australian Senate 2016, 2019, 2022 and 2025, all 32 state and territory counts (59,355,672 ballots; NSW alone up to 5.0 million) | `senate` | every round's tallies, exhausted votes and loss by fraction, to the vote, against the AEC's official distribution of preferences, and the senators in order of election; see below | the AEC's formal preference data |
| NSW local government elections 2024, all 227 councillor counts with published figures (118 councils; 3–73 candidates, 2–15 seats; 4,051,337 ballots) | `nsw-lg` | every round's tallies and votes lost, to the vote, against the NSW Electoral Commission's full distribution of preferences, and the councillors elected; see below | the Commission's ballot-paper data |
| Dublin North, Dublin West, Meath — Irish general election 2002 (137,861 ballots) | see below | first preferences and the TDs returned | [PrefLib "Irish Election Data"](https://www.preflib.org/dataset/00001) |

Every one is cross-checked against the election's published or officially declared result,
not against figures derived from this engine (`ScottishRealElectionFixtureTest`,
`OrkneyNorthIslesFixtureTest`, `BadenochAndStrathspeyFullReplayTest` with its expected figures
in `highland-2022-badenoch-and-strathspey.expected.txt`, `ActOfficialDistributionTest`, `ActKurrajongFixtureTest`,
`Irish2002GeneralElectionFixtureTest`). North Isles is the one where the final seat is settled
by elimination rather than a surplus transfer. When several candidates reach quota in the same
round they're elected together, the largest surplus transferred first, as each official count
does.

**Every Scottish council ward** (`tools/reference/scotland`): all 1,070 regular ward elections
with ballots in scot-elex elect the declared councillors, 1,068 matched automatically and two
by hand (a ballot file that truncates surnames, and a ward where Wikipedia's result box is wrong
and Denis Mollison's recount of the ballots confirms ours). That took one fix. When a surplus
transfer takes a candidate to quota, the official count elects them there and then, and the
next surplus passes them by; this engine used to wait until the next round, still crediting
them with the next transfer. In North Lanarkshire's Coatbridge South in 2017 that was decisive:
Fergus MacGregor took 2.15 votes of Tom Castles's surplus he never had, and Jim Brooks finished
0.58 ahead of Gordon Encinias at stage 6 instead of 0.12 behind, electing the wrong councillor.
It also corrected two fixture tests that had recorded the old behaviour as official (Shetland
North 2017, Orkney Kirkwall East 2022): their official results elect Emma Macdonald on 379.04 and
David Dawson on 340.55, the figures they reached when elected.

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

Each of the 136 rounds of the five ACT counts matches one of the official counts exactly:
the same exclusions in the same order, every candidate's votes, the votes exhausted and the
loss by fraction, to the last of the 6 decimal places. (The official sheets have more counts:
they record each value group of an exclusion separately.) Getting there took three changes to
the `act-hare-clark` count, each settled by the official figures:

- **Rounding per transfer step.** The Act rounds "BP x TV" down to 6 places per transfer, so a
  tally is a sum of each surplus's and each value group's rounded gain, and so are the votes
  exhausted. Rounding each candidate's running total instead left Kurrajong, Murrumbidgee and
  Yerrabi a few millionths off.
- **A surplus no paper can carry** (worth more than the transferable papers) is loss by
  fraction on the ACT's sheets: Brindabella's 1,428 of Joy Burch's 2,244. ERS97, as pyRCV2
  counts it, calls the same thing exhausted, which is what the other last-parcel rulesets keep.
- **The count stops when the last seat is filled**, even partway through an exclusion: Yerrabi's
  last count leaves James Milligan "partially excluded", his remaining papers untransferred.

Checking the exhausted votes and the loss also found two bugs in last-parcel transfers (`ers97`,
`ers76`, `act-hare-clark`, `hare-clark-qpq`):

- **Papers with no further preference** were moved to exhausted at full value. They stay with
  the elected candidate as part of the quota: Kurrajong's count 2 shows none of Barr's 49 such
  papers exhausted, and the ERS97 rulebook's stage 2 shows 0.59 non-transferable, not Smith's 11.
  Reported exhausted votes were too high (Kurrajong's by 1,647 by the end); tallies and winners
  weren't affected.
- **The transfer value** is a value per paper (surplus / transferable papers; the Act's
  "BP x TV"), not a fraction of each paper's own value. Applied as a fraction, a candidate
  elected on papers from an earlier surplus passed on only part of their surplus; and when the
  surplus exceeded what the transferable papers were worth, papers gained value, creating
  votes. Papers now move at the transfer value, or at their own value if that's less. Four of
  the five ACT electorates have such surpluses (Ginninderra's Elizabeth Kikkert, Murrumbidgee's
  Giulia Jones, ...): before the fix, rounds in each of them stopped matching the official
  counts, by up to 40 (Murrumbidgee) to 1,873 votes (Brindabella), though the same candidates
  were elected. On 40 random elections under
  each last-parcel ruleset, tallies after such surpluses changed and no winner did.

The rounding loss is checked against the officially published figures where they exist:
Highland's round 2 loses 0.0096 (Russell Jones's surplus of 174 at a truncated 0.13593 per
vote), ERS97's stages 2 and 4 the rulebook's 0.59, and every count of the five ACT electorates
the official loss by fraction.
On those 40 random elections, no ruleset ever reports a negative loss, which would mean votes
created.

**The Australian Senate** (`senate`): every state and territory in 2016, 2019, 2022 and 2025,
32 counts in all (`tools/reference/aec`), matches the AEC's official distribution of preferences
at every round: 1,771 rounds, each one an official count, every candidate's votes, the
exhausted votes and the loss by fraction to the vote, with the same senators elected in the
same order. 2016 was a double dissolution, 12 senators per state; NSW's count that year, 151
rounds over 4.5 million ballots, takes about 17 seconds. Tasmania 2016 and 2019 are test
fixtures (`AecSenateFixtureTest`); 2016 ends on s273(17), below.

Getting there took two corrections, both from the official counts:
- No bulk exclusion. The Act allows it (s273(13A)) only on narrower terms than the usual rule,
  and the AEC excluded one candidate at a time where the usual rule (pyRCV2's, adopted at first)
  would exclude several together: ACT 2019 excludes Cowton at count 9 and Angel at count 11. One
  at a time, ACT, SA and QLD 2019 all matched.
- The end of the count (`electAsTheyStand`). When an exclusion leaves as many continuing
  candidates as seats, they're elected on their votes as they stand, without the excluded
  candidate's papers moving (s273(18); NSW 2019 count 429, Davey and Faruqi); and the last seat
  between two continuing candidates goes to the one ahead, without excluding the other
  (s273(17); QLD 2019 count 288, Waters over Ketter; also Tasmania and SA in 2016, SA and WA in
  2022). Neither changed who was elected, only the
  final figures, and here possibly the order of election. Ireland's and Scotland's official
  counts transfer the last exclusion, so this is a Senate setting only.

pyRCV2's Senate counter (as its own AEC test configures it, in exact arithmetic, but without
bulk exclusion) checked 200 random elections too. Every candidate's votes and the exhausted votes
match in all 191 it can count (it crashes in its exclusion code on the other 9), and the loss by
fraction in 189. In the other 2 pyRCV2's value-group exclusion leaves the excluded candidate with
fractional or even negative votes, which this engine doesn't, so the loss it reports is off by
those. One of them is a fixture (`testdata/aec/pyrcv2/s219`), where surpluses go in order of
election rather than largest first.

**NSW councils** (`nsw-lg`): every councillor count of the 2024 NSW local government elections
the NSW Electoral Commission published figures for, 227 in all (`tools/reference/nsw`). 212
match its full distribution of preferences at every round: every candidate's votes and the
votes lost (exhausted plus loss by fraction, which it reports together), to the vote. The other
15 reach a tie for exclusion that no earlier count settles, which the Regulation leaves to lot;
the Commission's draw and this project's declared order part ways there. All 227 elect the
councillors the Commission declared. (Balranald's published distribution has no counts in it.)
Six are test fixtures (`NswLocalGovernmentFixtureTest`).

The Commission's distributions show each parcel's sub-count, and the rules came from them:

- **The surplus fraction** is the surplus over the transferable votes: the candidate's votes
  less the value of the papers with no further preference, which stay with them (Newcastle ward
  4: 4,172 over 10,397). Each paper moves at its value times that fraction.
- **Each candidate's gain from each parcel**, the papers received in one transfer at one value,
  is rounded down to whole votes; an excluded candidate's parcels all move in one count, each
  rounded on its own. A new transfer method, `weighted-transferable`, does both.
- **Surpluses go in order of election**, two elected on equal votes in one transfer in the
  order the count before set (Upper Lachlan: Searl ahead of Reynolds). That last part was a bug
  for every ruleset: the tie-break looked back from the start of the stage, not from the
  transfer that elected them.
- **The count ends once the leaders can't be overtaken** (`electSureWinners`): the leading
  candidates for the seats left are elected as soon as the rest together, with any surplus
  still to transfer, have fewer votes than the last of them (Blayney: Dorsett on 568, under the
  quota of 569, against 554). A surplus that could still change it is transferred first (Ballina
  B: Bailey's 440 lets Ramsey overtake Broadley).
- **A candidate elected mayor** leaves the councillor count: their papers go to the next
  preference, and those ranking no one else don't count towards the quota (Coffs Harbour, quota
  5,021 rather than 5,022). The quota is now on the votes the first count gives a candidate,
  which changes nothing where no candidate has withdrawn.

OpenTally's NSW preset counts the same way (below), short of one shortcut: where an exclusion
would leave as many candidates as seats, it elects them without transferring the excluded
candidate's papers. No 2024 count reached that point without the leaders already being sure of
their seats.

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
  does. The `batchExclusion` ruleset flag takes the largest such set that leaves enough
  candidates for the seats and doesn't split candidates on equal votes, as pyRCV2 does. Enabled
  for the `ers97`/`ers76` presets only: the Scottish statute
  ([SSI 2007/42 rule 50](https://www.legislation.gov.uk/ssi/2007/42)) only ever excludes one
  candidate, and for Hare-Clark no primary source settles it either way.

The count now replays the rulebook's count sheet in full: every round matches one of its
stages, every candidate's votes and the non-transferable votes, through the last stage. That
took two more of its provisions, as pyRCV2 counts them (`deferSurpluses` and `votesRequired`,
again only in the `ers97`/`ers76` presets), and one change for every last-parcel ruleset:

- **Surplus deferral.** Pending surpluses wait while exclusions go ahead, unless together they
  could change which candidate, or bulk exclusion, comes next. Duke's 1.10 waits to the end.
- **Votes required for election.** The total active vote divided by one more than the seats
  left, rounded up: as votes are lost it drops below the quota, and Prince (104.31) and Freeman
  (101.62) are elected on it. It's worked out again after each election, so one step can elect
  several candidates.
- **The count ends when the last seat is filled**, with any surplus untransferred.

pyRCV2 itself runs as a Python program (`tools/reference/pyrcv2`), so it also served as a
reference on 100 random elections (4–13 candidates, up to 1,000 voters): 99 match it in every
round. In the other, pyRCV2 elects four candidates for three seats (two tied exactly on the
votes required, and it elects both). Getting there meant fixing pyRCV2 on the way: it records
each stage without copying it, so its tie-break, which looks back to the last stage where the
tied candidates differed, only ever saw the current votes and fell through to its last resort.
(An earlier version of this page put one difference down to how this engine looks back for
ties; it was that bug.) Three of the random elections are fixtures (`testdata/ers97/pyrcv2`),
picked for what they exercise.

The same 100 elections check the inclusive rulesets against pyRCV2's weighted inclusive
Gregory counter:

- **Exact fractional transfer** (the default `stv`): all 100 match in every round, to the 10
  places this project reports exact values to.
- **`scottish`**: all 100 match within a hundredth of a vote, 64 exactly. The rest is
  rounding: when a surplus comes from ballots of different values, pyRCV2
  truncates one transfer value and scales each ballot by it, where the Scottish rules give each
  value its own truncated transfer value. Highland's official count sheet settles which is
  right: after Muriel Cockburn's surplus it shows Bill Lobban on 978.40465, as this engine
  counts; pyRCV2's way gives 978.40485.
- Five of them first differed in their last round: when one stage's surpluses filled the last
  seats, this engine still transferred the first surplus before recording the others'
  elections. pyRCV2 stops there, and so do official Scottish counts (Shetland North 2017 ends
  at stage 2, Orkney Kirkwall East 2022 likewise), so now this engine does too, under every
  ruleset: once the last seat is filled, no further surplus moves.

## OpenTally

[OpenTally](https://yingtongli.me/git/OpenTally) is an independent STV counter with presets for
many real rule sets, each option documented against its legislation. `tools/reference/opentally`
counts the same 200 seeded random elections (5–12 candidates, 1–5 seats, 30–600 voters) with
OpenTally and with this project, preset by preset, and matches every round to an OpenTally
stage: every candidate's votes, and the votes set aside (exhausted plus lost) to within 10⁻⁹.
OpenTally breaks a tie it can't settle backwards at random, where this project uses declared
order, so elections that differ after such a tie are counted apart.

| This project | OpenTally | Every round matches | After a random tie | Otherwise |
|---|---|---|---|---|
| default `stv` (exact Gregory) | weighted inclusive Gregory, rational | 192 | 8 | 0 |
| `scottish` | "Scottish STV" | 173 | 8 | 19, all within 0.001 |
| `act-hare-clark` | "Australian Capital Territory STV" | 188 | 9 | 3, set-aside only |
| `senate` (less `electAsTheyStand`) | "Australian Senate STV", without early election | 179 | 13 | 8, set-aside only |
| `hare-clark-qpq` (10× the voters) | Tasmania's Schedule 4, from OpenTally's options | 189 | 1 | 10, set-aside only |
| `nsw-lg` | "NSW Local Government STV" | 180 | 13 | 7, OpenTally's end-of-count shortcut |
| `ers97` | "ERS97" | 163 | 11 | 26, OpenTally's own exceptions |
| `meek` | "Meek STV (2006)" and "(New Zealand)" | the same winners in all 200 | | |

The differences that remain are understood:

- **Set-aside only** (`act-hare-clark`, `senate`, `hare-clark-qpq`): every candidate's votes
  match in every round. OpenTally's exclusion by transfer value leaves the excluded candidate
  holding fractional or even negative votes (C5 on −1.08 in one Senate election), as pyRCV2's
  does, so the totals it sets aside differ by those; and it doesn't round exhausted votes to the
  ACT's 6 places, which the official ACT counts do. This engine matches the AEC's and Elections
  ACT's official counts on both.
- **`scottish`**: fifth-decimal rounding of a surplus from ballots of different values, as with
  pyRCV2 above; Highland's official count sheet confirms this engine's.
- **`ers97`**: pyRCV2 agrees with this engine on all 26. OpenTally documents these as its own
  departures from ERS97: it doesn't defer a surplus exactly equal to the gap between the two
  lowest candidates ([E8]), and it defers for bulk exclusion differently ([E9]).
- **Meek**: OpenTally's Meek presets stop iterating as soon as the surplus left can't change an
  exclusion, and stop at their own tolerance, so the figures between exclusions aren't
  comparable; the winners are.
- **`nsw-lg`**: in all 7, OpenTally ends on a tie for the last seat by electing the other
  candidates without transferring the excluded one's papers; this engine transfers them first.
  The same councillors are elected (see [NSW councils](#real-elections)).
- **The Senate's end of count**: OpenTally also elects candidates who can no longer be
  overtaken, which the Act doesn't provide for (ConcreteSTV's reading of s273 agrees); with that
  off on both sides the counts are compared up to the end, which the AEC's 32 official counts
  check above.

It found three bugs, each now fixed:

- **ACT surplus order.** Schedule 4 clause 7 of the ACT's Act deals with the surplus of whoever
  reached quota at the earliest count first, the largest of several from the same count; this
  engine took the largest pending surplus. None of the 2020 counts had a larger surplus waiting
  behind an earlier one. `act-hare-clark` now has `surplusOrder: election`.
- **ERS97's votes required** was rounded down, not up: "rounding up" truncated the negated
  share, and truncation goes towards zero. With 29.59 active votes for 2 seats the votes required
  is 9.87, not 9.86, so a candidate on 9.86 must wait for the next surplus. The 100 random
  elections against pyRCV2 never hit it; `testdata/ers97/pyrcv2/ot11` is the one that did.
- **`hare-clark-qpq` didn't follow Tasmania's Act.** It divided a surplus over the papers with
  a next preference and truncated the transfer value to 2 places. Schedule 4 of the Electoral
  Act 2004 (Tas) divides it over every paper of the last parcel and counts each candidate's
  share in whole votes, and the Tasmanian Electoral Commission truncates the value to 6 places
  (its 2 places are for council elections); surpluses go in order of election. A new transfer
  method, `unweighted-exclusive:6`, does that.

OpenTally's CSV report can't show Meek's stages, so the tool reads its text report. It crashes
on some single-seat elections in a log message, which the tool reports apart.

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
  last-parcel with deferred rounding by the five ACT electorates.
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
- **OpenTally's Meek presets** ("Meek STV (2006)" and "(New Zealand)") elect the same candidates
  in all 200 of its random elections (see [OpenTally](#opentally)).
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
- **`hare-clark-qpq`** against official counts — the Tasmanian Electoral Commission publishes
  detailed round-by-round scrutiny reports but never ballot-level data, so there's nothing to
  replay. It's checked against the Act's Schedule 4 through OpenTally instead (see above).
- **Imperiali quota** — hand-derived only (see above).
- **Cambridge STV against official counts** — see above.

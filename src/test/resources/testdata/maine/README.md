# Maine ranked-choice contests, 2018–2026

Four of the Maine contests `tools/reference/maine/check.py` checks, counted with the `irv` ruleset
by `MaineIrvFixtureTest`: the 2018 race for Congress in the 2nd District (the first congressional
race decided by ranked choice, two independents defeated together), the 2020 Democratic primary
for State Senate District 11, the 2022 race for Congress in the 2nd District (an independent
and the write-ins defeated together), and the 2026 Republican primary for State Senate District 4
(one candidate eliminated a round). All four match the official count to the vote, every round.

- `<contest>.blt.gz`: the ballots, from FairVote's collection of single-winner RCV cast vote
  records (https://doi.org/10.7910/DVN/AMK8PJ, file `dataverseFile`), or for 2026 from the State's
  own exports (`check.py`'s `STATE_2026`, joined), converted by
  `tools/reference/cvr_to_blt.py --skipped two-end --write-in candidate` (two skipped rankings in
  a row end the ballot; an overvote ends it; a candidate ranked again counts once; write-ins are
  one candidate). Identical rankings are merged.
- `<contest>.official.json`: the Secretary of State's RCV Summary Report (`source`), read by
  `check.py`: each round's votes per candidate, and the winner.

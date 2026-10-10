# Eastpointe, Michigan, City Council, 2019 and 2021

Eastpointe's two at-large City Council elections by proportional ranked-choice voting (2 seats
each), checked by `EastpointeFixtureTest`.

- `<contest>.blt`: the ballots, from Macomb County's cast vote records as FairVote's collection of
  proportional RCV records has them (https://doi.org/10.7910/DVN/STVUET), converted by
  `tools/reference/cvr_to_blt.py --skipped two-end
  --write-in skip --repeat end`. Identical rankings are merged.
- `<contest>.official.json`: the RCTab summary of the County's count, as published on RCVis (the
  page at `source`): every round's tallies, and who was elected or defeated in it.

The 2021 summary's round 4 has Rob Baker on 1,058.8175, 2 votes more than its round 3 tally plus
the transfers it lists for that round, and than the total it carries into round 5; the test
holds it to the consistent 1,056.8175.

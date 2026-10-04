# Albany, California, City Council and Board of Education, 2022 and 2024

Albany's multi-seat ranked-choice contests (2 or 3 seats each), checked by `AlbanyFixtureTest`.

- `<contest>.blt`: the ballots, from Alameda County's cast vote records as FairVote's collection of
  proportional RCV records has them (https://doi.org/10.7910/DVN/STVUET), converted by
  `tools/reference/albany/cvr_to_blt.py`. Identical rankings are merged.
- `<contest>.official.json`: the County's Dominion "RCV Detailed Report" for the contest (the XML
  at `source`), read by `tools/reference/albany/check.py`: each round's threshold and candidates'
  votes, and the candidates elected. Names are spelled as the report spells them, which isn't
  always as the cast vote record does ("ROBIN D. LÓPEZ", "ROBIN D  LOPEZ").
- `albany-2022.yaml`: the County's settings for 2022, which the 2024 `albany` ruleset changed: the
  threshold recomputed every round, and 6 decimal places.

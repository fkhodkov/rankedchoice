# Alaska, House districts 28 (2022) and 38 (2024)

Two of the Alaska contests `tools/reference/alaska/check.py` checks, counted with the `irv`
ruleset by `AlaskaIrvFixtureTest`: both went to three rounds, and both match the Division of
Elections' count to the vote.

- `<contest>.blt`: the ballots, from FairVote's collection of single-winner RCV cast vote records
  (https://doi.org/10.7910/DVN/AMK8PJ), converted by `tools/reference/cvr_to_blt.py --skipped
  two-end`: an unresolved write-in is passed over, two skipped rankings in a row end the ballot,
  and so does an overvote. Identical rankings are merged. The checker now also reads a candidate
  ranked again as a skipped ranking (`--repeat skip`); for these two contests that gives the same
  file.
- `<contest>.official.json`: the Division's Dominion "RCV Detailed Report" for the contest, as the
  Internet Archive kept it (`source`; elections.alaska.gov turns away scripted requests): each
  round's votes per candidate, the candidates eliminated in order, and the winner.

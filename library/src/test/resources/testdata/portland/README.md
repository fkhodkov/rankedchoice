# Portland, Oregon, City Council District 4, 2024

The November 2024 general election's District 4 contest (3 seats, 30 candidates), checked by
`PortlandFixtureTest`.

- `district-4-2024.blt.gz`: the ballots, from Multnomah County's cast vote record for the contest
  (linked from https://multco.us/info/turnout-and-statistics-november-2024-general-election),
  converted by `tools/reference/portland/cvr_to_blt.py`. Identical rankings are merged.
- `district-4-2024.official.json`: the County's final RCTab tabulation summary for the contest, as
  published at https://rcvresults.multco.us (Final Results, 2 December 2024): every round's
  tallies, and who was defeated or elected in it.

Of the four districts, this is the one whose count turns on Portland's second truncation (each
ballot's new transfer value truncated to 4 places after the surplus fraction is).

# ACT Legislative Assembly election 2020

All five electorates (5 seats each), checked by `ActOfficialDistributionTest` and, for
Kurrajong's first counts, `ActKurrajongFixtureTest`.

- `act-2020-<electorate>.blt`: the ballots, from Elections ACT's "Ballot paper preference
  data" for the 2020 election (one row per preference per ballot paper), converted with
  `tools/reference/act/act_to_blt.py`. The Kurrajong file predates that tool and has its own
  header, but the tool reproduces its ballots exactly.
- `act-2020-<electorate>.official.txt`: Elections ACT's official "Final Distribution of
  Preferences" (table 2, votes), extracted with `tools/reference/act/act_table.py`: every
  candidate's votes, the votes exhausted and the loss by fraction at the end of each count, to
  the 6 decimal places the Electoral Act specifies.

Elections ACT's site blocks automated downloads; every file was retrieved from the Wayback
Machine (web.archive.org), from https://www.elections.act.gov.au/elections_and_voting/2020_legislative_assembly_election/
(`ballot-paper-preference-data-2020-election`, and `distribution-of-preferences-2020/table2_<Electorate>.xlsx`).
See `tools/reference/act/README.md` for the exact steps.

# NSW council elections, 2024

Six councillor counts from the 2024 NSW local government elections (event LG2401), checked by
`NswLocalGovernmentFixtureTest`; see its javadoc for what each exercises.

- `<name>.blt.gz`: the ballot papers, from the NSW Electoral Commission's published
  `finalpreferencedatafile.zip` for the count, converted by `tools/reference/nsw/nsw_to_blt.py`
  (Coffs Harbour's marks Nikki Williams, elected mayor, as withdrawn). Identical ballots are
  merged.
- `<name>.official.csv`: the Commission's official distribution of preferences
  (`dopfulldetails.xlsx`) as `tools/reference/nsw/nsw_table.py` writes it: a row per count with
  every candidate's votes, the votes lost (exhausted plus loss by fraction) and who that count
  elected.

Source: https://pastvtr.elections.nsw.gov.au/LG2401 (each council's councillor results page).

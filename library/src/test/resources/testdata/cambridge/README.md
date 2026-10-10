# Cambridge STV reference data

`synthetic.blt` is a synthetic election (1,000 voters, 21 candidates, 4 parties) generated with
the VoteKit tool in `tools/votekit` (slate Plackett-Luce, skewed support within each slate, so
it has surplus transfers, a 50-vote batch exclusion and tie-breaks). `synthetic.openstv.csv` is
OpenSTV's count of it:

    python2 runElection.py -r CsvReport -t index CambridgeSTV synthetic.blt

with OpenSTV from https://github.com/Conservatory/openstv at commit 2a267a4 (OpenSTV 1.7).
`-t index` makes OpenSTV break any tie its look-back can't by candidate order, which is what
`DeclaredOrderTieBreak` does. `CambridgeOpenStvReferenceTest` replays the count stage by stage.

## Official counts

`council-2017` (the 2017 City Council election, 26 candidates for 9 seats) and `school-2013` (the
2013 School Committee election, 9 for 6), checked by `CambridgeOfficialCountTest`:

- `<contest>.blt.gz`: the ballots, from FairVote's collection of proportional RCV cast vote records
  (https://doi.org/10.7910/DVN/STVUET, the file `dataverseFile`), converted by
  `tools/reference/cambridge/cvr_to_blt.py`: one line per ballot, in the record's order, which is
  the City's.
- `<contest>.official.json`: the official count sheet the Cambridge Civic Journal keeps (`sheet`),
  read by `tools/reference/cambridge/check.py`: the quota, the candidates elected, and each
  candidate's votes after every count (`counts`), under the ballot file's spelling of their name;
  write-ins are left out.

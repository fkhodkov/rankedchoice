# Cambridge STV reference data

`synthetic.blt` is a synthetic election (1,000 voters, 21 candidates, 4 parties) generated with
the VoteKit tool in `tools/votekit` (slate Plackett-Luce, skewed support within each slate, so
it has surplus transfers, a 50-vote batch exclusion and tie-breaks). `synthetic.openstv.csv` is
OpenSTV's count of it:

    python2 runElection.py -r CsvReport -t index CambridgeSTV synthetic.blt

with OpenSTV from https://github.com/Conservatory/openstv at commit 2a267a4 (OpenSTV 1.7).
`-t index` makes OpenSTV break any tie its look-back can't by candidate order, which is what
`DeclaredOrderTieBreak` does. `CambridgeOpenStvReferenceTest` replays the count stage by stage.

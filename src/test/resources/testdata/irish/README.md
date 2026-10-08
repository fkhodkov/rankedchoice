# Ireland: three constituencies of the 2002 general election

Checked by `Irish2002GeneralElectionFixtureTest`.

- `dublin-north-2002.blt`: 12 candidates, 3 seats, 43,942 valid votes.
- `dublin-west-2002.blt`: 9 candidates, 3 seats, 29,988 valid votes.
- `meath-2002.blt`: 14 candidates, 5 seats, 64,081 valid votes.

The ballots are real: PrefLib's "Irish Election Data"
(https://www.preflib.org/dataset/00001, series 00001-00000001 to -00000003), originally posted
by the Dublin County Returning Officer and donated to PrefLib by Jeffrey O'Neill (OpenSTV),
converted from PrefLib's `soi` format to BLT. Each header gives the first preferences, which
match Wikipedia's figures for the constituency, and the TDs elected.

The `.expected.txt` files are not official counts. Ireland moves surpluses by a random sample of
papers, so its official counts can't be replayed from ballots. They are this project's own
Droop/WIGM count, checked at every stage against droop to its 5 decimal places; the winners are
the TDs actually elected. Their headers name the `.blt` files with underscores
(`dublin_north_2002.blt`), as they were first called; the files are kept byte for byte.

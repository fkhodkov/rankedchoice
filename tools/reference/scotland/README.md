# Scottish council elections

Checks the `scottish` ruleset against the declared result of every Scottish council election
with published ballots: 1,100 counts from MGGG's
[scot-elex](https://github.com/mggg/scot-elex) (David McCune's ballot files: Glasgow in 2007,
every council in 2012, 2017 and 2022, and 30 by-elections).

- `scot_elex.py` converts a scot-elex ballot file into a BLT this project reads (its candidate
  lines hold names and parties in several layouts).
- `official.py` collects the declared winners: for 2022, the Electoral Management Board for
  Scotland's results file (every ward's returning officer's return); for 2007–2022, every
  council's election article on Wikipedia (STV result boxes, winners in bold).
- `check.py` counts every ward with the `scottish` ruleset, matches it to its official result
  and compares the winners.

```sh
git clone https://github.com/mggg/scot-elex
curl -sLo emb2022.xlsx https://www.emb.scot/downloads/file/980/local-government-elections-2022-results-updated-with-postals-station-split
python3 tools/reference/scotland/official.py emb2022.xlsx official.json wp   # needs openpyxl
mvn package -DskipTests
python3 tools/reference/scotland/check.py scot-elex official.json
```

Result: every one of the 1,070 regular ward elections elects the declared councillors. 1,068
match automatically. Two are matched by hand: Midlothian 2012 ward 1 (Penicuik), whose ballot
file truncates surnames ("Gary BEL"); and East Lothian 2017 ward 3 (Tranent/Wallyford/
Macmerry), where Wikipedia's result box is wrong (3 seats, Gordon Mackett missing) and Denis
Mollison's recount confirms the ballot file's 4 seats and the four we elect. Three
by-elections are matched too; the other 27 have no official result here.

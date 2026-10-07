# Minneapolis multi-seat contests

Checks the `minneapolis` ruleset against the City of Minneapolis's summary statements for its
multi-seat ranked-choice contests of 2009 to 2025, the Park and Recreation Board at large (3
seats) and the Board of Estimate and Taxation (2 seats), and its single-seat races of 2017 to 2025
(mayor, City Council wards, Park Board districts) that have a round summary: 29.

- The records (a row per distinct ballot: precinct, 1st, 2nd and 3rd choice, count) are
  converted with `../cvr_to_blt.py --overvote skip --write-in candidate`: an undervote or an
  overvote is passed over, which reproduces the City's first rounds; a candidate ranked again
  counts once; "UWI" is the candidate "Undeclared Write-ins". FairVote's copies of the 2009 and
  2013 records (https://doi.org/10.7910/DVN/STVUET) read the same way, their write-ins one
  candidate, "Write-ins".
- `check.py` fetches each contest's cast vote record and summary statement, converts and counts
  it, and matches every official round to a round of this count, every candidate to 4 decimal
  places, with the threshold and the candidates elected. 2017–2025 are counted with
  `minneapolis` and their PDF summaries read with `pdftotext` (poppler-utils); 2009 with the 2006
  ordinance's rules alone (`src/test/resources/testdata/minneapolis/minneapolis-2009.yaml`) and
  its Excel summaries read with LibreOffice (`soffice`); for 2013, only the winners, both ways.
  It also compares each matched round's "Exhausted" row with this count's exhausted votes (for
  2009, the write-ins left out of the matching, as the record lumps them).

```sh
mvn package -DskipTests
python3 tools/reference/minneapolis/check.py minneapolis-data
```

The ruleset's rules are partly inferred from these counts; see
[VALIDATION.md](../../../VALIDATION.md#real-elections).

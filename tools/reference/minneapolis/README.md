# Minneapolis multi-seat contests

Checks the `minneapolis` ruleset against the City of Minneapolis's summary statements for its
multi-seat ranked-choice contests of 2017, 2021 and 2025: the Park and Recreation Board at large
(3 seats) and the Board of Estimate and Taxation (2 seats).

- `cvr_to_blt.py` converts a contest's cast vote record (a row per distinct ballot: precinct,
  1st, 2nd and 3rd choice, count) to BLT. An undervote or an overvote is passed over, which
  reproduces the City's first rounds; a candidate ranked again counts once; "UWI" is the
  candidate "Undeclared Write-ins".
- `check.py` fetches each contest's cast vote record and summary statement from
  vote.minneapolismn.gov, converts and counts it with `minneapolis`, reads the summary with
  `pdftotext` (poppler-utils), and matches every official round to a round of this count, every
  candidate to 4 decimal places, with the threshold and the candidates elected.

```sh
mvn package -DskipTests
python3 tools/reference/minneapolis/check.py minneapolis-data
```

The ruleset's rules are partly inferred from these counts; see
[VALIDATION.md](../../../VALIDATION.md#real-elections).

# San Francisco

Checks the `irv` ruleset against San Francisco's official ranked-choice counts: the 25 contests of
2019, 2020, 2022 and 2024 whose final round-by-round results the Internet Archive kept (the
Department of Elections' own site, sfelections.org, turns away scripted requests) and whose
ballots FairVote's collection of single-winner RCV cast vote records has
(https://doi.org/10.7910/DVN/AMK8PJ).

- The records are converted with `../albany/cvr_to_blt.py`: an overvote ends the ballot; a
  skipped ranking or an unnamed write-in is passed over.
- `check.py` finds each contest's latest archived results file, fetches it and the record,
  converts and counts the record with `irv`, and compares every official round's votes with this
  count's, round for round, with the order of eliminations and the winner. The results come as
  an Excel report (2019, 2022; read with LibreOffice's `soffice`), a PDF (2020; `pdftotext`) or a
  web page (2024). The Archive's index rate-limits; the script retries, and remembers each
  capture it chose in the data directory.

```sh
mvn package -DskipTests
python3 tools/reference/sanfrancisco/check.py sanfrancisco-data
```

The citywide records are about 70–100 MB each. Result: see
[VALIDATION.md](../../../VALIDATION.md#real-elections).

# San Francisco

Checks the `irv` ruleset against San Francisco's official ranked-choice counts: the 53 contests of
2010–2024 whose final round-by-round results the Internet Archive kept (the Department of
Elections' own site, sfelections.org, turns away scripted requests) and whose ballots FairVote's
collection of single-winner RCV cast vote records has (https://doi.org/10.7910/DVN/AMK8PJ).
Nothing usable was kept for 2004–2008.

- The records are converted with `../cvr_to_blt.py`: an overvote ends the ballot; a
  skipped ranking is passed over. So is an unnamed write-in from 2015 on; before that, the
  reports count write-ins as a candidate, WRITE-IN (`--write-in candidate --write-in-name
  WRITE-IN`).
- `check.py` finds each contest's latest archived results file, fetches it and the record,
  converts and counts the record with `irv`, and requires every official round to be one of this
  count's rounds, in order, with the same candidates continuing, and the same winner, comparing
  the votes. Since 2019 that is round for round. Before, the Department defeated together the
  candidates who couldn't overtake the next one up, which is several of this count's rounds in
  one, and stopped at a majority.
- The results come as the counting system's pass report (2010–2014, a PDF; `pdftotext`), a web
  page (2015–2018, 2024), an Excel report (2019, 2022; LibreOffice's `soffice`) or a PDF (2020).
  The Archive's index rate-limits; the script retries, and remembers each capture it chose in
  the data directory.

```sh
mvn package -DskipTests
python3 tools/reference/sanfrancisco/check.py sanfrancisco-data
```

The citywide records are about 70–100 MB each. Result: see
[VALIDATION.md](../../../VALIDATION.md#real-elections).

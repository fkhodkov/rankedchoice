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
  the data directory (`<contest>.capture`, with the day it asked) for 30 days, then asks again,
  so a later capture, a certified report say, is found; each results file is saved under its
  capture's timestamp, so a later one is fetched anew. Where the index can't be reached, the
  remembered capture is used.
- From 2019 it also compares each elimination's transfers with the report's: each candidate's
  gain and the change in the "Exhausted" and "Overvotes" rows, which are the same, off one way
  (ballots the record lacks or has over), or moved between destinations (a rule of reading the
  ballots that differs). For 2015–2018, whose counts defeat several at once, it compares each
  round's exhausted votes (the "Exhausted Ballots" and "Exhausted by Over Votes" rows) instead.
- For 2024 it also fetches the certified statement of vote (`sov.xlsx`, a sheet per contest)
  and compares the record's first rankings with it, citywide: each candidate, the undervotes
  and the overvotes. All 10 contests match it to the ballot, the three whose round pages the
  Archive only kept from before certification among them.

```sh
mvn package -DskipTests
python3 tools/reference/sanfrancisco/check.py sanfrancisco-data
```

The citywide records are about 70–100 MB each. Result: see
[VALIDATION.md](../../../VALIDATION.md#real-elections).

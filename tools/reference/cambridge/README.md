# Cambridge, Massachusetts

Checks `--method cambridge` against Cambridge's official counts: every City Council (9 seats) and
School Committee (6 seats) election from 2001 to 2023 that FairVote's collection of proportional
RCV cast vote records has (22; https://doi.org/10.7910/DVN/STVUET).

- `cvr_to_blt.py` converts a record to BLT, one line per ballot in the record's order, which is
  the order the City counted them in (a Cambridge count depends on it). A skipped or overvoted
  ranking is passed over, as the City does; a candidate ranked again counts once.
- `official.json` gives each election's quota and winners, from the official count sheets the
  Cambridge Civic Journal keeps (`sheet`), and its Dataverse file. The 2013 Council's sheet holds
  the original count and the recount; `part` names the one the record matches (the original).
- The sheets are read by the PDF's coordinates (`pdftotext -bbox`): each table's Total row marks
  every count's change and total columns, and each figure belongs to the count whose total
  column it's centred in. A table counting other ballots (a later vacancy's recount) is left
  out. The Exhausted row is compared with this count's exhausted votes in every matched count.
- `check.py` fetches each record and sheet, converts and counts the record, and checks it: for
  2001–2017, every official count's votes against a round of this count, the quota and the
  winners; for 2019–2023, whose records don't mark overvotes, the winners only.

```sh
mvn package -DskipTests
python3 tools/reference/cambridge/check.py cambridge-data
```

Result: see [VALIDATION.md](../../../VALIDATION.md#cambridge-stv). The 2023 School Committee is
the one election that elects differently, on a last exclusion the City decided by 8 votes.

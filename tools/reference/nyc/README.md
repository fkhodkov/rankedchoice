# New York City primaries

Checks the `irv` ruleset against the Board of Elections in the City of New York's certified
round-by-round results ("Official Ranked Choice Rounds") for the primaries of 2021, 2023 and 2025
that FairVote's collection of single-winner RCV cast vote records
(https://doi.org/10.7910/DVN/AMK8PJ) also has: 123, from the mayoral primaries to city council.
Left out: the 2025 Republican comptroller primary, whose record in the collection holds 5,402
ballots, mostly from Queens, of about 30,400 counted citywide; and the 2025 District 44 and 51
specials, whose results are PDFs in another layout (one link broken, the other decided in round 1).

- The records are converted with `../albany/cvr_to_blt.py`: an overvote ends the ballot, a
  skipped ranking or a write-in is passed over, a candidate ranked again counts once.
- The Board counts write-ins as one candidate in round 1 and eliminates them first, in round 2,
  whatever their number (in 2025's mayoral primary, before two candidates with fewer votes). That
  is the same as passing them over, so this count's first round is the Board's second; the
  Board's first is compared with the record's first choices, write-ins included.
- The Board then eliminates the candidate with the fewest votes round by round until two are
  left, but defeats together the lowest candidates who couldn't overtake the next one up, and
  prints a round that changes nothing where a candidate first passes 50%. `check.py` requires
  every later official round to be one of this count's rounds, in order, with the same candidates
  continuing, and the same winner, and reports each round's largest difference in votes.
- Names are compared by their letters, initials left out ("Selvena N. Brooks-Powers" in the
  record, "Selvena Brooks-Powers" on the page).

```sh
mvn package -DskipTests
python3 tools/reference/nyc/check.py nyc-data
```

The citywide records are about 100 MB each, and the 2021 council records 10–55 MB each.
Result: see [VALIDATION.md](../../../VALIDATION.md#real-elections).

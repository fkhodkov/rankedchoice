# pref_voting reference checks

`check.py` checks how this project's Schulze method and Borda count read tied rankings, against
[pref_voting](https://github.com/voting-tools/pref_voting) 1.18.2 (Holliday and Pacuit's Python
library), whose `ProfileWithTies` takes ballots with ties. OpenSTV (`tools/reference/openstv`)
checks the same methods without ties, which its ballot files can't hold.

On 500 seeded random elections each (the same every time; each rank tied with the next
candidate's three times in ten, a withdrawn candidate in a quarter):

- `--method schulze`: every pairwise figure against pref_voting's supports under its extended
  strict preference (ranked over unranked, unranked tied, as this project counts a ballot cut
  short); the whole beat-path defeat relation on winning votes (`beat_path_defeat` with
  `strength_function=support`) against the one this project's strongest paths give; and the
  winners (`beat_path`) against this project's first place.
- `--method borda`: every score, on ballots that rank every candidate. This project's score for
  a tied candidate, the average of the tied positions' points, must equal (pref_voting's
  symmetric Borda score + N(n-1))/2. pref_voting gives an unranked candidate a share of the last
  places, this project none, so ballots cut short aren't compared here.

pref_voting needs Python 3.10-3.13:

```sh
python3.12 -m venv pv && pv/bin/pip install pref_voting==1.18.2
mvn package -DskipTests
pv/bin/python tools/reference/pref_voting/check.py work --method schulze
pv/bin/python tools/reference/pref_voting/check.py work --method borda
```

Results: see [VALIDATION.md](../../../VALIDATION.md#schulze) and its Borda section.

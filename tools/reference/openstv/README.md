# OpenSTV reference checks

`check.py` checks this project's Schulze method and Borda count against
[OpenSTV](https://github.com/Conservatory/openstv) 1.7 (commit 2a267a4), an independent counter in
Python 2, on 500 seeded random elections (the same every time):

- `--method schulze` against OpenSTV's `Condorcet` with its default completion, Schwartz
  sequential dropping, which is the beatpath method on winning votes: every pairwise figure
  (OpenSTV's "Pairwise Comparison Matrix"), every strongest-path strength ("Matrix of beatpath
  magnitudes", printed when the Smith set has more than one candidate), and the winner. Where the
  dropping leaves several candidates, they must be exactly this project's first place; OpenSTV
  then picks one by candidate order (`-t index`).
- `--method borda` against OpenSTV's `Borda` without ballot completion (its default): every
  candidate's score. Both give n-1 points for a first preference down to 0, nothing to a
  candidate left unranked, and pass over a withdrawn candidate.

The elections are small, so that cycles and ties are common: 3-8 candidates, a withdrawn one in
a quarter of them, 5-60 ballot lines of weight 1-5, rankings cut short at random. OpenSTV's
ballot files have no tied ranks, so the elections have none.

```sh
git clone https://github.com/Conservatory/openstv && git -C openstv checkout 2a267a4
mvn package -DskipTests
python3 tools/reference/openstv/check.py openstv work --method schulze --python2 PATH_TO_PYTHON2
python3 tools/reference/openstv/check.py openstv work --method borda --python2 PATH_TO_PYTHON2
```

Results: see [VALIDATION.md](../../../VALIDATION.md#schulze) and its Borda section.

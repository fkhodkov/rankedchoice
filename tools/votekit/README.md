# Mock elections with VoteKit

Generates synthetic ranked-ballot elections with [VoteKit](https://github.com/mggg/VoteKit)
(MGGG's toolkit for ranked-choice research) and counts them with every method in this
project, to compare how the methods behave.

## Setup

VoteKit 3.5 needs Python 3.11–3.13:

```sh
python3.12 -m venv .venv-votekit
.venv-votekit/bin/pip install -r tools/votekit/requirements.txt
mvn package -DskipTests        # the comparison runs the CLI jar
```

## Generate one election

```sh
.venv-votekit/bin/python tools/votekit/generate.py tools/votekit/scenarios/two-parties.json out.blt --seed 7
java -jar target/rankedchoice-0.1.0-SNAPSHOT.jar --input out.blt --method meek
```

A scenario (see `generate.py`'s docstring and `scenarios/`) describes voter **blocs** and
candidate **slates** (parties): bloc sizes, how loyal each bloc is to each slate (*cohesion*),
and support for each candidate within a slate. Models: `slate-pl`, `slate-bt`, `name-pl`,
`name-bt`, `cambridge` (VoteKit's bloc/slate models), `ic` (impartial culture) and `spatial`.

Every model except `cambridge` has each voter rank every candidate, which real voters don't;
set `"truncation": {"mean_length": N}` to cut each ballot to a geometric length with mean N.
The `cambridge` model resamples real Cambridge, MA ballots, so its lengths are already real
(it supports exactly two blocs and two slates).

## Compare methods

```sh
.venv-votekit/bin/python tools/votekit/compare.py tools/votekit/scenarios/two-parties.json --seeds 20
```

For each seed it generates an election and counts it with every method (Scottish, Gregory and
ERS97 STV, Meek, Warren, QPQ, CPO-STV, and the top-ranked candidates under Schulze and Borda),
then reports how the seats split between slates. `--methods` picks a subset, `--voters`
overrides the scenario's size, `--keep DIR` keeps the generated `.blt` files. CPO-STV is by far
the slowest (seconds per election at 10 candidates / 5 seats) and is skipped automatically when
the candidate/seat combination exceeds its 500-outcome cap.

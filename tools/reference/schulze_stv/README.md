# Schulze STV and proportional ranking reference checks

Tools that check this project's Schulze STV (`--method schulze-stv`) and Schulze proportional
ranking (`--method schulze-proportional`) against Markus Schulze's own programs and published
figures, and Schulze STV against an independent implementation of his paper.
[VALIDATION.md](../../../VALIDATION.md#schulze-stv) has the results.

- `tideman.py` reads an election from Tideman's database in the format Schulze distributes it
  in, and writes it as BLT. It reads the Pirate Party files in the same archive too (Latin-1,
  candidates numbered past 26); `--names` takes the candidates' names from their comment lines.
- `proportional_check.py` ranks every election with a published list (`*_list.dat`) and
  compares the list, and every comparison `H[b][c]` behind every place, with Schulze's output.
- `check.py` runs the project's jar on every election and compares every support
  `N[committee; outsider]` with the value Schulze publishes. Given a build of his program, it
  also compares the winning committees.
- `schulze_stv.py` is an independent count written from the paper alone: exact fractions and
  deliberately naive algorithms (every pair of committees' strongest path). It checks a
  project JSON report's every support and its winners. Practical up to a few hundred
  committees.

## The data

Schulze distributes his program with 66 elections from Nicolaus Tideman's database:
`schulze3.zip` from http://m-schulze.9mail.de/schulze3.zip. Unpack it anywhere; `SCHULZE3` below
is that directory. For each election `A*.dat` it has `A*_stv.dat`, his program's output: each
contest of M+1 candidates with `N[the other M; that candidate]` for each, to 6 decimals.

The `.dat` files have Windows line endings, which the tools handle. In the letter format (`F
2`), a candidate named twice on a ballot ties with them everyone ranked since their first
mention. That's how his program reads such ballots, and reading them that way is what
reproduces his figures for the 7 elections that have them.

## Checking supports

```sh
mvn package -DskipTests
python3 tools/reference/schulze_stv/check.py SCHULZE3            # every election
python3 tools/reference/schulze_stv/check.py SCHULZE3 A53 A04    # just these
```

A90 (20 candidates, 12 seats) isn't checked: its supports come only as a RAR archive,
`A90_stv.rar`, and at 125,970 committees it's over the project's cap of 50,000 anyway.

## Comparing winners with Schulze's program

`singl01g.cpp` (single-threaded, for g++) needs one fix to run on a modern system: it reads the
election's file name into a single `char`. Change the three lines that use `Problemname`:

```sh
cd SCHULZE3
sed -e 's/char Problemname;/char Problemname[512];/' \
    -e 's/scanf("%s",&Problemname);/scanf("%511s",Problemname);/' \
    -e 's/fopen(&Problemname,"r")/fopen(Problemname,"r")/' singl01g.cpp > singl01p.cpp
g++ -O2 -fpermissive -o singl01p singl01p.cpp
cd -
python3 tools/reference/schulze_stv/check.py SCHULZE3 --program SCHULZE3/singl01p
```

The program reads the file name from standard input and writes `output.txt` in the current
directory; `check.py` runs it in a temporary directory per election.

It elects a different committee on 2 elections, A04 and A34. Both differences are definitional,
not errors: the program (2017) compares links by how a candidate ranks within its contest
rather than by the paper's margin (A04), and breaks ties between potential winners with an
older single-winner ranking (A34).

## The independent count

```sh
java -jar cli/target/rankedchoice-cli-0.1.0-SNAPSHOT.jar -i election.blt -m schulze-stv \
    --report-format json > report.json
python3 tools/reference/schulze_stv/schulze_stv.py election.blt report.json
```

On the 58 database elections with at most 500 committees, every support (48,233) and every
potential winner and winner match.

## Checking the proportional ranking

For each election `X.dat` the archive has `X_list.dat`, the output of `singl04g.cpp`: for each
place, the matrix H behind it (row b, column c: `N[the list so far + b; c]`, to 6 decimals),
then who takes the place. Some files hold two or three runs of the program, appended; its
tie-break ranking is drawn from a random ballot order, so the runs can differ, and the first is
used.

```sh
mvn package -DskipTests
python3 tools/reference/schulze_stv/proportional_check.py SCHULZE3                 # every list
python3 tools/reference/schulze_stv/proportional_check.py SCHULZE3 A53 ppb2011     # just these
```

Each comparison is checked for as long as both lists have placed the same candidates, since H
depends only on who's placed. Where the lists part, the script reruns Stage 3 on Schulze's own
printed H both ways: by margin (the paper) and by winning votes (the program), naming the
potential winners of each. `--timeout` (default 1800 s) gives up on an election that takes
longer. Every list takes about 9 minutes, most of it Sweden 2014's (about 5) and Berlin 2013's
(about 2).

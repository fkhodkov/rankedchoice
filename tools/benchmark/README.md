# Benchmarks

Large synthetic elections, and the times every method takes on them through the command line.
README.md's [Performance](../../README.md#performance) section comes from these.

```sh
mvn -o -q package -DskipTests
python3 tools/benchmark/run.py DATA_DIR                 # README's table and its 300-candidate line
python3 tools/benchmark/run.py DATA_DIR --only small    # the table alone, a few minutes
python3 tools/benchmark/run.py DATA_DIR --heap 256m     # the same within a 256 MB heap
python3 tools/benchmark/run.py DATA_DIR --find-heap     # each count's smallest heap instead
```

`run.py` generates the elections into `DATA_DIR` the first time (about 175 MB in all, and about
four minutes; later runs reuse them), runs each method once on each with `java -jar`, JVM start-up
and parsing included, and prints Markdown tables. A whole run takes about 20 minutes, most of it
Meek and Warren on 300 candidates, and exact STV on the 300-candidate election at 40 preferences,
which never finishes and is stopped at `--limit` (300 s by default). A count that fails shows its
error in place of a time: within `--heap 256m`, every other method but Borda and Schulze runs out
of memory on that election.

`--find-heap` prints each count's smallest heap in place of its time: the first of 256 MB, 384 MB,
512 MB, 768 MB, 1 GB, ... it finishes in. A count just short of memory can spend minutes collecting
garbage rather than run out, so a heap that hits `--limit` moves on to the next too; a second limit
in a row ends the search. A whole run takes about half an hour.

`generate.py CANDIDATES SEATS VOTERS OUT.blt [--mean-length N] [--seed S]` makes one election:
every voter their own ballot line, candidate i weighted 1/i^0.8, each voter ranking a geometric
number of candidates (mean N, default 5) drawn without replacement by weight. The same arguments
always give the same file.

Single runs vary by up to a third, and more when other work is running: under a load average of
10, `scottish` and QPQ took about 1.6 times their quiet times. To tell a real slowdown from noise,
time the old and new jars alternately on the same file.

# Ballot-order sensitivity (Cambridge STV)

Cambridge's Cincinnati method moves whole ballots chosen by their position in the count, so the
same votes counted in a different order can elect different people. `OrderProbe.java` measures
how much: it counts each election as filed and in many random orders of its individual ballots
(fixed seed, so runs repeat), and reports every committee that came out and how often, next to
the committees Scottish STV and Meek elect (neither depends on order).

```sh
mvn package -DskipTests
java -cp target/rankedchoice-0.1.0-SNAPSHOT.jar tools/order-sensitivity/OrderProbe.java 200 election.blt
```

`--json` prints one JSON object per election; `--save DIR` writes, for each committee seen, the
first shuffled order that produced it as a BLT file, to replay with `--method cambridge`.

What it found on elections from `tools/votekit` (200 orders each):

- 30 elections of 10,000 voters (every scenario there): order never changed the result, and the
  committee was always Scottish STV's (and Meek's in 29). A surplus of hundreds of ballots taken
  every n-th is effectively a large random sample of the candidate's pile.
- 60 varied elections of 1,000–20,000 voters: order changed the committee in 10, the party split
  in 7, mostly in small electorates with many seats.
- At 1,000 ballots for 9 seats (quota 101, about twice the 50-vote cutoff) order can decide
  whether every seat is filled: the first exclusion removes everyone under 50 at once, and
  whether a candidate sits on 49 or 51 by then depends on which ballots earlier surpluses
  happened to move. Seats went unfilled in 53, 131 and 193 of 200 orders in three such elections
  (OpenSTV counts them the same way). At realistic Cambridge quotas (around 2,500) this can't
  happen.

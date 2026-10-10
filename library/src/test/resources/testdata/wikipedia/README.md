# Wikipedia's "Andrea, Brad, Carter, Delilah" example

`wikipedia-andrea-brad-carter-delilah.blt`: the worked example of Wikipedia's "Counting single
transferable votes" article, 4 candidates for 2 seats, 57 voters. The article gives only each
round's totals, so the ballots are reconstructed to reproduce them, not copied from a source;
the file's header says how, and where its first preferences (Andrea 40, Delilah 17) part from
the article's (39 and 17, which sum to 56).

The article's quota is a plain 57/(2+1) = 19, the Hagenbach-Bischoff quota, which
`HagenbachBischoffQuotaFixtureTest` counts it under. It's also the README's first example of the
command line.

# Releasing

How `common` and `library` go to Maven Central, as `info.fkhodkov.rankedchoice:rankedchoice-common`
and `info.fkhodkov.rankedchoice:rankedchoice-library`, with the parent POM
(`info.fkhodkov.rankedchoice:rankedchoice`). The command line and the demo server aren't published:
they're runnable jars, built from the repository.

The everyday build doesn't need any of this: the `release` profile in the root `pom.xml` adds the
sources and javadoc jars, the signatures and the upload, and its plugins are downloaded the first
time it runs.

## Once

1. **An account** at the [Central Portal](https://central.sonatype.com/).
2. **The namespace.** Add `info.fkhodkov` under Namespaces. The portal gives a verification key;
   add it to the `fkhodkov.info` domain's DNS as a TXT record, wait for it to show
   (`dig +short TXT fkhodkov.info`), and press Verify. The TXT record can go once it's verified.
   `info.fkhodkov` covers `info.fkhodkov.rankedchoice`.
3. **A token.** Generate a user token in the portal (under the account), and put it in
   `~/.m2/settings.xml`, never in the repository:

   ```xml
   <settings>
     <servers>
       <server>
         <id>central</id>
         <username>TOKEN-USERNAME</username>
         <password>TOKEN-PASSWORD</password>
       </server>
     </servers>
   </settings>
   ```

4. **A signing key.** Central needs every file signed. `gpg --full-generate-key` makes one, and
   `gpg --keyserver keys.openpgp.org --send-keys KEY-ID` publishes its public half, which Central
   checks the signatures against (keys.openpgp.org mails a link to confirm the address). The
   `maven-gpg-plugin` asks gpg's agent for the passphrase.

## Each release

1. **The version.** Set it in all five POMs (the root's, and each module's parent), e.g.
   `0.1.0-SNAPSHOT` to `0.1.0`:

   ```sh
   grep -rl --include=pom.xml 0.1.0-SNAPSHOT . | xargs sed -i 's/0\.1\.0-SNAPSHOT/0.1.0/'
   mvn -o -q verify
   ```

   Set the root POM's `project.build.outputTimestamp` to the release's date too (e.g.
   `2027-01-15T00:00:00Z`): every jar entry carries it, so anyone building the tag gets the same jars.
   Commit it, and tag the commit `v0.1.0`.
2. **Upload.** `mvn -Prelease clean deploy` builds and signs everything, installs it locally,
   and uploads the bundle to the portal, which validates it. `autoPublish` is off: nothing is
   public yet. A deployment that fails validation shows why under Deployments, and can be dropped
   there.
3. **Check it** as a user would: `tools/release/check-consumer.sh 0.1.0` builds a separate project
   against the version just installed, and counts by a bundled ruleset. `cli`'s
   `LibraryOnTheModulePathTest`, in the build, checks the same jars as modules.
4. **Publish** the deployment from the portal. It can't be taken back: a published version is
   there for good, and a fix is a new version. It usually shows on Maven Central within half an hour.
5. **The next version.** Set the POMs to the next snapshot (`0.2.0-SNAPSHOT`) and commit it.

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

On `develop`, with everything committed. The root POM configures `maven-release-plugin`, which
pushes nothing: the commits and the tag stay local until you push them.

1. **Prepare.**

   ```sh
   mvn release:prepare
   ```

   It asks for the release's version (`0.1.0`), the tag (`v0.1.0`) and the next snapshot
   (`0.2.0-SNAPSHOT`), offering each; `-B` takes the offers without asking. It then sets the
   release's version in all five POMs, with `project.build.outputTimestamp` set to now (every jar
   entry carries it, so anyone building the tag gets the same jars), runs `clean verify`, and
   commits it as `[release] prepare release v0.1.0`. It tags that commit `v0.1.0`, sets the next
   snapshot, and commits again (`[release] prepare for next development iteration`). The
   pre-commit hook builds both commits. It needs to be online: the plugin isn't in the offline
   cache.

   It leaves `release.properties` and `pom.xml.releaseBackup` files (git ignores them) for the
   next step. If something fails, nothing has left this machine, so git undoes it: `git reset
   --hard` to the commit you started from, `git tag -d v0.1.0` if it got that far, then `mvn
   release:clean` for the leftover files.
2. **Upload.**

   ```sh
   export GPG_TTY=$(tty)
   echo warm-up | gpg --clearsign -u KEY-ID > /dev/null
   mvn release:perform
   ```

   The first two lines are for the signing. `perform` runs Maven again as a child process with no
   terminal of its own, where gpg's passphrase prompt can't appear: gpg waits about a minute and
   fails, and the build stops at the parent POM, the first thing signed, with nothing uploaded.
   `GPG_TTY` tells gpg's agent which terminal to ask in, and the warm-up signature asks for the
   passphrase now, which the agent then keeps for 10 minutes (its `default-cache-ttl`), longer than
   perform takes (about a minute and a half). A perform that fails this way can simply be run
   again, after `rm -rf target/checkout`: prepare's commits, its tag and `release.properties` are
   still there.

   It clones the tag from this repository (not GitHub's) into `target/checkout`, and runs `mvn
   deploy -Prelease` there: it builds, signs everything, installs it locally, and uploads the
   bundle to the portal, which validates it. `autoPublish` is off: nothing is public yet. A
   deployment that fails validation shows why under Deployments: drop it there. While nothing is pushed, the
   same version can be redone once the cause is fixed: undo prepare as above (back to the commit
   before it, the tag deleted), commit the fix, and prepare again. `mvn -Prelease deploy
   -DskipUpload=true -Dgpg.skip` builds the bundle without uploading it, in
   `target/central-publishing/central-bundle.zip`, to look at first; it needs a release version,
   as the plugin makes no bundle of a snapshot.
3. **Check it** as a user would: `tools/release/check-consumer.sh 0.1.0` builds a separate project
   against the version just installed, and counts by a bundled ruleset. `cli`'s
   `LibraryOnTheModulePathTest`, in the build, checks the same jars as modules.
4. **Publish** the deployment from the portal. It can't be taken back: a published version is
   there for good, and a fix is a new version. It usually shows on Maven Central within half an hour.
5. **Push** the two commits and the tag when you choose: `git push origin develop v0.1.0`.

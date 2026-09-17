# Cutting a release

The reproducible build works and `docs/reproducible-build.html` is current. What is
still missing is the path between this repository and the binary:

- the APK is only reachable from `b.pyblock.xyz:8443/app.php`, so someone who finds
  the source here cannot get the binary here;
- there are no tags and no releases, so someone holding an APK cannot tell which
  commit produced it without reading commit messages. There are now three
  `Release build N` commits — 21, 22 and 23 — and not one tag.

A tagged release closes both. Nothing below needs anything this repo does not
already have; the build is already reproducible.

## Per release

```bash
# 1. Tag the commit that produced the build
git tag -a v0.2.3-23 -m "PyBLØCK ᛒ 0.2.3 (build 23)"
git push origin v0.2.3-23

# 2. Build and sign as usual (keystore.properties present)
./gradlew clean assembleRelease

# 3. Publish, attaching the signed APK
gh release create v0.2.3-23 app/build/outputs/apk/release/app-release.apk \
  --title "PyBLØCK ᛒ 0.2.3 (build 23)"
```

Release notes worth carrying, all of which you already compute for the docs page:

```
APK   SHA-256  6c46683cbf3ad661285faa2ac8399756cf20ac6a7c5ff657762e224c9663237a
Cert  SHA-256  e86002aa3ac72325099f92065ec8ab3b7adc70db9e74514ebd53c78acdba3fb5
Unsigned       186f6c9f380298ebd9b0c7d5c63b158b6678b8ec7201e9ca0bdc94b0552a6b27
Reproducible:  README-REPRODUCIBLE.md
```

The workflow added alongside this file rebuilds every commit on a clean runner and
prints that unsigned hash in the job summary, so the number can be checked against a
build nobody controlled.

## Zapstore

Zapstore reads GitHub releases, so the tag has to exist first. Publishing links your
APK signing certificate to your nostr identity via NIP-C1 on the first publish —
which is why only the keystore holder can do it.

```bash
go install github.com/zapstore/zsp@latest
zsp publish --wizard          # fills zapstore.yaml with your npub — commit it
```

The relay fetches `zapstore.yaml` from this repo, checks the pubkey matches, and
whitelists you automatically. Later releases can use a NIP-46 bunker instead of an
nsec in the environment:

```bash
SIGN_WITH="bunker://..." zsp publish -r github.com/AstrolexisAI/pyblock-blake2b
```

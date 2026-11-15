# Test signing key

`debug.keystore` is a throwaway key used only to sign debug test builds from
this fork's `build/*` branches, so each new test build can update the previous
one on a device without uninstalling it.

It is not secret (store password `android`, alias `androiddebugkey`, key
password `android`, the Android debug defaults) and must never be used for a
nightly, release candidate or stable build. Do not merge this directory
upstream.

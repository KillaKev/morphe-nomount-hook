# Morphe NoMount Hook

An LSPosed module that makes [Morphe Manager](https://github.com/MorpheApp/morphe-manager)'s
root mount install use NoMount (the VFS path-redirection metamodule by maxsteeel) rules
instead of bind mounts.
Written against Morphe's `dev` branch.

## What it does

It hooks `RootInstaller.execute(String[], Continuation)` inside Morphe and rewrites the shell
commands Morphe runs:

| Morphe runs | Hook runs |
| --- | --- |
| `mount -o bind SRC DST` | `nm rule add DST SRC` |
| `nsenter ... mount -o bind` (zygote namespaces) | nothing, rules are global |
| `mount \| grep -F <apk>` (is it mounted?) | same, plus `nm rule list` entries that point into `*-morphe` modules |
| `umount -l DST` loops | also `nm rule del DST` (Morphe's loops never run, NoMount leaves no mountinfo entry) |
| writing a module's `service.sh` / `post-fs-data.sh` | same, with a shim injected so the **boot-time** mounts become rules too |
| writing the YouTube module | same, plus `system/product/app/YouTube/.replace` (whites out the system stub) |
| after a mount / unmount | same, then toggles the app's launcher activity so Pixel Launcher rebuilds its cached icon |

The YouTube stub whiteout exists because the stock stub is newer than the YouTube version Morphe
patches, and the package manager reverted to it on every boot. Edit `STUB_WHITEOUTS` in
`Hook.java` to cover other apps.

## Install

1. Install `morphe-nomount-hook.apk` (from a build artifact or release).
2. In LSPosed, enable **Morphe NoMount Hook** and scope it to `app.morphe.manager`.
3. Force-stop Morphe. The LSPosed module log should show `MorpheNoMount: hooked RootInstaller.execute`.

Requires root with the NoMount metamodule (`/data/adb/modules/nomount/bin/nm`). Without it the
hook leaves Morphe's commands untouched.

Do not add Morphe's own UID to NoMount's exclusion list: Morphe needs to see the patched APK
through `sourceDir`.

## Build

Needs JDK 17 and the Android SDK (`platforms;android-34`, `build-tools;34.0.0`).

```bash
ANDROID_SDK=/path/to/sdk bash build.sh      # writes dist/morphe-nomount-hook.apk
```

No Gradle and no Xposed API download: `stubs/` holds the few Xposed API signatures needed to
compile, and they are not packaged. On Windows run it from Git Bash.

### GitHub Action

`.github/workflows/build.yml` builds on every push to `main`, on pull requests and on manual
runs, and uploads the APK as the `morphe-nomount-hook` artifact. Pushing a tag like `v2.3` also
attaches it to a release. It builds on `ubuntu-latest` with the runner's preinstalled Android
SDK, so there is nothing to set up.

CI builds are signed with the repository secret `KEYSTORE_BASE64`, which is already configured
here, so they install as an in-place update over an existing install. There is no signing step
to do by hand.

### Signing key

An update only installs over an existing install if it is signed with the same key. The key
exists in two places: `debug.keystore` locally (gitignored, generated on the first local build)
and the `KEYSTORE_BASE64` secret. Both are the same key. GitHub secrets can't be read back, so
keep a backup of `debug.keystore`; if both copies are lost, the next build can't update an
installed copy and the old module has to be uninstalled first.

If you fork this, the Action still builds without the secret but signs with a throwaway key.
To sign with your own, add the key as a secret named `KEYSTORE_BASE64`
(`base64 -w0 debug.keystore`), plus `KS_PASS` if its password is not `android`.

## Debugging

Rewritten commands and missed script writes are logged to LSPosed's module log
(`/data/adb/lspd/log/modules_*.log`, search for `MorpheNoMount`). If a Morphe update changes
`execute` or its command text, the hook logs `WARNING module script write not rewritten` and new
installs fall back to plain bind mounts.

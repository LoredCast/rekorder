# Publishing Rekorder to F-Droid

This repository is prepared to satisfy the [F-Droid Inclusion Policy](https://f-droid.org/docs/Inclusion_Policy/) and [Submitting to F-Droid Quick Start Guide](https://f-droid.org/docs/Submitting_to_F-Droid_Quick_Start_Guide/).

## Inclusion Policy Checklist

- **FLOSS License:** `GPL-3.0-or-later` ([LICENSE](LICENSE)) with `SPDX-License-Identifier: GPL-3.0-or-later` headers across all Java, C, header, and build files.
- **Zero Proprietary Dependencies / Zero Blobs:** Zero third-party libraries (no AndroidX, no GMS, no Firebase, no trackers, no ads), zero `gradle-wrapper.jar`, zero Google Play `DependencyInfoBlock`, and zero `INTERNET` permission.
- **No Prebuilt Binaries in Git:** `.gitignore` excludes `build/`, `*.apk`, `*.so`, `*.dex`, `*.class`, and `*.keystore`.
- **Reproducible Offline Build:** `./build.sh --unsigned` compiles native C libraries (`arm64-v8a`, `armeabi-v7a`, `x86_64`, `x86`), Android resources, and Java classes with deterministic timestamps (`1980-01-01 00:00:00 UTC`), `-ffile-prefix-map`, and `-Wl,--build-id=none`, producing a bit-for-byte reproducible 16 KB page-aligned unsigned APK at `build/rekorder-unsigned.apk`.
- **Upstream Fastlane Metadata:** Complete store listing in `fastlane/metadata/android/en-US/` (`title.txt`, `short_description.txt`, `full_description.txt`, `changelogs/1.txt`, `images/icon.png`, `images/featureGraphic.png`, and `images/phoneScreenshots/`).

---

## Step 1 — Configure Automatic GitHub Release Signing (One-Time)

This repo includes [`.github/workflows/release.yml`](.github/workflows/release.yml), which automatically tests, builds, signs, and publishes `rekorder-vX.Y.Z.apk` + `SHA256SUMS.txt` on GitHub Releases whenever you push a `v*` tag.

1. Generate your permanent release keystore **outside** the repo and back it up safely:
   ```bash
   mkdir -p ~/.keys
   keytool -genkeypair -v \
     -keystore ~/.keys/rekorder-release.keystore \
     -alias rekorder \
     -keyalg RSA -keysize 4096 -validity 10000 \
     -dname "CN=Rekorder, O=loredcast"
   ```
2. Base64-encode the keystore to copy into GitHub Secrets:
   ```bash
   base64 -w 0 ~/.keys/rekorder-release.keystore
   ```
3. In your GitHub repo (`https://github.com/loredcast/rekorder/settings/secrets/actions`), add these 4 **Repository secrets**:
   - `RELEASE_KEYSTORE_BASE64` — base64 output from step 2
   - `RELEASE_KEY_ALIAS` — `rekorder`
   - `RELEASE_STORE_PASS` — your keystore password
   - `RELEASE_KEY_PASS` — your key password

---

## Step 2 — Push to GitHub (`loredcast/rekorder`) and Tag `v1.0.0`

Create an empty public repository named `rekorder` under `https://github.com/loredcast`, add the 4 secrets above, then push `main` and the `v1.0.0` tag:

```bash
git push -u origin main
git push origin v1.0.0
```

Pushing `v1.0.0` triggers GitHub Actions to run all C + JVM unit tests, verify bit-for-byte build reproducibility, sign `rekorder-v1.0.0.apk`, and publish the GitHub Release using `fastlane/metadata/android/en-US/changelogs/1.txt`.

---

## Step 3 — Submit Merge Request to `fdroiddata`

1. Sign in to GitLab and fork [https://gitlab.com/fdroid/fdroiddata](https://gitlab.com/fdroid/fdroiddata).
2. Clone your fork and create a branch for Rekorder:
   ```bash
   git clone --depth=1 https://gitlab.com/<YOUR_GITLAB_USERNAME>/fdroiddata
   cd fdroiddata
   git checkout -b com.rekorder.app
   ```
3. Copy the prepared F-Droid metadata recipe from this repository:
   ```bash
   cp /path/to/rekorder/fdroid/com.rekorder.app.yml metadata/com.rekorder.app.yml
   ```
4. *(Optional, if `fdroidserver` is installed locally)* Validate metadata and build locally:
   ```bash
   fdroid readmeta
   fdroid rewritemeta com.rekorder.app
   fdroid lint com.rekorder.app
   fdroid checkupdates --auto com.rekorder.app
   fdroid build -v -l com.rekorder.app
   ```
5. Commit, push, and open a Merge Request targeting `fdroid/fdroiddata:master`:
   ```bash
   git add metadata/com.rekorder.app.yml
   git commit -m "New App: com.rekorder.app (Rekorder)"
   git push -u origin com.rekorder.app
   ```

---

## Releasing Future Updates

Because `com.rekorder.app.yml` configures `AutoUpdateMode: Version` and `UpdateCheckMode: Tags`:

1. Bump `android:versionCode` and `android:versionName` in `app/src/main/AndroidManifest.xml`.
2. Add release notes (≤ 500 chars) in `fastlane/metadata/android/en-US/changelogs/<versionCode>.txt`.
3. Commit and push a matching Git tag `v<versionName>` (for example, `v1.1.0`).

F-Droid's automated `checkupdates` bot will detect the new tag, parse `AndroidManifest.xml`, build `build/rekorder-unsigned.apk`, and pull your updated changelog automatically.

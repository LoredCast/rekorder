# Publishing Rekorder to F-Droid

This repository is prepared to satisfy the [F-Droid Inclusion Policy](https://f-droid.org/docs/Inclusion_Policy/) and [Submitting to F-Droid Quick Start Guide](https://f-droid.org/docs/Submitting_to_F-Droid_Quick_Start_Guide/).

## Inclusion Policy Checklist

- **FLOSS License:** `GPL-3.0-or-later` ([LICENSE](LICENSE)) with `SPDX-License-Identifier: GPL-3.0-or-later` headers across all Java, C, header, and build files.
- **Zero Proprietary Dependencies / Zero Blobs:** Zero third-party libraries (no AndroidX, no GMS, no Firebase, no trackers, no ads), zero `gradle-wrapper.jar`, zero Google Play `DependencyInfoBlock`, and zero `INTERNET` permission.
- **No Prebuilt Binaries in Git:** `.gitignore` excludes `build/`, `*.apk`, `*.so`, `*.dex`, `*.class`, and `*.keystore`.
- **Reproducible Offline Build:** `./build.sh --unsigned` compiles native C libraries (`arm64-v8a`, `armeabi-v7a`, `x86_64`, `x86`), Android resources, and Java classes with deterministic timestamps (`1980-01-01 00:00:00 UTC`), `-ffile-prefix-map`, and `-Wl,--build-id=none`, producing a bit-for-byte reproducible 16 KB page-aligned unsigned APK at `build/rekorder-unsigned.apk`.
- **Upstream Fastlane Metadata:** Complete store listing in `fastlane/metadata/android/en-US/` (`title.txt`, `short_description.txt`, `full_description.txt`, `changelogs/1.txt`, `images/icon.png`, `images/featureGraphic.png`, and `images/phoneScreenshots/`).


## Releasing Future Updates

Because `com.rekorder.app.yml` configures `AutoUpdateMode: Version` and `UpdateCheckMode: Tags`:

1. Bump `android:versionCode` and `android:versionName` in `app/src/main/AndroidManifest.xml`.
2. Add release notes (≤ 500 chars) in `fastlane/metadata/android/en-US/changelogs/<versionCode>.txt`.
3. Commit and push a matching Git tag `v<versionName>` (for example, `v1.1.0`).

F-Droid's automated `checkupdates` bot will detect the new tag, parse `AndroidManifest.xml`, build `build/rekorder-unsigned.apk`, and pull your updated changelog automatically.

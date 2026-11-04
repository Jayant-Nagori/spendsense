# SpendSense

Personal expense tracker for Android and iPhone (Kotlin Multiplatform + Compose Multiplatform). It catches UPI, bank
and card payments from bank SMS and payment-app notifications, asks what each payment was for and whether you split
it, and stores financial data encrypted on the phone. Maintained end to end by
[Jayant Nagori](https://github.com/Jayant-Nagori), with source available under the MIT license.

- `shared/` — commonMain / androidMain / iosMain: models, parsers, storage, sync, all screens.
- `app/` — Android shell (SMS receiver, notification listener, pop-up, jobs).
- `iosApp/` — SwiftUI host, generated with `cd iosApp && xcodegen generate`.

## Build Android

Install Android Studio, Android SDK platform 36, and JDK 17 (required by the Gradle toolchain).
Set `JAVA_HOME` to your JDK and configure `ANDROID_HOME`, or create an ignored `local.properties`
containing `sdk.dir=/path/to/Android/sdk`.

```sh
git clone https://github.com/Jayant-Nagori/spendsense.git
cd spendsense
./gradlew :app:assembleDebug
./gradlew :shared:testDebugUnitTest :app:testDebugUnitTest
```

The debug APK is in `app/build/outputs/apk/debug/`. Debug builds work without the maintainer's signing keys.
Official release builds check the public signing-certificate fingerprint. A release built with another key
will fail that check; fork maintainers should configure their own signing and matching fingerprint in
`app/build.gradle.kts`.

## Build iOS

Use macOS with Xcode, Xcode command-line tools, JDK 17 and 21, and XcodeGen.

```sh
cd iosApp
xcodegen generate
open SpendSense.xcodeproj
```

Select the SpendSense scheme and an iOS simulator, then build in Xcode. Device deployment requires your own
Apple signing configuration. The shared code targets iOS 17.2 and later. From the repository root, run:

```sh
./gradlew :shared:iosSimulatorArm64Test
```

`scripts/build-ipa.sh` creates an unsigned device IPA for sideloading, without a signing identity.

## Downloads and privacy

Prebuilt downloads and installation instructions: [spendsense-releases](https://github.com/Jayant-Nagori/spendsense-releases).
Core tracking stores data on device. Optional AI, email, Splitwise, and connected-service features communicate
with the services you configure. Review their settings before enabling them. On iOS, bank SMS import uses a
Shortcuts automation because apps cannot read SMS directly.

## Contributing and security

Issues and pull requests are welcome. Explain the problem, use synthetic examples, and run the tests relevant
to your change. Never upload real statements, SMS, account data, backups, API keys, or signing keys.
Report vulnerabilities through GitHub's private vulnerability reporting for this repository.

Additional build notes: PROGRESS.md. Prior security review notes: SECURITY_REVIEW.md.

## License

Application code: [MIT](LICENSE). Bundled fonts have separate SIL Open Font Licenses;
see [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md). Dependencies retain their respective licenses.

## Updating

Install updates over the top — don't uninstall first. If Android says 'App not installed' you have an old test build: take a backup in Settings → Backup first, then uninstall, install, and restore.

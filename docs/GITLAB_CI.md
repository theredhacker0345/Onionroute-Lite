# GitLab CI Usage

Onionroute-Lite uses GitLab as its canonical source repository. The pipeline is intentionally **manual-only**: GitLab creates it only when an owner selects **Build → Pipelines → Run pipeline** in the GitLab interface. Pushes, merge requests, tags, schedules, triggers, and API events do not create a pipeline.

| Manual job | Purpose | Output | Important limit |
| --- | --- | --- | --- |
| `metadata_validate` | Validates F-Droid and Fastlane metadata. | Job log. | Does not build an APK. |
| `build_debug` | Builds the F-Droid debug APK with Java 17, Go 1.21.13, Android API 34, and NDK r26d. | Debug APK and lint report. | A native-build or lint failure fails the job rather than producing a potentially incomplete APK. |
| `build_release` | Performs a strict ARM-only F-Droid release build. | Release APK artifact. | It does not create a GitLab release or tag. |
| `emulator_runtime` | Installs and launches the debug APK on API 34 x86_64. | Screenshot and logcat artifacts. | Requires a trusted runner tagged `android-kvm` with `/dev/kvm`. Without one, the job remains unavailable or fails clearly. |
| `bridge_health_diagnostics` | Tests bundled bridges and prepares review artifacts. | Results, decision data, candidates, summary, and a proposed bridge JSON. | It makes no repository changes, commits, pushes, releases, or automatic bridge swaps. |

> **Bridge updates require human review.** The diagnostic job deliberately does not call `apply_changes.py --apply` and never receives a repository write credential. To update bridge data, review the saved artifacts locally, make a normal reviewed commit, and then use GitLab’s standard merge-request process.

The legacy GitHub Actions workflow files were removed when this configuration was introduced. GitHub issue and pull-request templates, if any, are not CI configuration and are outside the scope of this change.

# GitLab CI Usage

Onionroute-Lite uses GitLab as its canonical source repository. The pipeline is intentionally **manual-only**: GitLab creates it only when an owner selects **Build → Pipelines → Run pipeline** in the GitLab interface. Pushes, merge requests, tags, schedules, triggers, and API events do not create a pipeline.

| Manual job | Purpose | Output | Important limit |
| --- | --- | --- | --- |
| `android_verify` | Validates F-Droid/Fastlane metadata, builds the debug and ARM release APKs, and runs debug lint with Java 17, Go 1.21.13, Android API 34, and NDK r26d. | Debug APK, release APK, and lint report. | A metadata, native-build, lint, or release-validation failure fails this one combined job; it creates no GitLab release or tag. |
| `bridge_health_diagnostics` | Tests bundled bridges and prepares review artifacts. | Results, decision data, candidates, summary, and a proposed bridge JSON. | It makes no repository changes, commits, pushes, releases, or automatic bridge swaps. |

> **Bridge updates require human review.** The diagnostic job deliberately does not call `apply_changes.py --apply` and never receives a repository write credential. To update bridge data, review the saved artifacts locally, make a normal reviewed commit, and then use GitLab’s standard merge-request process.

This compact configuration has **two** manual jobs. `android_verify` replaces the former separate metadata, debug-build, and release-build jobs so maintainers choose one Android validation action instead of three. The optional emulator test was removed because it required a specialized KVM runner and was not part of the required release-validation path. Bridge Health remains independent because it is a separate network-facing diagnostic operation.

The legacy GitHub Actions workflow files were removed when this configuration was introduced. GitHub issue and pull-request templates, if any, are not CI configuration and are outside the scope of this change.

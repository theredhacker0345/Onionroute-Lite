# GitLab CI Usage

Onionroute-Lite uses GitLab as its canonical source repository. The pipeline is intentionally **manual-only**: GitLab creates it only when an owner selects **Build → Pipelines → Run pipeline** in the GitLab interface. Pushes, merge requests, tags, schedules, triggers, and API events do not create a pipeline.

| Manual job | Purpose | Output | Important limit |
| --- | --- | --- | --- |
| `android_verify` | Validates F-Droid/Fastlane metadata, builds the debug and ARM release APKs, and runs debug lint with Java 17, Go 1.21.13, Android API 34, and NDK r26d. | Debug APK, release APK, and lint report. | A metadata, native-build, lint, or release-validation failure fails this one combined job; it creates no GitLab release or tag. |
| `bridge_health_diagnostics` | Tests bundled bridges, updates health history, and writes current runtime bridge data. | Results, decision data, candidates, summary, and copies of the committed bridge and health JSON. | It runs only after a maintainer clicks the manual job on protected `main`; it commits exactly `alive_bridges.json`, `health.json`, and the rendered README bridge section. It never runs on a schedule or push, changes `Constants.kt`, creates a release, or commits candidate source swaps. |

> **Bridge write-back remains owner-triggered.** The job is available only in a web-created pipeline and must be clicked manually on protected `main`. It receives a protected, masked `BRIDGE_WRITE_TOKEN` and protected `BRIDGE_WRITE_USERNAME` only for that run. The job rejects other branches, permits only three tracked-file changes, uses a concurrency lock, and cannot alter `Constants.kt`. It performs the commit after producing the local artifacts, so the pipeline details retain the evidence for the exact published result.

This compact configuration has **two** manual jobs. `android_verify` replaces the former separate metadata, debug-build, and release-build jobs so maintainers choose one Android validation action instead of three. The optional emulator test was removed because it required a specialized KVM runner and was not part of the required release-validation path. Bridge Health remains independent because it is a separate network-facing diagnostic operation.

## Bridge Health Write-Back Prerequisites

The project needs a project access token named `bridge-health-writeback-main` with the **write_repository** scope and **Maintainer** role. It must be stored in the project variable `BRIDGE_WRITE_TOKEN` with **Protected**, **Masked**, and **Hidden** enabled. Store the generated project-bot username in a separate protected project variable named `BRIDGE_WRITE_USERNAME`. Do not put either value in `.gitlab-ci.yml`, repository files, artifacts, logs, or commit messages.

The `main` branch must remain protected with pushes restricted to Maintainers and force-push disabled. The job uses Git askpass rather than embedding the credential in a remote URL. A new pipeline must always be created through **Build → Pipelines → Run pipeline**, selecting `main`; then a maintainer clicks `bridge_health_diagnostics`. The resulting push cannot spawn another pipeline because the workflow permits only `web` pipeline sources.

The legacy GitHub Actions workflow files were removed when this configuration was introduced. GitHub issue and pull-request templates, if any, are not CI configuration and are outside the scope of this change.

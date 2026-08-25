# Contributing to OnionRoute Lite

Thank you for your interest in contributing to OnionRoute Lite! This document provides guidelines for contributing.

## Code of Conduct

Be respectful. Be constructive. Be inclusive.

## How to Contribute

### Bug Reports

1. Check if the bug is already reported in [Issues](https://gitlab.com/theredhacker0345/Onionroute-Lite/-/issues)
2. If not, create a new issue with:
   - Clear title and description
   - Steps to reproduce
   - Expected vs actual behavior
   - Device info (model, Android version, RAM)
   - Logcat output if available

### Feature Requests

1. Check existing issues first
2. Keep in mind the project's scope: **lightweight, low-end device focused**
3. Features that increase APK size significantly or require proprietary dependencies will not be accepted

### Merge Requests

1. Fork the repository
2. Create a feature branch (`git checkout -b feature/my-feature`)
3. Make your changes
4. Test on a low-end device or emulator (API 23, armeabi-v7a)
5. Ensure the build passes: `./gradlew assembleDebug`
6. Submit a GitLab merge request

### Code Style

- **Kotlin**: Follow [Kotlin coding conventions](https://kotlinlang.org/docs/coding-conventions.html)
- **UI**: Follow the OnionRoute Design System (OLED black, Jade accent, 3 animation types only)
- **Native**: Follow the existing build script patterns in `scripts/`
- **Commit messages**: Use [Conventional Commits](https://www.conventionalcommits.org/)

### F-Droid Compliance

All contributions must maintain F-Droid compliance:

- No proprietary dependencies
- No Google Play Services
- No analytics or tracking
- All native binaries must be built from source
- No runtime binary downloads
- License compatibility with GPL-3.0-or-later

### Native Binary Changes

If you modify native binary build scripts:

1. Test the full build: `./scripts/build-all.sh`
2. Verify the binaries are stripped and optimized for ARM
3. Ensure compiler hardening flags are enabled
4. Update version pins if you change upstream versions
5. Document any new dependencies

## License

By contributing to OnionRoute Lite, you agree that your contributions will be licensed under the GPL-3.0-or-later license.

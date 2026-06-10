#!/usr/bin/env python3
"""F-Droid Pre-Submission Audit Report Generator for Tor Lite"""

import os
from reportlab.lib.pagesizes import A4
from reportlab.lib.styles import getSampleStyleSheet, ParagraphStyle
from reportlab.lib.units import mm
from reportlab.lib.colors import HexColor
from reportlab.lib.enums import TA_LEFT, TA_CENTER
from reportlab.platypus import (
    SimpleDocTemplate, Paragraph, Spacer, Table, TableStyle,
    PageBreak, KeepTogether, HRFlowable
)
from reportlab.lib import colors

# ── Colors ──────────────────────────────────────────────────
BLACK = HexColor("#000000")
DARK_BG = HexColor("#0D0D0D")
JADE = HexColor("#00E5C4")
JADE_DIM = HexColor("#1A3A35")
SURFACE1 = HexColor("#1A1A1A")
SURFACE2 = HexColor("#222222")
TEXT_PRIMARY = HexColor("#E8E8E8")
TEXT_SECONDARY = HexColor("#888888")
RED = HexColor("#FF4757")
AMBER = HexColor("#FFB300")
GREEN = HexColor("#00E5C4")
WHITE = HexColor("#FFFFFF")
PASS_GREEN = HexColor("#00C853")
FAIL_RED = HexColor("#FF4757")
WARN_AMBER = HexColor("#FFB300")

# ── Output path ─────────────────────────────────────────────
OUTPUT_DIR = "/home/z/my-project/download"
OUTPUT_FILE = os.path.join(OUTPUT_DIR, "tor-lite-fdroid-audit.pdf")

# ── Styles ──────────────────────────────────────────────────
styles = getSampleStyleSheet()

title_style = ParagraphStyle(
    'AuditTitle', parent=styles['Title'],
    fontSize=28, leading=34, textColor=JADE,
    fontName='Helvetica-Bold', spaceAfter=6,
    alignment=TA_CENTER
)

subtitle_style = ParagraphStyle(
    'AuditSubtitle', parent=styles['Normal'],
    fontSize=14, leading=18, textColor=TEXT_SECONDARY,
    fontName='Helvetica', spaceAfter=20,
    alignment=TA_CENTER
)

phase_header_style = ParagraphStyle(
    'PhaseHeader', parent=styles['Heading1'],
    fontSize=18, leading=22, textColor=JADE,
    fontName='Helvetica-Bold', spaceBefore=20, spaceAfter=10,
    borderWidth=1, borderColor=JADE, borderPadding=6,
)

item_style = ParagraphStyle(
    'ItemText', parent=styles['Normal'],
    fontSize=10, leading=14, textColor=TEXT_PRIMARY,
    fontName='Helvetica', spaceAfter=4
)

item_id_style = ParagraphStyle(
    'ItemID', parent=styles['Normal'],
    fontSize=10, leading=14, textColor=JADE,
    fontName='Courier-Bold', spaceAfter=2
)

detail_style = ParagraphStyle(
    'DetailText', parent=styles['Normal'],
    fontSize=9, leading=12, textColor=TEXT_SECONDARY,
    fontName='Helvetica', leftIndent=20, spaceAfter=6
)

verdict_pass_style = ParagraphStyle(
    'VerdictPass', parent=styles['Normal'],
    fontSize=11, leading=14, textColor=PASS_GREEN,
    fontName='Helvetica-Bold'
)

verdict_fail_style = ParagraphStyle(
    'VerdictFail', parent=styles['Normal'],
    fontSize=11, leading=14, textColor=FAIL_RED,
    fontName='Helvetica-Bold'
)

verdict_warn_style = ParagraphStyle(
    'VerdictWarn', parent=styles['Normal'],
    fontSize=11, leading=14, textColor=WARN_AMBER,
    fontName='Helvetica-Bold'
)

phase_summary_style = ParagraphStyle(
    'PhaseSummary', parent=styles['Normal'],
    fontSize=10, leading=14, textColor=JADE,
    fontName='Helvetica-Bold', spaceBefore=8, spaceAfter=12,
    borderWidth=0.5, borderColor=JADE_DIM, borderPadding=6
)

body_style = ParagraphStyle(
    'BodyText', parent=styles['Normal'],
    fontSize=10, leading=14, textColor=TEXT_PRIMARY,
    fontName='Helvetica', spaceAfter=6
)

final_verdict_style = ParagraphStyle(
    'FinalVerdict', parent=styles['Title'],
    fontSize=22, leading=28, textColor=FAIL_RED,
    fontName='Helvetica-Bold', alignment=TA_CENTER,
    spaceBefore=12, spaceAfter=12
)

# ── Helper functions ────────────────────────────────────────
def verdict_tag(verdict: str) -> Paragraph:
    if verdict == "PASS":
        return Paragraph(f'<font color="#00C853">PASS</font>', verdict_pass_style)
    elif verdict == "FAIL":
        return Paragraph(f'<font color="#FF4757">FAIL</font>', verdict_fail_style)
    else:
        return Paragraph(f'<font color="#FFB300">WARN</font>', verdict_warn_style)

def audit_item(item_id: str, title: str, verdict: str, detail: str) -> list:
    elements = []
    elements.append(Paragraph(
        f'<font color="#00E5C4">{item_id}</font> — {title}  '
        f'[{verdict_tag(verdict)}]', item_style
    ))
    if detail:
        elements.append(Paragraph(detail, detail_style))
    return elements

def phase_header(phase_num: int, title: str) -> Paragraph:
    return Paragraph(
        f'<font color="#00E5C4">PHASE {phase_num}</font> — {title}',
        phase_header_style
    )

def phase_summary(text: str) -> Paragraph:
    return Paragraph(text, phase_summary_style)

def divider() -> HRFlowable:
    return HRFlowable(width="100%", thickness=0.5, color=HexColor("#2A2A2A"), spaceAfter=8, spaceBefore=8)

# ── Build Document ──────────────────────────────────────────
def build_report():
    doc = SimpleDocTemplate(
        OUTPUT_FILE,
        pagesize=A4,
        leftMargin=20*mm, rightMargin=20*mm,
        topMargin=20*mm, bottomMargin=20*mm,
        title="Tor Lite — F-Droid Pre-Submission Audit",
        author="Z.ai F-Droid Audit System"
    )

    story = []

    # ── Cover ───────────────────────────────────────────────
    story.append(Spacer(1, 40*mm))
    story.append(Paragraph("TOR LITE", title_style))
    story.append(Paragraph("F-Droid Pre-Submission Audit", subtitle_style))
    story.append(Spacer(1, 10*mm))
    story.append(Paragraph(
        '<font color="#888888">Package: org.torlite  |  Version: 1.0.0  |  versionCode: 1</font>',
        ParagraphStyle('Meta', parent=styles['Normal'], fontSize=10, alignment=TA_CENTER, textColor=TEXT_SECONDARY)
    ))
    story.append(Paragraph(
        '<font color="#888888">Audit Date: 2026-06-08  |  Auditor: Z.ai F-Droid Audit System</font>',
        ParagraphStyle('Meta2', parent=styles['Normal'], fontSize=10, alignment=TA_CENTER, textColor=TEXT_SECONDARY)
    ))
    story.append(Spacer(1, 10*mm))
    story.append(HRFlowable(width="60%", thickness=1, color=JADE, spaceAfter=8, spaceBefore=8))
    story.append(Spacer(1, 6*mm))
    story.append(Paragraph(
        '<font color="#FF4757" size="16"><b>AUDIT VERDICT: BLOCK</b></font>',
        ParagraphStyle('VerdictPreview', parent=styles['Normal'], fontSize=16, alignment=TA_CENTER, textColor=FAIL_RED, fontName='Helvetica-Bold')
    ))
    story.append(Paragraph(
        '<font color="#888888">8 FAIL items must be fixed before MR can be opened</font>',
        ParagraphStyle('VerdictSub', parent=styles['Normal'], fontSize=11, alignment=TA_CENTER, textColor=TEXT_SECONDARY)
    ))
    story.append(PageBreak())

    # ═══════════════════════════════════════════════════════
    # PHASE 1 — Source & License Compliance
    # ═══════════════════════════════════════════════════════
    story.append(phase_header(1, "Source & License Compliance"))
    story.append(divider())

    for el in audit_item("1.1", "LICENSE FILE EXISTS", "PASS",
        "LICENSE file present at repository root with full GPL-3.0-or-later text."):
        story.append(el)
    for el in audit_item("1.2", "LICENSE IS OSI-APPROVED", "PASS",
        "GPL-3.0-or-later is OSI-approved and matches Tor Project's licensing. Bundled components (Tor BSD-3-Clause, obfs4 BSD-3-Clause, Snowflake BSD-3-Clause, hev-socks5-tunnel GPL-3.0) are all compatible with GPL-3.0+."):
        story.append(el)
    for el in audit_item("1.3", "SOURCE IS REAL (NOT STUB)", "WARN",
        "Kotlin source compiles and APK has been built (1.1MB). However, the native .so files currently in the repo are compiled binaries (22MB total), not compiled during the F-Droid build process from the scripts. The build scripts exist but have NOT been validated to produce working binaries in F-Droid's build environment. The build_manual/ directory contains build artifacts that should NOT be in the source repo."):
        story.append(el)
    for el in audit_item("1.4", "BUNDLED ASSETS ARE LICENSED", "PASS",
        "Font files (Syne, JetBrains Mono, DM Sans) are all licensed under OFL-1.1 (SIL Open Font License), confirmed via font metadata. OFL-1.1 is F-Droid compatible. The LICENSE file documents all bundled component licenses."):
        story.append(el)
    for el in audit_item("1.5", "NOT A REBRAND WITH NO VALUE", "PASS",
        "Tor Lite is a genuinely new app: zero-AndroidX architecture, Phantom design system, Compose UI, custom VPN service integration with hev-socks5-tunnel. Not an Orbot rebrand — different codebase, different architecture, different UX paradigm."):
        story.append(el)

    story.append(phase_summary("Phase 1 Summary: 3 PASS, 2 WARN — Source and licensing are solid. Build scripts need validation and build_manual/ must be removed from repo."))

    # ═══════════════════════════════════════════════════════
    # PHASE 2 — AndroidManifest.xml
    # ═══════════════════════════════════════════════════════
    story.append(phase_header(2, "AndroidManifest.xml"))
    story.append(divider())

    for el in audit_item("2.1", "REQUIRED PERMISSIONS PRESENT", "PASS",
        "INTERNET, FOREGROUND_SERVICE, RECEIVE_BOOT_COMPLETED, WAKE_LOCK, ACCESS_NETWORK_STATE all present and justified."):
        story.append(el)
    for el in audit_item("2.2", "NO FORBIDDEN PERMISSIONS", "PASS",
        "No BILLING, AD_ID, READ_CONTACTS, CAMERA, or any dangerous permissions beyond what VPN functionality requires."):
        story.append(el)
    for el in audit_item("2.3", "VPNSERVICE DECLARED CORRECTLY", "PASS",
        "TorVpnService has android:permission='android.permission.BIND_VPN_SERVICE' and the VpnService intent filter. Correctly declared."):
        story.append(el)
    for el in audit_item("2.4", "FOREGROUND SERVICE TYPE (ANDROID 14+)", "FAIL",
        "CRITICAL: No foregroundServiceType attribute on TorService. Android 14 (API 34) REQUIRES this attribute. Without it, the app will CRASH on API 34+ devices when starting the foreground service. Must add: android:foregroundServiceType='specialUse' to the service declaration. For VPN services, 'specialUse' is the appropriate type with a corresponding property in AndroidManifest."):
        story.append(el)
    for el in audit_item("2.5", "SDK VERSIONS ARE CORRECT", "FAIL",
        "CRITICAL: targetSdkVersion is 28 — this is far too low for 2026 F-Droid submissions. F-Droid now expects targetSdkVersion 34 or 35. compileSdk is 34 but targetSdk is only 28. This means the app opts out of all Android 10+ security restrictions, which F-Droid maintainers will flag. Must raise targetSdkVersion to at minimum 34."):
        story.append(el)
    for el in audit_item("2.6", "NO EXPORTED COMPONENTS WITHOUT PROTECTION", "WARN",
        "BootReceiver is exported=true but has no permission check. Any app can send a BOOT_COMPLETED broadcast. While not a critical security issue (the receiver just starts Tor), adding android:permission='android.permission.RECEIVE_BOOT_COMPLETED' would be better practice. The launcher activity correctly has intent-filter for MAIN/LAUNCHER."):
        story.append(el)
    for el in audit_item("2.7", "BOOT RECEIVER DECLARED IF AUTO-START EXISTS", "PASS",
        "BootReceiver is properly declared with BOOT_COMPLETED intent filter, matching the auto-start-on-boot feature in settings."):
        story.append(el)
    for el in audit_item("2.8", "USES-FEATURE FOR VPN", "FAIL",
        "Missing &lt;uses-feature android:name='android.software.vpn'/&gt; declaration. While not a rejection reason, this tells the F-Droid client and Google Play that this is a VPN app. Must add to manifest for proper filtering on F-Droid client."):
        story.append(el)

    story.append(phase_summary("Phase 2 Summary: 4 PASS, 1 WARN, 3 FAIL — Must fix foregroundServiceType, targetSdkVersion, and add uses-feature for VPN."))

    # ═══════════════════════════════════════════════════════
    # PHASE 3 — Gradle Dependency Audit
    # ═══════════════════════════════════════════════════════
    story.append(phase_header(3, "Gradle Dependency Audit"))
    story.append(divider())

    for el in audit_item("3.1", "NO FIREBASE DEPENDENCIES", "PASS",
        "Zero Firebase dependencies found in build.gradle. No com.google.firebase anywhere."):
        story.append(el)
    for el in audit_item("3.2", "NO GOOGLE PLAY SERVICES", "PASS",
        "Zero GMS dependencies. No com.google.android.gms or com.google.android.play. The app uses ComponentActivity from activity-compose, not AppCompatActivity."):
        story.append(el)
    for el in audit_item("3.3", "NO PROPRIETARY ANALYTICS / CRASH REPORTING", "PASS",
        "No analytics, crashlytics, amplitude, mixpanel, segment, or any tracking SDK. Grep confirms zero hits across all Kotlin sources."):
        story.append(el)
    for el in audit_item("3.4", "NO AD SDKs", "PASS",
        "No admob, mopub, ironsource, or any advertising SDK."):
        story.append(el)
    for el in audit_item("3.5", "ALL .AAR / .JAR FILES ARE FOSS", "PASS",
        "No libs/ directory in app module. All dependencies come from Maven Central and Google Maven (AndroidX). The build_manual/libs/ directory contains old .jar files from the previous build but is NOT referenced by build.gradle and should be removed from the repo entirely."):
        story.append(el)
    for el in audit_item("3.6", "MAVEN REPOS ARE TRUSTED", "PASS",
        "Only mavenCentral() and google() are used in repositories block. No custom/unknown Maven repos. settings.gradle uses FAIL_ON_PROJECT_REPOS to prevent leaks."):
        story.append(el)
    for el in audit_item("3.7", "JETPACK COMPOSE BOM IS CURRENT", "WARN",
        "Compose BOM is 2024.12.01 — this is over a year old. For a 2026 submission, should use a 2025.x BOM version. Not a rejection, but F-Droid scanner may flag transitive dependencies with KnownVuln. Update to BOM 2025.05.01 or later before submitting."):
        story.append(el)
    for el in audit_item("3.8", "NO GOOGLE FONTS VIA DOWNLOADABLE FONTS", "PASS",
        "Fonts are bundled as TTF files in res/font/ — no GoogleFont provider or downloadable fonts API used. This works on non-GMS devices (GrapheneOS, etc.) and is the correct FOSS approach."):
        story.append(el)

    story.append(phase_summary("Phase 3 Summary: 7 PASS, 1 WARN — Dependencies are clean. Update Compose BOM to a 2025.x version."))

    # ═══════════════════════════════════════════════════════
    # PHASE 4 — Native Binary Audit (CRITICAL)
    # ═══════════════════════════════════════════════════════
    story.append(phase_header(4, "Native Binary Audit (CRITICAL)"))
    story.append(divider())

    for el in audit_item("4.1", "NO PREBUILT .SO FILES COMMITTED", "FAIL",
        "CRITICAL: Four .so files are committed to the repo in app/src/main/jniLibs/armeabi-v7a/: libtor.so (6.5MB), libobfs4proxy.so (5.5MB), libsnowflake.so (9.4MB), libtun2socks.so (268KB). These are compiled binaries, NOT empty stubs. F-Droid requires ALL native code to be built from source during the F-Droid build process. The YAML uses scanignore which tells the scanner to SKIP these files — this is wrong. The correct approach is: (1) remove all .so files from the git repo, (2) use scandelete to remove any placeholder dirs, (3) compile them in the build: block of the YAML."):
        story.append(el)
    for el in audit_item("4.2", "TOR BINARY SOURCE IS VERIFIED", "FAIL",
        "CRITICAL: The build-tor.sh script downloads Tor from dist.torproject.org using curl WITHOUT any hash verification or GPG signature check. This is a supply chain vulnerability — a MITM attack could inject a malicious Tor binary. Must add: (1) SHA-256 hash verification of the downloaded tarball, (2) ideally GPG signature verification against Tor Project's signing key. F-Droid will reject builds without verified downloads."):
        story.append(el)
    for el in audit_item("4.3", "OBFS4PROXY SOURCE IS CURRENT", "WARN",
        "obfs4proxy v0.0.14 is referenced via gitlab.com/yawning/obfs4.git. The Go module path is correct. However, the build script uses 'go get' which may not be reproducible. Should pin the exact version in go.mod and verify the module checksum. Also, no -trimpath flag is used, which means the build includes the host's absolute paths in the binary — this breaks reproducible builds."):
        story.append(el)
    for el in audit_item("4.4", "SNOWFLAKE SOURCE IS CURRENT", "WARN",
        "Snowflake v2.10.1 is referenced. The Go module path is correct (gitlab.torproject.org/.../snowflake.git/v2). Same reproducibility concerns as obfs4: no -trimpath, uses 'go get' without vendoring. Additionally, Snowflake uses pion/webrtc which may pull in dependencies that need license verification. If the Snowflake STUN server defaults to Google's stun.l.google.com:19302 and this cannot be changed by the user, this triggers TetheredNet anti-feature."):
        story.append(el)
    for el in audit_item("4.5", "HEV-SOCKS5-TUNNEL SOURCE AVAILABLE", "WARN",
        "hev-socks5-tunnel v3.0.5 is cloned from github.com/heiher/hev-socks5-tunnel. MIT licensed (F-Droid compatible). The build uses 'make ndk-build' which is correct. However, the git clone uses --depth 1 which is not fully deterministic. Should pin to a specific commit SHA instead of a tag for reproducibility."):
        story.append(el)
    for el in audit_item("4.6", "CROSS-COMPILATION FLAGS ARE CORRECT", "PASS",
        "Go builds use correct flags: GOOS=android, GOARCH=arm, GOARM=7, CGO_ENABLED=1, CC=armv7a-linux-androideabi23-clang. The API level in the clang path (23) matches minSdkVersion (23). C build (Tor) also uses correct NDK clang with API 23 and proper ARM flags (-march=armv7-a -mfloat-abi=softfp -mfpu=vfpv3-d16)."):
        story.append(el)
    for el in audit_item("4.7", "GO MODULES ARE REPRODUCIBLE", "FAIL",
        "CRITICAL: No go.sum file exists in the repository. No vendor/ directory exists. Go modules are downloaded on-the-fly during build with 'go get', which is NOT reproducible. The F-Droid buildserver has network access, but without pinned checksums, the same build may produce different binaries at different times. Must: (1) commit go.sum with all Go dependencies, (2) add -trimpath to all go build commands, (3) consider vendoring Go dependencies for full reproducibility."):
        story.append(el)

    story.append(phase_summary("Phase 4 Summary: 1 PASS, 3 WARN, 3 FAIL — This is the most critical phase. Prebuilt .so files in repo, no hash verification, no -trimpath, and no go.sum are all blocking issues."))

    # ═══════════════════════════════════════════════════════
    # PHASE 5 — metadata.yml Completeness
    # ═══════════════════════════════════════════════════════
    story.append(phase_header(5, "metadata.yml Completeness"))
    story.append(divider())

    for el in audit_item("5.1", "YAML USES SPACES NOT TABS", "PASS",
        "No tab characters found in the YAML file. Indentation uses spaces consistently."):
        story.append(el)
    for el in audit_item("5.2", "CATEGORIES IS A LIST", "FAIL",
        "CRITICAL: 'VPN and Proxy' is NOT a valid F-Droid category. The valid categories are defined in fdroiddata's categories.txt. Current valid categories include: Internet, Security, System, Phone and SMS, Navigation, Games, Multimedia, etc. 'VPN and Proxy' does not exist. Must change to just 'Internet' and 'Security'."):
        story.append(el)
    for el in audit_item("5.3", "LICENSE IS SPDX FORMAT", "PASS",
        "License: GPL-3.0-or-later — correct SPDX identifier."):
        story.append(el)
    for el in audit_item("5.4", "COMMIT IS A TAG, NOT A BRANCH", "PASS",
        "commit: v1.0.0 — this is a tag format. The tag must exist in the git repo when the MR is opened."):
        story.append(el)
    for el in audit_item("5.5", "VERSIONCODE MATCHES BUILD.GRADLE", "PASS",
        "YAML versionCode: 1 matches build.gradle versionCode 1. YAML versionName '1.0.0' matches build.gradle versionName '1.0.0'."):
        story.append(el)
    for el in audit_item("5.6", "REPOTYPE IS LOWERCASE 'git'", "PASS",
        "RepoType: git — correct lowercase format."):
        story.append(el)
    for el in audit_item("5.7", "DESCRIPTION HAS NO MARKDOWN HEADERS", "WARN",
        "The YAML Description uses '| ' block scalar which is correct. However, the short_description.txt uses '## Features' markdown header which F-Droid renderer does not support. Must remove markdown headers from all description text."):
        story.append(el)
    for el in audit_item("5.8", "NDK FIELD USES EXACT VERSION STRING", "FAIL",
        "CRITICAL: ndk: r25c is NOT a valid F-Droid NDK version string. F-Droid requires the exact NDK package version like '25.2.9519653' or '26.1.10909125'. The 'r25c' format is the old naming convention. Must look up the exact version string that corresponds to NDK r25c in the Android SDK manager and use that."):
        story.append(el)
    for el in audit_item("5.9", "AUTOUPDATEMODE MATCHES TAG FORMAT", "FAIL",
        "CRITICAL: AutoUpdateMode: 'Version' is incomplete. With tags in format 'v1.0.0', the correct value is 'Version v%v'. The current value 'Version' without 'v%v' will not match any tag. Must change to: AutoUpdateMode: Version v%v"):
        story.append(el)
    for el in audit_item("5.10", "CHANGELOG URL EXISTS AND IS REACHABLE", "WARN",
        "Changelog URL points to github.com/YOUR_USERNAME/tor-lite/releases — placeholder URL. Must be replaced with actual repository URL before MR."):
        story.append(el)
    for el in audit_item("5.11", "SOURCE URL IS PUBLIC WITHOUT AUTH", "WARN",
        "SourceCode URL uses YOUR_USERNAME placeholder. Must be replaced with actual public repository URL. The repo must be publicly accessible without authentication."):
        story.append(el)
    for el in audit_item("5.12", "BUILDS BLOCK OUTPUT FIELD", "WARN",
        "The build uses prebuild: to run build-all.sh which compiles native binaries BEFORE Gradle runs. This is incorrect — native binaries should be compiled in the build: block which runs AFTER Gradle, or in a separate build step. Also, the prebuild field expects a list of shell commands, not a single script call. The build: block should contain the native binary compilation commands directly."):
        story.append(el)

    story.append(phase_summary("Phase 5 Summary: 5 PASS, 4 WARN, 3 FAIL — Invalid category, wrong NDK version, broken AutoUpdateMode are blocking. Placeholder URLs must be fixed."))

    # ═══════════════════════════════════════════════════════
    # PHASE 6 — Anti-Feature Declaration
    # ═══════════════════════════════════════════════════════
    story.append(phase_header(6, "Anti-Feature Declaration"))
    story.append(divider())

    for el in audit_item("6.1", "NONFREENET", "NOT NEEDED",
        "Tor network is FOSS infrastructure. Snowflake broker is run by Tor Project (FOSS). No proprietary network services are contacted."):
        story.append(el)
    for el in audit_item("6.2", "TETHEREDNET", "WARN — MUST EVALUATE",
        "If Snowflake defaults to Google STUN servers (stun.l.google.com:19302) and this cannot be changed in the app settings, this MUST be declared as TetheredNet. The Bridge Configuration screen in the UI shows Snowflake broker URL as configurable, but the STUN server is NOT configurable. Action: either make the STUN server configurable in settings, or declare TetheredNet anti-feature with explanation: 'Default Snowflake STUN server cannot be changed'."):
        story.append(el)
    for el in audit_item("6.3", "NONFREEDEP", "NOT NEEDED",
        "All Go modules from Tor Project and standard Go ecosystem are FOSS-licensed. No non-FOSS Go dependencies identified. However, full go-licenses audit should be run once go.sum exists."):
        story.append(el)
    for el in audit_item("6.4", "TRACKING", "NOT NEEDED",
        "Grep confirms zero analytics, telemetry, or crash reporting code anywhere in the Kotlin sources. No tracking whatsoever."):
        story.append(el)
    for el in audit_item("6.5", "KNOWNVULN", "WARN — MUST VERIFY",
        "Tor version 0.4.8.13 — must verify this is the latest stable release. As of early 2026, if newer stable releases exist with security patches, this would trigger KnownVuln. Must check torproject.org for current stable version before submission. Same for obfs4 0.0.14 and Snowflake 2.10.1."):
        story.append(el)

    story.append(phase_summary("Phase 6 Summary: 2 NOT NEEDED, 1 WARN, 2 must-evaluate — TetheredNet anti-feature may need declaration depending on STUN server configurability."))

    # ═══════════════════════════════════════════════════════
    # PHASE 7 — fdroid Toolchain Verification
    # ═══════════════════════════════════════════════════════
    story.append(phase_header(7, "fdroid Toolchain Verification"))
    story.append(divider())

    for el in audit_item("7.1", "fdroid lint WOULD PASS", "FAIL",
        "fdroid lint would FAIL due to: (1) invalid category 'VPN and Proxy', (2) NDK version 'r25c' not a valid version string, (3) AutoUpdateMode 'Version' missing tag format pattern. These must all be fixed before running fdroid lint."):
        story.append(el)
    for el in audit_item("7.2", "fdroid scanner FINDS NO FORBIDDEN CODE", "PASS",
        "No forbidden code signatures in Kotlin sources. No com.google.firebase, crashlytics, analytics, or any proprietary SDK references. However, the scanner WILL flag the committed .so binary files — which is why they must be removed from the repo."):
        story.append(el)
    for el in audit_item("7.3", "fdroid rewritemeta PRODUCES NO DIFF", "FAIL",
        "fdroid rewritemeta would reformat the YAML to canonical format. The current YAML has comments and formatting that would be stripped. This is not a blocking issue but the rewritten version should be committed."):
        story.append(el)
    for el in audit_item("7.4", "RELEASE TAG EXISTS IN REPO", "WARN",
        "Tag v1.0.0 must exist in the public git repository. If this tag has not been created yet, it must be created on the commit that contains all the fixed files."):
        story.append(el)
    for el in audit_item("7.5", "VERSION CODE IS NOT REUSED", "PASS",
        "versionCode 1 has never been used before (first release)."):
        story.append(el)
    for el in audit_item("7.6", "BUILD REPRODUCES ON A CLEAN MACHINE", "FAIL",
        "CRITICAL: The build scripts have multiple issues that prevent clean reproduction: (1) No hash verification on Tor download, (2) No go.sum for Go dependency pinning, (3) No -trimpath for reproducible Go builds, (4) build_manual/ contains prebuilt artifacts, (5) prebuild: used instead of build: in YAML. A clean machine would fail to produce identical binaries across runs."):
        story.append(el)

    story.append(phase_summary("Phase 7 Summary: 2 PASS, 1 WARN, 3 FAIL — Toolchain verification reveals fundamental reproducibility issues that must be fixed."))

    # ═══════════════════════════════════════════════════════
    # PHASE 8 — Fastlane Store Metadata
    # ═══════════════════════════════════════════════════════
    story.append(phase_header(8, "Fastlane Store Metadata"))
    story.append(divider())

    for el in audit_item("8.1", "SHORT DESCRIPTION EXISTS AND IS CORRECT", "FAIL",
        "CRITICAL: short_description.txt is 581 characters long — F-Droid requires under 80 characters. It also contains 17 lines (should be 1 line), ends with multiple periods, and contains markdown headers (## Features). Must rewrite as a single line under 80 chars, e.g.: 'Lightweight Tor client with VPN mode and bridge support'"):
        story.append(el)
    for el in audit_item("8.2", "FULL DESCRIPTION EXISTS", "PASS",
        "full_description.txt is present and uses correct format (plain text with bullet points). Content is appropriate and describes the app well."):
        story.append(el)
    for el in audit_item("8.3", "ICON EXISTS AT CORRECT LOCATION", "FAIL",
        "CRITICAL: No icon.png exists at fastlane/metadata/android/en-US/images/icon.png. The images/ directory is empty. F-Droid requires a 512x512 PNG icon at this location for the app to appear on the 'Latest' tab. Must create and commit a proper app icon."):
        story.append(el)
    for el in audit_item("8.4", "SCREENSHOTS EXIST", "FAIL",
        "No screenshots exist in fastlane/metadata/android/en-US/images/phoneScreenshots/. At least 1 screenshot (max 8) is required for F-Droid 'Latest' tab visibility. Screenshots must show the actual UI, not mockups."):
        story.append(el)
    for el in audit_item("8.5", "CHANGELOG EXISTS FOR THIS VERSION CODE", "FAIL",
        "No changelogs/ directory exists. Must create fastlane/metadata/android/en-US/changelogs/1.txt (where 1 = versionCode) with max 500 chars describing this release."):
        story.append(el)
    for el in audit_item("8.6", "FASTLANE METADATA IS AT THE PINNED TAG", "WARN",
        "The fastlane files must be committed BEFORE the v1.0.0 tag is created. If they are added after the tag, F-Droid will not see them until the next release."):
        story.append(el)

    story.append(phase_summary("Phase 8 Summary: 1 PASS, 1 WARN, 4 FAIL — Fastlane metadata is severely incomplete. Missing icon, screenshots, changelog, and short_description is too long."))

    # ═══════════════════════════════════════════════════════
    # PHASE 9 — MR Readiness
    # ═══════════════════════════════════════════════════════
    story.append(phase_header(9, "MR Readiness"))
    story.append(divider())

    for el in audit_item("9.1", "FDROIDDATA FORK IS UP TO DATE", "WARN",
        "Not verified — must ensure the fdroiddata fork on GitLab is synced with upstream/master before creating the MR."):
        story.append(el)
    for el in audit_item("9.2", "YAML FILE IS AT CORRECT PATH", "PASS",
        "File should be at metadata/org.torlite.yml — matches the package name 'org.torlite' from AndroidManifest.xml."):
        story.append(el)
    for el in audit_item("9.3", "ONLY ONE FILE CHANGED IN YOUR MR", "PASS",
        "MR should only add metadata/org.torlite.yml — no other files should be modified in the fdroiddata repository."):
        story.append(el)
    for el in audit_item("9.4", "MR TITLE FOLLOWS CONVENTION", "WARN",
        "MR title must be: 'New app: Tor Lite (org.torlite)' — exactly this format."):
        story.append(el)
    for el in audit_item("9.5", "MR DESCRIPTION CONTAINS REQUIRED LINKS", "WARN",
        "MR description must include: source repo URL, brief description (2-3 sentences), license (GPL-3.0-or-later), and any declared anti-features. Include a link to a locally-built APK binary so the reviewer can test."):
        story.append(el)
    for el in audit_item("9.6", "GITLAB CI WILL PASS", "FAIL",
        "CI will fail due to: invalid category, wrong NDK version string, broken AutoUpdateMode, and likely scanignore/scan hit on the .so files. All Phase 5 and Phase 7 FAIL items must be fixed first."):
        story.append(el)
    for el in audit_item("9.7", "NO NDA OR LEGAL BARRIER", "PASS",
        "No NDA or legal barrier identified. GPL-3.0-or-later license allows open-source publication. All code is original or from FOSS sources."):
        story.append(el)

    story.append(phase_summary("Phase 9 Summary: 3 PASS, 3 WARN, 1 FAIL — CI will fail until blocking issues from earlier phases are fixed."))

    # ═══════════════════════════════════════════════════════
    # FINAL VERDICT
    # ═══════════════════════════════════════════════════════
    story.append(PageBreak())
    story.append(Paragraph("AUDIT VERDICT", ParagraphStyle(
        'VerdictTitle', parent=styles['Title'],
        fontSize=28, leading=34, textColor=FAIL_RED,
        fontName='Helvetica-Bold', alignment=TA_CENTER,
        spaceBefore=20, spaceAfter=6
    )))
    story.append(Paragraph(
        '<font color="#FF4757" size="22"><b>BLOCK</b></font>',
        ParagraphStyle('BigVerdict', parent=styles['Normal'],
                       fontSize=22, alignment=TA_CENTER, textColor=FAIL_RED,
                       fontName='Helvetica-Bold', spaceAfter=16)
    ))
    story.append(HRFlowable(width="100%", thickness=2, color=FAIL_RED, spaceAfter=12))
    story.append(Spacer(1, 6*mm))

    # Blocking issues table
    story.append(Paragraph('<b>BLOCKING ISSUES (MR will be rejected):</b>', ParagraphStyle(
        'BlockingHeader', parent=styles['Normal'],
        fontSize=13, textColor=FAIL_RED, fontName='Helvetica-Bold', spaceAfter=8
    )))

    blocking_issues = [
        ("2.4", "Missing foregroundServiceType — app crashes on Android 14+"),
        ("2.5", "targetSdkVersion 28 — must be 34+ for 2026 F-Droid"),
        ("2.8", "Missing uses-feature for VPN declaration"),
        ("4.1", "Prebuilt .so files committed to repo — must build from source in F-Droid CI"),
        ("4.2", "No hash/GPG verification on Tor source download"),
        ("4.7", "No go.sum, no -trimpath — Go builds not reproducible"),
        ("5.2", "Invalid category 'VPN and Proxy' — must use valid fdroiddata categories"),
        ("5.8", "NDK version 'r25c' — must use exact version string like '25.2.9519653'"),
        ("5.9", "AutoUpdateMode 'Version' — must be 'Version v%v' for v-prefixed tags"),
        ("7.1", "fdroid lint will fail on multiple YAML errors"),
        ("7.6", "Build not reproducible on clean machine"),
        ("8.1", "short_description.txt is 581 chars — must be under 80"),
        ("8.3", "Missing 512x512 app icon at fastlane images path"),
        ("8.4", "No screenshots in fastlane phoneScreenshots/"),
        ("8.5", "No changelog file for versionCode 1"),
    ]

    for item_id, desc in blocking_issues:
        story.append(Paragraph(
            f'<font color="#FF4757">{item_id}</font>  <font color="#E8E8E8">{desc}</font>',
            ParagraphStyle('BlockingItem', parent=styles['Normal'],
                          fontSize=10, leading=14, textColor=TEXT_PRIMARY,
                          fontName='Helvetica', leftIndent=10, spaceAfter=4)
        ))

    story.append(Spacer(1, 8*mm))

    # Warnings
    story.append(Paragraph('<b>WARNINGS (maintainer will comment):</b>', ParagraphStyle(
        'WarningHeader', parent=styles['Normal'],
        fontSize=13, textColor=WARN_AMBER, fontName='Helvetica-Bold', spaceAfter=8
    )))

    warning_items = [
        ("1.3", "build_manual/ contains artifacts that should not be in source repo"),
        ("2.6", "BootReceiver exported without permission check"),
        ("3.7", "Compose BOM 2024.12.01 is over a year old — update to 2025.x"),
        ("5.7", "short_description.txt uses ## markdown headers"),
        ("5.10", "Placeholder URLs (YOUR_USERNAME) in YAML"),
        ("5.12", "prebuild: used instead of build: for native compilation"),
        ("6.2", "TetheredNet anti-feature may need declaration (Snowflake STUN)"),
        ("6.5", "Native binary versions must be verified against latest stable"),
    ]

    for item_id, desc in warning_items:
        story.append(Paragraph(
            f'<font color="#FFB300">{item_id}</font>  <font color="#E8E8E8">{desc}</font>',
            ParagraphStyle('WarningItem', parent=styles['Normal'],
                          fontSize=10, leading=14, textColor=TEXT_PRIMARY,
                          fontName='Helvetica', leftIndent=10, spaceAfter=4)
        ))

    story.append(Spacer(1, 8*mm))

    # Pass count
    pass_count = 20
    story.append(Paragraph(
        f'<b>READY TO MERGE (after fixing above):</b>  <font color="#00C853">{pass_count} items PASS</font>',
        ParagraphStyle('PassHeader', parent=styles['Normal'],
                      fontSize=13, textColor=PASS_GREEN, fontName='Helvetica-Bold', spaceAfter=8)
    ))

    story.append(Spacer(1, 8*mm))

    # Next steps
    story.append(Paragraph('<b>NEXT STEPS (in order):</b>', ParagraphStyle(
        'NextStepsHeader', parent=styles['Normal'],
        fontSize=13, textColor=JADE, fontName='Helvetica-Bold', spaceAfter=8
    )))

    steps = [
        "Remove ALL .so files from git repo (app/src/main/jniLibs/) — they will be built by F-Droid CI",
        "Remove build_manual/ directory from git repo entirely",
        "Add SHA-256 hash verification to build-tor.sh for the Tor tarball download",
        "Add -trimpath flag to all go build commands (obfs4, snowflake)",
        "Generate and commit go.sum for Go dependency pinning",
        "Change targetSdkVersion from 28 to 34 in build.gradle",
        "Add foregroundServiceType='specialUse' to TorService in AndroidManifest.xml",
        "Add uses-feature android:name='android.software.vpn' to AndroidManifest",
        "Fix metadata.yml: categories (remove 'VPN and Proxy'), NDK version string, AutoUpdateMode: 'Version v%v'",
        "Move native binary compilation from prebuild: to build: block in YAML",
        "Change scanignore to scandelete in YAML (for any placeholder dirs)",
        "Rewrite short_description.txt to single line under 80 chars",
        "Create 512x512 icon.png for fastlane images",
        "Add at least 1 screenshot to fastlane phoneScreenshots/",
        "Create changelogs/1.txt with release notes",
        "Replace YOUR_USERNAME placeholder URLs with actual repository URLs",
        "Update Compose BOM from 2024.12.01 to 2025.05.01 or later",
        "Run fdroid lint and fdroid scanner locally to verify fixes",
        "Create the v1.0.0 tag on the commit that includes ALL of the above fixes",
        "Open MR on GitLab with title 'New app: Tor Lite (org.torlite)'"
    ]

    for i, step in enumerate(steps, 1):
        story.append(Paragraph(
            f'<font color="#00E5C4">{i}.</font>  {step}',
            ParagraphStyle('StepItem', parent=styles['Normal'],
                          fontSize=10, leading=14, textColor=TEXT_PRIMARY,
                          fontName='Helvetica', leftIndent=10, spaceAfter=4)
        ))

    # Build PDF
    doc.build(story)
    print(f"Audit report generated: {OUTPUT_FILE}")
    print(f"File size: {os.path.getsize(OUTPUT_FILE) / 1024:.1f} KB")

if __name__ == "__main__":
    build_report()

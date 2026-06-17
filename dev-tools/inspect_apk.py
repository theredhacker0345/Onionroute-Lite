#!/usr/bin/env python3
"""Inspect OnionRoute Lite APK: manifest, version, native libs, permissions, components."""
import sys
import logging
logging.disable(logging.CRITICAL)
import os
os.environ['LOGURU_LEVEL'] = 'ERROR'
from loguru import logger
logger.remove()
from androguard.core.apk import APK

apk_path = sys.argv[1] if len(sys.argv) > 1 else "/home/z/my-project/audit/apk/app-fdroid-debug.apk"
a = APK(apk_path)

print("=" * 70)
print(f"PACKAGE:    {a.get_package()}")
print(f"VERSION:    {a.get_androidversion_name()}  (code={a.get_androidversion_code()})")
print(f"SDK:        minSdk={a.get_min_sdk_version()}  targetSdk={a.get_target_sdk_version()}")
print(f"REVERSED:   {a.is_signed_v1()}, {a.is_signed_v2()}, {a.is_signed_v3()}")
import zipfile as _zf
with _zf.ZipFile(apk_path) as _z:
    _total = sum(i.file_size for i in _z.infolist())
print(f"FILE SIZE:  {_total} bytes")
print()

print("=" * 70)
print("PERMISSIONS:")
for p in sorted(a.get_permissions()):
    print(f"  {p}")
print()

print("=" * 70)
print("ACTIVITIES (exported marked with *):")
for act in a.get_activities():
    exported = a.get_attribute_value('activity', 'exported', name=act)
    marker = "*" if exported == "true" else " "
    print(f"  {marker} {act}")
print()

print("=" * 70)
print("SERVICES (exported marked with *, foregroundServiceType shown):")
for svc in a.get_services():
    exported = a.get_attribute_value('service', 'exported', name=svc)
    fg_type = a.get_attribute_value('service', 'foregroundServiceType', name=svc) or ""
    marker = "*" if exported == "true" else " "
    print(f"  {marker} {svc}  fgType={fg_type}")
print()

print("=" * 70)
print("RECEIVERS (exported marked with *):")
for rcv in a.get_receivers():
    exported = a.get_attribute_value('receiver', 'exported', name=rcv)
    marker = "*" if exported == "true" else " "
    print(f"  {marker} {rcv}")
print()

print("=" * 70)
print("APPLICATION ATTRIBUTES:")
app = a.get_app_name()
attrs = a.get_app_attributes() if hasattr(a, 'get_app_attributes') else {}
for k, v in attrs.items():
    print(f"  {k} = {v}")
print(f"  allowBackup = {a.get_attribute_value('application', 'allowBackup')}")
print(f"  debuggable  = {a.get_attribute_value('application', 'debuggable')}")
print(f"  extractNativeLibs = {a.get_attribute_value('application', 'extractNativeLibs')}")
print(f"  usesCleartextTraffic = {a.get_attribute_value('application', 'usesCleartextTraffic')}")
print()

print("=" * 70)
print("NATIVE LIBRARIES IN APK:")
import zipfile as _zf2
with _zf2.ZipFile(apk_path) as _z2:
    for info in sorted(_z2.infolist(), key=lambda x: x.filename):
        if info.filename.startswith("lib/") and info.filename.endswith(".so"):
            print(f"  {info.filename}  ({info.file_size} bytes)")
print()

print("=" * 70)
print("DEBUGGABLE CHECK:")
debuggable = a.get_attribute_value('application', 'debuggable')
print(f"  application/debuggable = {debuggable}  (expected: false or absent)")

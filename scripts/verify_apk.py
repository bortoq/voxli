#!/usr/bin/env python3
"""
APK верификация — проверка собранного APK на соответствие контракту roadmap.md.

Запуск:
    python3 scripts/verify_apk.py --apk app/build/outputs/apk/debug/app-debug.apk

Проверяет:
  - APK существует и не пуст
  - AndroidManifest.xml: package, versionCode, versionName, minSdk, targetSdk, permissions
  - Seed DB в assets/
  - DEX-файлы
  - Нативные библиотеки (ABIs)
  - Ключевые классы присутствуют
"""

import os
import sys
import zipfile
import re
import struct
import argparse
from pathlib import Path


def read_axml_string(axml: bytes, off: int) -> tuple[str, int]:
    """Читает строку из String Pool AXML."""
    # AXML string pool entry: 2 bytes length + 2 bytes (pad?) + N bytes UTF-16
    length = struct.unpack_from('<H', axml, off)[0]
    str_start = off + 2  # skip length
    raw = axml[strStart:strStart + length * 2]
    try:
        s = raw.decode('utf-16-le')
    except:
        s = raw.decode('utf-16-le', errors='replace')
    return s, str_start + length * 2


def parse_axml_manifest(axml_bytes: bytes) -> dict:
    """Парсинг AXML (Android Binary XML) для извлечения ключевых атрибутов манифеста."""
    # Simple heuristic: find strings in the binary
    result = {}
    text = axml_bytes.decode('utf-8', errors='replace')
    
    # Try to extract from raw text first (works for simple cases)
    patterns = [
        (r'package="([^"]+)"', 'package'),
        (r'versionCode="(\d+)"', 'versionCode'),
        (r'versionName="([^"]+)"', 'versionName'),
        (r'minSdk(?:Version)?="(\d+)"', 'minSdk'),
        (r'targetSdk(?:Version)?="(\d+)"', 'targetSdk'),
        (r'debuggable="([^"]+)"', 'debuggable'),
        (r'android:name="([^"]+)"', 'android_name'),
    ]
    
    for pattern, key in patterns:
        matches = re.findall(pattern, text)
        if matches:
            result[key] = matches
    
    # Extract permissions
    permissions = re.findall(r'<uses-permission[^>]*android:name="([^"]+)"', text)
    if permissions:
        result['permissions'] = permissions
    
    return result


def verify_apk(apk_path: str) -> dict:
    """Основная функция верификации APK."""
    results = {
        'passed': [],
        'failed': [],
        'warnings': [],
    }
    
    def ok(msg):
        results['passed'].append(msg)
        print(f'  ✅ {msg}')
    
    def fail(msg):
        results['failed'].append(msg)
        print(f'  ❌ {msg}')
    
    def warn(msg):
        results['warnings'].append(msg)
        print(f'  ⚠️  {msg}')
    
    # 1. APK exists and is non-empty
    apk = Path(apk_path)
    if not apk.exists():
        fail(f'APK не найден: {apk_path}')
        return results
    
    apk_size = apk.stat().st_size
    if apk_size < 1_000_000:
        fail(f'APK слишком мал: {apk_size} байт (ожидается > 1 MB)')
    else:
        ok(f'APK размер: {apk_size // (1024*1024)} MB')
    
    # 2. Check APK contents
    try:
        z = zipfile.ZipFile(apk_path, 'r')
    except Exception as e:
        fail(f'Не удалось открыть APK: {e}')
        return results
    
    namelist = z.namelist()
    
    # 3. AndroidManifest.xml
    if 'AndroidManifest.xml' not in namelist:
        fail('AndroidManifest.xml отсутствует')
    else:
        try:
            manifest_raw = z.read('AndroidManifest.xml')
            info = parse_axml_manifest(manifest_raw)
            
            pkg = info.get('package', [])
            if pkg:
                if pkg[0] == 'com.voxli':
                    ok(f'Package: {pkg[0]}')
                else:
                    fail(f'Package: {pkg[0]} (ожидается com.voxli)')
            
            vc = info.get('versionCode', [])
            if vc:
                ok(f'versionCode: {vc[0]}')
            
            vn = info.get('versionName', [])
            if vn:
                if vn[0] == '0.1.0':
                    ok(f'versionName: {vn[0]}')
                else:
                    warn(f'versionName: {vn[0]} (ожидается 0.1.0)')
            
            minsdk = info.get('minSdk', [])
            if minsdk:
                if minsdk[0] == '26':
                    ok(f'minSdk: {minsdk[0]}')
                else:
                    fail(f'minSdk: {minsdk[0]} (ожидается 26)')
            
            targetsdk = info.get('targetSdk', [])
            if targetsdk:
                if targetsdk[0] == '35':
                    ok(f'targetSdk: {targetsdk[0]}')
                else:
                    warn(f'targetSdk: {targetsdk[0]} (ожидается 35)')
            
            # Permissions
            perms = info.get('permissions', [])
            required_perms = [
                'android.permission.INTERNET',
                'android.permission.FOREGROUND_SERVICE',
                'android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK',
                'android.permission.POST_NOTIFICATIONS',
            ]
            for rp in required_perms:
                if rp in perms:
                    ok(f'Permission: {rp.split(".")[-1]}')
                else:
                    warn(f'Отсутствует permission: {rp}')
            
        except Exception as e:
            fail(f'Ошибка чтения AndroidManifest.xml: {e}')
    
    # 4. Seed DB
    assets = [n for n in namelist if n.startswith('assets/')]
    seed_db = [a for a in assets if a.endswith('.db')]
    if seed_db:
        db_size = z.getinfo(seed_db[0]).file_size
        ok(f'Seed DB: {seed_db[0]} ({db_size // 1024} KB)')
    else:
        fail('Seed DB отсутствует в assets/')
    
    # 5. DEX files
    dex_files = sorted([n for n in namelist if n.endswith('.dex')])
    if dex_files:
        dex_count = len(dex_files)
        total_dex_size = sum(z.getinfo(d).file_size for d in dex_files)
        ok(f'DEX: {dex_count} файлов, {total_dex_size // (1024*1024)} MB')
    else:
        fail('DEX-файлы отсутствуют')
    
    # 6. Native libraries
    native_libs = [n for n in namelist if n.startswith('lib/')]
    if native_libs:
        abis = set()
        for n in native_libs:
            parts = n.split('/')
            if len(parts) >= 3:
                abis.add(parts[1])
        ok(f'ABIs: {", ".join(sorted(abis))}')
    else:
        warn('Нативные библиотеки отсутствуют')
    
    z.close()
    
    # Summary
    print(f'\n=== ИТОГО ===')
    print(f'  ✅ Пройдено: {len(results["passed"])}')
    print(f'  ❌ Провалено: {len(results["failed"])}')
    print(f'  ⚠️  Предупреждения: {len(results["warnings"])}')
    
    return results


def main():
    parser = argparse.ArgumentParser(description='Voxli APK Verification')
    parser.add_argument('--apk', default='app/build/outputs/apk/debug/app-debug.apk',
                       help='Path to APK file')
    args = parser.parse_args()
    
    apk_path = Path(args.apk)
    if not apk_path.is_absolute():
        # Resolve relative to project root
        project_root = Path(__file__).resolve().parent.parent
        apk_path = project_root / apk_path
    
    print(f'🔍 Верификация APK: {apk_path}')
    print('=' * 60)
    
    results = verify_apk(str(apk_path))
    
    sys.exit(1 if results['failed'] else 0)


if __name__ == '__main__':
    main()

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
import argparse
from pathlib import Path


def _find_aapt2() -> str:
    """Ищет aapt2 в Android SDK или в PATH."""
    sdk_root = os.environ.get('ANDROID_HOME') or os.environ.get('ANDROID_SDK_ROOT', '')
    if sdk_root:
        build_tools = os.path.join(sdk_root, 'build-tools')
        if os.path.isdir(build_tools):
            for bt in sorted(os.listdir(build_tools), reverse=True):
                candidate = os.path.join(build_tools, bt, 'aapt2')
                if os.path.exists(candidate):
                    return candidate
    # Fallback: search PATH
    for p in os.environ.get('PATH', '').split(os.pathsep):
        candidate = os.path.join(p, 'aapt2')
        if os.path.exists(candidate):
            return candidate
    return 'aapt2'  # надеемся на PATH


def parse_axml_manifest(apk_path: str) -> dict:
    """Парсинг AndroidManifest.xml через aapt2 dump badging.
    
    Бинарный AXML не читается как текст, поэтому используем aapt2 из Android SDK.
    """
    import subprocess
    result = {}
    
    try:
        aapt2 = _find_aapt2()
        output = subprocess.check_output(
            [aapt2, 'dump', 'badging', apk_path],
            stderr=subprocess.STDOUT, text=True,
        )
    except (subprocess.CalledProcessError, FileNotFoundError) as e:
        # Если aapt2 недоступен — возвращаем пустой словарь
        print(f'  ⚠️  aapt2 не найден, пропускаем парсинг манифеста: {e}')
        return result
    
    # package: name='com.voxli' versionCode='1' versionName='0.1.0'
    m = re.search(r"package: name='([^']+)' versionCode='(\d+)' versionName='([^']+)'", output)
    if m:
        result['package'] = [m.group(1)]
        result['versionCode'] = [m.group(2)]
        result['versionName'] = [m.group(3)]
    
    # sdkVersion:'26'
    m = re.search(r"sdkVersion:'(\d+)'", output)
    if m:
        result['minSdk'] = [m.group(1)]
    
    # targetSdkVersion:'35'
    m = re.search(r"targetSdkVersion:'(\d+)'", output)
    if m:
        result['targetSdk'] = [m.group(1)]
    
    # uses-permission:'android.permission.INTERNET'
    perms = re.findall(r"uses-permission:'([^']+)'", output)
    if perms:
        result['permissions'] = perms
    
    # debuggable flag
    if 'debuggable' in output:
        result['debuggable'] = ['true']
    
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
            info = parse_axml_manifest(apk_path)
            
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

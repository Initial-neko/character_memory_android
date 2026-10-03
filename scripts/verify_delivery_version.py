"""Reject version regressions and record which builds come from merged main."""
import argparse
import json
import os
from pathlib import Path
import re
import subprocess


def verify(code, *, base_code=None, installed_code=None, source_ref=''):
    errors = []
    values = {'version_code': code, 'base_code': base_code, 'installed_code': installed_code}
    for name, value in values.items():
        if value is None and name != 'version_code':
            continue
        if type(value) is not int or value <= 0:
            errors.append(f'{name} must be a positive integer')
    if not errors:
        if base_code is not None and code < base_code:
            errors.append('versionCode regresses from the integration base')
        if installed_code is not None and code <= installed_code:
            errors.append('versionCode must exceed the installed phone version')
    return {**values, 'status': 'FAIL' if errors else 'PASS', 'errors': errors,
            'source_ref': source_ref,
            'main_delivery_eligible': not errors and source_ref == 'refs/heads/main'}


def version(text):
    code = re.findall(r'^\s*versionCode\s*=\s*(\d+)\s*$', text, re.MULTILINE)
    name = re.findall(r'^\s*versionName\s*=\s*"([^"]+)"\s*$', text, re.MULTILINE)
    if len(code) != 1 or len(name) != 1:
        raise ValueError('expected exactly one literal versionCode/versionName')
    return int(code[0]), name[0]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--base-ref')
    parser.add_argument('--installed-code', type=int)
    parser.add_argument('--output', required=True)
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    try:
        code, name = version((root / 'app/build.gradle.kts').read_text(encoding='utf-8'))
        base_code = None
        if args.base_ref and set(args.base_ref) != {'0'}:
            base = subprocess.check_output(['git', '-C', str(root), 'show', f'{args.base_ref}:app/build.gradle.kts'], text=True)
            base_code, _ = version(base)
        result = verify(code, base_code=base_code, installed_code=args.installed_code,
                        source_ref=os.getenv('GITHUB_REF', 'LOCAL_UNVERIFIED'))
        result['version_name'] = name
        result['git_sha'] = subprocess.check_output(['git', '-C', str(root), 'rev-parse', 'HEAD'], text=True).strip()
        result['ci_run_id'] = os.getenv('GITHUB_RUN_ID')
    except (ValueError, OSError, subprocess.SubprocessError) as error:
        result = {'status': 'FAIL', 'errors': [str(error)], 'main_delivery_eligible': False}
    output = Path(args.output)
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    print(json.dumps(result, ensure_ascii=False, indent=2))
    return 0 if result['status'] == 'PASS' else 1


if __name__ == '__main__':
    raise SystemExit(main())

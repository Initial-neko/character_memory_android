"""Validate and optionally zip local model3 resources; no network, SDK or GPU."""
import argparse
import hashlib
import json
from pathlib import Path, PurePosixPath
import zipfile


def validate(entry):
    entry = Path(entry).resolve()
    root = entry.parent
    errors, files = [], []
    report = {'entry': entry.name, 'errors': errors, 'files': files,
              'scope': 'Local resource integrity only', 'runtime_verified': False}
    try:
        model = json.loads(entry.read_text(encoding='utf-8-sig'))
        refs = model['FileReferences']
        if model.get('Version') != 3:
            errors.append('Expected model3 Version 3')
        if not isinstance(refs['Moc'], str) or not isinstance(refs['Textures'], list) or not refs['Textures']:
            raise ValueError('Moc string and nonempty Textures array required')
        paths = [refs['Moc'], *refs['Textures']]
        paths += [refs[k] for k in ('Physics','Pose','UserData','DisplayInfo') if k in refs]
        paths += [item['File'] for item in refs.get('Expressions', [])]
        paths += [item[k] for group in refs.get('Motions', {}).values() for item in group
                  for k in ('File','Sound') if k in item]
        for relative in paths:
            if not isinstance(relative,str) or not relative or ':' in relative:
                errors.append('Invalid local reference: ' + repr(relative)); continue
            relative_path = PurePosixPath(relative.replace('\\','/'))
            if relative_path.is_absolute() or '..' in relative_path.parts:
                errors.append('Unsafe reference: ' + relative); continue
            file = (root / str(relative_path)).resolve()
            if not file.is_relative_to(root):
                errors.append('Reference resolves outside model root: ' + relative); continue
            if not file.is_file() or file.stat().st_size == 0:
                errors.append('Missing/empty resource: ' + relative); continue
            data = file.read_bytes()
            if relative == refs['Moc']:
                report['moc_header_hex'] = data[:8].hex()
                if len(data) < 8 or data[:4] != b'MOC3':
                    errors.append('Invalid MOC3 header')
            files.append({'path':str(relative_path),'bytes':len(data),'sha256':hashlib.sha256(data).hexdigest()})
    except (OSError,ValueError,KeyError,TypeError) as exc:
        errors.append(str(exc))
    report['pass'] = not errors
    return report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('entry',type=Path)
    parser.add_argument('--report',type=Path,required=True)
    parser.add_argument('--pack',type=Path)
    args = parser.parse_args()
    result = validate(args.entry)
    if args.pack and result['pass']:
        root = args.entry.resolve().parent
        assets = [args.entry.resolve()] + [(root / f['path']).resolve() for f in result['files']]
        if args.pack.resolve() in assets:
            result['errors'].append('ZIP output must not overwrite a model resource')
            result['pass'] = False
        else:
            args.pack.parent.mkdir(parents=True,exist_ok=True)
            with zipfile.ZipFile(args.pack,'w',zipfile.ZIP_DEFLATED) as archive:
                for asset in dict.fromkeys(assets):
                    archive.write(asset,str(asset.relative_to(root)).replace('\\','/'))
            result['zip_files'] = len(set(assets))
    protected = [args.entry.resolve()] + [(args.entry.resolve().parent / f['path']).resolve() for f in result['files']]
    if args.report.resolve() in protected or (args.pack and args.report.resolve()==args.pack.resolve()):
        raise ValueError('Report output must not overwrite model/ZIP resources')
    args.report.parent.mkdir(parents=True,exist_ok=True)
    args.report.write_text(json.dumps(result,ensure_ascii=False,indent=2),encoding='utf-8')
    print(json.dumps({'pass':result['pass'],'resources':len(result['files']),'errors':result['errors']}))
    return 0 if result['pass'] else 1


if __name__ == '__main__':
    raise SystemExit(main())

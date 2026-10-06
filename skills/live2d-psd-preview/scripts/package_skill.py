"""Package an allowlisted skill; reject environment files and credential literals."""
import argparse
import hashlib
import json
import re
import zipfile
from pathlib import Path


def files_for_package(root):
    allowed = []
    for path in sorted(root.rglob('*')):
        if not path.is_file():
            continue
        relative = path.relative_to(root)
        if '__pycache__' in relative.parts or path.suffix == '.pyc':
            continue
        if path.name.startswith('.env') and relative.as_posix() != '.env.example':
            raise ValueError('Real environment file cannot be packaged')
        if relative.as_posix() in {'SKILL.md', '.env.example', '.gitignore'}:
            allowed.append(path)
        elif relative.parts[0] in {'scripts', 'references', 'agents'} and path.suffix in {
                '.py', '.ps1', '.kt', '.gradle', '.js', '.cjs', '.md', '.yaml'}:
            allowed.append(path)
        else:
            raise ValueError('Unexpected file in skill: ' + relative.as_posix())
    return allowed


def credential_findings(root, files):
    patterns = [r'\b(?:sk-|hf_|ghp_|github_pat_|ms-)[A-Za-z0-9_-]{20,}\b',
                r'(?i)Bearer\s+[A-Za-z0-9_.-]{20,}',
                r'(?i)(?:MSIMG_API_KEY|MODELSCOPE_API_TOKEN)\s*=\s*[\x22\x27]?([^\s\x22\x27\\]{8,})']
    findings = []
    for path in files:
        for number, line in enumerate(path.read_text(encoding='utf-8').splitlines(), 1):
            for pattern in patterns:
                match = re.search(pattern, line)
                if match and not (match.lastindex and match.group(1).startswith('test-only-')):
                    findings.append({'file': path.relative_to(root).as_posix(), 'line': number})
    return findings


def package(root, output, report_file):
    root, output = Path(root), Path(output)
    if output.resolve().is_relative_to(root.resolve()):
        raise ValueError('Archive must be outside the skill directory')
    files = files_for_package(root)
    findings = credential_findings(root, files)
    report = {'status': 'FAIL' if findings else 'PASS', 'credential_findings': findings,
              'files': [p.relative_to(root).as_posix() for p in files]}
    if not findings:
        output.parent.mkdir(parents=True, exist_ok=True)
        with zipfile.ZipFile(output, 'w', compression=zipfile.ZIP_DEFLATED) as archive:
            for path in files:
                archive.write(path, root.name + '/' + path.relative_to(root).as_posix())
        report['archive_sha256'] = hashlib.sha256(output.read_bytes()).hexdigest()
    Path(report_file).write_text(json.dumps(report, indent=2), encoding='utf-8')
    if findings:
        raise ValueError('Credential candidates detected; see locations in report')
    return report


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', required=True)
    parser.add_argument('--report', required=True)
    args = parser.parse_args()
    package(Path(__file__).resolve().parents[1], args.output, args.report)
    print('PASS: allowlisted archive created; credential scan clean')

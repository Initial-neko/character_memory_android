import tempfile
import unittest
import zipfile
from pathlib import Path
from package_skill import package, files_for_package, credential_findings


class PackageTests(unittest.TestCase):
    def test_environment_and_binary_files_are_rejected(self):
        for name in ['.env', '.env.local', 'model.moc3', 'config.yaml', 'archive.zip']:
            with self.subTest(name=name), tempfile.TemporaryDirectory() as folder:
                root = Path(folder)
                (root / name).write_text('test-only')
                with self.assertRaises(ValueError):
                    files_for_package(root)

    def test_credential_detection_reports_locations_without_values(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            path = root / 'SKILL.md'
            fake = 'ghp_' + 'x' * 30
            path.write_text(fake)
            findings = credential_findings(root, [path])
            self.assertEqual(findings, [{'file': 'SKILL.md', 'line': 1}])
            self.assertNotIn(fake, str(findings))

    def test_empty_template_and_source_are_the_archive_payload(self):
        with tempfile.TemporaryDirectory() as folder:
            base = Path(folder)
            root = base / 'example-skill'
            root.mkdir()
            (root / 'SKILL.md').write_text('test-only instructions')
            (root / '.env.example').write_text('MSIMG_API_KEY=\n')
            report = package(root, base / 'skill.zip', base / 'report.json')
            self.assertEqual(report['status'], 'PASS')
            with zipfile.ZipFile(base / 'skill.zip') as archive:
                self.assertEqual(set(archive.namelist()),
                    {'example-skill/SKILL.md', 'example-skill/.env.example'})


if __name__ == '__main__':
    unittest.main()

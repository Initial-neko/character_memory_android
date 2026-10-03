import importlib.util
from pathlib import Path
import unittest


class DeliveryVersionTests(unittest.TestCase):
    def check(self, code, base=None, installed=None, branch='refs/heads/main'):
        path = Path(__file__).with_name('verify_delivery_version.py')
        self.assertTrue(path.is_file(), 'delivery version guard missing')
        spec = importlib.util.spec_from_file_location('delivery_version', path)
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        return module.verify(code, base_code=base, installed_code=installed, source_ref=branch)

    def test_upgrade_from_phone_version_four(self):
        self.assertEqual(self.check(5, 3, 4)['status'], 'PASS')

    def test_downgrade_from_main_is_rejected(self):
        self.assertEqual(self.check(3, 4)['status'], 'FAIL')

    def test_same_or_older_than_phone_is_rejected(self):
        for version in (3, 4):
            self.assertEqual(self.check(version, 3, 4)['status'], 'FAIL')

    def test_non_version_change_may_build_without_claiming_phone_upgrade(self):
        self.assertEqual(self.check(5, 5)['status'], 'PASS')

    def test_pr_build_is_never_a_main_delivery(self):
        result = self.check(5, 3, branch='refs/pull/22/merge')
        self.assertEqual(result['status'], 'PASS')
        self.assertFalse(result['main_delivery_eligible'])

    def test_main_build_is_eligible(self):
        self.assertTrue(self.check(5, 3)['main_delivery_eligible'])

    def test_invalid_version_code_is_rejected(self):
        for value in (0, -1, '5', True):
            self.assertEqual(self.check(value)['status'], 'FAIL')


if __name__ == '__main__':
    unittest.main()

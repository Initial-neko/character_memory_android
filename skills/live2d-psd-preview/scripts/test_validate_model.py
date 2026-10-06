import json
import tempfile
import unittest
from pathlib import Path
from validate_model import validate


class ModelIntegrityTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = Path(self.tmp.name)
        self.entry = self.root/'model.model3.json'
        (self.root/'model.moc3').write_bytes(b'MOC3\x05\x00\x00\x00')
        (self.root/'texture.png').write_bytes(b'nonempty')
        self.model = {'Version':3,'FileReferences':{'Moc':'model.moc3','Textures':['texture.png']}}

    def tearDown(self):
        self.tmp.cleanup()

    def check(self):
        self.entry.write_text(json.dumps(self.model),encoding='utf-8')
        return validate(self.entry)

    def test_valid_resources_without_runtime_claim(self):
        report=self.check()
        self.assertTrue(report['pass'])
        self.assertFalse(report['runtime_verified'])

    def test_unsafe_and_remote_paths(self):
        for value in ['../secret','..\\secret','/secret','C:\\secret','https://host/model','//host/share']:
            with self.subTest(path=value):
                self.model['FileReferences']['Textures']=[value]
                self.assertFalse(self.check()['pass'])

    def test_missing_and_empty_resources(self):
        self.model['FileReferences']['Textures']=['absent.png']
        self.assertFalse(self.check()['pass'])
        (self.root/'empty.png').write_bytes(b'')
        self.model['FileReferences']['Textures']=['empty.png']
        self.assertFalse(self.check()['pass'])

    def test_invalid_moc(self):
        (self.root/'model.moc3').write_bytes(b'not-a-moc')
        self.assertFalse(self.check()['pass'])

    def test_nested_motion_and_sound_are_required(self):
        self.model['FileReferences']['Motions']={'Idle':[{'File':'idle.motion3.json','Sound':'voice.wav'}]}
        self.assertFalse(self.check()['pass'])

    def test_invalid_document_is_reported(self):
        self.entry.write_text('{',encoding='utf-8')
        self.assertFalse(validate(self.entry)['pass'])


if __name__ == '__main__':
    unittest.main()

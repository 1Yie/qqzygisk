"""Run locally with python3 -B -m unittest discover -s tests -p 'test_*.py'."""
import os
from pathlib import Path
import stat
import subprocess
import tempfile
import unittest


SCRIPT = Path(__file__).resolve().parents[1] / 'app/src/main/resources/repair-media-permissions.sh'


class MediaPermissionRepairTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.base = Path(self.tmp.name)
        self.users = self.base / 'user'
        self.media = self.base / 'media'
        (self.users / '0/com.tencent.mm').mkdir(parents=True)
        self.target = self.media / '0/Android/media/com.tencent.mm/.qqzygisk'
        self.log = self.base / 'commands.log'
        self.bin = self.base / 'bin'
        self.bin.mkdir()
        self.command('chown', 'printf "chown %s\\n" "$*" >> "$TEST_REPAIR_LOG"\n')
        self.command('restorecon', 'printf "restorecon %s\\n" "$*" >> "$TEST_REPAIR_LOG"\n')
        self.env = dict(os.environ, QQZ_USER_ROOT=str(self.users), QQZ_MEDIA_ROOT=str(self.media),
                        TEST_REPAIR_LOG=str(self.log), PATH=str(self.bin) + ':' + os.environ['PATH'])

    def command(self, name, body):
        executable = self.bin / name
        executable.write_text('#!/bin/sh\n' + body)
        executable.chmod(0o755)

    def run_repair(self):
        return subprocess.run(['sh', str(SCRIPT)], env=self.env, capture_output=True, text=True)

    def test_repairs_legacy_modes_and_media_group_without_deleting_images(self):
        folder = self.target / '中文分类'
        folder.mkdir(parents=True)
        image = folder / 'image.gif'
        image.write_bytes(b'keep-image')
        self.target.chmod(0o2700)
        folder.chmod(0o700)
        image.chmod(0o600)
        sibling = self.target.parent / 'other-wechat-data'
        sibling.mkdir()
        sibling.chmod(0o700)
        result = self.run_repair()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(stat.S_IMODE(self.target.stat().st_mode), 0o2770)
        self.assertEqual(stat.S_IMODE(folder.stat().st_mode), 0o2770)
        self.assertEqual(stat.S_IMODE(image.stat().st_mode), 0o660)
        self.assertEqual(stat.S_IMODE(sibling.stat().st_mode), 0o700)
        self.assertEqual(image.read_bytes(), b'keep-image')
        log = self.log.read_text()
        self.assertIn(f'chown {os.getuid()}:1023 ', log)
        self.assertIn(f'restorecon -RF {self.target}', log)
        self.assertNotIn(str(sibling), log)

    def test_missing_directory_is_left_for_the_app_to_create(self):
        result = self.run_repair()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertFalse(self.target.exists())
        self.assertFalse(self.log.exists())

    def test_symlink_does_not_repair_unrelated_directory(self):
        outside = self.base / 'outside'
        outside.mkdir()
        outside.chmod(0o700)
        self.target.parent.mkdir(parents=True)
        self.target.symlink_to(outside, target_is_directory=True)
        result = self.run_repair()
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(stat.S_IMODE(outside.stat().st_mode), 0o700)
        self.assertFalse(self.log.exists())

    def test_existing_file_is_reported_without_replacing_it(self):
        self.target.parent.mkdir(parents=True)
        self.target.write_text('keep')
        result = self.run_repair()
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(self.target.read_text(), 'keep')

    def test_other_android_users_are_repaired_independently(self):
        self.target.mkdir(parents=True)
        (self.users / '10/com.tencent.mm').mkdir(parents=True)
        other = self.media / '10/Android/media/com.tencent.mm/.qqzygisk'
        other.mkdir(parents=True)
        other.chmod(0o700)
        result = self.run_repair()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(stat.S_IMODE(other.stat().st_mode), 0o2770)
        self.assertIn(str(other), self.log.read_text())

    def test_ownership_failure_is_not_reported_as_success(self):
        self.target.mkdir(parents=True)
        self.command('chown', 'exit 1\n')
        result = self.run_repair()
        self.assertNotEqual(result.returncode, 0)
        self.assertNotIn('- Repaired WeChat', result.stdout)


if __name__ == '__main__':
    unittest.main()

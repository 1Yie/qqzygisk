"""Guard the Douyin send path against the previously used favorite-registration services."""
from pathlib import Path
import re
import unittest

ROOT = Path(__file__).resolve().parents[1]
SENDER = ROOT / 'app/src/main/java/com/qm/qqzygisk/hook/app/chat/DouyinMediaSender.kt'


def collection_dependencies(text):
    return re.findall(r'"(?:X\.(?:13oo|13x0|13wx)|com\.ss\.android\.ugc\.aweme\.emoji\.service\.EmojiServiceProxy|LJIJJ)"', text)


class DouyinNoFavoriteTest(unittest.TestCase):
    def test_chat_sender_uses_native_photo_task_without_custom_upload_or_emoji_requests(self):
        text = SENDER.read_text()
        self.assertEqual(collection_dependencies(text), [])
        for dependency in ['X.1483', 'buildUrlListForOid', 'ResourceUrlApi', 'EmojiContent', 'DouyinEmojiMessage']:
            self.assertNotIn(dependency, text)
        self.assertIn('com.ss.android.ugc.aweme.im.service.model.MediaModel', text)
        self.assertIn('X.0sFp', text)

    def test_guard_rejects_the_previous_send_then_favorite_pipeline(self):
        old = 'cls("X.13x0"); cls("X.13wx"); cls("com.ss.android.ugc.aweme.emoji.service.EmojiServiceProxy").getMethod("LJIJJ")'
        self.assertEqual(len(collection_dependencies(old)), 4)


if __name__ == '__main__':
    unittest.main()

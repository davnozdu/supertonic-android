"""Run: PYTHONPYCACHEPREFIX=/tmp/bsb-pycache python3 -m unittest discover -s tools/sound_bank -p test_prepare_bigsoundbank.py"""
import tempfile
import unittest
import wave
from pathlib import Path

import numpy as np
import prepare_bigsoundbank as bank


class PreparationTest(unittest.TestCase):
    def item(self, **changes):
        x = dict(title='Door opening', category='Doors / Wood',
                 source_type='Alone sound', author_display='Recorder', license='CC0-1.0')
        x.update(changes)
        return x

    def test_scene_keeps_scene_type(self):
        self.assertEqual(bank.classify(self.item(title='Forest', category='Ambience / Forest', source_type='Soundscape'))[0], 'scenes')

    def test_isolated_door_is_event(self):
        self.assertEqual(bank.classify(self.item())[0], 'events')

    def test_uncertain_and_restricted_go_to_review(self):
        for change in [dict(author_display='Anonyme'), dict(license='UNVERIFIED'),
                       dict(source_type='Music'), dict(title='iPhone ringtone'),
                       dict(source_type='Soundscape', category='Unknown')]:
            with self.subTest(change=change):
                self.assertEqual(bank.classify(self.item(**change))[0], 'review')

    def test_descriptive_names_are_stable_and_collision_safe(self):
        x = self.item(id='0123')
        self.assertEqual(bank.filename(x), 'bsb-0123-door-opening.m4a')
        self.assertNotEqual(bank.filename(x), bank.filename(dict(x, id='0124')))

    def test_window_captures_two_event_onsets(self):
        with tempfile.TemporaryDirectory() as d:
            file = Path(d) / 'source.wav'
            sr = 8000
            audio = np.zeros(24 * sr, dtype=np.float32)
            t = np.arange(sr) / sr
            audio[11*sr:12*sr] = .3 * np.sin(2 * np.pi * 650 * t)
            audio[14*sr:15*sr] = .3 * np.sin(2 * np.pi * 1700 * t)
            with wave.open(str(file), 'wb') as out:
                out.setparams((1, 2, sr, 0, 'NONE', 'not compressed'))
                out.writeframes((audio * 32767).astype('<i2').tobytes())
            start, duration, _ = bank.spectral_window(file, 24)
            self.assertEqual(duration, 8)
            self.assertLessEqual(start, 11)
            self.assertGreaterEqual(start + duration, 15)

    def test_short_events_not_padded(self):
        self.assertEqual(bank.spectral_window(Path('unused'), .53)[:2], (0, .53))

    def test_near_eight_second_recording(self):
        with tempfile.TemporaryDirectory() as d:
            file = Path(d) / 'source.wav'
            audio = (.2 * np.sin(np.arange(64040) * .1) * 32767).astype('<i2')
            with wave.open(str(file), 'wb') as out:
                out.setparams((1, 2, 8000, 0, 'NONE', 'not compressed'))
                out.writeframes(audio.tobytes())
            start, duration, _ = bank.spectral_window(file, 8.005)
            self.assertGreaterEqual(start, 0)
            self.assertEqual(duration, 8)
            self.assertLessEqual(start + duration, 8.005)


if __name__ == '__main__':
    unittest.main()

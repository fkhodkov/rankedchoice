"""The San Francisco checker's choice of Internet Archive capture: remembered for a month, then asked for again, so a
later capture (a certified report, say) is found; the remembered one used when the index can't be reached.

python3 -m unittest discover -s tools/reference/tests
"""
import datetime
import importlib.util
import os
import tempfile
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
spec = importlib.util.spec_from_file_location("sanfrancisco_check",
                                              os.path.join(HERE, "..", "sanfrancisco", "check.py"))
sf = importlib.util.module_from_spec(spec)
spec.loader.exec_module(sf)

DAY = datetime.date(2026, 10, 7)


class LatestCapture(unittest.TestCase):

    def setUp(self):
        self.data = tempfile.TemporaryDirectory()
        self.asked = 0
        self.captures = [["timestamp", "original"], ["20241126203918", "https://sfelections.org/results/x.html"]]
        self.real_cdx = sf.cdx
        sf.cdx = self.cdx

    def tearDown(self):
        sf.cdx = self.real_cdx
        self.data.cleanup()

    def cdx(self, query):
        self.asked += 1
        if self.captures is None:
            raise OSError("the index is offline")
        return self.captures

    def latest(self, day=DAY):
        return sf.latest_capture(self.data.name, "2024-sheriff", "x.html", today=day)[1]

    def test_asked_once_then_remembered_for_a_month(self):
        self.assertEqual(self.latest(), "20241126203918")
        self.assertEqual(self.latest(DAY + datetime.timedelta(days=sf.RECHECK_DAYS - 1)), "20241126203918")
        self.assertEqual(self.asked, 2)     # both hosts, once

    def test_a_later_capture_found_after_a_month(self):
        self.latest()
        self.captures.append(["20241210120000", "https://www.sfelections.org/results/x.html"])
        self.assertEqual(self.latest(DAY + datetime.timedelta(days=sf.RECHECK_DAYS)), "20241210120000")

    def test_a_capture_remembered_without_a_day_is_asked_for_again(self):
        with open(os.path.join(self.data.name, "2024-sheriff.capture"), "w") as out:
            out.write("https://web.archive.org/web/20241101000000id_/x 20241101000000\n")
        self.assertEqual(self.latest(), "20241126203918")

    def test_the_remembered_capture_when_the_index_is_offline(self):
        self.latest()
        self.captures = None
        self.assertEqual(self.latest(DAY + datetime.timedelta(days=90)), "20241126203918")

    def test_nothing_remembered_and_the_index_offline_fails(self):
        self.captures = None
        with self.assertRaises(OSError):
            self.latest()


if __name__ == "__main__":
    unittest.main()

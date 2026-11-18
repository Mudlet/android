#!/usr/bin/env python3
"""Tests android_instrumented.py's watchdog on the host, with no device: when a run of the instrumented tests counts as
hung. scripts/lint.sh instrumented-watchdog runs it."""
import os
import sys
import time
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from android_instrumented import Watchdog, watched  # noqa: E402

START = "INSTRUMENTATION_STATUS_CODE: 1\n"
END = "INSTRUMENTATION_STATUS_CODE: 0\n"
FAILED = "INSTRUMENTATION_STATUS_CODE: -2\n"


class WatchdogTest(unittest.TestCase):
    def test_a_long_suite_that_keeps_ending_tests_is_not_hung(self):
        dog = Watchdog(stall=420, limit=2400, now=0)
        for t in range(10, 1500, 10):
            dog.line(END, t)
            self.assertIsNone(dog.hung(t))

    def test_no_test_ending_for_the_stall_is_a_hang(self):
        dog = Watchdog(stall=420, limit=2400, now=0)
        dog.line(END, 100)
        self.assertIsNone(dog.hung(519))
        self.assertEqual(dog.hung(520), "no test ended for 420 s")

    def test_a_test_starting_is_no_progress(self):
        dog = Watchdog(stall=420, limit=2400, now=0)
        dog.line(START, 300)
        dog.line("INSTRUMENTATION_STATUS: test=x\n", 400)
        self.assertEqual(dog.hung(420), "no test ended for 420 s")

    def test_a_failed_test_is_progress(self):
        dog = Watchdog(stall=420, limit=2400, now=0)
        dog.line(FAILED, 400)
        self.assertIsNone(dog.hung(800))

    def test_the_overall_limit_holds_however_the_tests_go(self):
        dog = Watchdog(stall=420, limit=2400, now=0)
        dog.line(END, 2399)
        self.assertEqual(dog.hung(2400), "still running after 2400 s")


def fake(script):
    return [sys.executable, "-c", f"import sys, time\n{script}"]


class WatchedTest(unittest.TestCase):
    def test_a_run_that_finishes_gives_all_its_output(self):
        output, why = watched(fake("for _ in range(5): print('INSTRUMENTATION_STATUS_CODE: 0', flush=True); "
                                   "time.sleep(0.3)\nprint('INSTRUMENTATION_CODE: -1')"), stall=2, limit=30)
        self.assertIsNone(why)
        self.assertEqual(output.count("INSTRUMENTATION_STATUS_CODE: 0"), 5)
        self.assertIn("INSTRUMENTATION_CODE: -1", output)

    def test_a_run_that_stops_ending_tests_is_stopped_with_what_it_printed(self):
        started = time.monotonic()
        output, why = watched(fake("print('INSTRUMENTATION_STATUS_CODE: 0', flush=True)\n"
                                   "print('INSTRUMENTATION_STATUS_CODE: 1', flush=True)\ntime.sleep(60)"),
                              stall=2, limit=30)
        self.assertEqual(why, "no test ended for 2 s")
        self.assertIn("INSTRUMENTATION_STATUS_CODE: 1", output)
        self.assertLess(time.monotonic() - started, 10)

    def test_a_run_past_its_limit_is_stopped_though_tests_end(self):
        output, why = watched(fake("while True: print('INSTRUMENTATION_STATUS_CODE: 0', flush=True); "
                                   "time.sleep(0.2)"), stall=2, limit=3)
        self.assertEqual(why, "still running after 3 s")


if __name__ == "__main__":
    unittest.main()

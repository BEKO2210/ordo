# Copyright (c) 2026 Ordo contributors; SPDX-License-Identifier: GPL-3.0-or-later
# Tests of the farm's own checks, against the real scripts (run: python3 -m unittest e2e/test_scripts.py, or
# e2e/selftest.sh): the a11y scanner on dumps with planted defects, and logcheck.py on logcat with planted violations.
import os
import subprocess
import sys
import tempfile
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import importlib

a11y = importlib.import_module("a11y-scan")
import logcheck
import ui_dump

PKG = "com.beko2210.ordo"


def dump(*nodes):
    return '<?xml version="1.0"?><hierarchy>' + "".join(nodes) + "</hierarchy>"


def node(bounds, text="", desc="", clickable="true", enabled="true", pkg=PKG, children=""):
    return (f'<node class="android.view.View" package="{pkg}" text="{text}" content-desc="{desc}" '
            f'clickable="{clickable}" enabled="{enabled}" bounds="{bounds}">{children}</node>')


def scan(xml, density=160):
    with tempfile.NamedTemporaryFile("w", suffix=".xml", delete=False) as f:
        f.write(xml)
    try:
        return a11y.findings(f.name, density)
    finally:
        os.unlink(f.name)


class A11yScan(unittest.TestCase):
    def test_unlabelled_button_is_found(self):
        self.assertEqual(1, sum(l.startswith("unlabelled") for l in scan(dump(node("[0,0][100,100]")))))

    def test_small_target_is_found(self):
        self.assertTrue(any(l.startswith("small") for l in scan(dump(node("[0,0][40,40]", text="OK")))))

    def test_short_wide_target_is_found(self):
        self.assertTrue(any(l.startswith("small") for l in scan(dump(node("[0,0][300,40]", text="OK")))))

    def test_density_scales_the_target(self):
        self.assertEqual([], scan(dump(node("[0,0][96,96]", text="OK")), density=320))
        self.assertTrue(scan(dump(node("[0,0][70,70]", text="OK")), density=320))

    def test_clean_screen_is_quiet(self):
        child = node("[0,0][50,50]", text="Name", clickable="false")
        self.assertEqual([], scan(dump(node("[0,0][200,60]", children=child),
                                       node("[0,0][10,10]", enabled="false"),
                                       node("[0,0][10,10]", pkg="other.app"))))

    def test_script_fails_loudly_on_a_broken_dump(self):
        r = subprocess.run([sys.executable, os.path.join(HERE, "a11y-scan.py"), "/nonexistent", "160"],
                           capture_output=True)
        self.assertNotEqual(0, r.returncode)


STRICT = ("11-23 12:00:00.000  100  100 W StrictMode: StrictMode policy violation; ~duration=150 ms: "
          "android.os.strictmode.DiskReadViolation\n"
          "11-23 12:00:00.000  100  100 W StrictMode: \tat me.zhanghai.android.files.Foo.bar(Foo.kt:1)\n")


class LogCheck(unittest.TestCase):
    def test_violation_in_our_code_is_found(self):
        self.assertEqual(1, len(logcheck.findings(STRICT)))

    def test_violation_in_foreign_code_is_ignored(self):
        self.assertEqual([], logcheck.findings(STRICT.replace("me.zhanghai.android.files", "com.other")))

    def test_two_violations_count_twice(self):
        self.assertEqual(2, len(logcheck.findings(STRICT + STRICT)))

    def test_unfinished_heap_analysis_is_found(self):
        dump = "11-23 12:00:00.000 100 100 D LeakCanary: Dumping the heap because 1 retained object\n"
        self.assertEqual(1, len(logcheck.findings(dump)))
        self.assertTrue(logcheck.pending(dump))
        done = dump + "11-23 12:00:09.000 100 100 D LeakCanary: 0 APPLICATION LEAKS\n"
        self.assertEqual([], logcheck.findings(done))
        self.assertFalse(logcheck.pending(done))

    def test_violation_at_every_priority_is_found(self):
        for prio in "DIWE":
            self.assertEqual(1, len(logcheck.findings(STRICT.replace(" W StrictMode", f" {prio} StrictMode"))), prio)

    def test_filter_passes_debug_strictmode_and_run_fails(self):
        # StrictMode's penaltyLog writes at D; a fake logcat honours only the tag filters it is given.
        lib = os.path.join(HERE, "lib.sh")
        line = STRICT.splitlines()[0].replace(" W StrictMode", " D StrictMode")
        stack = STRICT.splitlines()[1].replace(" W StrictMode", " D StrictMode")
        script = (f'HERE={HERE}; a() {{ case "$*" in *StrictMode:D*) printf "%s\\n%s\\n" "$LINE" "$STACK";; esac; }}; '
                  f'eval "$(sed -n \'/^debug_findings()/,/^}}/p\' {lib})"; debug_findings')
        r = subprocess.run(["bash", "-c", script], capture_output=True, text=True,
                           env={**os.environ, "LINE": line, "STACK": stack})
        self.assertIn("DiskReadViolation", r.stdout)

    def test_unreadable_logcat_fails_the_run(self):
        lib = os.path.join(HERE, "lib.sh")
        script = (f'HERE={HERE}; a() {{ echo "adb: device offline"; return 1; }}; '
                  f'eval "$(sed -n \'/^debug_findings()/,/^}}/p\' {lib})"; debug_findings')
        r = subprocess.run(["bash", "-c", script], capture_output=True, text=True)
        self.assertEqual(1, r.returncode)
        self.assertIn("could not be read", r.stdout)

    def test_leak_is_found_zero_leaks_are_not(self):
        leak = "11-23 12:00:00.000 100 100 D LeakCanary: 1 APPLICATION LEAKS\n"
        self.assertEqual(1, len(logcheck.findings(leak)))
        self.assertEqual([], logcheck.findings(leak.replace("1 APPLICATION", "0 APPLICATION")))

    def test_empty_log_is_quiet(self):
        self.assertEqual([], logcheck.findings(""))

    def test_short_disk_read_or_write_is_info_only(self):
        for kind in ("DiskRead", "DiskWrite"):
            short = STRICT.replace("~duration=150 ms", "~duration=99 ms").replace("DiskRead", kind)
            self.assertEqual([], logcheck.findings(short), kind)
            self.assertEqual(1, sum(f.startswith("INFO ") for f in logcheck.report(short)), kind)

    def test_disk_read_from_100_ms_fails(self):
        self.assertEqual(1, len(logcheck.findings(STRICT.replace("~duration=150 ms", "~duration=100 ms"))))

    def test_other_violation_fails_however_short(self):
        other = STRICT.replace("~duration=150 ms", "~duration=1 ms").replace("DiskRead", "UnbufferedIo")
        self.assertEqual(1, len(logcheck.findings(other)))
        self.assertEqual(1, len(logcheck.findings(other.replace("; ~duration=1 ms:", ":"))))

    def test_finding_names_the_first_ordo_frame(self):
        self.assertIn("Foo.bar(Foo.kt:1)", logcheck.findings(STRICT)[0])


class UiDump(unittest.TestCase):
    def test_scrollable_bounds_uses_nested_container_around_anchor(self):
        xml = ('<hierarchy><node scrollable="true" bounds="[0,0][1000,700]">'
               '<node scrollable="true" bounds="[300,100][900,650]">'
               '<node resource-id="com.beko2210.ordo:id/choice_root" text="One" bounds="[320,120][880,180]"/>'
               '</node></node></hierarchy>')
        self.assertEqual((300, 100, 900, 650), ui_dump.scrollable_bounds(xml, "choice_root"))

    def test_scrollable_bounds_ignores_horizontal_strip(self):
        xml = ('<hierarchy><node scrollable="true" bounds="[0,0][1000,60]"/>'
               '<node scrollable="true" bounds="[0,80][1000,700]"/></hierarchy>')
        self.assertEqual((0, 80, 1000, 700), ui_dump.scrollable_bounds(xml))

    def test_scrollable_bounds_skips_horizontal_scroll_view(self):
        xml = ('<hierarchy><node class="androidx.core.widget.NestedScrollView" scrollable="true" '
               'bounds="[0,0][1000,2000]"><node class="android.widget.HorizontalScrollView" scrollable="true" '
               'bounds="[0,1500][1000,1626]"/></node></hierarchy>')
        self.assertEqual((0, 0, 1000, 2000), ui_dump.scrollable_bounds(xml))

    def test_scrollable_bounds_requires_anchor_in_container(self):
        xml = ('<hierarchy><node scrollable="true" bounds="[0,0][1000,700]">'
               '<node text="Other" bounds="[0,0][20,20]"/></node></hierarchy>')
        self.assertIsNone(ui_dump.scrollable_bounds(xml, "choice_root"))


class CrashFilter(unittest.TestCase):
    def test_only_ordo_crash_is_reported(self):
        lib = os.path.join(HERE, "lib.sh")
        foreign = "E AndroidRuntime: FATAL EXCEPTION: main\nE AndroidRuntime: Process: com.other, PID: 1\nforeign\n"
        ordo = f"E AndroidRuntime: FATAL EXCEPTION: main\nE AndroidRuntime: Process: {PKG}, PID: 2\nordo stack\n"
        script = (f'PKG={PKG}; a() {{ printf "%s" "$LOG"; }}; '
                  f'eval "$(sed -n \'/^crash_report()/,/^}}/p\' {lib})"; crash_report')
        result = subprocess.run(["bash", "-c", script], capture_output=True, text=True,
                                env={**os.environ, "LOG": foreign + ordo})
        self.assertEqual(0, result.returncode)
        self.assertNotIn("foreign", result.stdout)
        self.assertIn("ordo stack", result.stdout)


if __name__ == "__main__":
    unittest.main()

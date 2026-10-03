"""Tests for smoke_test.py against a fake server; CI runs them with the release script's tests."""

import contextlib
import io
import sys
import tempfile
import textwrap
import unittest
from pathlib import Path

import smoke_test

# Prints a server log the way NeoForge does and answers console commands. The first argument picks how the
# server misbehaves, if at all.
FAKE_SERVER = textwrap.dedent("""\
    import sys
    import time

    mode = sys.argv[1]

    def log(message, level="INFO", thread="Server thread", logger="minecraft/MinecraftServer"):
        print(f"[12:00:00] [{thread}/{level}] [{logger}]: {message}", flush=True)

    log("Anchor 0.1.0-alpha.1 loaded", thread="modloading-worker-0", logger="io.gi.os.an.ne.AnchorMod/")
    if mode == "silent":
        time.sleep(60)
    if mode == "crash":
        log("Failed to start the minecraft server", level="ERROR")
        sys.exit(1)
    if mode == "error":
        log("Couldn't parse data map anchor:materials", level="ERROR", logger="ne.ne.ne.re.da.DataMapLoader/")
    if mode == "trace":
        log("Something went wrong", level="WARN")
        print("java.lang.NoClassDefFoundError: io/github/osir933/anchor/core/Engine", flush=True)
    log('Done (1.234s)! For help, type "help"', logger="minecraft/DedicatedServer")
    for command in sys.stdin:
        command = command.strip()
        if command == "anchor selftest":
            if mode == "selftest":
                log("Anchor self-test failed 1 of 42 checks:")
            else:
                log("Anchor self-test passed: 42 checks.")
        elif command == "anchor heat status":
            log("Heat in minecraft:overworld")
            log("  Energy and mass balanced at the last audit")
        elif command == "stop":
            log("Stopping server")
            if mode == "hang":
                time.sleep(60)
            sys.exit(3 if mode == "exitcode" else 0)
""")


class SmokeTestTest(unittest.TestCase):

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.dir = Path(self.tmp.name)
        (self.dir / "fake_server.py").write_text(FAKE_SERVER, encoding="utf-8")

    def run_fake(self, mode, **timeouts):
        with contextlib.redirect_stdout(io.StringIO()):
            smoke_test.session([sys.executable, "fake_server.py", mode], self.dir, "0.1.0-alpha.1", **timeouts)

    def assert_fails(self, mode, message, **timeouts):
        with self.assertRaises(smoke_test.SmokeTestError) as failure:
            self.run_fake(mode, **timeouts)
        self.assertIn(message, str(failure.exception))

    def test_a_server_that_answers_every_command_passes(self):
        self.run_fake("ok")

    def test_errors_in_the_log_fail_even_when_everything_answers(self):
        self.assert_fails("error", "1 error lines in the log, the first: [12:00:00] [Server thread/ERROR]")

    def test_stack_traces_under_warnings_fail(self):
        self.assert_fails("trace", "java.lang.NoClassDefFoundError")

    def test_a_server_that_stops_while_starting_fails(self):
        self.assert_fails("crash", "the server stopped before printing anything matching 'Done")

    def test_a_server_that_never_starts_fails_in_time(self):
        self.assert_fails("silent", "within 1 s", startup_timeout=1.0)

    def test_a_wrong_answer_fails_at_once(self):
        self.assert_fails("selftest", "the server answered: [12:00:00] [Server thread/INFO] "
                                      "[minecraft/MinecraftServer]: Anchor self-test failed 1 of 42 checks:")

    def test_a_server_that_does_not_stop_fails(self):
        self.assert_fails("hang", "the server did not stop within 1 s", stop_timeout=1.0)

    def test_a_server_that_stops_badly_fails(self):
        self.assert_fails("exitcode", "the server exited with code 3")

    def test_the_log_must_show_the_version_that_was_built(self):
        with self.assertRaises(smoke_test.SmokeTestError) as failure:
            with contextlib.redirect_stdout(io.StringIO()):
                smoke_test.session([sys.executable, "fake_server.py", "ok"], self.dir, "0.2.0")
        self.assertIn("the log never says 'Anchor 0.2.0 loaded'", str(failure.exception))

    def test_the_start_script_from_the_installer_starts_the_server(self):
        self.assertIsNone(smoke_test.launch_command(self.dir))
        (self.dir / "run.sh").write_text("java @user_jvm_args.txt \"$@\"\n", encoding="utf-8")
        self.assertEqual(["sh", "run.sh", "nogui"], smoke_test.launch_command(self.dir))

    def test_preparing_puts_only_this_jar_in_the_mods_folder_and_a_fresh_world(self):
        jar = self.dir / "anchor-0.1.0-alpha.1.jar"
        jar.write_bytes(b"PK new")
        server = self.dir / "server"
        (server / "mods").mkdir(parents=True)
        (server / "mods" / "anchor-0.0.1.jar").write_bytes(b"PK old")
        (server / "world" / "region").mkdir(parents=True)
        smoke_test.prepare(server, jar)
        self.assertEqual(["anchor-0.1.0-alpha.1.jar"], sorted(p.name for p in (server / "mods").iterdir()))
        self.assertFalse((server / "world").exists())
        self.assertIn("eula=true", (server / "eula.txt").read_text(encoding="utf-8"))
        self.assertIn("online-mode=false", (server / "server.properties").read_text(encoding="utf-8"))

    def test_log_checks_ignore_warnings_without_traces(self):
        lines = ["[12:00:00] [main/INFO] [io.gi.os.an.ne.AnchorMod/]: Anchor 1.0.0 loaded",
                 "[12:00:01] [Server thread/WARN] [minecraft/MinecraftServer]: Can't keep up! Is the server "
                 "overloaded?",
                 "WARNING: A terminally deprecated method in sun.misc.Unsafe has been called"]
        self.assertEqual([], smoke_test.check_log(lines, "1.0.0"))


if __name__ == "__main__":
    unittest.main()

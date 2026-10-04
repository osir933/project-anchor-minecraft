"""Runs the built mod jar on a real NeoForge server and checks that it works there.

The game tests run in the development setup, where the mod's classes come straight from the build directories.
This test runs the jar that is released, the way players run it: NeoForge's own installer sets up a server, the
jar goes into its mods folder, and the server is started, given a few console commands and stopped. It fails if
the server does not start, a command does not answer as expected, the server does not stop cleanly or anything
is logged as an error.

    smoke_test.py [--dir DIR] [--timeout SECONDS] [JAR]

The NeoForge and mod versions come from gradle.properties, and JAR defaults to the jar the build makes. Only the
standard library is used, so the workflow needs nothing installed.
"""

import argparse
import queue
import re
import shutil
import subprocess
import sys
import threading
import time
import urllib.error
import urllib.request
from pathlib import Path

from release import ROOT, USER_AGENT, properties

INSTALLER = "https://maven.neoforged.net/releases/net/neoforged/neoforge/{0}/neoforge-{0}-installer.jar"

# What the test asks the server, what it must answer and what counts as a wrong answer. The core's self-test
# shows that the core is inside the jar and works; the heat status shows that the mod's side runs, and the time
# commands that heat can be paced.
NOT_RUNNING = r"not simulated|stopped after an error|Unknown or incomplete command"
STEPS = [
    ("anchor selftest", r"Anchor self-test passed: \d+ checks\.", r"Anchor self-test failed"),
    ("anchor heat status", r"Energy and mass balanced at the last audit",
     r"Heat is not simulated|stopped after an error|NOT balanced"),
    ("anchor time", r"Heat in minecraft:overworld: Running at normal speed", NOT_RUNNING),
    ("anchor time pause", r"Paused heat in minecraft:overworld", NOT_RUNNING),
    ("anchor time resume", r"Heat in minecraft:overworld runs again", NOT_RUNNING),
]

# The same world every run, kept small. No player joins, so the server needs no connection to Mojang.
SERVER_PROPERTIES = """\
# Written by Anchor's smoke test.
level-seed=anchor
online-mode=false
view-distance=3
simulation-distance=3
"""

# A log line at the ERROR or FATAL level, such as "[12:00:00] [Server thread/ERROR] [logger/]: message".
ERROR_LINE = re.compile(r"^\[[^\]]*\] \[[^\]]*/(ERROR|FATAL)\]")
# The first line of a stack trace, which a warning can carry without being an error, and the lines after it.
TRACE_LINE = re.compile(r"^(Exception in thread .*|(Caused by: )?[a-zA-Z_$][\w.$]*(Exception|Error)(: .*)?)$")
TRACE_CONTINUATION = re.compile(r"^(\s+at |\s*\.\.\. \d+ more|\s*Caused by: |\s*Suppressed: )")
# Stack traces that the server logs with or without Anchor, each matched by a line only that trace has.
KNOWN_TRACES = [
    # Netty checks whether macOS's kqueue is there, and on other systems log4j fails to write the stack
    # trace of that check into the debug log.
    re.compile(r"io\.netty\.channel\.kqueue\.Native"),
]


class SmokeTestError(Exception):
    """Something that failed the smoke test, with a message that says what."""


def download(url, target, attempts=5):
    """Downloads a file, trying again after network errors with growing pauses."""
    for attempt in range(attempts):
        try:
            request = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
            with urllib.request.urlopen(request, timeout=120) as response, open(target, "wb") as out:
                shutil.copyfileobj(response, out)
            return
        except urllib.error.HTTPError as e:
            if e.code < 500 or attempt == attempts - 1:
                raise SmokeTestError(f"downloading {url} failed: {e.code} {e.reason}") from None
        except (urllib.error.URLError, OSError) as e:
            if attempt == attempts - 1:
                raise SmokeTestError(f"downloading {url} failed: {e}") from None
        pause = 2 ** (attempt + 1)
        print(f"Downloading {url} failed; trying again in {pause} s")
        time.sleep(pause)


def launch_command(server_dir):
    """Returns the command that starts an installed server with the start script NeoForge's installer wrote."""
    if (Path(server_dir) / "run.sh").is_file():
        return ["sh", "run.sh", "nogui"]
    return None


def install(neoforge, server_dir):
    """Installs a NeoForge server into a directory with NeoForge's installer, unless it is already there."""
    server_dir.mkdir(parents=True, exist_ok=True)
    marker = server_dir / ".neoforge-version"
    if marker.is_file() and marker.read_text(encoding="utf-8").strip() == neoforge and launch_command(server_dir):
        print(f"NeoForge {neoforge} is already installed in {server_dir}")
        return
    installer = server_dir / f"neoforge-{neoforge}-installer.jar"
    print(f"Installing a NeoForge {neoforge} server in {server_dir}")
    download(INSTALLER.format(neoforge), installer)
    result = subprocess.run(["java", "-jar", installer.name, "--installServer", "."], cwd=server_dir,
                            stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, errors="replace")
    if result.returncode != 0 or not launch_command(server_dir):
        print("\n".join(result.stdout.splitlines()[-40:]))
        print("The server directory holds: " + ", ".join(sorted(p.name for p in server_dir.iterdir())))
        raise SmokeTestError(f"NeoForge's installer exited with code {result.returncode}"
                             + ("" if result.returncode else " and wrote no run.sh"))
    installer.unlink()
    marker.write_text(neoforge + "\n", encoding="utf-8")


def prepare(server_dir, jar):
    """Puts the jar in the server's mods folder and sets up a fresh world."""
    mods = server_dir / "mods"
    mods.mkdir(exist_ok=True)
    for old in mods.glob("*.jar"):
        old.unlink()
    shutil.copy2(jar, mods / Path(jar).name)
    shutil.rmtree(server_dir / "world", ignore_errors=True)
    (server_dir / "server.properties").write_text(SERVER_PROPERTIES, encoding="utf-8")
    # The server only starts once its licence is accepted; this is an automated test run, not a public server.
    (server_dir / "eula.txt").write_text("# Accepted for Anchor's automated smoke test.\neula=true\n",
                                         encoding="utf-8")


class Server:
    """A running server: its output is read line by line on a separate thread and echoed to the log."""

    def __init__(self, command, cwd):
        self.lines = []
        self.process = subprocess.Popen(command, cwd=cwd, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                                        stderr=subprocess.STDOUT, text=True, encoding="utf-8", errors="replace",
                                        bufsize=1)
        self._output = queue.Queue()
        self._reader = threading.Thread(target=self._read, daemon=True)
        self._reader.start()

    def _read(self):
        for line in self.process.stdout:
            line = line.rstrip("\r\n")
            print(line, flush=True)
            self._output.put(line)
        self._output.put(None)

    def expect(self, pattern, timeout, fail=None):
        """Waits for an output line that matches a pattern and returns it.

        Raises SmokeTestError when a line matches the fail pattern first, the server stops or time runs out.
        """
        deadline = time.monotonic() + timeout
        while True:
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                raise SmokeTestError(f"the server printed nothing matching '{pattern}' within {timeout:g} s")
            try:
                line = self._output.get(timeout=remaining)
            except queue.Empty:
                continue
            if line is None:
                self._output.put(None)
                raise SmokeTestError(f"the server stopped before printing anything matching '{pattern}'")
            self.lines.append(line)
            if fail and re.search(fail, line):
                raise SmokeTestError(f"the server answered: {line}")
            if re.search(pattern, line):
                return line

    def send(self, command):
        """Types a command into the server's console."""
        print(f"> {command}", flush=True)
        try:
            self.process.stdin.write(command + "\n")
            self.process.stdin.flush()
        except OSError:
            raise SmokeTestError(f"the server stopped before it could be sent '{command}'") from None

    def wait(self, timeout):
        """Waits for the server to exit and returns its exit code, once all of its output is read."""
        try:
            code = self.process.wait(timeout=timeout)
        except subprocess.TimeoutExpired:
            raise SmokeTestError(f"the server did not stop within {timeout:g} s") from None
        self._reader.join(timeout=10)
        while True:
            try:
                line = self._output.get_nowait()
            except queue.Empty:
                return code
            if line is not None:
                self.lines.append(line)

    def close(self):
        """Stops the server at once if it is still running and closes its console."""
        if self.process.poll() is None:
            self.process.kill()
            self.process.wait()
        self._reader.join(timeout=10)
        for stream in (self.process.stdin, self.process.stdout):
            try:
                stream.close()
            except OSError:
                pass


def traces(lines):
    """Returns the stack traces in a log, each as the index of its first line and its lines."""
    found = []
    current = None
    for index, line in enumerate(lines):
        if current is not None and TRACE_CONTINUATION.match(line):
            current.append(line)
        elif TRACE_LINE.match(line):
            current = [line]
            found.append((index, current))
        else:
            current = None
    return found


def check_log(lines, version):
    """Returns what is wrong in a server's log: the mod not loading, errors and stack traces."""
    problems = []
    if not any(f"Anchor {version} loaded" in line for line in lines):
        problems.append(f"the log never says 'Anchor {version} loaded'")
    errors = [(index, line) for index, line in enumerate(lines) if ERROR_LINE.match(line)]
    errors += [(index, trace[0]) for index, trace in traces(lines)
               if not any(known.search(line) for known in KNOWN_TRACES for line in trace)]
    if len(errors) == 1:
        problems.append(f"the log shows an error: {errors[0][1]}")
    elif errors:
        problems.append(f"the log shows {len(errors)} errors, the first: {min(errors)[1]}")
    return problems


def session(command, cwd, version, startup_timeout=300.0, step_timeout=60.0, stop_timeout=120.0, steps=STEPS):
    """Starts a server, runs the steps in its console, stops it and checks its log."""
    server = Server(command, cwd)
    try:
        server.expect(r"Done \(", startup_timeout)
        for console_command, answer, wrong in steps:
            server.send(console_command)
            server.expect(answer, step_timeout, wrong)
        server.send("stop")
        code = server.wait(stop_timeout)
    finally:
        server.close()
    problems = check_log(server.lines, version)
    if code != 0:
        problems.insert(0, f"the server exited with code {code}")
    if problems:
        raise SmokeTestError("; ".join(problems))


def main(argv):
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("jar", nargs="?", help="the mod jar; by default the one the build makes")
    parser.add_argument("--dir", default=str(ROOT / "build" / "smoke-server"),
                        help="where the server is installed and run")
    parser.add_argument("--timeout", type=float, default=300.0, help="seconds the server may take to start")
    args = parser.parse_args(argv)
    props = properties()
    version = props["mod_version"]
    jar = Path(args.jar) if args.jar else ROOT / "anchor-neoforge" / "build" / "libs" / f"anchor-{version}.jar"
    try:
        if not jar.is_file():
            raise SmokeTestError(f"there is no jar at {jar}; build the mod first")
        server_dir = Path(args.dir).resolve()
        install(props["neo_version"], server_dir)
        prepare(server_dir, jar)
        session(launch_command(server_dir), server_dir, version, startup_timeout=args.timeout)
    except SmokeTestError as e:
        print(f"::error::Smoke test: {e}")
        return 1
    print(f"Anchor {version} works on a real NeoForge {props['neo_version']} server.")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))

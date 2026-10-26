"""pyflakes finds nothing in the Python under tools/: no unused imports, undefined names or the like, as Checkstyle
holds the Java. It runs as this Python's pyflakes module or, where this Python hasn't one (a system Python that
refuses pip), as the pyflakes command (pipx install pyflakes, or a distribution's python3-pyflakes). Where there is
neither, the test fails, saying how to install it: a skipped check is how an undefined name once reached a commit.
RANKEDCHOICE_SKIP_PYFLAKES=1 (mvn -DskipPyflakes) skips it on purpose.

python3 -m unittest discover -s tools/reference/tests
"""
import io
import os
import shutil
import subprocess
import unittest

try:
    from pyflakes import api, reporter
except ImportError:
    api = None

TOOLS = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..")
INSTALL = ("pyflakes isn't installed: pipx install pyflakes (or pip install --user pyflakes, or the distribution's "
           "python3-pyflakes), or skip it on purpose with mvn -DskipPyflakes (RANKEDCHOICE_SKIP_PYFLAKES=1)")


def python_files():
    paths = []
    for directory, subdirectories, files in os.walk(TOOLS):
        subdirectories[:] = [d for d in subdirectories if not d.startswith((".", "__pycache__", "venv"))]
        paths += [os.path.join(directory, name) for name in files if name.endswith(".py")]
    return sorted(paths)


@unittest.skipIf(os.environ.get("RANKEDCHOICE_SKIP_PYFLAKES") in ("1", "true"), "skipped on purpose")
class Pyflakes(unittest.TestCase):

    def test_nothing_found(self):
        if api is not None:
            warnings, errors = io.StringIO(), io.StringIO()
            found = sum(api.checkPath(path, reporter.Reporter(warnings, errors)) for path in python_files())
            self.assertEqual(found, 0, warnings.getvalue() + errors.getvalue())
            return
        command = shutil.which("pyflakes") or shutil.which("pyflakes3")
        if command is None:
            self.fail(INSTALL)
        result = subprocess.run([command, *python_files()], capture_output=True, text=True)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)


if __name__ == "__main__":
    unittest.main()

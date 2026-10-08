"""pyflakes finds nothing in the Python under tools/: no unused imports, undefined names or the like, as Checkstyle
holds the Java. Skipped where pyflakes isn't installed (pip install --user pyflakes, or a distribution's
python3-pyflakes), so the build still runs offline without it.

python3 -m unittest discover -s tools/reference/tests
"""
import io
import os
import unittest

try:
    from pyflakes import api, reporter
except ImportError:
    api = None

TOOLS = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..")


@unittest.skipIf(api is None, "pyflakes isn't installed")
class Pyflakes(unittest.TestCase):

    def test_nothing_found(self):
        paths = []
        for directory, subdirectories, files in os.walk(TOOLS):
            subdirectories[:] = [d for d in subdirectories if not d.startswith((".", "__pycache__", "venv"))]
            paths += [os.path.join(directory, name) for name in files if name.endswith(".py")]
        warnings, errors = io.StringIO(), io.StringIO()
        found = sum(api.checkPath(path, reporter.Reporter(warnings, errors)) for path in sorted(paths))
        self.assertEqual(found, 0, warnings.getvalue() + errors.getvalue())


if __name__ == "__main__":
    unittest.main()

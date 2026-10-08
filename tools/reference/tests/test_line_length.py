"""Every Python file under tools/ keeps to 120 columns, as Checkstyle holds the Java to (AGENTS.md).

python3 -m unittest discover -s tools/reference/tests
"""
import os
import unittest

TOOLS = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..")
LIMIT = 120


class LineLength(unittest.TestCase):

    def test_no_line_over_120_columns(self):
        long_lines = []
        for directory, subdirectories, files in os.walk(TOOLS):
            subdirectories[:] = [d for d in subdirectories if not d.startswith((".", "__pycache__", "venv"))]
            for name in files:
                if name.endswith(".py"):
                    path = os.path.join(directory, name)
                    with open(path, encoding="utf-8") as source:
                        for number, line in enumerate(source, 1):
                            if len(line.rstrip("\n")) > LIMIT:
                                long_lines.append(f"{os.path.relpath(path, TOOLS)}:{number}")
        self.assertEqual(long_lines, [])


if __name__ == "__main__":
    unittest.main()

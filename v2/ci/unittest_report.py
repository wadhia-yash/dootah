"""Run unittest discovery and write counts, never assertion bodies or captured secrets."""
import io
from pathlib import Path
import sys
import unittest
import xml.etree.ElementTree as ET

suite = unittest.defaultTestLoader.discover(sys.argv[1], pattern='test_*.py')
result = unittest.TextTestRunner(stream=io.StringIO()).run(suite)
root = ET.Element('testsuite', tests=str(result.testsRun), failures=str(len(result.failures)),
                  errors=str(len(result.errors)), skipped=str(len(result.skipped)))
for i in range(result.testsRun):
    ET.SubElement(root, 'testcase', name=str(i), classname='regressions')
Path(sys.argv[2]).write_bytes(ET.tostring(root))
if not result.wasSuccessful() or result.skipped or not result.testsRun:
    print('Required Python suite failed; diagnostic bodies suppressed.', file=sys.stderr)
    sys.exit(1)

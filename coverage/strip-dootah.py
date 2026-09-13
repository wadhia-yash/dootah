"""
Takes Dootah back out of an app, so the app it was added to can be measured.

Used only by measure-overhead.sh, to build the same source with and without
Dootah. Removing the plugin is not enough on its own: the runtime call the
integration adds would then name a package that is no longer on the classpath.

Usage: strip-dootah.py build <build file>
       strip-dootah.py idle  <build file>
       strip-dootah.py init  <source file holding Dootah.initialize>
"""

import re
import sys


def strip_plugin(path):
    source = open(path).read()
    source = re.sub(r'\n *id\("dev\.dootah"\)[^\n]*', "", source)
    source = re.sub(r"\ndootah \{[^}]*\}\n", "\n", source)
    open(path, "w").write(source)


def make_idle(path):
    source = open(path).read()
    source = re.sub(r"(dootah \{)", r'\1\n    discovery = "annotated"', source, count=1)
    open(path, "w").write(source)


def strip_initialization(path):
    kept, depth, dropping = [], 0, False

    for line in open(path).read().split("\n"):

        if line.strip().startswith("import com.dootah"):
            continue

        if not dropping and "Dootah.initialize(" in line:
            dropping, depth = True, 0

        if dropping:
            depth += line.count("(") - line.count(")")
            if depth <= 0:
                dropping = False
            continue

        kept.append(line)

    open(path, "w").write("\n".join(kept))


if __name__ == "__main__":
    what, target = sys.argv[1], sys.argv[2]
    {"build": strip_plugin, "idle": make_idle, "init": strip_initialization}[what](target)

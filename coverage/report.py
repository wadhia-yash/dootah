#!/usr/bin/env python3
"""Turns Dootah's coverage records into the numbers worth arguing about.

One record per Compose function, written by the analysis pass; this groups them.
The grouping is the point: a hundred screens refused for taking a `State` are one
problem, and counting them as a hundred named components would hide that.
"""
import sys, os, glob, json, collections

# Which generic cause a refusal belongs to. Keyed on the refusal's stable code
# first, then on the concrete type it named -- never on a component's name.
def cause(code, detail):
    d = (detail or "")
    if code in ("UNSUPPORTED_LOCAL_TYPE", "UNSUPPORTED_PARAMETER_TYPE",
                "UNSUPPORTED_COMPONENT_ARGUMENT", "UNSUPPORTED_FUNCTION_PARAMETER",
                "UNSUPPORTED_FUNCTION_RETURN"):
        if "navigation" in d.lower() or "NavController" in d or "NavHost" in d:
            return "navigation"
        if ("compose.runtime.State" in d or "MutableState" in d or "SnapshotState" in d
                or "androidx.lifecycle" in d or "StateFlow" in d or "Flow" in d):
            return "state pattern"
        if d.startswith("kotlin.Function") or d.startswith("kotlin.coroutines"):
            return "callback argument type"
        if d.startswith("kotlin.collections") or d.startswith("kotlin.Array") or "List<" in d:
            return "collections/data classes"
        if d.startswith("androidx.compose.") or d.startswith("androidx.graphics"):
            return "Compose value type"
        if d.startswith("kotlin.") :
            return "Compose value type" if "Unit" in d else "collections/data classes"
        return "collections/data classes"
    if code in ("UNSUPPORTED_MODIFIER", "UNREADABLE_MODIFIER",
                "UNSUPPORTED_MODIFIER_ARGUMENT", "UNSUPPORTED_COLOR",
                "UNSUPPORTED_LAYOUT_ARGUMENT", "UNREADABLE_LAYOUT_CALL",
                "LAYOUT_WITHOUT_CONTENT"):
        return "Compose value type"
    if code in ("UNRESOLVED_CALL", "UNREADABLE_COMPONENT_ARGUMENTS",
                "CONTENT_NOT_A_LAMBDA", "COMPONENT_READS_SCOPE"):
        return "third-party composable"
    if code in ("UNSUPPORTED_CALL_IN_HANDLER", "UNSUPPORTED_CALL_IN_VALUE",
                "UNSUPPORTED_CALL_IN_LAYOUT"):
        if "navigation" in d.lower() or "navigate" in d.lower():
            return "navigation"
        return "native capability"
    return "unsupported Kotlin syntax"

def read_kv(path):
    out = {}
    for line in open(path, errors="replace").read().splitlines():
        if "=" in line:
            k, v = line.split("=", 1)
            out[k] = v
    return out

def read_rejections(d):
    """Rejections per function, in file order, so 'first reason' is stable."""
    per = collections.defaultdict(list)
    for f in sorted(glob.glob(os.path.join(d, "unsupported", "*.txt"))):
        cur = {}
        for line in open(f, errors="replace").read().splitlines():
            if line == "--":
                if cur.get("found"):
                    per[cur.get("function", "?")].append(cur)
                cur = {}
            elif "=" in line:
                k, v = line.split("=", 1)
                cur[k] = v
    return per

def scan(app, dirs):
    total = collections.Counter()
    blocked_by = collections.Counter()      # screens, first reason only
    occurrences = collections.Counter()
    details = collections.defaultdict(collections.Counter)
    modules = 0
    files = lines = 0
    failed_modules = 0

    for d in dirs:
        modules += 1
        mod = os.path.join(d, "module.properties")
        if os.path.exists(mod):
            m = read_kv(mod)
            files += int(m.get("kotlinFiles", 0) or 0)
            lines += int(m.get("sourceLines", 0) or 0)
            if m.get("analysisExitCode") not in ("0", None):
                failed_modules += 1

        rej = read_rejections(d)

        for f in glob.glob(os.path.join(d, "discovery", "*.txt")):
            r = read_kv(f)
            outcome = r.get("outcome", "INELIGIBLE")
            fq = r.get("fqName", "?")
            total["composables"] += 1
            if outcome == "INELIGIBLE":
                total["ineligible"] += 1
                total["ineligible:" + r.get("reason", "?")] += 1
                continue
            total["eligible"] += 1
            if outcome == "LOWERED":
                if int(r.get("adapters", 0) or 0) > 0:
                    total["mixed"] += 1
                else:
                    total["remote"] += 1
            else:
                total["fallback"] += 1
                reasons = rej.get(fq, [])
                if reasons:
                    first = reasons[0]
                    blocked_by[cause(first.get("code", "UNKNOWN"), first.get("detail"))] += 1
                    for x in reasons:
                        c = cause(x.get("code", "UNKNOWN"), x.get("detail"))
                        occurrences[c] += 1
                        if x.get("detail"):
                            details[c][x["detail"]] += 1
                else:
                    blocked_by["unrecorded"] += 1
    return dict(app=app, modules=modules, files=files, lines=lines,
                failed_modules=failed_modules, totals=dict(total),
                blocked_by=dict(blocked_by), occurrences=dict(occurrences),
                details={k: dict(v.most_common(6)) for k, v in details.items()})

if __name__ == "__main__":
    app = sys.argv[1]
    dirs = sorted(set(os.path.dirname(p) for p in
                      glob.glob(os.path.join(sys.argv[2], "**", "dootah", "coverage", "discovery"),
                                recursive=True)))
    if not dirs:
        dirs = sorted(glob.glob(os.path.join(sys.argv[2], "**", "dootah", "coverage"), recursive=True))
    print(json.dumps(scan(app, dirs), indent=2))

#!/usr/bin/env python3
"""Combines the per-app coverage records into the cross-app picture."""
import sys, json, glob, os, collections

rows = []
blocked = collections.Counter()
occurrences = collections.Counter()
details = collections.defaultdict(collections.Counter)
agg = collections.Counter()

for path in sorted(sys.argv[1:]):
    r = json.load(open(path))
    t = r["totals"]
    row = dict(
        app=r["app"],
        files=r["files"],
        composables=t.get("composables", 0),
        eligible=t.get("eligible", 0),
        remote=t.get("remote", 0),
        mixed=t.get("mixed", 0),
        not_worth=t.get("not_worth", 0),
        fallback=t.get("fallback", 0),
        ineligible=t.get("ineligible", 0),
        modules=r["modules"],
        top=sorted(r["blocked_by"].items(), key=lambda x: -x[1])[:3],
    )
    rows.append(row)
    for k in ("composables", "eligible", "remote", "mixed", "not_worth", "fallback",
              "ineligible", "files"):
        agg[k] += row[k]
    for k, v in r["blocked_by"].items():
        blocked[k] += v
    for k, v in r["occurrences"].items():
        occurrences[k] += v
    for c, d in r["details"].items():
        for name, n in d.items():
            details[c][name] += n
    for k, v in t.items():
        if k.startswith("ineligible:"):
            agg[k] += v

print("| App | Compose funcs | Eligible | Updatable | Not worth shipping "
      "| Refused | Out of scope | Top blockers |")
print("|---|---|---|---|---|---|---|---|")
for r in rows:
    top = ", ".join(f"{k} {v}" for k, v in r["top"]) or "--"
    print(f"| {r['app']} | {r['composables']} | {r['eligible']} | "
          f"{r['remote'] + r['mixed']} | {r['not_worth']} | {r['fallback']} | "
          f"{r['ineligible']} | {top} |")
print()
print("TOTALS", dict(agg))
c = agg["composables"] or 1
e = agg["eligible"] or 1
u = agg['remote'] + agg['mixed']
print(f"\nof all composables: updatable {100*u/c:.1f}%  "
      f"not worth shipping {100*agg['not_worth']/c:.1f}%  "
      f"refused {100*agg['fallback']/c:.1f}%  "
      f"out of scope {100*agg['ineligible']/c:.1f}%")
print(f"of eligible only:   updatable {100*u/e:.1f}%  refused {100*agg['fallback']/e:.1f}%")
print("\nblockers (screens, first reason):", blocked.most_common(8))
print("\nblockers (occurrences):", occurrences.most_common(8))
print("\ntop details per cause:")
for c2, d in sorted(details.items(), key=lambda x: -sum(x[1].values())):
    print(f"  {c2}: {d.most_common(5)}")
print("\nineligible reasons:",
      {k.split(':')[1]: v for k, v in agg.items() if k.startswith('ineligible:')})

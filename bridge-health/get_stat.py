#!/usr/bin/env python3
"""Print a stat from current_results.json. Usage: get_stat.py <alive|total>"""
import json
import sys

with open("bridge-health/current_results.json") as f:
    results = json.load(f)

stat = sys.argv[1] if len(sys.argv) > 1 else "total"
if stat == "alive":
    print(sum(1 for b in results if b["status"] == "alive"))
else:
    print(len(results))

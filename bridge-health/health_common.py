#!/usr/bin/env python3
"""Shared helpers for the bridge-health tooling.

Kept in its own module rather than duplicated across the five scripts that
touch health.json, so the failure-count key normalisation cannot drift between
the writer (apply_changes.py) and the readers.
"""

import html
import re

# Matches any named or numeric HTML entity.
_ENTITY_RE = re.compile(r"&(?:[A-Za-z][A-Za-z0-9]{1,31}|#\d{1,7}|#[xX][0-9A-Fa-f]{1,6});")

# Characters BridgeDB's web UI escapes, which Tor needs literally.
_BRIDGEDB_MAP = {
    "&#43;": "+",
    "&#47;": "/",
    "&#61;": "=",
    "&#58;": ":",
    "&#44;": ",",
    "&#46;": ".",
    "&#37;": "%",
    "&amp;": "&",
    "&lt;": "<",
    "&gt;": ">",
    "&quot;": '"',
    "&apos;": "'",
}


def normalize_bridge_line(line: str) -> str:
    """Return a canonical form of a bridge line for use as a lookup key.

    WHY THIS IS NEEDED
    ------------------
    health.json's ``failure_counts`` is keyed by bridge line. Those keys were
    captured at different times from different sources, so the same logical
    bridge exists in the file under two spellings: the raw BridgeDB form with
    ``&#43;`` in place of ``+`` inside the obfs4 certificate, and the decoded
    form with a literal ``+``.

    Every lookup is a plain ``failure_counts.get(line)``. Against a mixed set of
    keys that silently misses for whichever spelling the caller happened not to
    use, and the miss returns 0. So the "bridge failed twice, propose a
    replacement" rule could never fire for those bridges: the counter was
    written, then read back under a different key and treated as zero, forever.

    Normalising both the key on write and the lookup on read makes the two
    spellings collapse to one. Whitespace runs are collapsed too, since a stray
    space is invisible in a diff and would silently create a new key.
    """
    if not line:
        return ""
    out = _BRIDGEDB_MAP.get(line, line)
    if out == line:
        out = html.unescape(line)
    # html.unescape leaves unknown entities alone; drop any that survived.
    out = _ENTITY_RE.sub("", out)
    return " ".join(out.split())


def normalize_failure_counts(counts: dict) -> dict:
    """Re-key a failure_counts mapping by canonical bridge line.

    Existing keys are merged rather than overwritten, keeping the HIGHER count:
    if the same bridge is present under both spellings, the larger value is the
    one that actually accumulated failures, and dropping it would let a bridge
    that is genuinely failing be reported as healthy.
    """
    merged = {}
    for line, count in counts.items():
        key = normalize_bridge_line(line)
        if not key:
            continue
        try:
            count = int(count)
        except (TypeError, ValueError):
            continue
        # Membership check first, NOT `count > merged.get(key, 0)`: a healthy
        # bridge is recorded with a count of 0, and `0 > 0` is false, so that
        # formulation silently drops every healthy bridge from the map — which
        # is nearly all of them. Merging is about preferring the larger count
        # when a key exists under two spellings, not about filtering zeroes.
        if key not in merged or count > merged[key]:
            merged[key] = count
    return merged

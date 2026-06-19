#!/usr/bin/env python3
"""
AI Bridge Health Agent
======================

The AI brain of the bridge health monitor. Takes bridge test results
+ historical health data, asks a local LLM (Qwen 2.5 3B via llama.cpp)
whether to swap any dead bridges, and returns structured decisions.

The LLM makes the JUDGMENT call (swap or not? which replacement?).
The actual code edit is done deterministically by apply_changes.py.

Usage:
    python3 agent.py <health_data.json> <candidates.json> [--llama-bin PATH] [--model PATH]

Input: health_data.json (current + historical bridge results)
       candidates.json (replacement bridges, already tested alive)
Output: JSON decision to stdout
"""
import argparse
import json
import os
import subprocess
import sys
import tempfile
from pathlib import Path

# Default paths — set by the workflow via environment variables
LLAMA_BIN = os.environ.get("LLAMA_BIN", "llama-cli")
MODEL_PATH = os.environ.get("MODEL_PATH", "qwen2.5-3b-instruct-q4_k_m.gguf")


def build_prompt(health_data: dict, candidates: list) -> str:
    """Build the prompt for the LLM.

    The prompt includes:
    - Current bridge health (alive/dead/latency)
    - Failure history (how many consecutive failures)
    - Candidate replacements (already tested alive)
    - Decision rules
    - Required JSON output format
    """
    current = health_data.get("current_results", [])
    history = health_data.get("failure_counts", {})

    # Summarize current bridge status
    bridge_summary = []
    for b in current:
        line_short = b["line"][:80] + "..." if len(b["line"]) > 80 else b["line"]
        failures = history.get(b["line"], 0)
        bridge_summary.append({
            "transport": b["transport"],
            "line_preview": line_short,
            "full_line": b["line"],
            "status": b["status"],
            "bootstrap_time": b.get("bootstrap_time", 0),
            "error": b.get("error"),
            "consecutive_failures": failures,
        })

    # Summarize candidates
    candidate_summary = []
    for c in candidates:
        line_short = c["line"][:80] + "..." if len(c["line"]) > 80 else c["line"]
        candidate_summary.append({
            "transport": c["transport"],
            "line_preview": line_short,
            "full_line": c["line"],
            "bootstrap_time": c.get("bootstrap_time", 0),
        })

    prompt = f"""You are a Tor bridge health monitor AI. Analyze bridge test results and decide which dead bridges to replace.

CURRENT BRIDGE HEALTH:
{json.dumps(bridge_summary, indent=2)}

CANDIDATE REPLACEMENTS (already tested alive):
{json.dumps(candidate_summary, indent=2) if candidate_summary else "[]"}

RULES:
1. Only swap a bridge if it has 3+ consecutive failures (90+ minutes dead)
2. Prefer replacements with bootstrap_time < 30 seconds
3. Only swap if there is a working replacement of the SAME transport type
4. If no good replacement exists, do NOT swap (return no_action for that bridge)
5. Be conservative — false swaps are worse than waiting

Respond as valid JSON only, no markdown:
{{
  "actions": [
    {{
      "type": "swap",
      "dead_bridge_line": "the full bridge line being replaced",
      "replacement_bridge_line": "the full new bridge line",
      "reason": "one sentence why"
    }}
  ],
  "pr_title": "short PR title",
  "pr_body": "markdown PR description with evidence and reasoning",
  "summary": "one-line commit message summary"
}}

If no swaps needed, return empty actions array:
{{
  "actions": [],
  "pr_title": "",
  "pr_body": "",
  "summary": "no changes needed"
}}
"""
    return prompt


def call_llm(prompt: str, llama_bin: str, model_path: str, max_tokens: int = 800) -> str:
    """Call llama.cpp with the prompt and return the raw text output.

    Uses --no-cnv (no conversation mode) so the model just completes
    the prompt without interactive back-and-forth.
    """
    # Write prompt to a temp file (avoids shell escaping issues)
    with tempfile.NamedTemporaryFile(mode="w", suffix=".txt", delete=False) as f:
        f.write(prompt)
        prompt_file = f.name

    try:
        cmd = [
            llama_bin,
            "-m", model_path,
            "-f", prompt_file,
            "-n", str(max_tokens),
            "--temp", "0.3",  # low temperature for deterministic decisions
            "--no-cnv",
            "--no-warmup",
            "-t", "4",  # use 4 threads (GitHub runner has 4 vCPUs)
        ]

        print(f"[*] Running: {llama_bin} -m {model_path} ...", file=sys.stderr)
        result = subprocess.run(
            cmd,
            capture_output=True,
            text=True,
            timeout=120,  # 2 min max for inference
        )

        if result.returncode != 0:
            print(f"[!] llama-cli exited with code {result.returncode}", file=sys.stderr)
            print(f"[!] stderr: {result.stderr[:500]}", file=sys.stderr)
            return ""

        # llama-cli output includes some preamble — extract just the model's response
        # The response comes after the prompt echo
        output = result.stdout

        # Try to find the JSON block in the output
        # Look for the first { and last }
        first_brace = output.find("{")
        last_brace = output.rfind("}")
        if first_brace != -1 and last_brace != -1 and last_brace > first_brace:
            return output[first_brace:last_brace + 1]

        return output

    except subprocess.TimeoutExpired:
        print("[!] llama-cli timed out after 120s", file=sys.stderr)
        return ""
    finally:
        os.unlink(prompt_file)


def parse_llm_response(raw: str) -> dict:
    """Parse the LLM's JSON response. If parsing fails, return a safe
    no-action response (never crash the workflow on bad AI output).
    """
    if not raw.strip():
        print("[!] Empty LLM response — returning no-action", file=sys.stderr)
        return {
            "actions": [],
            "pr_title": "",
            "pr_body": "AI returned empty response — no changes made.",
            "summary": "no changes (AI returned empty)",
        }

    try:
        result = json.loads(raw)
        # Validate structure
        if "actions" not in result:
            result["actions"] = []
        if "pr_title" not in result:
            result["pr_title"] = ""
        if "pr_body" not in result:
            result["pr_body"] = ""
        if "summary" not in result:
            result["summary"] = "no changes"
        return result
    except json.JSONDecodeError as e:
        print(f"[!] Failed to parse LLM JSON: {e}", file=sys.stderr)
        print(f"[!] Raw response: {raw[:500]}", file=sys.stderr)
        return {
            "actions": [],
            "pr_title": "",
            "pr_body": f"AI returned unparseable response. No changes made.\n\nRaw output:\n```\n{raw[:500]}\n```",
            "summary": "no changes (AI JSON parse failed)",
        }


def main():
    parser = argparse.ArgumentParser(description="AI Bridge Health Agent")
    parser.add_argument("health_data", help="Path to health data JSON")
    parser.add_argument("candidates", help="Path to candidates JSON")
    parser.add_argument("--llama-bin", default=LLAMA_BIN, help="Path to llama-cli binary")
    parser.add_argument("--model", default=MODEL_PATH, help="Path to GGUF model file")
    parser.add_argument("--output", default="-", help="Output file (- for stdout)")
    parser.add_argument("--dry-run", action="store_true", help="Skip LLM call, return no-action")
    args = parser.parse_args()

    # Load health data
    with open(args.health_data) as f:
        health_data = json.load(f)

    # Load candidates
    try:
        with open(args.candidates) as f:
            candidates = json.load(f)
    except FileNotFoundError:
        candidates = []

    # Check if any bridges need AI judgment
    dead_bridges = [
        b for b in health_data.get("current_results", [])
        if b["status"] != "alive" and health_data.get("failure_counts", {}).get(b["line"], 0) >= 3
    ]

    if not dead_bridges:
        print("[*] No bridges need AI judgment (none have 3+ failures)", file=sys.stderr)
        result = {
            "actions": [],
            "pr_title": "",
            "pr_body": "No bridges require replacement. All bridges are either alive or haven't failed enough times yet.",
            "summary": "no changes needed",
        }
    elif args.dry_run:
        print("[*] Dry run — skipping LLM call", file=sys.stderr)
        result = {
            "actions": [],
            "pr_title": "",
            "pr_body": "Dry run — no changes made.",
            "summary": "dry run",
        }
    else:
        # Build prompt and call LLM
        prompt = build_prompt(health_data, candidates)
        print(f"[*] Prompt: {len(prompt)} chars", file=sys.stderr)

        raw_response = call_llm(prompt, args.llama_bin, args.model)
        result = parse_llm_response(raw_response)

    # Validate AI decisions before returning
    validated_actions = []
    for action in result.get("actions", []):
        if action.get("type") == "swap":
            # Verify the dead bridge actually exists in current results
            dead_line = action.get("dead_bridge_line", "")
            replacement = action.get("replacement_bridge_line", "")

            # Find the full dead bridge line (AI may have truncated it)
            full_dead = None
            for b in health_data.get("current_results", []):
                if b["line"] == dead_line or b["line"].startswith(dead_line[:40]):
                    full_dead = b["line"]
                    break

            # Find the full replacement line
            full_replacement = None
            for c in candidates:
                if c["line"] == replacement or c["line"].startswith(replacement[:40]):
                    full_replacement = c["line"]
                    break

            if full_dead and full_replacement:
                action["dead_bridge_line"] = full_dead
                action["replacement_bridge_line"] = full_replacement
                validated_actions.append(action)
                print(f"[*] AI decided to swap: {full_dead[:40]}... -> {full_replacement[:40]}...",
                      file=sys.stderr)
            else:
                print(f"[!] AI suggested swap but couldn't validate bridge lines — skipping",
                      file=sys.stderr)

    result["actions"] = validated_actions

    output = json.dumps(result, indent=2)
    if args.output == "-":
        print(output)
    else:
        with open(args.output, "w") as f:
            f.write(output)
        print(f"[*] Decision written to {args.output}", file=sys.stderr)

    print(f"[*] Actions: {len(validated_actions)}", file=sys.stderr)


if __name__ == "__main__":
    main()

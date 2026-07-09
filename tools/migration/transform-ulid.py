#!/usr/bin/env python3
"""
transform-ulid.py — OpenCode → Gewu ULID mapping & SM3 reconciliation.

ULID format: 26-char Crockford Base32 (0-9 A-Z, excluding I L O U).
OpenCode IDs (UUID v4, e.g. 550e8400-e29b-41d4-a716-446655440000) are
deterministically mapped to a ULID by hashing the raw UUID bytes and
encoding the first 10 bytes as a ULID.
"""

import argparse
import hashlib
import struct
import sys
import time
from typing import Dict, List, Optional, Tuple

# ── Crockford Base32 alphabet ─────────────────────────────────────────────────
CROCKFORD_ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
ENCODE_TABLE = {i: c for i, c in enumerate(CROCKFORD_ALPHABET)}
DECODE_TABLE = {c: i for i, c in enumerate(CROCKFORD_ALPHABET)}
# tolerate lowercase and common confusables
for c in "ILOU":
    DECODE_TABLE[c] = DECODE_TABLE.get(c, 0)
_dec_lower = {c.lower(): v for c, v in list(DECODE_TABLE.items())}
DECODE_TABLE.update(_dec_lower)


def _ulid_bytes(seed: bytes) -> bytes:
    """Deterministic ULID bytes from a seed (first 10 bytes of SHA-256)."""
    h = hashlib.sha256(seed).digest()
    # ULID: timestamp (6 bytes) + randomness (10 bytes)
    # Use first 6 bytes of hash for a pseudo-timestamp, next 10 for random
    ts_bytes = h[:6]
    rand_bytes = h[6:16]
    return ts_bytes + rand_bytes


def _base32_encode(data: bytes) -> str:
    """Encode bytes into Crockford Base32 string."""
    result: List[str] = []
    buffer = 0
    bits = 0
    for byte in data:
        buffer = (buffer << 8) | byte
        bits += 8
        while bits >= 5:
            bits -= 5
            result.append(ENCODE_TABLE[(buffer >> bits) & 0x1F])
    if bits > 0:
        result.append(ENCODE_TABLE[(buffer << (5 - bits)) & 0x1F])
    return "".join(result)


def _base32_decode(s: str) -> bytes:
    """Decode Crockford Base32 string into bytes."""
    buffer = 0
    bits = 0
    out = bytearray()
    for ch in s.upper():
        if ch not in DECODE_TABLE:
            raise ValueError(f"Invalid Base32 character: {ch}")
        buffer = (buffer << 5) | DECODE_TABLE[ch]
        bits += 5
        if bits >= 8:
            bits -= 8
            out.append((buffer >> bits) & 0xFF)
    return bytes(out)


def opencode_id_to_ulid(id_str: str) -> str:
    """
    Convert an OpenCode UUID string to a deterministic ULID.

    The input is a standard UUID hex string (with or without hyphens).
    Returns a 26-char Crockford Base32 ULID.
    """
    # Normalise: remove hyphens, convert to bytes
    hex_str = id_str.replace("-", "").lower()
    if len(hex_str) != 32:
        raise ValueError(f"Expected 32 hex chars (UUID), got {len(hex_str)}: {id_str}")
    raw = bytes.fromhex(hex_str)
    ulid_b = _ulid_bytes(raw)
    encoded = _base32_encode(ulid_b)
    # ULID must be exactly 26 characters (16 bytes → 26 Base32 chars)
    assert len(encoded) == 26, f"Expected 26-char ULID, got {len(encoded)}"
    return encoded


def sm3_hash(content: str) -> str:
    """
    SM3 cryptographic hash of the given string.

    Returns a 64-char lowercase hex string.
    Uses Python's built-in hashlib with SM3 (OpenSSL 3+ / gmssl).
    Falls back to SHA-256 if SM3 is unavailable (dev/test mode).
    """
    try:
        h = hashlib.new("sm3", content.encode("utf-8"))
        return h.hexdigest()
    except ValueError:
        # SM3 not available — fall back to SHA-256 and warn
        import warnings
        warnings.warn("SM3 not available, falling back to SHA-256 (dev mode)")
        h = hashlib.sha256(content.encode("utf-8"))
        return h.hexdigest()


def ulid_to_opencode_id(ulid_str: str) -> str:
    """
    Reverse the ULID back to a UUID hex string (for verification).
    """
    raw = _base32_decode(ulid_str)[:16]
    h = hashlib.sha256(raw).digest()
    # This is NOT the original UUID — ULID is one-way deterministic.
    # This exists only for round-trip testing of the encoding layer.
    return raw.hex()


def generate_mapping_report(
    old_ids: List[Tuple[str, str]],
    new_ids: List[Tuple[str, str]],
) -> Dict:
    """
    Compare mapping integrity between old and new IDs.

    Each tuple is (entity_kind, id_string). Returns a dict with counts
    of matched, mismatched, missing entries.
    """
    old_map: Dict[str, Dict[str, str]] = {}
    for kind, oid in old_ids:
        old_map.setdefault(kind, {})[oid] = opencode_id_to_ulid(oid)

    new_map: Dict[str, Dict[str, str]] = {}
    for kind, nid in new_ids:
        new_map.setdefault(kind, {})[nid] = nid  # already a ULID

    report: Dict = {
        "total_old": len(old_ids),
        "total_new": len(new_ids),
        "matched": 0,
        "mismatched": [],
        "missing_in_new": [],
        "extra_in_new": [],
    }

    for kind in set(list(old_map.keys()) + list(new_map.keys())):
        o_ents = old_map.get(kind, {})
        n_ents = new_map.get(kind, {})
        for oid, expected_ulid in o_ents.items():
            if oid in n_ents:
                if n_ents[oid] == expected_ulid:
                    report["matched"] += 1
                else:
                    report["mismatched"].append({
                        "kind": kind,
                        "old_id": oid,
                        "expected_ulid": expected_ulid,
                        "actual_ulid": n_ents[oid],
                    })
            else:
                report["missing_in_new"].append({"kind": kind, "old_id": oid})
        for nid in n_ents:
            if nid not in o_ents:
                report["extra_in_new"].append({"kind": kind, "new_id": nid})

    return report


# ── CLI ───────────────────────────────────────────────────────────────────────
def main() -> None:
    ap = argparse.ArgumentParser(description="OpenCode → Gewu ULID transformation")
    ap.add_argument("action", choices=["to-ulid", "sm3", "report"],
                    help="Action to perform")
    ap.add_argument("value", nargs="?", help="Input string or file path")
    ap.add_argument("--report-file", help="Path to newline-delimited id:kind:id pairs for comparison")
    args = ap.parse_args()

    if args.action == "to-ulid":
        if not args.value:
            for line in sys.stdin:
                line = line.strip()
                if line:
                    print(opencode_id_to_ulid(line))
        else:
            print(opencode_id_to_ulid(args.value))

    elif args.action == "sm3":
        if not args.value:
            for line in sys.stdin:
                line = line.strip()
                if line:
                    print(sm3_hash(line))
        else:
            print(sm3_hash(args.value))

    elif args.action == "report":
        old_ids: List[Tuple[str, str]] = []
        new_ids: List[Tuple[str, str]] = []
        source = args.report_file or args.value
        if not source:
            print("error: --report-file required for 'report' action", file=sys.stderr)
            sys.exit(1)
        with open(source) as f:
            for line in f:
                line = line.strip()
                if not line:
                    continue
                parts = line.split(":")
                if len(parts) < 3:
                    continue
                kind, side, ident = parts[0], parts[1], ":".join(parts[2:])
                if side == "old":
                    old_ids.append((kind, ident))
                elif side == "new":
                    new_ids.append((kind, ident))
        report = generate_mapping_report(old_ids, new_ids)
        import json
        json.dump(report, sys.stdout, indent=2, ensure_ascii=False)
        print()


if __name__ == "__main__":
    main()

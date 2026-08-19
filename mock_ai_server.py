#!/usr/bin/env python3
"""
Mock local AI server for CHARM.

Implements the contract described in LLM_ENDPOINTS.md using only the Python
standard library (no dependencies):

    POST /chat   {model, prompt, temperature}  -> {text}
    POST /embed  {model, input[]}              -> {vectors[][]}

Both responses are fully DETERMINISTIC (same input -> same output), so it is
suitable for smoke-testing that CHARM reaches the endpoints and parses the
responses. It does NOT run a real model, so /chat answers are canned and the
embeddings are hash-derived — do not expect meaningful matching quality.

Usage:
    python3 mock_ai_server.py                 # listens on 0.0.0.0:8000
    PORT=9000 python3 mock_ai_server.py        # custom port
    python3 mock_ai_server.py --port 9000 --embed-dim 32

Point CHARM at it with:
    LLM_BASE_URL=http://localhost:8000
"""

import argparse
import hashlib
import json
import math
import os
import struct
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

# Deterministic dimensionality for /embed (overridable via --embed-dim).
DEFAULT_EMBED_DIM = 16


def deterministic_vector(text: str, dim: int) -> list:
    """Map a string to a fixed-length, L2-normalized float vector, deterministically."""
    values = []
    counter = 0
    # Pull enough bytes out of a repeated SHA-256 to fill `dim` floats.
    while len(values) < dim:
        digest = hashlib.sha256(f"{counter}:{text}".encode("utf-8")).digest()
        # 8 floats per 32-byte digest (4 bytes each).
        for i in range(0, len(digest), 4):
            if len(values) >= dim:
                break
            (raw,) = struct.unpack(">I", digest[i:i + 4])
            # Scale to roughly [-1, 1].
            values.append((raw / 0xFFFFFFFF) * 2.0 - 1.0)
        counter += 1

    norm = math.sqrt(sum(v * v for v in values)) or 1.0
    return [v / norm for v in values]


class Handler(BaseHTTPRequestHandler):
    server_version = "MockLLM/1.0"

    # --- helpers -----------------------------------------------------------
    def _send_json(self, status: int, payload: dict):
        body = json.dumps(payload).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def _read_json(self):
        length = int(self.headers.get("Content-Length", 0))
        raw = self.rfile.read(length) if length else b""
        if not raw:
            return {}
        return json.loads(raw.decode("utf-8"))

    # --- routing -----------------------------------------------------------
    def do_POST(self):
        try:
            data = self._read_json()
        except (ValueError, json.JSONDecodeError) as exc:
            self._send_json(400, {"error": f"invalid JSON: {exc}"})
            return

        if self.path.rstrip("/") == "/chat":
            self._handle_chat(data)
        elif self.path.rstrip("/") == "/embed":
            self._handle_embed(data)
        else:
            self._send_json(404, {"error": f"unknown path {self.path}"})

    def _handle_chat(self, data: dict):
        model = data.get("model", "?")
        prompt = data.get("prompt", "")
        if not isinstance(prompt, str):
            self._send_json(400, {"error": "'prompt' must be a string"})
            return
        # Canned, deterministic answer. A real server would call the LLM here.
        text = f"[mock:{model}] received {len(prompt)} chars"
        self.log_message("chat  model=%s prompt_len=%d", model, len(prompt))
        self._send_json(200, {"text": text})

    def _handle_embed(self, data: dict):
        model = data.get("model", "?")
        inputs = data.get("input", [])
        if not isinstance(inputs, list) or not all(isinstance(x, str) for x in inputs):
            self._send_json(400, {"error": "'input' must be a string array"})
            return
        vectors = [deterministic_vector(t, self.server.embed_dim) for t in inputs]
        self.log_message("embed model=%s n=%d dim=%d", model, len(inputs), self.server.embed_dim)
        self._send_json(200, {"vectors": vectors})

    # Quieter, single-line logging.
    def log_message(self, fmt, *args):
        print("[mock] " + (fmt % args))


class MockServer(ThreadingHTTPServer):
    def __init__(self, addr, handler, embed_dim):
        super().__init__(addr, handler)
        self.embed_dim = embed_dim


def main():
    parser = argparse.ArgumentParser(description="Mock local AI server for CHARM (see LLM_ENDPOINTS.md)")
    parser.add_argument("--host", default="0.0.0.0")
    parser.add_argument("--port", type=int, default=int(os.environ.get("PORT", "8000")))
    parser.add_argument("--embed-dim", type=int, default=int(os.environ.get("EMBED_DIM", DEFAULT_EMBED_DIM)))
    args = parser.parse_args()

    server = MockServer((args.host, args.port), Handler, args.embed_dim)
    print(f"Mock LLM server listening on http://{args.host}:{args.port}")
    print(f"  POST /chat   -> {{text}}")
    print(f"  POST /embed  -> {{vectors}} (dim={args.embed_dim})")
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("\nShutting down.")
        server.shutdown()


if __name__ == "__main__":
    main()

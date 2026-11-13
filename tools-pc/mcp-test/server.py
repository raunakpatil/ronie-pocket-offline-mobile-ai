"""A tiny MCP server (Streamable HTTP, JSON replies) for testing Ronie's connectors without any third party.

  python tools-pc/mcp-test/server.py            # listens on 127.0.0.1:8765/mcp
  adb reverse tcp:8765 tcp:8765                  # the phone reaches it as http://localhost:8765/mcp

Tools: lookup_order (read-only), send_note (changes something, so Ronie must ask first),
sloppy_tool (no annotations, so it counts as destructive). Token "test-token" is required.
"""
import json
import sys
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

TOKEN = "test-token"
NOTES = []
TOOLS = [
    {"name": "lookup_order", "description": "Look up the status of a shop order by its number.",
     "inputSchema": {"type": "object", "properties": {"order": {"type": "string", "description": "Order number"}}, "required": ["order"]},
     "annotations": {"readOnlyHint": True}},
    {"name": "send_note", "description": "Send a note to the user's team channel.",
     "inputSchema": {"type": "object", "properties": {"text": {"type": "string", "description": "The note"}}, "required": ["text"]},
     "annotations": {"readOnlyHint": False, "destructiveHint": False}},
    {"name": "sloppy_tool", "description": "A tool with no annotations.",
     "inputSchema": {"type": "object", "properties": {}}},
]


def handle(msg):
    m, mid, p = msg.get("method"), msg.get("id"), msg.get("params") or {}
    if mid is None:
        return None  # notification
    if m == "initialize":
        r = {"protocolVersion": "2025-06-18", "capabilities": {"tools": {}}, "serverInfo": {"name": "ronie-test", "version": "1"}}
    elif m == "tools/list":
        r = {"tools": TOOLS}
    elif m == "tools/call":
        name, a = p.get("name"), p.get("arguments") or {}
        if name == "lookup_order":
            r = {"content": [{"type": "text", "text": f"Order {a.get('order')}: shipped yesterday, arriving Thursday."}]}
        elif name == "send_note":
            NOTES.append(a.get("text"))
            r = {"content": [{"type": "text", "text": f"Note sent ({len(NOTES)} so far)."}]}
        else:
            r = {"content": [{"type": "text", "text": "ok"}]}
        print("CALL", name, a, flush=True)
    else:
        return {"jsonrpc": "2.0", "id": mid, "error": {"code": -32601, "message": "unknown method"}}
    return {"jsonrpc": "2.0", "id": mid, "result": r}


class H(BaseHTTPRequestHandler):
    def do_POST(self):
        if self.headers.get("Authorization") != f"Bearer {TOKEN}":
            self.send_response(401); self.end_headers(); return
        msg = json.loads(self.rfile.read(int(self.headers.get("Content-Length", 0))))
        print("<-", msg.get("method"), flush=True)
        out = handle(msg)
        if out is None:
            self.send_response(202); self.end_headers(); return
        body = json.dumps(out).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Mcp-Session-Id", "test-session")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)


if __name__ == "__main__":
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 8765
    print(f"MCP test server on http://127.0.0.1:{port}/mcp", flush=True)
    ThreadingHTTPServer(("127.0.0.1", port), H).serve_forever()

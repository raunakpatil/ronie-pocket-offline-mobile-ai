"""Gemma 4 native tool format: render declarations, parse calls, render responses.

Mirrors the GGUF chat template (checked against it with jinja2 in check_template.py) and the Kotlin
port in core/engine/.../ToolFormat.kt. Strings are wrapped in the special token <|"|>.
"""
import json
import re

Q = '<|"|>'


def _arg(v):
    """format_argument(escape_keys=False): the value syntax used inside calls and responses."""
    if v is None:
        return "null"
    if isinstance(v, bool):
        return "true" if v else "false"
    if isinstance(v, str):
        return Q + v + Q
    if isinstance(v, dict):
        return "{" + ",".join(f"{k}:{_arg(v[k])}" for k in sorted(v)) + "}"
    if isinstance(v, list):
        return "[" + ",".join(_arg(x) for x in v) + "]"
    if isinstance(v, float) and v.is_integer():
        return str(v)  # jinja prints 1.0 as 1.0
    return str(v)


def _props(props):
    out = []
    for key in sorted(props):
        p = props[key]
        parts = []
        if p.get("description"):
            parts.append(f"description:{Q}{p['description']}{Q}")
        t = p.get("type", "string").upper()
        if t == "STRING" and p.get("enum"):
            parts.append("enum:[" + ",".join(Q + e + Q for e in p["enum"]) + "]")
        parts.append(f"type:{Q}{t}{Q}")
        out.append(f"{key}:{{" + ",".join(parts) + "}")
    return ",".join(out)


def declaration(tool):
    """One <|tool>…<tool|> block from an MCP-style tool (name, description, inputSchema)."""
    s = tool.get("inputSchema") or {}
    body = f"declaration:{tool['name']}{{description:{Q}{tool['description']}{Q}"
    if s:
        body += ",parameters:{"
        if s.get("properties"):
            body += "properties:{" + _props(s["properties"]) + "},"
        if s.get("required"):
            body += "required:[" + ",".join(Q + r + Q for r in s["required"]) + "],"
        if s.get("type"):
            body += f"type:{Q}{s['type'].upper()}{Q}}}"
    body += "}"
    return "<|tool>" + body + "<tool|>"


def call(name, args):
    return "<|tool_call>call:" + name + "{" + ",".join(f"{k}:{_arg(args[k])}" for k in sorted(args)) + "}<tool_call|>"


def response(name, value):
    """<|tool_response>…<tool_response|> for a tool result (dict or plain string)."""
    if isinstance(value, dict):
        inner = ",".join(f"{k}:{_arg(value[k])}" for k in sorted(value))
    else:
        inner = "value:" + _arg(value)
    return "<|tool_response>response:" + name + "{" + inner + "}<tool_response|>"


# ---------------------------------------------------------------- parsing

CALL_RE = re.compile(r"<\|tool_call>call:([A-Za-z0-9_\-.]+)\{(.*?)\}<tool_call\|>", re.S)


class ParseError(ValueError):
    pass


def parse_args(s):
    """Parses Gemma's argument syntax (bare keys, <|"|>strings<|"|>, numbers, true/false/null, {}, []) to a dict."""
    i = 0

    def ws():
        nonlocal i
        while i < len(s) and s[i] in " \n\t":
            i += 1

    def value():
        nonlocal i
        ws()
        if s.startswith(Q, i):
            j = s.find(Q, i + len(Q))
            if j < 0:
                raise ParseError("unterminated string")
            v = s[i + len(Q):j]
            i = j + len(Q)
            return v
        if s.startswith('"', i):          # tolerate plain JSON strings
            m = re.compile(r'"((?:[^"\\]|\\.)*)"').match(s, i)
            if not m:
                raise ParseError("bad string")
            i = m.end()
            return json.loads(m.group(0))
        if s.startswith("{", i):
            i += 1
            return obj("}")
        if s.startswith("[", i):
            i += 1
            out = []
            ws()
            if s.startswith("]", i):
                i += 1
                return out
            while True:
                out.append(value())
                ws()
                if s.startswith(",", i):
                    i += 1
                elif s.startswith("]", i):
                    i += 1
                    return out
                else:
                    raise ParseError(f"expected , or ] at {i}")
        m = re.compile(r"-?\d+(\.\d+)?([eE][-+]?\d+)?|true|false|null").match(s, i)
        if not m:
            raise ParseError(f"bad value at {i}: {s[i:i + 20]!r}")
        i = m.end()
        t = m.group(0)
        if t in ("true", "false", "null"):
            return {"true": True, "false": False, "null": None}[t]
        return float(t) if any(c in t for c in ".eE") else int(t)

    def obj(end):
        nonlocal i
        out = {}
        ws()
        if end and s.startswith(end, i):
            i += 1
            return out
        while i < len(s):
            ws()
            m = re.compile(r'<\|"\|>([^<]*)<\|"\|>|"([^"]*)"|([A-Za-z_][A-Za-z0-9_\-]*)').match(s, i)
            if not m:
                raise ParseError(f"bad key at {i}: {s[i:i + 20]!r}")
            key = m.group(1) or m.group(2) or m.group(3)
            i = m.end()
            ws()
            if not s.startswith(":", i):
                raise ParseError(f"expected : after {key}")
            i += 1
            out[key] = value()
            ws()
            if s.startswith(",", i):
                i += 1
                continue
            if end and s.startswith(end, i):
                i += 1
                return out
            if not end and i >= len(s):
                return out
            raise ParseError(f"expected , at {i}: {s[i:i + 20]!r}")
        if end:
            raise ParseError("unterminated object")
        return out

    return obj(None)


BARE_RE = re.compile(r"^\s*(?:call:)?([A-Za-z0-9_]+)\s*(\{.*\}|\(.*\))?\s*(?:<tool_call\|>)?\s*(?:<turn\|>|<eos>|<\|tool_response>)?\s*$", re.S)


def parse_calls(text, known=None):
    """[(name, args_dict | ParseError)] for every complete call in the model output.

    Small models sometimes drop the <|tool_call>call: marker and write just `list_tasks{…}` or
    `set_alarm(time="06:30")`. When the whole reply is such a call to a tool in [known], it is accepted.
    """
    out = []
    for m in CALL_RE.finditer(text):
        try:
            out.append((m.group(1), parse_args(m.group(2))))
        except ParseError as e:
            out.append((m.group(1), e))
    if out or not known:
        return out
    m = BARE_RE.match(text)
    if m and m.group(1) in known:
        name, body = m.group(1), (m.group(2) or "{}")[1:-1]
        body = re.sub(r"(^|,)\s*([A-Za-z_][A-Za-z0-9_]*)\s*=\s*", r"\1\2:", body)  # k="v" -> k:"v"
        try:
            out.append((name, parse_args(body)))
        except ParseError as e:
            # remember(Your birthday is March 3rd.) -> the text is the tool's single required argument
            req = (known.get(name) or {}).get("required") if isinstance(known, dict) else None
            if req and len(req) == 1 and body.strip():
                out.append((name, {req[0]: body.strip().strip('"').replace(Q, "")}))
            else:
                out.append((name, e))
    return out


def visible_text(text):
    """What the user sees: model text with tool calls and control tokens removed."""
    text = CALL_RE.sub("", text)
    text = re.sub(r"<\|channel>.*?<channel\|>", "", text, flags=re.S)
    return re.sub(r"<\|?[a-z_\"]+\|?>", "", text).strip()

"""Checks gemma_tools.py renders exactly what the GGUF chat template renders (tools, calls, responses)."""
import json, sys
from pathlib import Path
import jinja2
sys.path.insert(0, str(Path(__file__).parent))
import gemma_tools as G

ROOT = Path(__file__).resolve().parents[2]
tpl_path = sys.argv[1]
env = jinja2.Environment()
env.globals["raise_exception"] = lambda m: (_ for _ in ()).throw(Exception(m))
tpl = env.from_string(open(tpl_path, encoding="utf-8").read())
tools = json.load(open(ROOT / "app/src/main/assets/tools/builtin.json", encoding="utf-8"))["tools"]
oa = [{"type": "function", "function": {"name": t["name"], "description": t["description"], "parameters": t["inputSchema"]}} for t in tools]
msgs = [{"role": "system", "content": "SYS"}, {"role": "user", "content": "add milk"},
        {"role": "assistant", "content": "", "tool_calls": [{"id": "1", "function": {"name": "create_task", "arguments": {"title": "milk", "due": "2026-11-10"}}}]},
        {"role": "tool", "tool_call_id": "1", "content": "Added task 'milk'"},
        {"role": "assistant", "content": "Done."}]
ref = tpl.render(messages=msgs, tools=oa, bos_token="<bos>", add_generation_prompt=False)
mine = ("<bos><|turn>system\nSYS" + "".join(G.declaration(t) for t in tools) + "<turn|>\n<|turn>user\nadd milk<turn|>\n<|turn>model\n"
        + G.call("create_task", {"title": "milk", "due": "2026-11-10"}) + G.response("create_task", "Added task 'milk'") + "Done.<turn|>\n")
if ref == mine:
    print("MATCH", len(ref))
else:
    for i, (a, b) in enumerate(zip(ref, mine)):
        if a != b:
            print("DIFF at", i); print("ref :", ref[i-80:i+120]); print("mine:", mine[i-80:i+120]); break
    else:
        print("length differs", len(ref), len(mine)); print(ref[-200:]); print(mine[-200:])

# Golden file for the Kotlin test (app/src/test/.../ToolSyntaxTest.kt): what the template renders for the
# built-in tools and one call/response round trip.
if len(sys.argv) > 2 and sys.argv[2] == "--write-golden" and ref == mine:
    out = ROOT / "app/src/test/resources/tools/gemma_template_golden.txt"
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(ref, encoding="utf-8", newline="")
    print("wrote", out)

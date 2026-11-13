"""Tool definitions shared by the eval sets and (later) the app.

These mirror the in-process MCP servers planned for Phase 5. Kept small on purpose:
the tool router will only ever show the model a handful at a time.
"""

def _fn(name, desc, props, required):
    return {"type": "function", "function": {
        "name": name, "description": desc,
        "parameters": {"type": "object", "properties": props, "required": required}}}

S = {"type": "string"}

TOOLS = {
    "create_task": _fn("create_task", "Add a task to the user's task list.",
        {"title": S, "due": {"type": "string", "description": "ISO date or datetime, if the user gave one"}},
        ["title"]),
    "set_reminder": _fn("set_reminder", "Remind the user about something at a specific time.",
        {"text": S, "time": {"type": "string", "description": "ISO datetime"}}, ["text", "time"]),
    "search_mail": _fn("search_mail", "Search the user's email.",
        {"query": S, "from": {"type": "string", "description": "sender name or address"},
         "unread_only": {"type": "boolean"}}, ["query"]),
    "web_search": _fn("web_search", "Search the web for current or factual information.",
        {"query": S}, ["query"]),
    "get_calendar": _fn("get_calendar", "List the user's calendar events in a date range.",
        {"start": {"type": "string", "description": "ISO date"}, "end": {"type": "string", "description": "ISO date"}},
        ["start"]),
    "remember_fact": _fn("remember_fact", "Save a lasting fact about the user to memory.",
        {"fact": S}, ["fact"]),
    "draft_email": _fn("draft_email", "Write an email draft for the user to review. Never sends.",
        {"to": S, "subject": S, "body": S}, ["to", "body"]),
    "notion_search": _fn("notion_search", "Search the user's Notion workspace (remote connector).",
        {"query": S}, ["query"]),
}

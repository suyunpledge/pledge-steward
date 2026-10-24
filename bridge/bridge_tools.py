"""Host adapters for Forge's public ToolRegistry. No Agent monkey patches."""
import asyncio
import json
from forge.tools import ToolSpec, ToolResult
from forge.policy import ToolEffect

READ_TOOLS = {"android.screen.inspect", "android.screen.capture", "android.app.list", "android.device.info"}
WRITE_TOOLS = {"android.ui.click", "android.ui.swipe", "android.ui.input", "android.app.launch"}
SCHEMAS = {
    "android.ui.click": ({"x":{"type":"integer","minimum":0},"y":{"type":"integer","minimum":0}},["x","y"]),
    "android.ui.swipe": ({**{k:{"type":"integer","minimum":0} for k in ("x1","y1","x2","y2")},"duration_ms":{"type":"integer","minimum":50,"maximum":2000}},["x1","y1","x2","y2"]),
    "android.ui.input": ({"text":{"type":"string","maxLength":4000}},["text"]),
    "android.app.launch": ({"package":{"type":"string","pattern":r"^[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z][A-Za-z0-9_]*)+$"}},["package"]),
    "android.screen.capture": ({"max_side":{"type":"integer","minimum":320,"maximum":1440}},[]),
}

def register_android_tools(registry, names, dispatch, emit, loop, *, approval_available=False):
    for name in READ_TOOLS | WRITE_TOOLS:
        registry.unregister(name)
    for name in set(names) & (READ_TOOLS | WRITE_TOOLS):
        def invoke(args, ctx, name=name):
            # Host readiness complements, and never replaces, the core Policy.
            if name in WRITE_TOOLS and not approval_available:
                return ToolResult(False,error="Device input approval is unavailable",
                    meta={"execution_state":"not_started","error_code":"approval_unavailable"})
            future = asyncio.run_coroutine_threadsafe(dispatch(name,args,emit),loop)
            try:
                response = future.result(timeout=35)
                return ToolResult(bool(response.get("ok")),json.dumps(response.get("result",{})),
                    error=response.get("error") or "", meta={"execution_state":response.get("execution_state") or ("completed" if response.get("ok") else "unknown" if name in WRITE_TOOLS else "not_started")})
            except Exception as exc:
                future.cancel()
                return ToolResult(False,error=str(exc),meta={"execution_state":"unknown" if name in WRITE_TOOLS else "not_started"})
        properties,required=SCHEMAS.get(name,({},[]))
        registry.register(ToolSpec(name,"Host Android device adapter; device input requires one-time user approval",invoke,
            read_only=name in READ_TOOLS, schema={"type":"object","properties":properties,"required":required,"additionalProperties":False}),
            trusted=True,effect=ToolEffect.READ if name in READ_TOOLS else ToolEffect.DELEGATED_WRITE)

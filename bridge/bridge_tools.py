"""Host adapters for Forge's public ToolRegistry. No Agent monkey patches."""
import asyncio
import json
from forge.tools import ToolSpec, ToolResult
from forge.policy import ToolEffect

READ_TOOLS = {"android.screen.inspect", "android.screen.capture", "android.app.list", "android.device.info"}
WRITE_TOOLS = {"android.ui.click", "android.ui.swipe", "android.ui.input", "android.app.launch"}

def register_android_tools(registry, names, dispatch, emit, loop):
    for name in READ_TOOLS | WRITE_TOOLS:
        registry.unregister(name)
    for name in set(names) & (READ_TOOLS | WRITE_TOOLS):
        def invoke(args, ctx, name=name):
            # Until Kotlin's actual user-confirmation UI is implemented, no
            # device input is permitted, even if a caller configures allow=*.
            if name in WRITE_TOOLS:
                return ToolResult(False,error="Device input approval is not implemented",
                    meta={"execution_state":"not_started","error_code":"approval_unavailable"})
            future = asyncio.run_coroutine_threadsafe(dispatch(name,args,emit),loop)
            try:
                response = future.result(timeout=35)
                return ToolResult(bool(response.get("ok")),json.dumps(response.get("result",{})),
                    error=response.get("error") or "")
            except Exception as exc:
                future.cancel()
                return ToolResult(False,error=str(exc),meta={"execution_state":"not_started"})
        registry.register(ToolSpec(name,"Host Android device adapter",invoke,
            read_only=name in READ_TOOLS, schema={"type":"object","properties":{}}),
            trusted=True,effect=ToolEffect.READ if name in READ_TOOLS else ToolEffect.DELEGATED_WRITE)

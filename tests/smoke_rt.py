"""Offline Android bridge checks against the real Forge contracts."""
import asyncio
import importlib
import os
from pathlib import Path
import sys
import tempfile
import unittest
from types import SimpleNamespace

ROOT = Path(__file__).resolve().parents[2]
TEMP = tempfile.TemporaryDirectory()
os.environ.update(FORGE_ROOT=str(ROOT), FORGE_HOME=TEMP.name+'/home',
                  FORGE_WORKSPACE=TEMP.name+'/workspace', FORGE_BRIDGE_TOKEN='test-token-'+'a'*40)
sys.path[:0] = [str(ROOT), str(ROOT/'forge-android/bridge')]
from fastapi.testclient import TestClient
from forge.tools import ToolRegistry, ToolContext
from forge.policy import Policy, Mode
import forge_bridge as bridge
from bridge_tools import register_android_tools

class BridgeTests(unittest.TestCase):
    def setUp(self):
        self.client=TestClient(bridge.app)
        self.headers={'Authorization':'Bearer '+os.environ['FORGE_BRIDGE_TOKEN']}

    def test_http_auth_and_health(self):
        self.assertFalse(bridge.authorized('Bearer 秘密'))
        self.assertEqual(self.client.get('/health').status_code,200)
        self.assertEqual(self.client.post('/capabilities',json={'tools':[]}).status_code,401)
        result=self.client.post('/capabilities',json={'tools':[]},headers=self.headers)
        self.assertEqual(result.status_code,200)
        self.assertEqual(result.json()['registered'],[])

    def test_websocket_auth_and_session_path_validation(self):
        from starlette.websockets import WebSocketDisconnect
        for headers,path in (({},'/agent/ws'),(self.headers,'/agent/ws?session=../../escape')):
            with self.assertRaises(WebSocketDisconnect):
                with self.client.websocket_connect(path,headers=headers): pass

    def test_real_agent_can_be_constructed(self):
        agent=bridge._build_session_agent('real-construction')
        self.assertIsInstance(agent.registry,ToolRegistry)
        self.assertFalse(hasattr(agent,'attach_tools'))

    def test_authenticated_websocket_ready_ping_and_real_reset(self):
        with self.client.websocket_connect('/agent/ws?session=reset-check',headers=self.headers) as ws:
            self.assertEqual(ws.receive_json()['type'],'ready')
            original=bridge._sessions['reset-check']
            ws.send_json({'type':'ping'})
            self.assertEqual(ws.receive_json()['type'],'pong')
            ws.send_json({'type':'reset'})
            self.assertEqual(ws.receive_json()['type'],'reset_ok')
            self.assertIsNot(bridge._sessions['reset-check'],original)

    def test_real_report_shape_is_consumed(self):
        from contextlib import nullcontext
        async def run():
            events=[]
            async def emit(event):events.append(event)
            agent=SimpleNamespace(session=nullcontext(),run=lambda *a,**kw:SimpleNamespace(
                text='actual-answer',steps=[1,2],usage={'cost':0.5}))
            await bridge._run_agent(agent,'test',[],emit)
            self.assertEqual(events[-1]['type'],'run_done')
            self.assertEqual(events[-1]['answer'],'actual-answer')
            self.assertEqual(events[-1]['steps'],2)
        asyncio.run(run())

    def test_device_tools_use_main_loop_and_preserve_readonly(self):
        async def run():
            registry=ToolRegistry();calls=[]
            async def dispatch(name,args,emit):
                calls.append(name);return {'ok':True,'result':{'model':'fixture'}}
            register_android_tools(registry,{'android.device.info','android.ui.click'},dispatch,None,asyncio.get_running_loop())
            ctx=ToolContext(Policy(mode=Mode.READ_ONLY,workspace=Path(TEMP.name),allow=('*',)),Path(TEMP.name))
            try:
                result=await asyncio.to_thread(registry.invoke,'android.device.info',{},ctx)
                self.assertTrue(result.ok,result)
                denied=await asyncio.to_thread(registry.invoke,'android.ui.click',{},ctx)
                self.assertFalse(denied.ok)
                ctx.policy=Policy(mode=Mode.BYPASS,workspace=Path(TEMP.name))
                denied=await asyncio.to_thread(registry.invoke,'android.ui.click',{},ctx)
                self.assertEqual(denied.meta['error_code'],'approval_unavailable')
                self.assertEqual(calls,['android.device.info'])
            finally:ctx.secret_scope.close()
        asyncio.run(run())

if __name__=='__main__':
    try:unittest.main()
    finally:TEMP.cleanup()

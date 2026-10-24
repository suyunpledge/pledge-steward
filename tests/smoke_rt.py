"""Offline Android bridge checks against the real Forge contracts."""
import asyncio
import importlib
import os
from pathlib import Path
import sys
import tempfile
import unittest
from types import SimpleNamespace
from unittest.mock import patch
from contextlib import nullcontext
import threading,time,hashlib,json

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

    def test_host_capabilities_cannot_enable_unconfirmed_writes(self):
        for approval,expected in ((False,[]),(True,['android.ui.click'])):
            result=self.client.post('/capabilities',headers=self.headers,json={
                'tools':['android.ui.click','android.unknown'],'approval':approval})
            self.assertEqual(result.json()['registered'],expected)
        self.client.post('/capabilities',headers=self.headers,json={'tools':[]})

    def test_confirmed_host_does_not_bypass_readonly_and_unknown_is_retained(self):
        async def run():
            registry=ToolRegistry();calls=[]
            async def dispatch(name,args,emit):
                calls.append(name);return {'ok':False,'error':'receipt lost','execution_state':'unknown'}
            register_android_tools(registry,{'android.ui.click'},dispatch,None,asyncio.get_running_loop(),approval_available=True)
            ctx=ToolContext(Policy(mode=Mode.READ_ONLY,workspace=Path(TEMP.name),allow=('*',)),Path(TEMP.name))
            try:
                denied=await asyncio.to_thread(registry.invoke,'android.ui.click',{'x':1,'y':2},ctx)
                self.assertFalse(denied.ok);self.assertEqual(calls,[])
                ctx.policy=Policy(mode=Mode.BYPASS,workspace=Path(TEMP.name))
                result=await asyncio.to_thread(registry.invoke,'android.ui.click',{'x':1,'y':2},ctx)
                self.assertEqual(result.meta['execution_state'],'unknown')
                self.assertEqual(calls,['android.ui.click'])
            finally:ctx.secret_scope.close()
        asyncio.run(run())

    def test_websocket_run_remains_responsive_and_cancel_is_cooperative(self):
        class Worker:
            session=nullcontext()
            def run(self,*a,**kw):
                if not self.cancellation_event.wait(5): raise RuntimeError('test cancellation missing')
                return SimpleNamespace(text='',steps=[],usage={},stopped='cancelled')
        with patch.object(bridge,'_build_session_agent',return_value=Worker()):
            with self.client.websocket_connect('/agent/ws?session=cancel-check',headers=self.headers) as ws:
                self.assertEqual(ws.receive_json()['type'],'ready')
                ws.send_json({'type':'run','task':'bounded test worker'})
                started=ws.receive_json();self.assertEqual(started['type'],'run_started')
                self.assertTrue(started['run_id'])
                ws.send_json({'type':'reset'})
                self.assertEqual(ws.receive_json()['type'],'error')
                ws.send_json({'type':'run','task':'must reject duplicate'})
                self.assertEqual(ws.receive_json()['error'],'session busy')
                ws.send_json({'type':'ping'});self.assertEqual(ws.receive_json()['type'],'pong')
                ws.send_json({'type':'cancel'})
                self.assertEqual(ws.receive_json()['type'],'cancel_tools')
                self.assertEqual(ws.receive_json()['type'],'cancel_requested')
                finished=ws.receive_json();self.assertEqual(finished['execution_state'],'cancelled')
                self.assertEqual(finished['run_id'],started['run_id'])

    def test_duplicate_session_connection_is_rejected(self):
        from starlette.websockets import WebSocketDisconnect
        with self.client.websocket_connect('/agent/ws?session=exclusive-check',headers=self.headers) as ws:
            ws.receive_json()
            with self.assertRaises(WebSocketDisconnect):
                with self.client.websocket_connect('/agent/ws?session=exclusive-check',headers=self.headers):pass

    def test_run_failure_preserves_identity(self):
        async def run():
            events=[]
            async def emit(e):events.append(e)
            def fail(*a,**kw):raise RuntimeError('fixture failure')
            await bridge._run_agent(SimpleNamespace(session=nullcontext(),run=fail),'test',[],emit)
            self.assertEqual(events[-1]['type'],'run_failed')
            self.assertEqual(events[-1]['run_id'],events[0]['run_id'])
        asyncio.run(run())

    def test_disconnect_keeps_session_reserved_until_worker_exits(self):
        release=threading.Event()
        class Worker:
            session=nullcontext()
            def run(self,*a,**kw):
                release.wait(5)
                return SimpleNamespace(text='',steps=[],usage={},stopped='cancelled')
        from starlette.websockets import WebSocketDisconnect
        try:
            with TestClient(bridge.app) as client,patch.object(bridge,'_build_session_agent',return_value=Worker()):
                with client.websocket_connect('/agent/ws?session=disconnect-check',headers=self.headers) as ws:
                    ws.receive_json();ws.send_json({'type':'run','task':'wait for release'})
                    self.assertEqual(ws.receive_json()['type'],'run_started')
                with self.assertRaises(WebSocketDisconnect):
                    with client.websocket_connect('/agent/ws?session=disconnect-check',headers=self.headers):pass
                self.assertTrue(bridge._sessions['disconnect-check'].cancellation_event.is_set())
                release.set()
                for _ in range(100):
                    if 'disconnect-check' not in bridge._sessions_busy:break
                    time.sleep(.01)
                self.assertNotIn('disconnect-check',bridge._sessions_busy)
                with client.websocket_connect('/agent/ws?session=disconnect-check',headers=self.headers) as ws:
                    self.assertEqual(ws.receive_json()['type'],'ready')
        finally:release.set()

    def test_device_timeout_is_unknown_and_sends_cancellation(self):
        async def run():
            events=[]
            async def emit(event):events.append(event)
            async def timeout(future,timeout):
                future.cancel();raise asyncio.TimeoutError()
            with patch.object(bridge.asyncio,'wait_for',new=timeout):
                result=await bridge._dispatch_android_tool('android.ui.click',{'x':1,'y':2},emit)
            self.assertEqual(result['execution_state'],'unknown')
            self.assertEqual([e['type'] for e in events],['tool_call','cancel_tool'])
            self.assertEqual(events[0]['call_id'],events[1]['call_id'])
            self.assertNotIn(events[0]['call_id'],bridge._tool_waiters)
        asyncio.run(run())

    def test_tool_result_can_settle_only_once(self):
        async def run():
            future=asyncio.get_running_loop().create_future()
            bridge._tool_waiters['test-once']=future
            payload=bridge.ToolResultPayload(call_id='test-once',ok=False,execution_state='unknown')
            try:
                await bridge.tool_result(payload)
                self.assertEqual((await future)['execution_state'],'unknown')
                with self.assertRaises(bridge.HTTPException):await bridge.tool_result(payload)
            finally:bridge._tool_waiters.pop('test-once',None)
        asyncio.run(run())

    def test_sse_cannot_overlap_existing_session(self):
        bridge._sessions_busy.add('sse')
        try:self.assertEqual(self.client.post('/agent/run',headers=self.headers,json={'task':'must not start'}).status_code,409)
        finally:bridge._sessions_busy.discard('sse')

    def test_native_libraries_have_pinned_hashes_and_complete_elf_dependencies(self):
        scripts=ROOT/'forge-android/scripts';sys.path.insert(0,str(scripts))
        from native_elf import elf
        lock=json.loads((scripts/'native-host-lock.json').read_text())
        binaries=lock['binaries'];self.assertEqual(len(binaries),8)
        for name,record in binaries.items():
            data=(ROOT/'forge-android/app/src/main/jniLibs/arm64-v8a'/name).read_bytes()
            self.assertEqual(hashlib.sha256(data).hexdigest(),record['sha256'],name)
            self.assertFalse(set(elf(data)['needed'])-set(binaries)-{'libc.so','libm.so','libdl.so','liblog.so'},name)
        for name in ('libproot.so','libproot-loader.so','libbusybox.so'):
            self.assertNotEqual(binaries[name]['entry'],0,name)

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

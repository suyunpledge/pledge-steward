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
from bridge_tools import register_android_tools, device_result

class BridgeTests(unittest.TestCase):
    def setUp(self):
        self.client=TestClient(bridge.app)
        self.headers={'Authorization':'Bearer '+os.environ['FORGE_BRIDGE_TOKEN']}

    def test_device_receipt_is_strict_and_unknown_cannot_claim_success(self):
        for response in ({'ok':'false'}, {'ok':True,'execution_state':'not_started'},
                         {'ok':True,'result':[]}, {'ok':True,'execution_state':'running'}, {'ok':True,'error':'failed'}):
            result=device_result('android.ui.click', response)
            self.assertFalse(result.ok)
            self.assertEqual(result.meta['execution_state'],'unknown')
        result=device_result('android.ui.click', {'ok':True,'execution_state':'unknown'})
        self.assertFalse(result.ok)
        self.assertEqual(result.meta['execution_state'],'unknown')
        self.assertEqual(self.client.post('/tool/call',headers=self.headers,
            json={'call_id':'none','ok':'false'}).status_code,422)

    def test_invalid_device_arguments_never_reach_host(self):
        async def run():
            registry=ToolRegistry();calls=[]
            async def dispatch(*args):
                calls.append(args);return {'ok':True}
            register_android_tools(registry,{'android.ui.click','android.ui.input','android.screen.capture'},dispatch,None,
                asyncio.get_running_loop(),approval_available=True)
            ctx=ToolContext(Policy(mode=Mode.BYPASS,workspace=Path(TEMP.name)),Path(TEMP.name))
            try:
                for tool,args in [('android.ui.click',{'x':True,'y':2}),('android.ui.click',{'x':1.1,'y':2}),
                    ('android.ui.click',{'x':1,'y':2,'script':'bad'}),('android.ui.input',{'text':'a'*4001}),
                    ('android.screen.capture',{'max_side':0})]:
                    result=await asyncio.to_thread(registry.invoke,tool,args,ctx)
                    self.assertFalse(result.ok)
                    self.assertEqual(result.meta['execution_state'],'not_started')
                self.assertFalse(calls)
            finally:ctx.secret_scope.close()
        asyncio.run(run())

    def test_capability_revocation_after_registration_blocks_dispatch(self):
        async def run():
            registry=ToolRegistry();calls=[];available={'android.device.info'}
            async def dispatch(*args):calls.append(args);return {'ok':True}
            register_android_tools(registry,available,dispatch,None,asyncio.get_running_loop(),available=lambda:available)
            available.clear()
            ctx=ToolContext(Policy(mode=Mode.BYPASS,workspace=Path(TEMP.name)),Path(TEMP.name))
            try:
                result=await asyncio.to_thread(registry.invoke,'android.device.info',{},ctx)
                self.assertEqual(result.meta['error_code'],'capability_unavailable')
                self.assertFalse(calls)
            finally:ctx.secret_scope.close()
        asyncio.run(run())

    def test_cancelled_device_call_waits_for_host_receipt_without_replay(self):
        async def run():
            token=threading.Event();events=[]
            async def emit(event):
                events.append(event)
                if event['type']=='tool_call':token.set()
                elif event['type']=='cancel_tool':
                    await bridge.tool_result(bridge.ToolResultPayload(call_id=event['call_id'],ok=False,
                        execution_state='not_started',error='approval cancelled'))
            result=await bridge._dispatch_android_tool('android.ui.click',{'x':1,'y':2},emit,cancellation=token,
                identity={'run_id':'run-cancel'})
            self.assertEqual(result['execution_state'],'not_started')
            self.assertEqual([e['type'] for e in events],['tool_call','cancel_tool'])
            self.assertEqual(events[0]['run_id'],'run-cancel')
            self.assertNotIn(events[0]['call_id'],bridge._tool_waiters)
        asyncio.run(run())

    def test_cancel_without_device_receipt_is_bounded_unknown(self):
        async def run():
            token=threading.Event();events=[]
            async def emit(event):
                events.append(event)
                if event['type']=='tool_call':token.set()
            with patch.object(bridge,'CANCEL_RECEIPT_TIMEOUT',.02):
                result=await asyncio.wait_for(bridge._dispatch_android_tool('android.ui.click',{},emit,cancellation=token),1)
            self.assertEqual(result['execution_state'],'unknown')
            self.assertEqual(len(events),2)
        asyncio.run(run())

    def test_cancel_before_dispatch_and_closed_host_do_not_emit_input(self):
        async def run():
            token=threading.Event();token.set();events=[]
            async def emit(event):events.append(event)
            result=await bridge._dispatch_android_tool('android.ui.click',{},emit,cancellation=token)
            self.assertEqual(result['execution_state'],'not_started');self.assertFalse(events)
            async def closed(event):return False
            result=await bridge._dispatch_android_tool('android.ui.click',{},closed)
            self.assertEqual(result['error_code'],'host_disconnected')
        asyncio.run(run())

    def test_real_agent_device_roundtrip_persists_results_and_finishes(self):
        from forge.model import ModelRouter,Provider,Usage
        class Transport:
            count=0
            def complete(self,provider,model,messages,**options):
                self.count+=1
                reply='{"tool":"android.device.info","args":{}}' if self.count==1 else 'Device inspected successfully'
                return reply,Usage(prompt_tokens=1,completion_tokens=1,model=model,provider=provider.name)
        async def run():
            events=[];transport=Transport()
            agent=bridge._build_session_agent('device-roundtrip')
            agent.router=ModelRouter([Provider('fixture','http://127.0.0.1:1',default_model='fixture')],transport=transport)
            async def emit(event):
                events.append(event)
                if event['type']=='tool_call':
                    await bridge.tool_result(bridge.ToolResultPayload(call_id=event['call_id'],ok=True,
                        execution_state='completed',result={'model':'fixture-device'}))
            with patch.object(bridge,'_android_tools',{'android.device.info'}):
                await bridge._run_agent(agent,'Inspect my phone',[],emit)
            self.assertEqual(events[-1]['type'],'run_done',events[-1])
            self.assertEqual(events[-1]['execution_state'],'final',events[-1])
            self.assertEqual(transport.count,2)
            calls=[e for e in events if e['type']=='tool_call']
            self.assertEqual(len(calls),1)
            self.assertEqual(calls[0]['run_id'],events[0]['run_id'])
            journal=(bridge.HOME/'sessions/phone-device-roundtrip.jsonl').read_text(encoding='utf-8')
            self.assertIn('fixture-device',journal)
            self.assertIn('tool_completed',journal)
        asyncio.run(run())

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

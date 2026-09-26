import importlib.util, threading, time, json, urllib.request
spec=importlib.util.spec_from_file_location('guarded_server','runtime/guarded_server.py'); g=importlib.util.module_from_spec(spec); spec.loader.exec_module(g)
from termux_mcp import mcp_transport_http as h
print('guarded=',g.guarded_call(None,'image_read',{'input':'/sdcard/Download/Chatgpt/RDT_20260926_213742303386801333472365.jpg'})['content'][0]['type'])
print('HTTP_DISPATCH=', 'core.dispatch(session, method, params' in __import__('inspect').getsource(h.MCPRequestHandler._run_dispatch))
from http.server import ThreadingHTTPServer
srv=ThreadingHTTPServer(('127.0.0.1',18081),h.MCPRequestHandler); h.MCPRequestHandler.endpoint='/mcp'
t=threading.Thread(target=srv.serve_forever,daemon=True); t.start(); time.sleep(.2)
# This endpoint uses the same handler; initialize then tools/call.
def req(body,sid=None):
 r=urllib.request.Request('http://127.0.0.1:18081/mcp',data=json.dumps(body).encode(),headers={'Content-Type':'application/json','Accept':'application/json, text/event-stream'} | ({'Mcp-Session-Id':sid} if sid else {})); return urllib.request.urlopen(r,timeout=5)
resp=req({'jsonrpc':'2.0','id':1,'method':'initialize','params':{'protocolVersion':'2025-03-26','capabilities':{},'clientInfo':{'name':'diag','version':'1'}}}); sid=resp.headers.get('Mcp-Session-Id'); print('INIT_STATUS',resp.status,'SID',bool(sid)); print('INIT_BODY',resp.read().decode()[:300])
resp=req({'jsonrpc':'2.0','id':2,'method':'tools/call','params':{'name':'image_read','arguments':{'input':'/sdcard/Download/Chatgpt/RDT_20260926_213742303386801333472365.jpg'}}},sid); body=resp.read().decode(); print('CALL_STATUS',resp.status,'BODY_HAS_IMAGE', '"type": "image"' in body, 'BODY_LEN',len(body), 'DATA_MARKER', '"data":' in body)
srv.shutdown()

import importlib.util, threading, time, json, urllib.request
spec=importlib.util.spec_from_file_location('guarded_server','runtime/guarded_server.py'); g=importlib.util.module_from_spec(spec); spec.loader.exec_module(g)
from termux_mcp import mcp_transport_http as h
print('GUARDED_CONTENT_TYPE',g.guarded_call(None,'image_read',{'input':'/sdcard/Download/Chatgpt/RDT_20260926_213742303386801333472365.jpg'})['content'][0]['type'])
from http.server import ThreadingHTTPServer
srv=ThreadingHTTPServer(('127.0.0.1',18082),h.MCPRequestHandler); t=threading.Thread(target=srv.serve_forever,daemon=True); t.start(); time.sleep(.2)
def req(body,sid=None):
 headers={'Content-Type':'application/json','Accept':'application/json, text/event-stream'}
 if sid: headers['Mcp-Session-Id']=sid
 return urllib.request.urlopen(urllib.request.Request('http://127.0.0.1:18082/mcp',data=json.dumps(body).encode(),headers=headers),timeout=5)
resp=req({'jsonrpc':'2.0','id':1,'method':'initialize','params':{'protocolVersion':'2025-03-26','capabilities':{},'clientInfo':{'name':'diag','version':'1'}}}); sid=resp.headers.get('Mcp-Session-Id'); print('INIT',resp.status,bool(sid)); resp.read()
resp=req({'jsonrpc':'2.0','id':2,'method':'tools/call','params':{'name':'image_read','arguments':{'input':'/sdcard/Download/Chatgpt/RDT_20260926_213742303386801333472365.jpg'}}},sid); body=resp.read().decode(); print('CALL',resp.status,'IMAGE_CONTENT', '"type": "image"' in body,'DATA_LEN_GT_900K', '"data":' in body and len(body)>900000,'BODY_LEN',len(body))
srv.shutdown()

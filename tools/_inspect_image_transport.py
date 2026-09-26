import inspect
from runtime import guarded_server as g
from termux_mcp import mcp_transport_http as h
print('guarded_call=', inspect.signature(g.guarded_call))
print('HTTP_SOURCE_START')
print(inspect.getsource(h)[:14000])
r=g.guarded_call(None,'image_read',{'input':'/sdcard/Download/Chatgpt/RDT_20260926_213742303386801333472365.jpg'})
print('RESULT_TYPE',type(r).__name__)
print('RESULT_KEYS',sorted(r.keys()))
print('CONTENT_TYPES',[x.get('type') for x in r.get('content',[])])
for x in r.get('content',[]): print('ITEM',x.get('type'),sorted(x.keys()),'DATA_LEN',len(x.get('data','')))

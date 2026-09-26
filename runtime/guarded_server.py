#!/usr/bin/env python3
# MCP Control guarded server
from __future__ import annotations
import sys
import base64
import mimetypes
from pathlib import Path
import json
ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
from core.enforcer import check, record, request_approval_bundle, consume_approval_bundle
from core.policy import canonical_custom_path
from core.command_guard import authorize_command
from core.capability_map import capability_for_tool, secondary_for_tool, git_capability_for_command, file_capabilities_for_path, file_capabilities_for_params, file_scope
from termux_mcp import mcp_core, mcp_server

def _canonical_runtime_path(raw):
    if not isinstance(raw, str) or not raw.strip(): return Path('.').resolve()
    try: return canonical_custom_path(raw)
    except ValueError: return Path(raw).expanduser().resolve()

def _request_path(params):
    raw = params.get('path', '.') if isinstance(params, dict) else '.'
    return _canonical_runtime_path(raw)

def _file_decisions(name, params):
    d=file_capabilities_for_path(name,_request_path(params)); d.extend(file_capabilities_for_params(name,params)); return d
_original=mcp_core.call_tool
_original_tool_list=mcp_core.tool_list
_IMAGE_LIST_TOOL={'name':'image_list','description':'List image files currently present in a selected folder.','inputSchema':{'type':'object','properties':{'path':{'type':'string'}},'required':['path']}}
_IMAGE_READ_TOOL={'name':'image_read','description':'Read an image file and return actual MCP ImageContent.','inputSchema':{'type':'object','properties':{'input':{'type':'string'},'path':{'type':'string'}},'anyOf':[{'required':['input']},{'required':['path']}]}}

def _image_list_result(params):
    raw=params.get('path','') if isinstance(params,dict) else ''
    if not isinstance(raw,str) or not raw.strip(): return {'content':[{'type':'text','text':'MCP CONTROL: missing image folder path'}],'isError':True}
    folder=_canonical_runtime_path(raw)
    if not folder.is_dir(): return {'content':[{'type':'text','text':f'MCP CONTROL: image folder not found: {folder}'}],'isError':True}
    exts={'.png','.jpg','.jpeg','.webp','.gif','.bmp','.heic','.heif'}; images=[]
    for item in sorted(folder.iterdir(),key=lambda p:p.stat().st_mtime,reverse=True):
        try:
            if item.is_file() and item.suffix.lower() in exts: images.append({'name':item.name,'path':str(item),'size':item.stat().st_size,'modified':item.stat().st_mtime})
        except OSError: pass
    return {'content':[{'type':'text','text':json.dumps({'ok':True,'folder':str(folder),'images':images},ensure_ascii=False)}]}

def _image_mime(data, name):
    # Prefer actual file signature over filename; Android downloads can be mislabeled.
    if data.startswith(b'\x89PNG\r\n\x1a\n'): return 'image/png'
    if data.startswith(b'\xff\xd8\xff'): return 'image/jpeg'
    if data.startswith((b'GIF87a', b'GIF89a')): return 'image/gif'
    if data.startswith(b'BM'): return 'image/bmp'
    if len(data) >= 12 and data[:4] == b'RIFF' and data[8:12] == b'WEBP': return 'image/webp'
    if len(data) >= 12 and data[4:8] == b'ftyp' and data[8:12] in {b'heic',b'heix',b'hevc',b'hevx',b'mif1',b'msf1'}: return 'image/heic'
    mime=mimetypes.guess_type(name)[0] or 'application/octet-stream'
    return mime if mime.startswith('image/') else None

def _image_read_result(params):
    raw=(params.get('input') or params.get('path') or '') if isinstance(params,dict) else ''
    if not isinstance(raw,str) or not raw.strip(): return {'content':[{'type':'text','text':'MCP CONTROL: missing image input'}],'isError':True}
    path=_canonical_runtime_path(raw)
    if not path.is_file(): return {'content':[{'type':'text','text':f'MCP CONTROL: image not found: {path}'}],'isError':True}
    raw_data=path.read_bytes(); mime=_image_mime(raw_data,path.name)
    if not mime: return {'content':[{'type':'text','text':f'MCP CONTROL: not an image: {path}'}],'isError':True}
    data=base64.b64encode(raw_data).decode('ascii')
    return {'content':[{'type':'image','data':data,'mimeType':mime},{'type':'text','text':f'Image: {path.name}'}]}

def guarded_tool_list():
    result=_original_tool_list(); tools=list(result.get('tools',[]))
    for t in (_IMAGE_LIST_TOOL,_IMAGE_READ_TOOL):
        if not any(x.get('name')==t['name'] for x in tools): tools.append(t)
    return {'tools':tools}

def guarded_call(session,name,params,on_progress=None):
    if name=='image_list':
        decisions=['files.list',file_scope(_canonical_runtime_path(params.get('path','') if isinstance(params,dict) else ''))]
        for cap in decisions:
            if not check(cap).allowed: record(cap,f'mcp.tools/call:{name}','DENY'); return {'content':[{'type':'text','text':f'MCP CONTROL: access denied ({cap}); policy is active'}],'isError':True}
        for cap in decisions: record(cap,f'mcp.tools/call:{name}','ALLOW')
        return _image_list_result(params)
    if name=='image_read':
        raw=(params.get('input') or params.get('path') or '') if isinstance(params,dict) else ''
        decisions=['files.read',file_scope(_canonical_runtime_path(raw))]
        for cap in decisions:
            if not check(cap).allowed: record(cap,f'mcp.tools/call:{name}','DENY'); return {'content':[{'type':'text','text':f'MCP CONTROL: access denied ({cap}); policy is active'}],'isError':True}
        for cap in decisions: record(cap,f'mcp.tools/call:{name}','ALLOW')
        return _image_read_result(params)
    decisions=[capability_for_tool(name)]
    if name in {'run','terminal_run','terminal_send','session_run'} and isinstance(params,dict):
        command=params.get('cmd',params.get('command','')).strip(); gc=git_capability_for_command(command)
        if gc and gc not in decisions: decisions.append(gc)
    for extra in secondary_for_tool(name):
        if extra not in decisions: decisions.append(extra)
    if name in {'run','terminal_run','session_run'} and isinstance(params,dict):
        ok,reason=authorize_command(params.get('cmd',params.get('command','')))
        if not ok: record('dangerous.outside_allowlist',f'mcp.tools/call:{name}','DENY_COMMAND'); return {'content':[{'type':'text','text':f'MCP CONTROL: command denied: {reason}'}],'isError':True}
    for cap in _file_decisions(name,params):
        if cap not in decisions: decisions.append(cap)
    ask=[]
    for cap in decisions:
        d=check(cap)
        if d.allowed: continue
        if d.requires_approval: ask.append(cap); continue
        record(cap,f'mcp.tools/call:{name}','DENY'); return {'content':[{'type':'text','text':f'MCP CONTROL: access denied ({cap}); policy is active'}],'isError':True}
    if ask:
        ask=sorted(set(ask)); aid=params.get('approval_id') if isinstance(params,dict) else None; clean=dict(params) if isinstance(params,dict) else {}; clean.pop('approval_id',None)
        if aid:
            try: consume_approval_bundle(aid,ask,name,clean)
            except PermissionError as e: return {'content':[{'type':'text','text':f'MCP CONTROL: approval denied: {e}'}],'isError':True}
            for cap in ask: record(cap,f'mcp.tools/call:{name}','APPROVED_ALLOW')
        else:
            approval=request_approval_bundle(ask,name,clean)
            for cap in ask: record(cap,f'mcp.tools/call:{name}','ASK')
            return {'content':[{'type':'text','text':f"MCP CONTROL: approval required for {', '.join(ask)}; approval_id={approval['approval_id']}; expires_at={approval['expires_at']}"}],'isError':True}
    for cap in decisions: record(cap,f'mcp.tools/call:{name}','ALLOW')
    return _original(session,name,params,on_progress=on_progress)

mcp_core.call_tool=guarded_call
mcp_core.tool_list=guarded_tool_list
if __name__=='__main__': mcp_server.run_http(host='127.0.0.1',port=8081)

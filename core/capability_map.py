"""Single source of truth for MCP tool to permission capability mappings."""
TOOL_CAPABILITIES={"ls":"files.list","read":"files.read","search":"files.search","context":"files.context","history":"files.history","changes_list":"files.changes","write":"files.write","mkdir":"files.mkdir","run":"terminal.run","cancel":"terminal.cancel","session_start":"terminal.background","session_run":"terminal.run","session_poll":"terminal.poll","session_list":"terminal.list","session_kill":"terminal.kill","terminal_open":"terminal.run","terminal_run":"terminal.run","terminal_send":"terminal.run","terminal_read":"terminal.read","terminal_list":"terminal.list","terminal_close":"terminal.cancel","location":"device.location","camera_photo":"device.camera","screenshot":"device.camera","image_process":"device.camera","text_extract":"device.camera","sms_send":"device.sms","sms_inbox":"device.sms","clipboard_get":"device.clipboard","clipboard_set":"device.clipboard","notify":"device.notifications","toast":"device.notifications","tts_speak":"device.tts","share":"device.share","open_url":"network.internet","download":"network.internet","public_ip":"network.internet","weather":"network.internet","speedtest":"network.internet","qrcode":"network.internet","cloud_sync":"network.internet","delete":"dangerous.delete","process_kill":"dangerous.kill","smart_install":"dangerous.install","system_info":"terminal.process","health":"terminal.process","process_list":"terminal.process","cron_list":"terminal.process","cron_add":"terminal.background","cron_remove":"dangerous.kill","git_pr":"git.diff","diff":"git.diff"}
TOOL_SECONDARY_CAPABILITIES={"run":("mcp.execute",),"terminal_run":("mcp.execute",),"terminal_send":("mcp.execute",),"session_run":("mcp.execute",),"session_start":("mcp.long_running",),"session_poll":("mcp.read_output",),"terminal_read":("mcp.read_output",),"session_list":("mcp.process",),"terminal_list":("mcp.process",),"session_kill":("mcp.process",),"terminal_close":("mcp.long_running",),"write":("mcp.modify",),"mkdir":("mcp.create",),"delete":("mcp.delete",)}
FILE_TOOLS={"ls":"files.list","read":"files.read","search":"files.search","context":"files.context","history":"files.history","changes_list":"files.changes","write":"files.write","mkdir":"files.mkdir"}
GIT_COMMAND_CAPABILITIES={"git pull":"git.pull","git status":"git.status","git diff":"git.diff","git add":"git.add","git commit":"git.commit","git push":"git.push","git switch":"git.branch","git checkout":"git.branch","git branch":"git.branch"}
def capability_for_tool(name): return TOOL_CAPABILITIES.get(name,"dangerous.outside_allowlist")
def secondary_for_tool(name): return TOOL_SECONDARY_CAPABILITIES.get(name,())
def git_capability_for_command(command):
    for prefix,cap in GIT_COMMAND_CAPABILITIES.items():
        if command.strip().startswith(prefix): return cap
    return None
def file_scope(path):
    from pathlib import Path
    p=Path(path).expanduser().resolve(); h=Path.home()
    for root,cap in ((h/"po_recorder"/"data","files.repo_data"),(h/"po_recorder"/"tools","files.repo_tools"),(h/"po_recorder"/"reports","files.repo_reports"),(h/"po_recorder"/"tmp","files.repo_tmp"),(h/"tunnel-client-install","files.tunnel_install"),(Path("/sdcard"),"files.shared_storage"),(h/"po_recorder","files.repo"),(h/"mcp-control","files.control"),(h,"files.home")):
        root=root.resolve()
        if p==root or root in p.parents: return cap
    return "dangerous.outside_allowlist"
def file_capabilities_for_path(name,path):
    cap=FILE_TOOLS.get(name)
    return [] if not cap else [cap,file_scope(path)]


# UI/action catalog: every non-overview area exposes real MCP actions.
AREA_TOOL_ACTIONS={
    "files":("ls","read","search","context","history","changes_list","write","mkdir"),
    "git":("git_pr","diff","run"),
    "terminal":("run","cancel","session_start","session_run","session_poll","session_list","session_kill","terminal_open","terminal_run","terminal_send","terminal_read","terminal_list","terminal_close","system_info","health","process_list","cron_list","cron_add","cron_remove"),
    "network":("open_url","download","public_ip","weather","speedtest","qrcode","cloud_sync"),
    "mcp":("run","session_start","session_run","session_poll","session_list","session_kill","terminal_run","terminal_send","terminal_read","terminal_list","terminal_close"),
    "device":("location","camera_photo","screenshot","image_process","text_extract","sms_send","sms_inbox","clipboard_get","clipboard_set","notify","toast","tts_speak","share"),
    "dangerous":("delete","process_kill","smart_install","cron_remove"),
}

def tools_for_area(area):
    return AREA_TOOL_ACTIONS.get(area,())

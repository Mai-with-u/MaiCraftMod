# SPDX-License-Identifier: GPL-3.0-only
"""MaiCraft 内嵌 MCP 端点的直连调试 CLI。

绕过宿主（IDE / CLI 代理）自带的 MCP 客户端会话，直接以 Streamable HTTP
调用游戏内嵌端点，用于实机验收、会话失效后的手工排查，以及宿主工具层
对嵌套 JSON 参数序列化失真时的绕行。

用法：
  python tools/maicraft_cli.py init                                # 重新握手，打印服务器信息与使用指引
  python tools/maicraft_cli.py tools                               # 列出服务器发布的全部工具与一句话描述
  python tools/maicraft_cli.py tools observe                       # 打印某工具的完整描述与 inputSchema
  python tools/maicraft_cli.py resources                           # 列出服务器发布的资源（玩法技能等）
  python tools/maicraft_cli.py call observe '{"what":"self"}'      # 内联 JSON 参数
  python tools/maicraft_cli.py call execute @payload.json          # UTF-8 文件载荷（含中文时推荐）
  python tools/maicraft_cli.py --raw call lookup '{"topic":"abilities"}'  # 打印完整响应

工具名、参数与取值范围以 `tools` 命令现查为准；玩法技能等资源以
`resources` 命令现查、`read` 读取。工具的真实行为尚未接入时，
调用返回结构化的 not_ready 错误（isError=true、code=not_ready），这本身就是
传输与空壳分发的验收口径。

Windows 注意：Git Bash 里 curl/argv 会把中文按本地代码页发出造成乱码，
含非 ASCII 参数一律走 @file（UTF-8），本脚本内部全程 UTF-8 读写。

端点与会话缓存可用参数或环境变量覆盖：
  --endpoint / MAICRAFT_ENDPOINT            默认 http://127.0.0.1:8766/mcp
  --session-file / MAICRAFT_SESSION_FILE    默认放在系统临时目录
"""

from __future__ import annotations

import argparse
import email.message
import json
import os
import sys
import tempfile
import urllib.error
import urllib.request
from pathlib import Path

DEFAULT_ENDPOINT = "http://127.0.0.1:8766/mcp"
SESSION_EXPIRED_MARKERS = ("unknown or expired mcp session",)
PROTOCOL_VERSION = "2024-11-05"


def default_session_file() -> Path:
    return Path(tempfile.gettempdir()) / "maicraft_cli_session_id.txt"


class McpConnection:
    def __init__(self, endpoint: str, session_file: Path, timeout: float):
        self.endpoint = endpoint
        self.session_file = session_file
        self.timeout = timeout
        self.session_id: str | None = None
        self.next_id = 1

    def _post(self, payload: bytes, session_id: str | None) -> tuple[int, str, "email.message.Message"]:
        headers = {
            "Content-Type": "application/json; charset=utf-8",
            "Accept": "application/json, text/event-stream",
        }
        if session_id:
            headers["Mcp-Session-Id"] = session_id
        request = urllib.request.Request(self.endpoint, data=payload, headers=headers, method="POST")
        try:
            with urllib.request.urlopen(request, timeout=self.timeout) as response:
                return response.status, response.read().decode("utf-8", errors="replace"), response.headers
        except urllib.error.HTTPError as error:
            return error.code, error.read().decode("utf-8", errors="replace"), error.headers

    def _rpc(self, method: str, params: dict | None = None, notify: bool = False) -> tuple[int, str, dict[str, str]]:
        body: dict = {"jsonrpc": "2.0", "method": method}
        if not notify:
            body["id"] = self.next_id
            self.next_id += 1
        if params is not None:
            body["params"] = params
        payload = json.dumps(body, ensure_ascii=False).encode("utf-8")
        return self._post(payload, self.session_id)

    def initialize(self) -> dict:
        self.session_id = None
        status, text, headers = self._rpc(
            "initialize",
            {
                "protocolVersion": PROTOCOL_VERSION,
                "capabilities": {},
                "clientInfo": {"name": "maicraft-cli", "version": "1.0.0-rc1"},
            },
        )
        if status != 200:
            raise RuntimeError(f"initialize 失败：HTTP {status} {text[:300]}")
        session_id = headers.get("Mcp-Session-Id")
        if not session_id:
            raise RuntimeError("initialize 响应未携带 Mcp-Session-Id，端点可能不是 Streamable HTTP")
        self.session_id = session_id
        self.session_file.write_text(session_id, encoding="utf-8")
        result = json.loads(text).get("result", {})
        self._rpc("notifications/initialized", notify=True)
        return result

    @staticmethod
    def _session_expired(status: int, text: str) -> bool:
        haystack = text.lower()
        return status in (400, 404) and any(marker in haystack for marker in SESSION_EXPIRED_MARKERS)

    def call(self, method: str, params: dict) -> str:
        cached = self.session_file.read_text(encoding="utf-8").strip() if self.session_file.exists() else ""
        self.session_id = self.session_id or (cached or None)
        if self.session_id is None:
            self.initialize()
        for attempt in (0, 1):
            status, text, _ = self._rpc(method, params)
            if status == 200:
                return text
            if attempt == 0 and self._session_expired(status, text):
                self.initialize()
                continue
            raise RuntimeError(f"MCP 调用失败：HTTP {status} {text[:500]}")
        raise RuntimeError("unreachable")


def unwrap_result(raw_text: str) -> str:
    """默认输出：解包 JSON-RPC 外层与 content[0].text 的嵌套 JSON 并美化。"""
    try:
        envelope = json.loads(raw_text)
    except json.JSONDecodeError:
        return raw_text
    result = envelope.get("result")
    if not isinstance(result, dict):
        return json.dumps(envelope, ensure_ascii=False, indent=2)
    contents = result.get("content")
    if isinstance(contents, list) and contents and isinstance(contents[0], dict) and "text" in contents[0]:
        inner = contents[0]["text"]
        try:
            return json.dumps(json.loads(inner), ensure_ascii=False, indent=2)
        except json.JSONDecodeError:
            return inner
    return json.dumps(result, ensure_ascii=False, indent=2)


def first_line(text: str, limit: int = 100) -> str:
    """取多行描述里第一行非空内容，截到 limit 字，给列表视图用。"""
    for line in (text or "").strip().splitlines():
        line = line.strip()
        if line:
            return line[:limit]
    return ""


def main() -> int:
    parser = argparse.ArgumentParser(description="MaiCraft 内嵌 MCP 端点直连调试 CLI")
    parser.add_argument("--endpoint", default=None, help=f"MCP 端点（默认 {DEFAULT_ENDPOINT}，可用 MAICRAFT_ENDPOINT 覆盖）")
    parser.add_argument("--session-file", default=None, help="会话标识缓存文件路径")
    parser.add_argument("--raw", action="store_true", help="打印完整 JSON-RPC 响应，不解包")
    parser.add_argument("--timeout", type=float, default=120.0, help="单次 HTTP 超时秒数")
    sub = parser.add_subparsers(dest="command", required=True)
    sub.add_parser("init", help="重新握手，缓存会话并打印服务器信息与使用指引")
    tools_parser = sub.add_parser("tools", help="列出服务器发布的工具（tools/list）；给工具名则打印完整描述与 inputSchema")
    tools_parser.add_argument("name", nargs="?", default=None, help="工具名，如 observe / lookup / execute / goal / events")
    sub.add_parser("resources", help="列出服务器发布的资源（resources/list），如玩法技能")
    call_parser = sub.add_parser("call", help="调用 MCP 工具：call <tool> '<json-args>' | call <tool> @args.json")
    call_parser.add_argument("tool")
    call_parser.add_argument("args", nargs="?", default="{}", help="内联 JSON，或 @文件路径（UTF-8，推荐含中文时使用）")
    read_parser = sub.add_parser("read", help="读取 MCP 资源，如 skill://survival/survival-opening/SKILL.md")
    read_parser.add_argument("uri")
    args = parser.parse_args()

    if sys.stdout.encoding and sys.stdout.encoding.lower() not in ("utf-8", "utf8"):
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")

    endpoint = args.endpoint or os.environ.get("MAICRAFT_ENDPOINT", DEFAULT_ENDPOINT)
    session_file = Path(
        args.session_file
        or os.environ.get("MAICRAFT_SESSION_FILE")
        or default_session_file()
    )
    connection = McpConnection(endpoint, session_file, args.timeout)

    if args.command == "init":
        result = connection.initialize()
        print(f"session {connection.session_id} -> {session_file}")
        info = result.get("serverInfo", {})
        print(f"server {info.get('name')} {info.get('version')} | 协议 {result.get('protocolVersion')}")
        instructions = result.get("instructions")
        if instructions:
            print("--- 服务器使用指引 ---")
            print(instructions)
        return 0
    if args.command == "tools":
        raw = connection.call("tools/list", {})
        tools = json.loads(raw).get("result", {}).get("tools", [])
        if args.name:
            for tool in tools:
                if tool.get("name") == args.name:
                    print(f"工具 {tool.get('name')}")
                    print(tool.get("description") or "")
                    print("--- inputSchema ---")
                    print(json.dumps(tool.get("inputSchema", {}), ensure_ascii=False, indent=2))
                    return 0
            known = ", ".join(tool.get("name", "") for tool in tools)
            print(f"服务器没有名为 {args.name} 的工具；现有：{known}")
            return 1
        for tool in tools:
            print(f"{tool.get('name')}: {first_line(tool.get('description') or '')}")
        return 0
    if args.command == "resources":
        raw = connection.call("resources/list", {})
        resources = json.loads(raw).get("result", {}).get("resources", [])
        for resource in resources:
            print(f"{resource.get('uri')}: {first_line(resource.get('description') or '')}")
        return 0
    if args.command == "call":
        if args.args.startswith("@"):
            params = json.loads(Path(args.args[1:]).read_text(encoding="utf-8"))
        else:
            params = json.loads(args.args)
        raw = connection.call("tools/call", {"name": args.tool, "arguments": params})
    else:
        raw = connection.call("resources/read", {"uri": args.uri})

    print(raw if args.raw else unwrap_result(raw))
    return 0


if __name__ == "__main__":
    sys.exit(main())

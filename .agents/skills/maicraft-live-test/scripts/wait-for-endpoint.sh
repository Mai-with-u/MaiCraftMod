#!/usr/bin/env bash
# 等客户端 MCP 端点活过来：轻量 HTTP 探活循环，端口通了即返回（此后再做一次 init 握手）。
# 用法: wait-for-endpoint.sh <端点> [总预算秒（默认 720）]
# 端点必传（形如 http://127.0.0.1:<端口>/mcp），从各自 AGENTS.local.md 的端口表来，不在脚本里设默认。
# stdout 保持 ASCII：脚本的输出是机器管道，经不同代码页的终端/工具转手会乱码（本仓库实发）；
# 中文说明写在注释里，不写进输出。任何 HTTP 响应码（含 4xx）都算「端口已监听」；连接拒绝才算没起。超时退出码 1。
set -u
ENDPOINT="${1:?usage: wait-for-endpoint.sh <endpoint> [budget-seconds]}"
BUDGET="${2:-720}"
deadline=$(( $(date +%s) + BUDGET ))
while [ "$(date +%s)" -lt "$deadline" ]; do
  code=$(curl -s --max-time 2 -o /dev/null -w "%{http_code}" "$ENDPOINT" 2>/dev/null)
  if [ -n "$code" ] && [ "$code" != "000" ]; then
    echo "endpoint is up (HTTP $code); do the init handshake now"
    exit 0
  fi
  sleep 3
done
echo "timeout: endpoint not listening within ${BUDGET}s; check the process first, then read logs per crash-triage.md" >&2
exit 1

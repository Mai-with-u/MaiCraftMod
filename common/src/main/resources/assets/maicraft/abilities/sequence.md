# sequence — 按顺序做几件事

`steps` 里每一步是一个完整的目标（`ability`、`parameters`、`target`、`permissions`、`on_failure`），上一步结束才开始下一步。本能力自己没有参数，也不接受目标对象。

## 怎么写

```json
{"ability": "sequence", "steps": [
  {"ability": "travel", "target": {"kind": "position", "x": 120, "z": -40}},
  {"ability": "remember", "parameters": {"name": "新营地"}},
  {"ability": "chat", "parameters": {"message": "新营地记下了"}, "on_failure": "continue"}
]}
```

- `on_failure`：这一步没做成时 `stop`（默认）整件事停下，`continue` 接着做下一步。
- 许可往下传：步骤没写的许可字段沿用整件事的，写了的以步骤为准；`protected_landmarks` 两边合在一起。
- 步骤里还能再套 `sequence`，最多 3 层。
- 分不清先后时先 `execute(dry_run=true)` 看 `estimate.steps`：每一步按此刻身上和周围单独估（不假设前面几步已经做完），
  `ready` 不用离开、`nearby` 知道在哪走过去、`search` 要出去找、`blocked` 按这次的写法弄不到。手边就能做的、
  尤其是武器和工具排前面，要出门找的放后面。下达时的回复也带一份同样的估算（不等附近扫完，没看完的写 `unknown`）。

## 结果怎么读

- 全部做成是 `done`；一步都没做成是 `failed`；其余是 `partial`。
- `remaining` 列出没做成的步骤（带它自己的结论）和没开始的步骤。
- 每一步已经发生的变化都并进整件事的 `changes`。
- 做完一步、整件事还要接着做时，`step_finished` 事件可能带 `ready_now`：后面还没开始的步骤里此刻不用走路就能做的那几步
  （`step`、`ability`、`purpose`、`how`），消息末尾也说一句。例如砍完木头，后面做木剑那步的料就够了。
  角色不会自己挪先后；想提前做，就取消这件 `sequence`、把那步排前面重新下（做完的步骤照常算进结果）。
- 某一步要问你时整件事停下等回答，用 `goal(answer)` 回答的就是这一步的问题；暂停、取消作用于整件事。

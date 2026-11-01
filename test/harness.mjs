/**
 * dsh-watch 插件的离线夹具测试。
 *
 * 不启动 DSH：用假的 ctx / agents / sessions 把插件加载起来，验证
 *   - HTTP 接口与令牌鉴权
 *   - SSE 事件流（工具动作、回复、状态）
 *   - 指令下发（followup / steer）
 *   - 审批转发：手表允许 / 拒绝 / 超时 / 不在线时交还 Web UI
 * 跑法：node test/harness.mjs
 */
import assert from 'node:assert/strict'
import { EventEmitter } from 'node:events'
import { mkdtempSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'

process.env.DSH_HOME = mkdtempSync(join(tmpdir(), 'dsh-watch-test-'))

const PORT = 18765
const DISCOVERY_PORT = 18766
process.env.DSH_WATCH_TEST = '1'

// ---------------------------------------------------------------------------
// 假 ctx
// ---------------------------------------------------------------------------
class FakeCtx {
  constructor() {
    this.emitter = new EventEmitter()
    this.services = new Map()
    this.effects = []
  }
  on(name, cb, options) {
    this.emitter.on(name, cb)
    return () => this.emitter.off(name, cb)
  }
  emit(name, ...args) {
    for (const cb of this.emitter.listeners(name)) cb(...args)
  }
  waterfall(name, payload, inner) {
    const chain = [...this.emitter.listeners(name)]
    const next = () => (chain.shift() ?? inner)(payload, next)
    return next()
  }
  get(name) {
    return this.services.get(name)
  }
  provide(name, value) {
    this.services.set(name, value)
  }
  effect(fn) {
    const dispose = fn()
    this.effects.push(dispose)
    return dispose
  }
}

function makeSession(id, cwd = 'C:/work') {
  return {
    id,
    header: { cwd, seedLength: 0, version: 1, createdAt: Date.now() },
    events: [],
    requestHeader: () => undefined,
  }
}

function makeAgent(id, session, status = 'idle') {
  const calls = { followup: [], steer: [], cancel: [] }
  const agent = {
    id,
    session,
    status,
    options: {},
    followup(message) {
      calls.followup.push(message)
    },
    steer(message) {
      calls.steer.push(message)
    },
    cancel(cause) {
      calls.cancel.push(cause)
    },
    whenIdle: async () => {},
    calls,
  }
  return agent
}

const ctx = new FakeCtx()
const session = makeSession('session-test-1')
const agent = makeAgent('session-test-1', session)

const agents = {
  list: () => [agent],
  roots: () => [agent],
  get: (id) => (id === agent.id ? agent : undefined),
  create: async () => {
    throw new Error('离线夹具不做创建')
  },
  resume: async () => {
    throw new Error('离线夹具不做恢复')
  },
}
ctx.provide('agents', agents)
ctx.provide('sessions', { get: () => undefined, list: () => [session] })
ctx.provide('agentDefaultModel', { currentSelection: () => ({ provider: 'p', model: 'm' }) })

// ---------------------------------------------------------------------------
// 加载插件
// ---------------------------------------------------------------------------
const mod = await import('../plugin/index.js')
assert.equal(typeof mod.apply, 'function', '插件应导出 apply')

mod.apply(ctx, {
  port: PORT,
  bind: '127.0.0.1',
  token: 'test-token',
  approvalTimeoutMs: 1200,
  discoveryPort: DISCOVERY_PORT,
  pairingTtlMs: 60000,
})
await new Promise((resolve) => setTimeout(resolve, 250))

const base = `http://127.0.0.1:${PORT}`
const auth = { 'x-dsh-watch-token': 'test-token' }
const jget = async (path, headers = auth) => {
  const res = await fetch(base + path, { headers })
  return { status: res.status, body: await res.json().catch(() => ({})) }
}
const jpost = async (path, body, headers = auth) => {
  const res = await fetch(base + path, {
    method: 'POST',
    headers: { ...headers, 'content-type': 'application/json' },
    body: JSON.stringify(body),
  })
  return { status: res.status, body: await res.json().catch(() => ({})) }
}

const results = []
async function test(name, fn) {
  try {
    await fn()
    results.push({ name, ok: true })
    console.log(`  ok    ${name}`)
  } catch (error) {
    results.push({ name, ok: false, error })
    console.log(`  FAIL  ${name}: ${error.message}`)
  }
}

// ---------------------------------------------------------------------------
// 用例
// ---------------------------------------------------------------------------
await test('ping 不需要令牌', async () => {
  const { status, body } = await jget('/api/ping', {})
  assert.equal(status, 200)
  assert.equal(body.ok, true)
  assert.equal(body.name, 'dsh-watch')
})

await test('没有令牌访问 /api/state 返回 401', async () => {
  const { status } = await jget('/api/state', {})
  assert.equal(status, 401)
})

await test('内置网页客户端可访问', async () => {
  const res = await fetch(base + '/')
  assert.equal(res.status, 200)
  const text = await res.text()
  assert.match(text, /dsh-watch/)
})

await test('/api/state 带出会话与状态', async () => {
  const { status, body } = await jget('/api/state')
  assert.equal(status, 200)
  assert.equal(body.selected.id, 'session-test-1')
  assert.equal(body.selected.running, false)
  assert.ok(Array.isArray(body.events))
})

// SSE 客户端
const sse = await fetch(`${base}/api/events?token=test-token`)
const reader = sse.body.getReader()
const decoder = new TextDecoder()
let buffer = ''
const received = []
// 持续读流：把每帧解析后塞进 received（真机/浏览器也是这样一直读）。
const ssePump = (async () => {
  try {
    for (;;) {
      const { value, done } = await reader.read()
      if (done) break
      buffer += decoder.decode(value, { stream: true })
      let index
      while ((index = buffer.indexOf('\n\n')) >= 0) {
        const frame = buffer.slice(0, index)
        buffer = buffer.slice(index + 2)
        const dataLines = frame
          .split('\n')
          .filter((line) => line.startsWith('data:'))
          .map((line) => line.slice(5).trim())
        const eventLine = frame.split('\n').find((line) => line.startsWith('event:'))
        if (dataLines.length === 0) continue
        received.push({ event: eventLine ? eventLine.slice(6).trim() : 'message', data: JSON.parse(dataLines.join('\n')) })
      }
    }
  } catch {
    /* 主动 cancel 时会走到这里 */
  }
})()
{
  const deadline = Date.now() + 4000
  while (!received.some((item) => item.event === 'hello') && Date.now() < deadline) {
    await new Promise((resolve) => setTimeout(resolve, 20))
  }
}
assert.equal(received[0].event, 'hello')

await test('工具动作会推到 SSE', async () => {
  const before = received.length
  ctx.emit('session/event', session, {
    type: 'tool/call',
    data: { turn: 1, step: 1, callId: 'call-1', name: 'bash', arguments: '{"command":"Get-ChildItem -Force"}' },
  })
  await new Promise((resolve) => setTimeout(resolve, 120))
  const ev = received.slice(before).map((item) => item.data).find((item) => item.kind === 'tool')
  assert.ok(ev, '应收到 tool 事件')
  assert.equal(ev.name, 'bash')
  assert.match(ev.text, /Get-ChildItem/)
})

await test('助手回复会推到 SSE', async () => {
  const before = received.length
  ctx.emit('session/event', session, {
    type: 'assistant/message',
    data: {
      turn: 1,
      step: 1,
      message: { id: 'm1', role: 'assistant', content: [{ type: 'text', text: '我看了一下，问题在依赖。' }], source: { kind: 'model' } },
    },
  })
  await new Promise((resolve) => setTimeout(resolve, 120))
  const ev = received.slice(before).map((item) => item.data).find((item) => item.kind === 'assistant')
  assert.ok(ev)
  assert.match(ev.text, /依赖/)
})

await test('空闲时发指令走 followup', async () => {
  const { status, body } = await jpost('/api/prompt', { text: '看看现在的目录' })
  assert.equal(status, 200)
  assert.equal(body.mode, 'followup')
  assert.equal(agent.calls.followup.length, 1)
  const message = agent.calls.followup[0]
  assert.equal(message.role, 'user')
  assert.equal(message.content[0].text, '看看现在的目录')
  assert.equal(message.source.kind, 'user')
})

await test('运行中发指令走 steer', async () => {
  agent.status = 'running'
  const { body } = await jpost('/api/prompt', { text: '别删那个文件' })
  assert.equal(body.mode, 'steer')
  assert.equal(agent.calls.steer.length, 1)
  agent.status = 'idle'
})

await test('显式 mode=followup 时即便在跑也排队', async () => {
  agent.status = 'running'
  const { body } = await jpost('/api/prompt', { text: '排队一条', mode: 'followup' })
  assert.equal(body.mode, 'followup')
  assert.equal(agent.calls.followup.length, 2)
  agent.status = 'idle'
})

await test('空指令被拒绝', async () => {
  const { status } = await jpost('/api/prompt', { text: '   ' })
  assert.equal(status, 500)
})

// 审批：先追加一条 tool/call + approval/asked，模拟真实审计序列
function emitApprovalAsked(approvalId, callId = 'call-approve-1') {
  session.events.push({
    type: 'tool/call',
    data: { turn: 1, step: 1, callId, name: 'bash', arguments: '{"command":"Remove-Item -Recurse -Force build","sandbox_permissions":"danger-full-access"}' },
  })
  session.events.push({ type: 'approval/asked', data: { id: approvalId, toolName: 'bash', callId, reason: '需要写入工作区之外' } })
}

let approvalChainCalls = 0
const askApproval = () => {
  emitApprovalAsked('approval-1')
  const req = { agent, toolName: 'bash', callId: 'call-approve-1', reason: '需要写入工作区之外' }
  return ctx.waterfall('approval/request', req, () => {
    approvalChainCalls += 1
    return 'rejected'
  })
}

await test('审批请求会转给手表并带工具预览', async () => {
  const before = received.length
  const pending = askApproval()
  await new Promise((resolve) => setTimeout(resolve, 150))
  const approval = received.slice(before).filter((item) => item.event === 'approval').map((item) => item.data)
  assert.equal(approval.length, 1, '手表应收到一条审批卡片')
  assert.equal(approval[0].tool, 'bash')
  assert.match(approval[0].preview, /Remove-Item/)
  // 手表点允许
  const decided = await jpost('/api/approval', { id: approval[0].id, decision: 'allow' })
  assert.equal(decided.status, 200)
  const outcome = await pending
  assert.equal(outcome, 'allowed-once')
  assert.equal(approvalChainCalls, 0, '手表允许后就不该再问下一条应答者')
})

await test('手表拒绝返回 rejected', async () => {
  session.events.push({ type: 'approval/decided', data: { id: 'approval-1', outcome: 'allowed-once' } })
  emitApprovalAsked('approval-2', 'call-approve-2')
  const pending = ctx.waterfall(
    'approval/request',
    { agent, toolName: 'bash', callId: 'call-approve-2', reason: '第二条' },
    () => 'unavailable',
  )
  await new Promise((resolve) => setTimeout(resolve, 120))
  const card = [...received].reverse().find((item) => item.event === 'approval')
  await jpost('/api/approval', { id: card.data.id, decision: 'deny' })
  assert.equal(await pending, 'rejected')
})

await test('审批超时会交还下一条应答者', async () => {
  session.events.push({ type: 'approval/decided', data: { id: 'approval-2', outcome: 'rejected' } })
  emitApprovalAsked('approval-3', 'call-approve-3')
  const started = Date.now()
  const outcome = await ctx.waterfall(
    'approval/request',
    { agent, toolName: 'bash', callId: 'call-approve-3', reason: '第三条' },
    () => 'unavailable',
  )
  assert.equal(outcome, 'unavailable', '超时应落到下一条应答者')
  assert.ok(Date.now() - started >= 1000, '应当等满超时时间')
})

await test('SSE 断线后审批直接交还 Web UI', async () => {
  await reader.cancel()
  // 等服务端感知断开
  await new Promise((resolve) => setTimeout(resolve, 200))
  session.events.push({ type: 'approval/decided', data: { id: 'approval-3', outcome: 'unavailable' } })
  emitApprovalAsked('approval-4', 'call-approve-4')
  let delegated = 0
  const outcome = await ctx.waterfall('approval/request', { agent, toolName: 'bash', callId: 'call-approve-4' }, () => {
    delegated += 1
    return 'rejected'
  })
  assert.equal(outcome, 'rejected')
  assert.equal(delegated, 1)
})

await test('approvalMode=off 时完全不拦', async () => {
  // 新开一个实例，端口 +1
  const mod2 = await import('../plugin/index.js')
  const ctx2 = new FakeCtx()
  ctx2.provide('agents', { list: () => [], get: () => undefined })
  ctx2.provide('agentDefaultModel', { currentSelection: () => ({ provider: 'p', model: 'm' }) })
  mod2.apply(ctx2, { port: PORT + 1, bind: '127.0.0.1', token: 't2', approvalMode: 'off', discovery: false })
  await new Promise((resolve) => setTimeout(resolve, 250))
  let delegated = 0
  const outcome = await ctx2.waterfall('approval/request', { agent, toolName: 'bash' }, () => {
    delegated += 1
    return 'rejected'
  })
  assert.equal(outcome, 'rejected')
  assert.equal(delegated, 1)
  for (const dispose of ctx2.effects) await dispose?.()
})

// ---- 配对 / 自动发现 -------------------------------------------------------
const nearIp = await (async () => {
  const { networkInterfaces } = await import('node:os')
  for (const list of Object.values(networkInterfaces())) {
    for (const item of list || []) {
      if (item.family === 'IPv4' && !item.internal) return item.address
    }
  }
  return '127.0.0.1'
})()

await test('本机 /pair 页面能拿到 6 位配对码', async () => {
  const res = await fetch(`http://127.0.0.1:${PORT}/pair`)
  assert.equal(res.status, 200)
  const html = await res.text()
  const match = html.match(/>(\d)<\/span>/g)
  assert.ok(match && match.length === 6, '页面应有 6 个数字格')
})

let pairingCode
await test('错误配对码被拒绝', async () => {
  const res = await fetch(`http://127.0.0.1:${PORT}/api/pair`, {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ code: '000000' }),
  })
  assert.equal(res.status, 401)
  const body = await res.json()
  assert.equal(body.ok, false)
})

await test('正确配对码换到长期令牌', async () => {
  const page = await (await fetch(`http://127.0.0.1:${PORT}/pair`)).text()
  const digits = [...page.matchAll(/>(\d)<\/span>/g)].map((m) => m[1]).join('')
  assert.equal(digits.length, 6)
  const res = await fetch(`http://127.0.0.1:${PORT}/api/pair`, {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ code: digits }),
  })
  assert.equal(res.status, 200)
  const body = await res.json()
  assert.equal(body.ok, true)
  assert.equal(body.token, 'test-token', '应当下发长期令牌')
  pairingCode = digits
})

await test('配对码一次性：用过即失效', async () => {
  const res = await fetch(`http://127.0.0.1:${PORT}/api/pair`, {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ code: pairingCode }),
  })
  assert.equal(res.status, 401)
})

await test('配对码错误太多次会被限速', async () => {
  let limited = false
  for (let i = 0; i < 12; i += 1) {
    const res = await fetch(`http://127.0.0.1:${PORT}/api/pair`, {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ code: '000000' }),
    })
    if (res.status === 429) {
      limited = true
      break
    }
  }
  assert.equal(limited, true, '连错应该返回 429')
})

await test('UDP 广播能发现这台电脑', async () => {
  const { createSocket } = await import('node:dgram')
  const socket = createSocket('udp4')
  const found = await new Promise((resolve) => {
    let done = false
    const finish = (value) => {
      if (done) return
      done = true
      try {
        socket.close()
      } catch {
        /* ignore */
      }
      resolve(value)
    }
    socket.on('message', (message) => {
      try {
        const json = JSON.parse(message.toString('utf8'))
        if (json.t === 'dshwatch') finish(json)
      } catch {
        /* ignore */
      }
    })
    socket.bind(0, '0.0.0.0', () => {
      socket.setBroadcast(true)
      const probe = Buffer.from('DSHWATCH_DISCOVER')
      socket.send(probe, 0, probe.length, DISCOVERY_PORT, '127.0.0.1')
      socket.send(probe, 0, probe.length, DISCOVERY_PORT, nearIp)
    })
    setTimeout(() => finish(null), 1500)
  })
  assert.ok(found, '应该收到发现应答')
  assert.equal(found.port, PORT)
  assert.equal(typeof found.name, 'string')
  assert.equal(found.pairing, true)
})

// 收尾
for (const dispose of ctx.effects) await dispose?.()

const failed = results.filter((item) => !item.ok)
console.log(`\n${results.length - failed.length}/${results.length} 通过`)
if (failed.length > 0) {
  for (const item of failed) console.error(item.error)
  process.exit(1)
}

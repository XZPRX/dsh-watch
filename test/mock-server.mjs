/**
 * 假 DSH：只为了在没有真宿主的情况下把「手表端」跑通。
 * 起 dsh-watch 插件 + 造一个假会话，持续往外推工具动作，并每 20 秒发一次审批请求。
 *
 * 用法：node test/mock-server.mjs [port] [token]
 */
import { EventEmitter } from 'node:events'

const PORT = Number(process.argv[2] || 8765)
const TOKEN = process.argv[3] || 'mock-token'

class FakeCtx {
  constructor() {
    this.emitter = new EventEmitter()
    this.services = new Map()
    this.effects = []
  }
  on(name, cb, options) {
    if (options && options.prepend) this.emitter.prependListener(name, cb)
    else this.emitter.on(name, cb)
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

const session = {
  id: 'session-mock',
  header: { cwd: process.cwd(), createdAt: Date.now() },
  events: [],
  requestHeader: () => undefined,
}

const prompts = []
const agent = {
  id: 'session-mock',
  session,
  status: 'idle',
  followup(message) {
    prompts.push({ mode: 'followup', text: message.content?.[0]?.text })
    reply('收到：' + message.content?.[0]?.text)
  },
  steer(message) {
    prompts.push({ mode: 'steer', text: message.content?.[0]?.text })
    reply('插话收到：' + message.content?.[0]?.text)
  },
  cancel() {
    log('收到停止请求')
    push({ type: 'turn/end', data: { turn: 1, reason: { kind: 'cancelled' } } })
  },
  whenIdle: async () => {},
}

const ctx = new FakeCtx()
ctx.provide('agents', { list: () => [agent], roots: () => [agent], get: (id) => (id === agent.id ? agent : undefined) })
ctx.provide('agentDefaultModel', { currentSelection: () => ({ provider: 'mock', model: 'mock' }) })
ctx.provide('sessionTitle', { get: () => ({ title: '整理宿舍文件（假数据）' }) })

const log = (...args) => console.log('[mock]', ...args)

function push(ev) {
  ctx.emit('session/event', session, ev)
}

function textEvent(text) {
  push({
    type: 'assistant/message',
    data: { turn: 1, step: 1, message: { id: 'm' + Math.random(), role: 'assistant', content: [{ type: 'text', text }], source: { kind: 'model' } } },
  })
}

let callSeq = 0
function toolStep() {
  callSeq += 1
  const callId = 'call-' + callSeq
  const tools = [
    ['bash', { command: 'Get-ChildItem -Force | Select-Object Name,Length' }],
    ['read', { file_path: 'README.md' }],
    ['grep', { pattern: 'dsh-watch' }],
    ['bash', { command: 'git status --short' }],
  ]
  const [name, args] = tools[callSeq % tools.length]
  push({ type: 'turn/start', data: { turn: 1 } })
  push({ type: 'tool/call', data: { turn: 1, step: 1, callId, name, arguments: JSON.stringify(args) } })
  setTimeout(() => {
    push({
      type: 'tool/result',
      data: {
        turn: 1, step: 1,
        message: {
          id: 'tr' + callSeq, role: 'user',
          content: [{ type: 'tool-result', toolCallId: callId, content: [{ type: 'text', text: '列出 12 个条目，其中 3 个目录' }] }],
          source: { kind: 'tool', callId },
        },
      },
    })
  }, 900)
}

function reply(text) {
  setTimeout(() => textEvent(text), 400)
}

let approvalSeq = 0
function askApproval() {
  approvalSeq += 1
  const callId = 'call-approve-' + approvalSeq
  session.events.push({
    type: 'tool/call',
    data: { turn: 1, step: 1, callId, name: 'bash', arguments: JSON.stringify({ command: 'Remove-Item -Recurse -Force C:\\Windows\\Temp\\build', sandbox_permissions: 'danger-full-access' }) },
  })
  session.events.push({ type: 'approval/asked', data: { id: 'approval-' + approvalSeq, toolName: 'bash', callId, reason: '这条命令要删工作区之外的目录，需要提升权限' } })
  const outcome = ctx.waterfall('approval/request', { agent, toolName: 'bash', callId, reason: '这条命令要删工作区之外的目录，需要提升权限' }, () => 'unavailable')
  Promise.resolve(outcome).then((result) => {
    session.events.push({ type: 'approval/decided', data: { id: 'approval-' + approvalSeq, outcome: result } })
    log('审批结果', result)
    textEvent(result === 'allowed-once' ? '已拿到授权，继续执行。' : '没拿到授权，我换个做法。')
  })
}

const mod = await import('../plugin/index.js')
mod.apply(ctx, { port: PORT, bind: '0.0.0.0', token: TOKEN, approvalTimeoutMs: 90000 })
log(`假 DSH 已启动：http://127.0.0.1:${PORT}  token=${TOKEN}`)

toolStep()
setInterval(toolStep, 5000)
setTimeout(askApproval, 6000)
setInterval(askApproval, 45000)
setInterval(() => textEvent('我在看这个目录，稍等。'), 17000)

process.on('SIGINT', () => process.exit(0))

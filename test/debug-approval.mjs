// 单点调试：审批路径为什么会挂住
import { EventEmitter } from 'node:events'
import { mkdtempSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'

class FakeCtx {
  constructor() { this.emitter = new EventEmitter(); this.services = new Map(); this.effects = [] }
  on(name, cb) { this.emitter.on(name, cb); return () => this.emitter.off(name, cb) }
  waterfall(name, payload, inner) {
    const chain = [...this.emitter.listeners(name)]
    const next = () => (chain.shift() ?? inner)(payload, next)
    return next()
  }
  get(name) { return this.services.get(name) }
  provide(name, v) { this.services.set(name, v) }
  effect(fn) { const d = fn(); this.effects.push(d); return d }
}

process.env.DSH_HOME = mkdtempSync(join(tmpdir(), 'dsh-watch-debug-'))
const session = {
  id: 's1', header: { cwd: 'C:/w' }, events: [],
  requestHeader: () => undefined,
}
const agent = { id: 's1', session, status: 'idle', followup() {}, steer() {}, cancel() {} }
const ctx = new FakeCtx()
ctx.provide('agents', { list: () => [agent], roots: () => [agent], get: () => agent })
ctx.provide('agentDefaultModel', { currentSelection: () => ({ provider: 'p', model: 'm' }) })

const mod = await import('../plugin/index.js')
mod.apply(ctx, { port: 18770, bind: '127.0.0.1', token: 't', approvalTimeoutMs: 3000 })
await new Promise((r) => setTimeout(r, 200))

const sse = await fetch('http://127.0.0.1:18770/api/events?token=t')
const reader = sse.body.getReader()
const pump = (async () => { for (;;) { const { value, done } = await reader.read(); if (done) break; console.log('SSE RAW:', new TextDecoder().decode(value)) } })()
await new Promise((r) => setTimeout(r, 150))

session.events.push({ type: 'tool/call', data: { callId: 'c1', name: 'bash', arguments: '{"command":"x"}' } })
session.events.push({ type: 'approval/asked', data: { id: 'a1', toolName: 'bash', callId: 'c1', reason: 'r' } })

const timeout = new Promise((r) => setTimeout(() => r('TIMEOUT-5s'), 5000))
const pending = ctx.waterfall('approval/request', { agent, toolName: 'bash', callId: 'c1' }, () => 'next-answer')
console.log('pending created, type =', typeof pending, pending instanceof Promise)

await new Promise((r) => setTimeout(r, 200))
const res = await fetch('http://127.0.0.1:18770/api/approval', { method: 'POST', headers: { 'x-dsh-watch-token': 't', 'content-type': 'application/json' }, body: JSON.stringify({ id: 'a1', decision: 'allow' }) })
console.log('POST status', res.status, await res.text())

console.log('race:', await Promise.race([pending, timeout]))
await reader.cancel()
for (const d of ctx.effects) await d?.()

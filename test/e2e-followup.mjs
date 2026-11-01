/**
 * 真实 DSH 联调用的指令注入脚本（UTF-8 正确版）。
 * 用法：node test/e2e-followup.mjs <port> <token> "指令文本" [mode]
 * 作用：POST /api/prompt，然后轮询 /api/state 确认指令原样出现在事件流里。
 */
const port = Number(process.argv[2] || 8765)
const token = process.argv[3] || 'mock-token'
const text = process.argv[4] || '插话：这一轮结束以后告诉我现在几点。'
const mode = process.argv[5] || 'followup'
const base = `http://127.0.0.1:${port}`
const headers = { 'x-dsh-watch-token': token, 'content-type': 'application/json' }

const post = async (path, body) => {
  const res = await fetch(base + path, { method: 'POST', headers, body: JSON.stringify(body) })
  const json = await res.json().catch(() => ({}))
  if (!res.ok) throw new Error(`POST ${path} → ${res.status} ${JSON.stringify(json)}`)
  return json
}

const state = async () => (await fetch(`${base}/api/state?token=${encodeURIComponent(token)}`)).json()

const result = await post('/api/prompt', { text, mode })
console.log('prompt accepted:', JSON.stringify(result))

let ok = false
for (let i = 0; i < 60; i += 1) {
  const snapshot = await state()
  const hit = (snapshot.events || []).reverse().find((event) => event.kind === 'user' && event.text === text)
  if (hit) {
    ok = true
    console.log(`event echoed intact at seq=${hit.seq}: ${hit.text}`)
    break
  }
  await new Promise((resolve) => setTimeout(resolve, 1000))
}
if (!ok) {
  console.error('FAIL: 指令没有原样出现在事件流里（可能被编码破坏）')
  process.exit(1)
}
console.log('PASS: 中文指令端到端无损')

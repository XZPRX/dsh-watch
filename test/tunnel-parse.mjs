// 用真实的 8.8KB ssh 输出验证解析（含二维码 ANSI 和分片场景）
import { readFileSync } from 'node:fs'
const raw = readFileSync('out/tunnel-raw.bin', 'utf8')
const KNOWN = /https:\/\/[a-zA-Z0-9](?:[a-zA-Z0-9-]*[a-zA-Z0-9])?\.(?:trycloudflare\.com|loca\.lt|lhr\.life|localhost\.run|serveo\.net|ngrok\.io|ngrok-free\.app|ngrok-free\.dev|devtunnels\.ms)(?::\d+)?\/?[^\s"'<>]*/u
function parseTunnelUrl(text) {
  const value = String(text)
  const tagged = value.match(/tunneled with tls termination,?\s*(https:\/\/[^\s"']+)/i)
  if (tagged) {
    const candidate = tagged[1].replace(/[\u001b\u009b][[\]()#;?]*(?:[0-9]{1,4}(?:;[0-9]{0,4})*)?[0-9A-ORZcf-nqry=><]/g, '')
    if (KNOWN.test(candidate)) return candidate.replace(/[),.;]+$/, '')
  }
  const hit = value.match(KNOWN)
  return hit ? hit[0].replace(/[),.;]+$/, '') : ''
}

console.log('整块解析 ->', parseTunnelUrl(raw))
// 模拟分片：每 997 字节一块，复刻插件 onData 的「先解析再截断」逻辑
let buffered = '', found = '', trimmed = ''
for (let i = 0; i < raw.length; i += 997) {
  const chunk = raw.slice(i, i + 997)
  buffered += chunk
  const url = parseTunnelUrl(buffered)
  if (url && !found) found = url
  if (buffered.length > 32000) buffered = buffered.slice(-16000)
  // 对照：旧逻辑（先截断再解析）
  trimmed += chunk
  if (trimmed.length > 8000) trimmed = trimmed.slice(-4000)
}
console.log('分片解析 ->', found)
console.log('旧逻辑会拿到 ->', parseTunnelUrl(trimmed) || '(空)')
const ok = /^https:\/\/[a-z0-9]+\.lhr\.life$/.test(found)
console.log(ok ? 'PASS 解析正确' : 'FAIL')
process.exit(ok ? 0 : 1)

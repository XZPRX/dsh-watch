# dsh-watch · 手表远程遥控 DSH

把手表（Android 手表，圆屏/方屏都行）接到电脑上的 DeepSeek Harness：

- **发指令**：手表上打字 / 点预设词 / 新建会话，指令直接进电脑上的当前会话；
- **看动作**：模型每一次工具调用、工具结果、回复，SSE 实时推到手表，断线自动重连；
- **审批提权**：工具要提升权限时，卡片推到手表，点「允许 / 拒绝」即生效；超时自动交还 Web UI 弹窗。

> **English TL;DR** — A tiny companion for [DeepSeek Harness](https://github.com/deepseek-ai/deepseek-harness) that puts your running agent on an Android watch: live tool/action stream, prompt injection into the current session, on-watch approval of privilege escalations, LAN auto-discovery with 6-digit pairing, and a public-tunnel fallback when you're away from home. The watch app is a ~54 KB single-APK with **zero third-party libraries** (built with aapt2/javac/d8, no Gradle); the PC side is a zero-dependency DSH plugin.
>
> ```
> watch APK ──HTTP/SSE──▶ dsh-watch plugin (inside the DSH process)
>                           ├── ctx.on('approval/request')  → on-watch approval (prepended before Web UI)
>                           ├── ctx.on('session/event')     → live action stream
>                           └── agent.followup / steer      → prompts from the watch
> ```
>
> Status: personal project, tested on OPPO Watch (Wear OS-like Android 11, 378×496). Issues/PRs welcome. MIT.


电脑端是一个零依赖的 DSH 插件（只跑 Node 内建模块），手表端是一个 **32 KB** 的原生 Android APK（纯 framework，无 AndroidX、无第三方库）。

```
手表 APK ──HTTP/SSE──▶ dsh-watch 插件（跑在 DSH 进程里）
                          ├── ctx.on('approval/request')   ← 提权审批（prepend，先于 Web UI）
                          ├── ctx.on('session/event')      ← 工具/回复/回合实时流
                          └── agent.followup / steer       ← 手表指令进会话
```

## 目录

```
dsh-watch/
├── plugin/                 # 电脑端插件（ESM，零依赖）
│   ├── index.js            # HTTP + SSE + 审批应答者 + 指令转发 + 发现/配对
│   ├── client.js           # 桌面端「设置 → 手表远程」入口（dsh.client 半边）
│   ├── package.json        # dsh.bundle.patch + dsh.client 声明
│   ├── cordis.patch.yml    # 默认配置（端口/绑定/审批模式/发现/配对码有效期）
│   └── README.md
├── app/                    # 手表端 Android 工程（Java，无 Gradle）
│   ├── AndroidManifest.xml
│   ├── res/values/strings.xml
│   └── src/com/dsh/watch/*.java
├── tools/
│   ├── build.ps1           # aapt2 → javac → d8 → zipalign → apksigner（SDK/JDK 自动探测）
│   ├── install-plugin.ps1  # 把插件装进某个 DSH profile（带备份/回滚）
│   └── allow-firewall.ps1  # 一键放行 TCP 8765 + UDP 8766（需要管理员）
├── test/
│   ├── harness.mjs         # 插件离线夹具测试（21 项）
│   ├── mock-server.mjs     # 假 DSH，用来单独调手表 UI
│   └── overlay-headless.yml
└── out/                    # 构建产物：dsh-watch.apk / 签名密钥
```

## 一、装电脑端插件

### 方式 A：装到某个 profile（推荐，可回滚）

```powershell
# 默认装进 desktop profile；换 profile 用 -Profile web
pwsh -File tools\install-plugin.ps1
# 装完重启 DSH（桌面端：退出应用再打开；CLI：重启 dsh web）
```

脚本做的事：把 `plugin/` 复制到 `$DSH_HOME/profiles/<profile>/dsh-watch/`，并在该 profile 的
`cordis.patch.yml` 末尾追加一段 `- insert: id: dsh-watch ...`（追加前备份为
`cordis.patch.yml.bak-dshwatch`；重复执行不会重复插入）。

### 方式 B：作为包安装

```powershell
dsh plugin --profile desktop add link:C:/path/to/dsh-watch/plugin
```

### 方式 C：只给某一次运行（不动 profile）

```powershell
dsh --profile headless --patch C:\path\to\overlay-headless.yml "你的任务"
```

插件启动后会打印：

```
[dsh-watch] 配对码 123456（10 分钟内有效；电脑上打开 /pair 查看）
[dsh-watch] 监听 http://0.0.0.0:8765
[dsh-watch] 手表配对：电脑上打开 http://127.0.0.1:8765/pair 查看 6 位配对码
[dsh-watch] 局域网发现已开启（UDP 8766）
```

## 二、装手表端 APK 并配对（三步，不用敲 IP 和长令牌）

```powershell
pwsh -File tools\build.ps1            # 产物 out\dsh-watch.apk（约 44 KB）
adb install -r out\dsh-watch.apk
```

### 第一次配对

1. **电脑**：浏览器打开 `http://127.0.0.1:8765/pair`（只在本机可见），看到 **6 位配对码**
   和这台电脑的局域网地址；
2. **手表**：打开 DSH Watch → 首次先选**圆屏/方屏** → 「连接电脑」页会自动搜索同一 WiFi 下的电脑，
   点列表里那台；
3. 输入 6 位配对码 → 自动连接。之后一直免配，除非换电脑或删掉电脑上的 `dsh-watch.json`。

配对码是**一次性**的：每配对成功一次自动换新；连错 8 次会限速 1 分钟；页面只在 `127.0.0.1` 可读，
所以同一 WiFi 的其他人看不到。

### 搜不到电脑时（手动）

在「连接电脑 → 手动输入」里填：

- **服务器**：`电脑局域网IP:8765`；
- **配对码或令牌**：填 6 位配对码（会走配对流程），或老版本里 `~/.dsh/dsh-watch.json` 的 `token`。

也可以用 adb 直接预置（装机/批量部署用）：

```powershell
# 用配对码（推荐，一次跑完配对+连接）
adb shell am start -n com.dsh.watch/.MainActivity --es host 192.168.1.50:8765 --es pair 123456
# 或直接给令牌
adb shell am start -n com.dsh.watch/.MainActivity --es host 192.168.1.50:8765 --es token <令牌>
```

### 手表连不上电脑时

1. 手表和电脑要在同一个网络（手表 App 用 UDP 8766 广播找电脑；很多路由器开了「AP 隔离」会挡广播，
   这时用上面的手动输入）；
2. 电脑防火墙放行 8765 和 UDP 8766（`pwsh -File tools\allow-firewall.ps1` 一键放行，需管理员）；
3. `http://电脑IP:8765/api/ping` 在电脑浏览器里能打开，说明服务正常；
4. 只是 USB 联调、没有 WiFi：`adb reverse tcp:8765 tcp:8765`，服务器填 `127.0.0.1:8765`
   （这条转发只在 adb 连着时有效，且广播发现不走 USB）；
5. 屏幕上「⚙ → 连接中/重连中」可以看到具体错误。

## 二·五、离开局域网（外网）怎么连

要满足两件事：**手表自己有网**（WiFi 或蜂窝）、**电脑有公网入口**。公网入口有三种做法，
插件都支持，配好后手表会从 `/api/state` **自动学到**这个地址并存成备用端点 —— 离开局域网时
连不上原地址，会自动切过去，不用手动改设置。

### 方式 1：插件托管 ssh 隧道（免账号，推荐）

```yaml
# profile 的 cordis.patch.yml 里 dsh-watch 的 config
tunnel: ssh              # 用系统 ssh 跑 localhost.run 反向隧道
tunnelHost: localhost.run
tunnelPort: 22
```

启动后日志里会出现：

```
[dsh-watch] 外网地址：https://xxxxxxxx.lhr.life（ssh 反向隧道）—— 手表离开局域网会自动切换到它
```

这个地址每次重启会变（免费版），但手表会自动更新。首次连接要信任主机指纹（已自动 `-o StrictHostKeyChecking=no`）。

### 方式 2：自己已有隧道/反代

```yaml
remoteUrl: https://你的域名        # 优先级最高，插件不再自己起隧道
```

适合 Cloudflare Tunnel 命名隧道、frp、Tailscale Funnel、Nginx 反代等任何把 8765 暴露出去的方式。

### 方式 3：cloudflared（本机已装时）

```yaml
tunnel: cloudflared
# cloudflaredPath: C:\path\to\cloudflared.exe
```

### 方式 0：`tunnel: auto`（推荐）

```yaml
tunnel: auto     # 有 cloudflared 就用 Quick Tunnel，没有就退到 ssh
```

cloudflared 二进制可以直接复用 npm 上的包（不用去 GitHub 下）：

```powershell
# 解包后把 vendor\cloudflared-windows-amd64.exe 放到 ~/.dsh/dsh-watch-bin/cloudflared.exe
npm pack agentroam-cloudflared-win32-x64
```

插件按 `cloudflaredPath` → `~/.dsh/dsh-watch-bin/cloudflared.exe` → `PATH` 的顺序找它。
实测 cloudflared Quick Tunnel 比 localhost.run 稳（后者约 10 分钟会自己断）。

### 方式 4：手动隧道（插件起不来时的兜底）

```powershell
powershell -File tools\tunnel.ps1     # 打印 https://xxxx.lhr.life，并写入 ~/.dsh/dsh-watch-remote.txt
```

插件每 10 秒读一次 `~/.dsh/dsh-watch-remote.txt`，读到就当成外网地址上报给手表 ——
所以不需要改配置、也不需要重启。

> 隧道地址解析参考了 [`@restregular/dsh-pocket`](https://www.npmjs.com/package/@restregular/dsh-pocket) 的 `lib/tunnel.js`：
> 只认已知隧道域名白名单（`lhr.life` / `trycloudflare.com` / `loca.lt` …），并且 ssh 远程端口冲突时换随机端口重试。
> 想直接要手机端 UI 的话，那个项目和 [shaobeichen/dsh-pocket](https://github.com/shaobeichen/dsh-pocket) 都是成熟选择；本项目是给手表写的原生小包。

### 手机/手表侧

- 第一次可以先在局域网配对（6 位码），之后手表自动学会外网地址；
- 也可以直接用手动输入：服务器填 `https://xxxxxxxx.lhr.life`，配对码填 6 位数字（走配对流程）。

> 注意：`/pair` 页面只在电脑本机可见，公网访问它会被 403；外网配对只能靠 6 位码（限速 + 一次性 + 自动轮换）。
> 从公网看，除了 `/api/pair`（需要配对码），所有接口都要令牌。

### 关于 SSE 和被缓冲的隧道

Cloudflare 的快速隧道会**缓冲 SSE 响应**：握手（HTTP 200）能成功，但一个字节都不往下送
（实测：本地 SSE 0s 首帧、15s 心跳；经 trycloudflare 隧道 70 秒零字节）。
所以手表端做了双轨：

- 局域网 → SSE 实时推送；
- 外网隧道 → SSE 连续失败或长时间无数据后自动切**轮询**（每 2 秒拉 `/api/state`，按 `seq` 增量补事件，延迟约 2 秒）。

日志里会打印「事件流不可用，改用轮询同步」「事件流恢复，切回实时推送」。

### 验证过

- `ssh -R 80:127.0.0.1:8765 nokey@localhost.run` 起的隧道，公网 `/api/state` 返回真实会话；
- cloudflared Quick Tunnel + 手表走蜂窝网络（`rmnet_data0`、无 adb 转发）实测**能连上并滚动显示电脑上的实时动作**，
  走的就是「轮询」这条路径；前提是**手表自己要能上网**。

## 三、手表上能干什么

| 屏幕 | 操作 |
| --- | --- |
| 首次启动 | **先选圆屏 / 方屏**（圆屏把内容收进内切圆，方屏用满四角；设置页随时可改） |
| 主屏 | 顶部：状态点（灰=离线 / 绿=待命 / 橙=模型在跑）+ 会话标题；中间：实时动作流（`▸ bash · 命令`、`✓ 结果`、模型回复、你说的指令）；底部：`指令` / `审批 N` / `停` |
| 指令 | 输入框（手表的搜狗输入法可用）+ 预设词「继续 / 现在在干什么 / 总结一下 / 跑测试」+ `发送`（空闲开一轮、运行中排队）/ `插入`（立刻插话）/ `＋`（新会话） |
| 审批 | `提权审批 1/2`、工具名、原因、要执行的参数、倒计时「Ns 后自动转交电脑端弹窗」、`允许` / `拒绝` |
| 会话（左滑进入） | 实时 + 已保存会话列表，当前会话蓝框标出；点一下切换（冷会话自动 resume）；右上「＋ 新建」开新会话 |
| 设置 | 改服务器/令牌、切换圆屏/方屏、看诊断日志；**最下面是彩蛋**（像素画头像，按住缩小、松手弹性回弹，白色背景已抠成透明头像） |

### 手势

| 手势 | 作用 |
| --- | --- |
| **右滑** | 返回上一屏（等于 `←` / 返回键）；在主屏不动作，避免误退 |
| **左滑** | 进「会话」屏：新建 / 切换已有会话（主屏、指令屏、审批屏都可用） |

判定条件：横向位移超过屏宽 16%（且不少于 26dp）并明显大于竖向位移，才认作滑动，
所以纵向滚动事件列表不会误触。

审批机制：插件用 `prepend: true` 抢在 Web UI 之前应答 `approval/request`。
**只有手表连着（SSE 在线）时才接管**；手表不在线、超时（默认 60s）、回合被取消，
都会把请求交还给下一条应答者，所以不会把电脑端卡死。

## 四、配置

插件配置（`cordis.patch.yml` 里 `dsh-watch.config`）：

| 键 | 默认 | 说明 |
| --- | --- | --- |
| `port` | `8765` | 监听端口；被占用会自动顺延最多 10 个 |
| `bind` | `0.0.0.0` | 监听地址；只给本机用可改 `127.0.0.1` |
| `token` | 自动生成 | 配对令牌；留空则生成并持久化到 `$DSH_HOME/dsh-watch.json` |
| `approvalMode` | `watch` | `watch`=手表在线时由手表审批；`off`=只旁观，审批仍走 Web UI |
| `approvalTimeoutMs` | `60000` | 手表审批超时，超时交还 Web UI |
| `defaultCwd` | 空 | 新建会话用的工作目录，空则用 DSH 进程 cwd |
| `maxText` | `400` | 单条事件文本上限 |
| `discovery` | `true` | 是否响应手表的局域网广播（UDP） |
| `discoveryPort` | `8766` | 发现用的 UDP 端口（防火墙要放行） |
| `pairingTtlMs` | `600000` | 6 位配对码有效期；成功配对后自动换新 |
| `tunnel` | `off` | 外网隧道：`off` / `ssh`（localhost.run）/ `cloudflared` |
| `remoteUrl` | 空 | 自己已有隧道或反代时直接写死对外地址，优先级最高 |
| `tunnelHost` / `tunnelPort` / `tunnelUser` | `localhost.run` / `22` / `nokey` | ssh 隧道目标（换 pinggy 填 `a.pinggy.io` / `443`） |

## 五、HTTP 接口（自测 / 二次开发）

除 `/`、`/pair`、`/api/ping`、`/api/pair` 外都要带 `x-dsh-watch-token` 头或 `?token=`。

| 方法 | 路径 | 作用 |
| --- | --- | --- |
| GET | `/` | 内置网页客户端（手机/电脑浏览器直接用，功能同手表端） |
| GET | `/pair` | **配对页（只允许 127.0.0.1）**：6 位配对码 + 局域网地址 |
| GET | `/api/ping` | 健康检查（免令牌） |
| POST | `/api/pair` | `{code}` → `{token, host, port}`；免令牌、一次性、8 次/分钟限速 |
| GET | `/api/pairing` | 当前配对码与地址（要令牌，给内置网页客户端用） |
| GET | `/api/state` | 选中会话、会话列表、待审批、最近 40 条事件 |
| GET | `/api/sessions?all=1` | 会话列表（`all=1` 含持久化会话） |
| GET | `/api/events?since=N` | **SSE**：`hello` / `ev` / `approval` / `approval-resolved` / `selected` |
| POST | `/api/prompt` | `{text, mode?: "steer"|"followup", sessionId?}` |
| POST | `/api/approval` | `{id, decision: "allow"|"deny"}` |
| POST | `/api/select` | `{sessionId}` 切换会话（冷会话会 resume） |
| POST | `/api/new` | 新建会话 |
| POST | `/api/cancel` | 停止当前回合 |

**自动发现**：UDP 8766。手表 App 广播 `DSHWATCH_DISCOVER`，插件单播回复
`{t:"dshwatch", name, version, port, pairing, sessions, running}`（不带配对码/令牌）。

事件统一是 `{seq, t, sessionId, kind, text, ...}`，`kind` 取值：
`user` / `assistant` / `tool` / `tool-result` / `turn` / `status` / `approval` / `approval-done` / `info`。

## 六、开发与测试

```powershell
node test\harness.mjs        # 插件夹具测试：鉴权/SSE/指令/审批/配对/发现，21 项
node test\mock-server.mjs    # 假 DSH（8765，token=mock-token），专门调手表 UI
pwsh -File tools\build.ps1   # 重新打包 APK
```

手表端不依赖 Gradle：`aapt2 compile/link → javac → d8 → zipalign → apksigner` 全在
`tools/build.ps1` 里，离线可跑，产物就是 `out/dsh-watch.apk`。

## 七、安全

- 令牌等于你电脑上 DSH 的钥匙（能发指令、能审批提权）。**不要外发**，怀疑泄露就删
  `$DSH_HOME/dsh-watch.json` 里的 token 并重启 DSH 换新。
- 默认监听 `0.0.0.0`，同一局域网内谁拿到令牌谁就能用；只在可信网络开。只给自己用可改
  `bind: 127.0.0.1` 配合 `adb reverse` / SSH 隧道。
- 审批卡片显示的是工具名和参数摘要；不确定的命令直接拒绝。

## 八、排错

| 现象 | 处理 |
| --- | --- |
| `dsh-watch` 没加载 | 看 DSH 启动输出里有没有 `[dsh-watch] 监听 …`；profile 的 `cordis.patch.yml` 是否被改回；重启 DSH |
| 端口被占 | 插件会自动顺延到 8766…8775，看启动日志里的实际端口 |
| 手表一直「重连中」 | `adb reverse` 是否还在；LAN IP 是否变化；防火墙 |
| 审批不弹到手表 | 手表 App 是否在前台/连着（SSE 订阅在）；`approvalMode` 是否 `off`；超时后会自动转 Web UI |
| 事件不动但能发指令 | SSE 断了，App 每 20s 会重连；看设置页诊断 |

## 九、验证记录（真机 / 真 DSH 实测）

环境：一台 Android 11 手表（API 30 / 378×496 px / 320dpi = 189×248 dp，方屏），
DSH 桌面端运行时 **0.2.0-rc.2**（headless profile + 同一份插件做联调）。

| 验证项 | 方式 | 结果 |
| --- | --- | --- |
| 插件夹具测试 | `node test/harness.mjs` | 21/21 通过（鉴权、SSE、followup/steer、审批允许/拒绝/超时/离线交还、配对码、发现广播） |
| 手表安装与首启 | `adb install` + `am start` | 44 KB APK 安装启动正常，首启出现圆屏/方屏选择 |
| **6 位配对码换令牌** | 真机 + 真 DSH headless（`/api/pair`） | 手表用配对码连上后自动保存令牌并进入会话，电脑端日志 `配对成功（127.0.0.1），已下发令牌` |
| **自动发现** | 插件侧 UDP 探针（夹具测试） | 广播 `DSHWATCH_DISCOVER` → 收到 `{t:"dshwatch", port, pairing:true}`；手表侧需真实 WiFi（本机手表当前未联网，未实测） |
| 实时动作流 | 真 DSH headless 跑 3 步任务 | 手表同步显示 `▸ pwsh Get-ChildItem …` → `✓ 结果` |
| 手表发指令（中文） | `POST /api/prompt`（`test/e2e-followup.mjs`） | 指令原样进入会话并在手表回显：`你 · 插话：这一轮结束以后…`，模型随后执行并回复 |
| 手表审批提权 | 任务第 3 步写工作区外文件 | 手表弹「提权审批 / pwsh / 原因 / 倒计时」，点「允许」后文件写入成功（读回 `hello`） |
| 会话切换 / 新建 | 左滑会话屏 | 列表标出当前会话；点选与「＋ 新建」都能切换并清空旧事件 |
| 右滑返回 | 会话屏右滑 | 回到主屏 |
| **桌面端实跑（重启后）** | 桌面 App 加载插件 + 手表 USB 连接 | `0.0.0.0:8765 LISTENING`，手表实时显示当前会话的 `netstat` / `Invoke-WebRequest` 等动作，标题同步 |
| **桌面端审批（与 Web UI 并存）** | 触发一次 `danger-full-access` 提权 | 手表弹审批卡（工具 `pwsh`、原因、60s 倒计时），点「允许」后命令执行、文件写入成功；Web UI 未被同时弹窗 |

联调日志与截图只留在开发机本地，不随仓库分发。

> 注：profile 补丁只在 **DSH 启动时**装配，装完插件必须重启一次桌面端；重启后手表会自动重连（实测 1 秒内）。


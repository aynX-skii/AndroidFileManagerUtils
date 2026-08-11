# FileBridge 传输协议规范 v1

本文档是 **实现 Linux 客户端的唯一依据**。所有内容都与 Android 端现有实现严格对应，
括号中标注了对应的源码位置。

传输层只用两样东西：

| 用途 | 协议 | 默认端口 |
|------|------|----------|
| 设备发现 | UDP 广播 | 8766 |
| 文件传输 | HTTP/1.1（明文） | 8765 |

设计原则：**协议是对称的**。手机和 PC 实现的是同一套接口，谁当服务端由场景决定
（手机收文件 → 手机当服务端；手机推文件到 PC → PC 当服务端）。因此 Linux 端最终
要实现两半：客户端（`ls` / `get` / `put`）和服务端（`serve`）。

---

## 1. 设备发现（UDP 8766）

### 1.1 探测包

客户端向所有接口的广播地址发送一个 UDP 包，载荷为纯文本：

```
AFMU-DISCOVER/1\n
```

接收端只检查**前缀** `AFMU-DISCOVER`（[Discovery.kt:PROBE_PREFIX](../app/src/main/java/com/aynux/afmu/core/Discovery.kt)），
后面的版本号目前被忽略，可用于以后扩展。

广播地址来源：遍历所有 up 且非 loopback 的接口，取其 broadcast 地址；再追加
`255.255.255.255` 作为兜底。Linux 下可用 `ioctl(SIOCGIFBRDADDR = 0x8919)` 获取，
无需第三方库。

### 1.2 应答包

每个在线设备回一个 UDP 包，载荷是单行 JSON：

```json
{"afmu": 1, "name": "Pixel 8", "os": "android", "port": 8765}
```

| 字段 | 类型 | 说明 |
|------|------|------|
| `afmu` | int | 协议版本，当前恒为 `1`。字段缺失 → 判定为非本协议设备，丢弃 |
| `name` | string | 设备显示名，用户可在 App 内修改 |
| `os` | string | `android` 或 `linux`，用于在结果里区分手机和 PC |
| `port` | int | **实际** HTTP 端口，不一定是 8765，见 §2.1 |

**应答里绝不包含 token。** 发现是公开的，鉴权是另一回事。

### 1.3 实现要点

- 应答用 `sendto()` 直接回到探测包的源地址和源端口，不要回广播。
- 服务端 socket 必须设置 `SO_REUSEADDR`，否则重启时 8766 会被 TIME_WAIT 占住。
- 客户端应过滤掉**自己**的 IP：如果本机也在监听 8766，广播会把自己的应答也收回来
  （Android 端就是这么处理的）。
- App 内可关闭"可被发现"开关；关闭后收到探测包直接忽略，不应答。
- 收集应答时按 `host:port` 去重，同一台设备多网卡会答多次。
- 建议超时 1.0–2.0 秒，采用"边收边等"而非固定 sleep。

### 1.4 定向探测

广播被 AP 隔离拦截时，可以对已知 IP 单播同样的探测包（Android 端的
`Discovery.probeHost()`）。收到应答即可确认对方端口和名字，无需用户输入端口。

---

## 2. HTTP 接口（TCP 8765）

### 2.1 端口选择

服务端依次尝试绑定 `8765`、`8766`、`8767`，全失败则绑定随机空闲端口
（[HttpServer.kt `bind()`](../app/src/main/java/com/aynux/afmu/core/HttpServer.kt)）。

> **因此客户端不能硬编码 8765**，必须以发现应答里的 `port` 为准；
> 只有用户手动输入地址时才回退到默认值。

### 2.2 鉴权

除 `GET /` 外，所有 `/api/*` 请求都必须带 token。三种等价写法，按此顺序取第一个非空值：

1. `X-AFMU-Token: <token>` ← **推荐**
2. 查询参数 `?token=<token>` ← 供浏览器 `<a href>` 直接下载用
3. `Authorization: Bearer <token>`

token 是 10 位小写字母数字（去掉了 `i l o 0 1` 等易混字符），由手机生成、显示在 App 首页，
用户手抄到 PC。比较使用常数时间算法。

失败返回：

```
HTTP/1.1 401 Unauthorized
Content-Type: application/json; charset=utf-8

{"ok": false, "error": "invalid or missing token"}
```

#### 失败退避（服务端必须实现）

token 有约 49 bit 熵，够用——**但前提是猜错要付出代价**。
没有这一层时，同一个地址连打 200 次错误 token 只花 0.78 秒且毫无惩罚，
爆破成本只剩带宽。

按**来源 IP** 计数，参数两端必须一致：

| 项 | 值 | 理由 |
|------|------|------|
| 宽限次数 | 前 `5` 次失败不惩罚 | token 是手抄的，打错很正常 |
| 退避时长 | 第 `5+n` 次失败后封 `min(2ⁿ⁻¹, 60)` 秒 | 1, 2, 4, 8, 16, 32, 60, 60… |
| 遗忘窗口 | `15` 分钟内没有新的失败就清零 | 别让一次误操作留一整天 |
| 成功即清零 | 任何一次校验通过都删掉该地址的记录 | 只惩罚**连续**失败；猜不中的人永远清不了零 |

封禁期内的 `/api/*`：

```
HTTP/1.1 429 Too Many Requests
Retry-After: 8
Content-Type: application/json; charset=utf-8

{"ok": false, "error": "too many failed attempts, retry in 8s"}
```

两条实现要求：

- **封禁期内不要比对 token**，直接回 429。比对本身是常数时间的，
  但「有没有走到比对」是可观测的，挡在门外最干净。
- **惩罚必须是立即回 429，不能是 sleep。** 占住线程正是攻击者想要的，
  而 Linux 端跑在单个事件循环里，睡一下等于全站停摆。

客户端要把 `429` 和 `401` 区别对待：`401` 是「token 不对，去改配置」，
`429` 是「等 `Retry-After` 秒再来，token 可能是对的」。

> 协议是明文 HTTP，token 只防同一局域网内的误连和顺手翻看，**不是**对抗嗅探的安全边界。
> 不要在不可信网络（公共 Wi-Fi、咖啡厅）上开启服务。

### 2.3 通用约定

- **HTTP/1.1**，必须发 `Content-Length`（不支持无长度的响应体）。
- 成功响应体统一含 `"ok": true`；失败统一为 `{"ok": false, "error": "<人类可读原因>"}`。
  客户端应先看 `ok` 再看 HTTP 状态码。
- 所有路径参数是**目标设备上的绝对文件系统路径**（如 `/storage/emulated/0/DCIM`），
  经过 URL 编码。空字符串或 `/` 是特殊值，表示"根列表"，见 §3.2。
- `+` 不被解释为空格；空格必须编码为 `%20`。
- 响应带 `Cache-Control: no-store`。
- 带请求体的请求，服务端处理完后会发 `Connection: close`（避免流位置错乱），
  客户端不要指望在上传后复用连接。GET 请求支持 keep-alive。
- **拒绝一个还没读请求体的请求时，也必须发 `Connection: close`**（401 / 403 / 405 等）。
  否则剩下的请求体会被当成流水线里的下一个请求解析出一堆乱七八糟的 400。
  服务端如果选择把请求体读完丢弃（drain）再复用连接，则要给丢弃量设上限。
- 服务端 socket 超时 120 秒；长时间无数据的连接会被断开。

---

## 3. 接口清单

### 3.1 `GET /api/info` — 设备信息

```json
{
  "ok": true,
  "name": "Pixel 8",
  "os": "android",
  "androidRelease": "15",
  "sdk": 35,
  "protocol": 1,
  "writable": true,
  "fullStorageAccess": true,
  "inbox": "/storage/emulated/0/Download/FileBridge",
  "roots": [
    {"name": "Internal storage", "path": "/storage/emulated/0"},
    {"name": "App folder (always writable)", "path": "/storage/emulated/0/Android/data/com.aynux.afmu/files"}
  ]
}
```

| 字段 | 说明 |
|------|------|
| `writable` | 为 `false` 时 upload / mkdir / delete 一律返回 403 |
| `fullStorageAccess` | Android 特有。为 `false` 时 `roots` 只有 App 私有目录 |
| `inbox` | 未指定 `dir` 的上传落到哪里 |
| `roots` | 可浏览的根目录列表，等价于 `GET /api/list?path=/` 的结果 |

Linux 服务端应至少返回 `ok` / `name` / `os` / `protocol` / `writable` / `inbox` / `roots`；
`fullStorageAccess`、`sdk` 等 Android 专有字段可省略，客户端必须容忍缺失。

### 3.2 `GET /api/list` — 列目录

| 参数 | 必填 | 说明 |
|------|------|------|
| `path` | 否 | 绝对路径。**省略或传 `/` 时返回根目录列表** |

```json
{
  "ok": true,
  "path": "/storage/emulated/0/DCIM",
  "parent": "/storage/emulated/0",
  "entries": [
    {"name": "Camera", "path": "/storage/emulated/0/DCIM/Camera", "dir": true,  "size": 0,      "mtime": 1754400000},
    {"name": "a.jpg",  "path": "/storage/emulated/0/DCIM/a.jpg",  "dir": false, "size": 238411, "mtime": 1754399000}
  ]
}
```

- `parent`：根列表时为 `null`；当父目录已在允许范围外时为 `"/"`（即回到根列表）。
- `mtime`：**Unix 秒**（不是毫秒）。
- `size`：目录恒为 `0`。
- 排序：目录在前，然后按文件名小写升序。
- 路径不存在 / 不是目录 / 越界 → `404` + `{"ok":false,...}`。

### 3.3 `GET|HEAD /api/download` — 下载

| 参数 | 必填 | 说明 |
|------|------|------|
| `path` | 是 | 文件绝对路径 |

响应头：

```
Content-Type: <按扩展名推断，未知为 application/octet-stream>
Content-Length: <字节数>
Accept-Ranges: bytes
Content-Disposition: attachment; filename="a.jpg"; filename*=UTF-8''a.jpg
Last-Modified: <RFC 1123 GMT>
```

**断点续传**：支持 `Range: bytes=<start>-[<end>]` 和 `Range: bytes=-<suffix>`。

- 命中 → `206 Partial Content` + `Content-Range: bytes <start>-<end>/<total>`
- 越界 → `416 Range Not Satisfiable` + `Content-Range: bytes */<total>`，无响应体
- 只支持**单区间**；多区间请求只取第一段

`HEAD` 返回完全相同的头、无响应体，可用来取大小和校验存在性。

文件名解析优先用 `filename*=UTF-8''<pct-encoded>`（中文文件名走这条），
回退到 `filename="..."`。

### 3.4 `POST|PUT /api/upload` — 上传

| 参数 | 必填 | 说明 |
|------|------|------|
| `name` | 原始体时必填 | 目标文件名；会被服务端做安全化处理（§4.2） |
| `dir` | 否 | 目标目录绝对路径。**省略、越界或不可写时落到 `inbox`** |
| `overwrite` | 否 | `1` = 覆盖同名文件；否则自动改名为 `a (1).txt` |

支持两种请求体：

**A. 原始字节流（客户端首选）**

```
POST /api/upload?name=clip.mp4&dir=%2Fstorage%2Femulated%2F0%2FMovies HTTP/1.1
X-AFMU-Token: abc123xyz9
Content-Type: application/octet-stream
Content-Length: 734003200

<raw bytes>
```

也接受 `Transfer-Encoding: chunked`（大小未知时用，例如从管道读）。
二者必居其一，都没有 → 500。

**B. `multipart/form-data`**（浏览器表单、`curl -F` 用）

服务端流式解析，逐段直接落盘，不在内存里缓冲整个文件。带 `filename` 的段才会被保存，
普通表单字段被丢弃。单次请求可含多个文件。

**响应（两种体一致）**

```json
{"ok": true, "saved": ["/storage/emulated/0/Movies/clip.mp4"]}
```

`saved` 是实际落盘路径数组（改名/落到 inbox 后的真实结果）。
Android 走 MediaStore 兜底时，这里是相对路径 `Download/FileBridge/clip.mp4`。

**`ok: true` 必须意味着文件真的完整落盘了。** 两个必须挡住的情况：

- multipart 的 `Content-Length` 用完了但结尾边界 `--<boundary>--` 没到 → 请求体被截断，
  回 `400` 并删掉 `.afmu-part`；
- 一个文件段都没解析出来 → 回 `400`，不要回 `{"ok":true,"saved":[]}`。

客户端拿到空的 `saved` 时通常会回退成"用原文件名显示成功"，于是文件静默丢失、
用户毫不知情——这是最坏的一种失败方式。

**服务端只有在对端半关闭（FIN）时才可能察觉截断。** 光是"发的字节数少于
`Content-Length`"察觉不到——那和"对端还没发完"没有区别，只能等 socket 超时。
所以这里的要求分两层：

| 层次 | 要求 | 说明 |
|------|------|------|
| **硬不变量** | 绝不回 `ok: true`，绝不留下 `.afmu-part` 或半个正式文件 | 两端实现都必须做到，没有例外 |
| **期望行为** | 回 `400` | 客户端据此区分"服务端拒绝了"和"网线被拔了" |

**已知偏差**：Linux 端（Qt）在对端 FIN 时 `QTcpSocket` 直接进入 disconnected，
连接被拆掉，`400` 发不出去——硬不变量守住了（残片会删），但客户端收到的是连接中断。
客户端**必须**把"响应到达之前连接断开"一律当作失败处理，不能当成成功。

失败：`400` 请求体截断或没有文件段、`403` 只读、`405` 方法不对、`500` 写入失败。

### 3.5 `POST /api/mkdir` — 建目录

| 参数 | 必填 | 说明 |
|------|------|------|
| `path` | 是 | 父目录绝对路径 |
| `name` | 是 | 新目录名，会被安全化 |

→ `{"ok": true, "path": "/storage/emulated/0/DCIM/新建"}`

**必填参数缺失 → `400`，不是 `404`。** 两个检查的顺序不能颠倒：
`path` 缺失时路径解析同样得到空结果，先解析就会把「没传参数」误报成「目录不存在」。
先判参数是否为空（`400`），再解析路径、判目标是否存在且在 root 之下（`404`）。

失败：`400` 缺 `path` 或 `name`、`403` 只读、`404` 父目录不存在或越界、`405` 方法不对、`500` 创建失败。

### 3.6 `POST /api/delete` — 删除

| 参数 | 必填 | 说明 |
|------|------|------|
| `path` | 是 | 目标绝对路径 |
| `recursive` | 目录时必填 | `1` 才允许删非空目录 |

→ `{"ok": true}`

**根目录本身不允许删**：`path` 恰好等于某个 root 时返回 `403`
`{"ok":false,"error":"refusing to delete a shared root"}`。root 代表整个存储卷／共享目录，
`recursive=1` 删它是一次不可逆的灾难，而且从来不是用户的本意。root **里面**的
单个文件和子目录仍然正常可删。

> 删除是不可逆的，且**没有回收站**。客户端必须在 UI 上做二次确认
> （CLI 则要求显式 `--yes`）。

### 3.7 `GET /` — 浏览器界面

Android 端返回一个自包含的单页 HTML（无外链资源），PC 上没装任何东西时可直接
浏览器访问 `http://<手机IP>:<port>` 完成收发。

Linux 服务端**不需要**实现这个页面，返回一句纯文本说明即可。

### 3.8 `POST|GET /api/authorize` — 授权连接（**不需要 token**）

给「客户端手上还没有对端 token」这一种情况用：客户端发起请求，对端在自己屏幕上弹窗，
用户点「允许」之后 token 才交出去。手抄 token 的老路子完全保留，两者并存。

> **这是整套协议里唯一免鉴权的 `/api/*` 接口**，因为它存在的意义就是「还没有 token」。
> 于是它必须自己扛住滥用，下面的约束不是建议，是实现要求。

**`POST /api/authorize`** — 登记一个请求

| 参数 | 必填 | 说明 |
|------|------|------|
| `name` | 否 | 请求方设备名，显示在弹窗里 |
| `os` | 否 | `linux` / `android` |
| `code` | 是 | 4 位确认码，**请求方生成并显示在自己屏幕上** |
| `port` | 否 | 请求方自己的服务端口，供接收方回连 |

```json
{"ok": true, "request": "3f9c…（32 位十六进制）", "expires": 60}
```

- `request` 是**只发给请求方的一次性密钥**，取结果时凭它认人；不要写日志、不要广播。
- `code` 由请求方生成、两端同时显示。同一局域网里谁都能让对方弹窗，用户唯一能分辨
  「弹的是不是我刚点的那一下」的手段就是比对这四位。接收方必须原样显示，不要自己再生成。
- 请求方地址取 socket 的对端 IP，**不信任任何请求参数里的 host**。

失败：

| 状态码 | 含义 |
|--------|------|
| `403` | 接收方关掉了「允许连接请求」开关 |
| `429` | 已经有一个请求在等待，或该地址刚被拒绝过（冷却中） |

**`GET /api/authorize?request=<id>`** — 轮询结果

```json
{"ok": true, "status": "pending"}
{"ok": true, "status": "granted", "token": "abc123xyz9", "name": "Pixel 8", "port": 8765}
{"ok": true, "status": "denied"}
{"ok": true, "status": "expired"}
```

`id` 不认识（从没见过，或结果已过保留期）→ `404`。客户端应把 `404` 当作 `expired` 处理。

建议轮询间隔 1 秒，GET 支持 keep-alive，所以整个等待过程复用同一条连接。

**接收方必须做到的几件事**（缺一条这个接口就变成骚扰入口）：

- 同一时刻只保留**一个**待决请求，新的一律 `429`；
- 用户拒绝之后，把该地址**冷却**一段时间（Android 端取 60 秒），
  防止「一直弹到用户点错为止」；
- 超时（`kAuthTimeoutSec` = 60 秒）默认视为拒绝，不是允许；
- 提供一个可以彻底关掉这个接口的开关；
- 服务端停止时清空所有待决请求——没人能再来取结果了。

**接收方可以不实现这个接口**。没实现时的表现有两种，客户端都要当成「对端不支持」而不是
「请求失败」：`404`（没有这条路由），或者 `401`（把 `/api/*` 一律先过 token 检查，
于是免鉴权的请求也被挡下）。

**两端都实现了这个接口，也都会主动发起它**，因此 Linux ↔ Linux、Android ↔ Android
和两者混连走的是同一条路：谁没有对方的 token，谁就来敲门，另一边在自己屏幕上确认。
扫二维码（§5）仍然有效，是 PC 把 token 交出去的另一条路。

### 3.9 `POST /api/pair` — 回填对端信息

一次配对之后把**反方向**也配好：扫码方 / 被授权方拿到对端 token 之后，用这个接口把
自己的地址和 token 送回去，于是两个方向都不用再手抄。

| 参数 | 必填 | 说明 |
|------|------|------|
| `token` | 是 | **请求方自己的** token |
| `port` | 否 | 请求方的服务端口，默认 8765 |
| `name` | 否 | 请求方设备名 |
| `os` | 否 | `linux` / `android` |

需要 token（用的是**接收方的** token，请求方刚拿到的那个）。

```json
{"ok": true, "name": "ice-desktop", "os": "linux", "protocol": 1}
```

失败：`400` 没带 `token`、`401` token 不对、`405` 方法不对。

接收方存下 `token` 作为自己的「对端 token」，并把对方地址记成
`<socket 的对端 IP>:<port>`——同样**不信任参数里的 host**，否则这个接口就成了
「让 A 把 token 指向 C」的跳板。

---

## 4. 服务端必须遵守的规则

### 4.1 路径越界防护（最重要）

持有 token 的一方能指定任意路径字符串。服务端必须：

1. 把用户传入的路径 **canonicalize**（解析 `..`、符号链接），Kotlin 用
   `File.canonicalFile`，Python 用 `Path.resolve()`；
2. 检查结果是否等于某个 root，或位于某个 root **之下**；
3. 不满足 → 当作"不存在"返回 404，**不要**泄露真实原因。

对应实现：[Storage.kt `resolve()`](../app/src/main/java/com/aynux/afmu/core/Storage.kt)。

### 4.2 文件名安全化

对所有来自对端的文件名（`name` 参数、multipart 的 `filename`）：

- 剥掉路径部分（`/` 和 `\` 之后的最后一段）——防止 `../../etc/passwd`
- 把控制字符和 `< > : " | ? *` 替换为 `_`（兼容 FAT32/exFAT 的 SD 卡）
- 截断到 200 字符
- 结果为空、或恰好是 `.` / `..` → `unnamed`

> `.` 和 `..` 必须单独挡：剥路径部分对它们不起作用（里面没有 `/`），
> 而 `File(dir, "..")` 指向的是父目录，不是 `dir` 里的一个文件。

### 4.3 原子落盘

先写 `<目标名>.afmu-part`，完整收完后 `rename()` 到正式名。
传输中断时删掉 `.afmu-part`，**绝不留下半个文件冒充完整文件**。

续传时可以保留 `.afmu-part` 并用其大小作为 `Range` 起点。但**残片必须能追溯到它属于哪个
远端文件**：临时名只用文件名的话，两个不同目录下的同名文件、或上次失败遗留的残片，
会共用同一个 `.afmu-part`，续传起点就是错的，最后 `rename` 出一个静默损坏的文件。
可行做法是把远端路径的哈希拼进临时名，并在残片大小 ≥ 已知总大小时直接丢弃重来。

### 4.4 自动改名

`overwrite != 1` 且目标已存在时，按 `名字 (1).扩展名`、`名字 (2).扩展名` 递增，
上限 10000 次。

### 4.5 并发

每个连接一个线程即可（Android 端用 cached thread pool，Python 用
`ThreadingHTTPServer`）。单个请求出异常必须只断这一条连接，不能拖垮服务。

---

## 5. 配对二维码

PC 端显示、手机端扫描。一次扫码把地址、端口和 token 全给到，用户不用再手抄那 10 位。

载荷是一行纯 ASCII 文本（**不要**用 UTF-8 直出中文：那要靠 ECI 段声明字符集，
各家扫码器支持程度不一。设备名一律百分号编码）：

```
afmu://pair?v=1&host=192.168.1.30&hosts=192.168.1.30,10.42.0.1&port=8765&token=a7k2m9x4qp&name=ice-desktop&os=linux
```

| 参数 | 必填 | 说明 |
|------|------|------|
| `v` | 否 | 协议版本，缺省按 1 处理。**大于自己支持的版本 → 拒绝，不要猜** |
| `host` | 是 | 首选地址 |
| `hosts` | 否 | 逗号分隔的全部候选地址，多网卡时给出 |
| `port` | 否 | 服务端口，缺省 8765 |
| `token` | 是 | **显示方自己的** token |
| `name` | 否 | 显示方设备名，百分号编码 |
| `os` | 否 | `linux` / `android` |

扫描方拿到之后：

1. 存下 `token` 作为「对端 token」；
2. 按 `host` → `hosts` 的顺序逐个 `GET /api/info`，**第一个应答的才是能用的那个**
   ——多网卡 PC 会广播好几个地址，只有和自己同网段的那个通；
3. 全都不通就报错，不要留下一个连不上的配置；
4. 连通之后 `POST /api/pair`（§3.9）把自己的 token 送回去，两个方向一次配好。

不是本协议的码（扫码器对着世界会解出各种条码）→ 直接忽略并提示，不要当成错误。

> 二维码里是明文 token，等价于把访问权交出去。截图、转发、投屏都要当成泄露处理。

---

## 6. 典型时序

### 6.1 PC 拉取手机上的文件

```
PC                                              手机
 |-- UDP  AFMU-DISCOVER/1  → 255.255.255.255:8766 |
 |← UDP  {"afmu":1,"name":"Pixel 8","port":8765} --|
 |                                                 |
 |-- GET /api/info            (X-AFMU-Token) ----→ |
 |← 200 {"ok":true,"roots":[...]}  ---------------|
 |-- GET /api/list?path=/storage/emulated/0/DCIM →|
 |← 200 {"entries":[...]}  -----------------------|
 |-- GET /api/download?path=...  (Range: bytes=0-)→|
 |← 206 <文件字节流>  ----------------------------|
```

### 6.2 手机推文件到 PC

角色互换：PC 侧运行服务端，手机侧用
[PeerClient.kt](../app/src/main/java/com/aynux/afmu/core/PeerClient.kt) 发起：

```
手机                                             PC
 |-- UDP  AFMU-DISCOVER/1 → 广播:8766 ----------→ |
 |← UDP  {"afmu":1,"os":"linux","port":8765} ----|
 |-- POST /api/upload?name=IMG_0001.jpg --------→ |
 |    Content-Length: 3821044                     |
 |    <raw bytes>                                 |
 |← 200 {"ok":true,"saved":["/home/ice/..."]} ---|
```

手机推送时使用的 token 是 **PC 的 token**（用户在 App 的 "PC token" 输入框里填、
或者扫码 / 被回填得到），和手机自己的 token 是两个独立的值。

### 6.3 扫码配对

```
PC                                              手机
 |  屏幕上显示 afmu://pair?…（§5）               |
 |                                        用户扫码 |
 |← GET /api/info      (X-AFMU-Token: PC 的)  ---|
 |-- 200 {"ok":true,…}  ------------------------→ |
 |← POST /api/pair?token=<手机的>&port=8765   ---|
 |-- 200 {"ok":true}  --------------------------→ |
 |  两个方向的 token 都到位，PC 侧自动连上         |
```

### 6.4 授权连接（PC 上没有手机 token）

```
PC                                              手机
 |-- POST /api/authorize?name=ice-pc&code=4821 -→ |
 |← 200 {"ok":true,"request":"3f9c…","expires":60}|
 |                              弹窗：确认码 4821 |
 |-- GET /api/authorize?request=3f9c… ----------→ |
 |← 200 {"ok":true,"status":"pending"}  ---------|
 |                                     用户点允许 |
 |-- GET /api/authorize?request=3f9c… ----------→ |
 |← 200 {"status":"granted","token":"abc123xyz9"}|
 |-- POST /api/pair?token=<PC 的>  -------------→ |
 |← 200 {"ok":true}  ----------------------------|
```

PC 屏幕上显示的确认码必须和手机弹窗里的一致，用户才应该点允许。

---

## 7. 版本与兼容

- `protocol` / `afmu` 字段当前为 `1`。
- 新增字段 → 不升版本，客户端必须忽略未知字段。
- 语义变更或删字段 → 升到 `2`，客户端应检查并拒绝不认识的大版本。

### v1 的新增接口

`/api/authorize`（§3.8）、`/api/pair`（§3.9）和配对二维码（§5）都是**纯新增**，
线格式没变，仍然是 v1：

- 两个接口都**可以不实现**。客户端必须能分辨「不支持」（`404`，或把 `/api/*`
  一律先过 token 检查而返回的 `401`）和「失败」，并退回手抄 token 的老路子；
- 已有的六个接口、鉴权方式、发现协议一个字都没动；
- 旧客户端连新服务端、新客户端连旧服务端都照常工作。

### v1 的几处澄清

以下几条是补写进来的**错误路径**约定，线格式没变，仍然是 v1。两端实现都已按此对齐；
早期实现在这些地方的行为是错的，而不是另有约定：

| 位置 | 澄清 |
|------|------|
| §2.3 | 拒绝还没读请求体的请求时必须 `Connection: close` |
| §3.4 | 请求体截断 / 没有文件段 → `400`，不能回 `ok:true`；截断只能靠对端 FIN 察觉，Linux 端此时是断连而非 `400`（硬不变量仍守住） |
| §3.5 | 必填参数缺失 → `400`；先判参数再解析路径，否则会误报成 `404` |
| §3.6 | 删除 root 本身 → `403` |
| §4.2 | 文件名恰好是 `.` 或 `..` → `unnamed` |
| §4.3 | `.afmu-part` 必须能追溯到具体的远端文件，否则续传会静默损坏文件 |

畸形的 `Range`（如 `bytes=zz-`）属于客户端错误，回 `416` + `Content-Range: bytes */<total>`，
不要让数字解析异常冒成 `500`。

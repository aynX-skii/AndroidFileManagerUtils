# Linux 端实现说明

协议细节见 [PROTOCOL.md](PROTOCOL.md)，本文讲 Linux 端**实际是怎么实现的**：模块划分、
关键路径的取舍、哪些地方容易踩坑、怎么验证。

代码在另一个仓库 [aynX-skii/afmu-linux](https://github.com/aynX-skii/afmu-linux)，用法见它自己的 README。

> **历史说明**：本文最初是一份蓝图，规划的是「纯标准库、单文件、`chmod +x` 就能跑」的
> Python CLI（`afmu ls/get/put/serve`）。实际落地时改成了 **Qt 6 + Qt Quick 桌面应用**：
> 传输任务需要长期驻留的进度/速度/ETA 展示、拖拽上传和可取消的队列，这些在 GUI 里是自然的，
> 在单文件 CLI 里会变成一堆终端控制码。**命令行接口目前不存在**，下文描述的是现状。

---

## 1. 技术选型

| 项 | 选择 | 理由 |
|----|------|------|
| 语言 / 框架 | C++20 + Qt 6.5+（Quick / QuickControls2 / Network） | 单二进制、无运行时依赖；Qt 自带 UDP、TCP、HTTP 客户端和事件循环 |
| HTTP 服务端 | 手写在 `QTcpServer` 之上 | 和 Android 端对称：协议只有 6 个接口，引入完整 HTTP 框架不划算 |
| HTTP 客户端 | `QNetworkAccessManager` | 上传要逐块回调进度，`QNetworkReply` 的 `uploadProgress` 直接够用 |
| 界面风格 | `QQuickStyle("Basic")` + 全自绘 | 只有 Basic 允许完全自定义控件外观；无边框窗体靠 `startSystemMove` / `startSystemResize` |

服务端是**单线程异步**的（每条连接一个 `HttpConnection` QObject，靠 `readyRead` /
`bytesWritten` 驱动），不是 Android 端那种「一连接一线程」。原因是 GUI 进程里再开线程池
就得为传输进度加锁；事件循环里跑反而更简单，且传输是 IO 密集型，单线程足够跑满千兆。

---

## 2. 模块划分

| 文件 | 职责 |
|------|------|
| `Protocol.*` | 协议常量、常数时间 token 比较、token 生成、配对 URI 拼装、体积格式化 |
| `QrCode.*` | 自写的 QR 编码器（byte 模式，版本 1–40），见 §5.9 |
| `QrImage.*` | `QrView` —— 把上面的位图画到 QML 里的 `QQuickPaintedItem` |
| `PathSafety.*` | §4.1 越界防护、§4.2 文件名安全化、§4.4 自动改名 |
| `Config.*` | `~/.config/afmu/config.json`（权限 600） |
| `Discovery.*` | UDP 8766：广播探测 + 应答，过滤自己的应答 |
| `PeerClient.*` | 客户端 HTTP 封装，token 走 `X-AFMU-Token`，统一的错误信息提取 |
| `Models.*` | `DeviceModel`（发现结果）、`RemoteFileModel`（远端目录 + 多选状态） |
| `TransferModel.*` | 传输队列（并发上限 2），下载续传、上传进度、速度与 ETA |
| `HttpServer.*` | 服务端：Range、chunked、multipart 流式解析全部手写 |
| `AppController.*` | 串起以上所有部分，作为唯一的门面暴露给 QML |

QML 侧 `Theme.qml` 是单例，颜色 / 间距 / 字号只有这一个来源；`AppIcon.qml` 用
Qt Quick Shapes 画线性图标，不引入任何图片资源。

---

## 3. 连接目标的解析顺序

用户体验的关键，顺序不能乱（实现在 `AppController::scan()` 和 `probeFinished` 回调）：

```text
1. 用户在「设备」页手动输入 IP[:PORT]   → 直接用（connectManual）
2. UDP 广播发现
   ├─ 有应答 → 列在「设备」页，用户点选
   └─ 0 台   → 继续
3. config["lastHost"] 非空且 token 已填 → 复用，日志提示"没收到广播，复用上次地址"
4. 全失败 → 提示三条可操作建议（同一 Wi-Fi / App 内开服务 / 手动连接）
```

第 3 步很重要：很多路由器（尤其开了 AP 隔离或 IGMP snooping 的）会吃掉广播包，
但单播 TCP 是通的。

**token 缺失或失效不再是死路**：目标确定之后，`fetchInfo()` 发现 `peerToken` 为空、
或者拿到 401，就自动改走 §3.1 的授权流程，而不是弹一句「请先填 token」让用户自己想办法。
手抄 token 仍然完全有效，填了就用填的那个。

### 3.1 授权连接（PROTOCOL.md §3.8）

```text
requestAuthorization(host, port)
  ├─ 生成 4 位确认码，界面上弹 AuthWaitDialog 显示它
  ├─ POST /api/authorize?name=…&code=…&port=…      （不带 token）
  │    ├─ 404 / 405 / 401 → "对端不支持"，退回手抄 token 或扫码
  │    ├─ 403 → 对方关掉了开关；429 → 对方正忙
  │    └─ 200 → 拿到 request id，每秒 GET 一次
  ├─ status=granted → 存下 token → connectToDevice() → pushPairBack()
  ├─ status=denied  → 提示被拒绝
  └─ 60 秒无结果 / 404 → 超时
```

轮询而不是长轮询：长轮询会把对端「一连接一线程」的服务端占住 60 秒，而 GET 支持
keep-alive，每秒一次实际上复用的是同一条连接，代价可以忽略。

`401` 也要算成「不支持」：不实现这个接口的服务端会把 `/api/*` 一律先过 token 检查，
免鉴权的授权请求同样被挡在门外。把它报成「请求失败」会让用户以为是网络问题，
而正确的动作是去扫码或手填 token。

Linux 自己的服务端**也实现了这个接口**（`AuthRequests` + `HttpServer::handleAuthorize`），
所以另一台 PC、或者一台手机，都能反过来敲本机的门：本机弹 `IncomingAuthDialog`，
用户点「允许」之后才把 `localToken` 交出去。Linux ↔ Linux 走的就是这条路。

### 3.2 扫码配对（PROTOCOL.md §5）

反过来的方向：PC 显示二维码，手机扫。二维码内容由 `afmu::buildPairUri()` 拼出来，
包含本机全部局域网地址、端口和 `localToken`。手机扫到之后逐个地址试 `/api/info`，
连通了再 `POST /api/pair` 把自己的 token 送回来——`HttpServer::pairRequested` 信号
传到 `AppController`，存进 `peerToken` 并直接连上。

于是两个方向都不用手抄：PC → 手机走授权弹窗，手机 → PC 走扫码。

---

## 4. USB 兜底（无局域网时）

```bash
adb forward tcp:18765 tcp:8765     # 本机 127.0.0.1:18765 即手机的服务端
adb reverse tcp:8765  tcp:8765     # 反过来，让手机能访问本机的服务端
```

然后在「设备」页手动连 `127.0.0.1:18765`。协议完全一样，两端都不需要改动——
服务端监听 `0.0.0.0`，本地回环同样能连上。目前**没有**内置 adb 检测，需要用户自己敲这两条。

另外两条无 Wi-Fi 路径不需要任何代码支持：手机开 USB 网络共享（`rndis0`，PC 会拿到
`192.168.42.x`），或手机开热点让 PC 连上——两种情况下正常的局域网发现直接就能工作。

---

## 5. 关键实现点

### 5.1 广播地址

`QNetworkInterface::addressEntries()` 直接给 `broadcast()`，不需要像 Python 那样敲
`ioctl(SIOCGIFBRDADDR)`。跳过 down / loopback / 非 IPv4 的接口，最后追加
`255.255.255.255` 兜底。丢包很常见，`startProbe` 会在 `timeout/3` 处补发一次。

### 5.2 过滤自己的应答

开着接收服务时本机也在监听 8766，广播会把自己的应答收回来。收包时比对源 IP 是否属于
本机（`QNetworkInterface` 收集一份本机 IP 集合），命中就丢弃。注意 `::ffff:` 前缀的
IPv4-mapped 地址要先剥掉再比。

### 5.3 multipart 流式解析

自己写带缓冲的边界扫描器（`MultipartParser`），逐段直接落盘，不在内存里缓冲整个文件：

- 分隔符是 `\r\n--<boundary>`（前导 CRLF 属于分隔符，不属于文件内容）；
  首个分隔符没有前导 CRLF，构造时先往缓冲区塞一个 `\r\n` 就能统一处理
- 缓冲区里找不到分隔符时，只能安全输出 `len(buf) - (len(delim) - 1)` 字节，
  尾部要留够，防止分隔符跨块被切开
- **`Content-Length` 用完但结尾边界没到 = 请求体被截断**，必须回 400 并删掉 `.afmu-part`。
  早期版本这里直接回 `{"ok":true,"saved":[]}`，对端会当成传输成功，文件静默丢失

### 5.4 `Content-Length` 必须准

HTTP/1.1 下每个响应都必须发 `Content-Length`（协议不支持无长度的响应体），
否则客户端会一直等。`sendHeaders()` 是唯一出口，强制带上。

### 5.5 拒绝带请求体的请求时必须断开

401 / 403 / 405 发生在请求体读取之前，此时流位置必然错乱：剩下的 body 会被当成流水线里
的下一个请求解析。所有 `>= 400` 且请求体尚未消费的响应统一发 `Connection: close`
（PROTOCOL.md §2.3）。这是 `sendJson()` 里的单一收口，新增错误分支不用再单独考虑。

### 5.6 单个请求异常不能拖垮服务

每条连接是独立的 `HttpConnection`，出错只 `abort()` 自己那条 socket。
用户中断下载是正常现象，`errorOccurred` 里不刷日志。
另外**停止服务端时必须主动断开已有连接**，只停监听会出现「界面显示已停止、文件还在写」。

### 5.7 下载续传的 `.afmu-part` 必须能区分来源

part 文件名是 `<安全文件名>.<远端路径 SHA1 前 8 位>.afmu-part`。只用文件名会让两个不同目录
下的同名文件（或上次失败遗留的残片）共用一个 part 文件，续传起点是错的，落盘的文件直接损坏。
另外残片大小 `>=` 已知总大小时视为陈旧数据丢弃重来。

### 5.8 进度刷新节流

传输进度刷新节流到 ~120 ms，否则大文件传输时刷新本身会成为瓶颈。
`TransferModel` 用一个共享的 `QTimer` 批量 `dataChanged`，而不是每个数据块都发信号。

### 5.9 二维码是自己编的

Qt 没有 QR 编码器，而链 libqrencode 会破坏「apt 只装 qt6-base-dev / qt6-declarative-dev
就能编」这条底线（和 I18n 不走 .ts 是同一个取舍）。`QrCode.*` 按 ISO/IEC 18004 实现了
byte 模式的完整编码：容量表选版本、GF(256) 上的 Reed-Solomon 分块纠错、块交织、
8 种掩码按罚分择优。约 400 行，没有依赖。

几个容易写错的地方：

- **纠错级别在格式信息里的编码和枚举顺序不一样**（L=1 M=0 Q=3 H=2），
  直接拿 `int(ecc)` 去拼格式位，出来的码扫不出来；
- 掩码是自反的，试完一种再应用一次就还原了，不需要复制整张位图；
- 版本 ≥ 7 才有版本信息块，≥ 2 才有校正图案，两处都要按版本跳过；
- 渲染时**每个模块必须落在整数像素上**。按浮点缩放会让相邻模块因为舍入差出一像素缝，
  在屏幕上看着没问题，扫码器直接读不出来。

验证方式是拿一个独立解码器做往返：版本 1–40（5 到 2331 字节）逐个编码再解码，
文本必须逐字节相同。设备名一律百分号编码，载荷保持纯 ASCII，绕开 ECI 字符集声明那一摊。

---

## 6. 验证清单

两端都跑起来后，用 `curl` 直接验证协议。下面以**验证 Linux 服务端**为例
（验证 Android 端把 `$H` 换成手机地址即可，接口完全对称）：

```bash
T=<token>; H=127.0.0.1:8765

# 发现（不需要 token）
echo -n 'AFMU-DISCOVER/1' | socat - UDP-DATAGRAM:255.255.255.255:8766,broadcast

# 信息 / 列目录
curl -s -H "X-AFMU-Token: $T" "http://$H/api/info" | jq
curl -s -H "X-AFMU-Token: $T" "http://$H/api/list" | jq
curl -s -H "X-AFMU-Token: $T" --get --data-urlencode 'path=/home/me/Downloads' \
     "http://$H/api/list" | jq

# 下载 + 区间
curl -H "X-AFMU-Token: $T" --get --data-urlencode 'path=/home/me/a.jpg' \
     "http://$H/api/download" -o a.jpg
curl -H "X-AFMU-Token: $T" -H 'Range: bytes=100-199' --get \
     --data-urlencode 'path=/home/me/a.jpg' \
     "http://$H/api/download" -D- -o /dev/null   # 应返回 206 + Content-Range

# 上传：原始体 / multipart
curl -X POST -H "X-AFMU-Token: $T" --data-binary @big.iso \
     "http://$H/api/upload?name=big.iso"
curl -X POST -H "X-AFMU-Token: $T" -F "f=@big.iso" "http://$H/api/upload"

# 鉴权必须失败
curl -s -o /dev/null -w '%{http_code}\n' "http://$H/api/info"          # 401
curl -s -o /dev/null -w '%{http_code}\n' "http://$H/api/info?token=xx" # 401
```

必须覆盖的边界情况：

- [x] 无 token / 错 token → 401；只读模式下 upload / mkdir / delete → 403，读仍然 200
- [x] 路径穿越 `path=/home/me/../../etc/hosts`、`path=/etc` → 404，不泄露真实原因
- [x] 中文 / emoji 文件名（上传、下载的 `filename*=UTF-8''`、列目录）
- [x] 同名文件重复上传 → 自动变成 `a (1).txt`
- [x] 三种请求体（原始字节流 / `chunked` / `multipart`）落盘后 md5 一致
- [x] `Range: bytes=100-199` → 206 + `Content-Range`；`bytes=-50` 后缀区间；
      越界或畸形（`bytes=zz-`）→ 416 + `Content-Range: bytes */<total>`；
      `HEAD` 返回同样的头、无响应体
- [x] 上传中途 kill 客户端 → 收件箱里什么都不剩，`.afmu-part` 已删
- [x] **multipart 请求体被截断 → 400，不能回 `ok:true`**
- [x] **错 token + 大请求体 → 401 且连接关闭，body 不会被当成后续请求解析**
- [x] 删除共享根目录本身 → 403（两端都必须挡）
- [x] 客户端断点续传：预置部分 `.afmu-part`，续传后 md5 与源文件一致
- [x] UDP 应答回到探测包的源端口、不含 token、`discoverable` 关闭时不应答
- [x] `/api/pair`：无 token → 401；缺 `token` 参数 → 400；`GET` → 405；
      成功后 `peerToken` 落盘、地址取自 socket 而不是参数
- [x] 二维码往返：版本 1–40 逐个编码后用独立解码器还原，文本逐字节相同
- [ ] 大文件（> 4 GB）全链路 64 位长度——代码里用的是 `qint64`，但没有实测过
- [ ] 手机切换 Wi-Fi / 熄屏时传输是否继续（App 侧靠 WifiLock + 前台服务保障）
- [ ] 授权连接的完整链路（PC 请求 → 手机弹窗 → 允许 → 自动连上）只做过分段验证，
      没有在真机上跑通一次

---

## 7. 尚未实现

- 命令行接口（本文开头的历史说明）
- 递归上传 / 下载目录：两端都只处理当前目录里的文件，子目录跳过并提示
- 上传的断点续传（下载有；上传要续传需要协议层加一个「已收到多少」的查询接口）
- 内置 adb 检测与自动 `forward`
- IPv6
- **`/api/authorize` 的接收侧**：Linux 只做发起方。手机想连 PC 而手上没有 PC token 时，
  走的是扫码（§3.2）——PC 有屏幕，显示二维码比弹窗等待更直接。反过来手机没屏幕可扫，
  所以那个方向才需要弹窗

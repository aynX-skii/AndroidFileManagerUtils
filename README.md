# FileBridge

Linux 电脑 ↔ Android 手机 双向传文件。**优先走局域网**，没有局域网时可以退回 USB。

两端跑的是同一套协议，所以 **PC ↔ PC、手机 ↔ 手机也一样能用**：谁手上没有对方的
token，就点「请求授权」让对方在自己屏幕上确认，广播发现同样不区分对端是什么系统。

不依赖任何云服务、不需要账号、不经过公网；App 侧不引入任何第三方网络库
（HTTP 服务端、设备发现、multipart 解析全部手写在 `ServerSocket` / `DatagramSocket` 之上）。

## 现状

| 部分 | 状态 |
|------|------|
| Android App（服务端 + 客户端 + 浏览器界面） | ✅ 已完成 |
| 传输协议规范 | ✅ [docs/PROTOCOL.md](docs/PROTOCOL.md) |
| 加密协议 v2（零信任网络） | ✅ 已落地：[docs/PROTOCOL.md](docs/PROTOCOL.md) 第二部分。身份、配对表、双向 TLS、扫码与 SAS 配对、滚动 rid、访客模式全部完成。**两端真机实测跑通**（Android 侧：OPPO PFZM10 / Android 15，密钥落在 TEE） |
| Linux 桌面客户端（Qt 6，服务端 + 客户端） | ✅ 已完成，单独一个仓库：[aynX-skii/afmu-linux](https://github.com/aynX-skii/afmu-linux) |
| Linux 命令行客户端 | ❌ 不做了，理由见 [docs/LINUX-CLIENT.md](docs/LINUX-CLIENT.md) 开头 |

PC 端有三种用法，按顺手程度排：

1. **Linux 桌面客户端**（`afmu-linux/`）——扫描、浏览、双向传输、本机也能当服务端。
2. **浏览器**——App 内置了一个自包含的网页界面，打开 `http://<手机IP>:8765` 就能双向收发。
   支持面包屑导航、排序、过滤、多选批量下载/删除、新建目录，以及带速度和剩余时间的
   串行上传队列（可取消、可重试）。什么都不用装。
3. **`curl`**——协议是普通 HTTP，验证清单见 [docs/LINUX-CLIENT.md](docs/LINUX-CLIENT.md) §6。

## 怎么连上的

```text
                  ① 局域网（默认，最快）
   ┌──────────┐   UDP 8766 广播发现 ────→  ┌──────────┐
   │  Linux   │                             │ Android  │
   │   PC     │   HTTP 8765 传输 ←────────→ │   手机   │
   └──────────┘                             └──────────┘
                  ② USB（无 Wi-Fi 时）
        adb forward tcp:18765 tcp:8765  → 127.0.0.1:18765 即手机
                  ③ 手机热点 / USB 网络共享
        退化成 ①，发现和传输都照常工作
```

同一 Wi-Fi 下**不需要输入任何 IP**：一端发一个 UDP 广播，所有开着服务的设备回一句 JSON
说明自己叫什么、是什么系统、端口是多少。应答里带 `os` 字段但没人拿它做过滤，两台 PC
或两台手机互相扫也照样列出来。路由器开了 AP 隔离吃掉广播时，手动填 IP 或走 USB。

## 快速上手

1. 用 Android Studio 打开本项目，编译安装到手机（`app` 模块）。
2. 打开 App，最上面的开关打到 **on**——服务就起来了，界面上会显示：
   - 一个或多个 `http://192.168.x.y:8765` 地址
   - 一串 10 位的 **access token**
3. 在 PC 浏览器里打开那个地址，把 token 填进去，点 Connect。
   - 浏览：直接点文件名下载
   - 上传：把文件拖进页面下方的虚线框
4. 手机主动收发（推送到 PC、从 PC 拉取）需要 PC 侧也跑着服务端——在 Linux 客户端的
   「接收服务」页点「启动服务」，然后点「显示配对二维码」，在 App 里点「扫码连接」扫一下
   （不想扫码就把它显示的 token 抄进「PC token」输入框）：
   - **推送**：App 里「Choose files and send」，或从任意 App 的分享菜单选 FileBridge
   - **拉取**：App 里「Browse the PC and pull files」，浏览 PC 的目录树，点文件就下载，
     也可以「Pull all」把当前目录的文件一次性拉完

   不想装 Linux 客户端的话，这两个方向都可以用网页界面代替（PC 浏览器操作手机）。

> 服务开关的状态会被记住：关掉之后重新打开 App **不会**自动开启服务。

手机收到的文件默认落在 `Download/FileBridge/`，从 PC 拉取的文件也落在这里。

### 两种配对方式

手抄那 10 位 token 一直都能用，下面两条是省掉手抄的捷径，任选其一：

| 方向 | 做法 |
|------|------|
| **PC 想连手机** | PC 上点「连接」→ 手机弹出授权通知 → 点「允许」，token 自动送过去 |
| **手机想连 PC** | PC 上点「显示配对二维码」→ App 里点「扫码连接」扫一下 |

两种方式都会把**反方向**也一起配好，一次操作两个方向都能用。

授权弹窗上有一个 4 位确认码，必须和 PC 屏幕上显示的一致才点「允许」——同一个局域网里
谁都能让手机弹这个窗，这四位是判断「弹的是不是我刚点的那一下」的唯一依据。同一时刻只会
有一个待决请求，拒绝过的地址会进冷却，60 秒没确认按拒绝处理。整个功能可以在
「设置 → 允许连接请求」里关掉。

> 二维码里是明文 token，等价于把 PC 的访问权交出去；扫码需要摄像头权限，只在点
> 「扫码连接」时才申请。

### 界面语言

中英双语。默认跟随系统语言，在 App 的「设置 → Language / 语言」里可以固定成中文或英文。
选择存在 App 自己的偏好里，下次启动自动加载。切换后界面会重建一次立即生效，
前台服务通知和手机内置的网页界面也跟着切。

### 权限说明

- **通知**：前台服务需要，否则息屏后系统会掐掉传输。
- **所有文件访问权限**（Android 11+ 需在系统设置里单独授予）：不给也能用，
  但 PC 只能看到 App 自己的目录；给了才能浏览整个内部存储。
  **接收文件不需要这个权限**（走 MediaStore 兜底）。
- **摄像头**：只用于扫配对二维码，点「扫码连接」时才申请，不扫码就永远不会问。

## 项目结构

```text
app/src/main/java/com/aynux/afmu/
├── MainActivity.kt              入口；权限、文件选择器、系统分享（发送到…）
├── MainViewModel.kt             UI 状态、传输任务、对端管理、远程目录浏览
├── core/
│   ├── HttpServer.kt            HTTP/1.1 服务端：list/download/upload/mkdir/delete
│   ├── HttpInput.kt             带缓冲的流读取器（协议行 + 按分隔符拷贝）
│   ├── WebUi.kt                 内置网页界面（单文件、无外链资源）
│   ├── Discovery.kt             UDP 广播发现：应答 + 探测
│   ├── AuthRequests.kt          待决状态机：v1 授权 + v2 配对（共用一个位置）
│   ├── AuthThrottle.kt          token 猜错的指数退避（§2.2）
│   ├── DownloadTicket.kt        短时、绑路径的下载券，顶替 ?token=（§2.5）
│   ├── PairPayload.kt           afmu://pair 二维码载荷的解析与拼装
│   ├── PeerClient.kt            出站客户端：推文件到 PC、带续传地拉取
│   ├── Storage.kt               存储根、路径越界检查、三种写入兜底、续传残片
│   ├── Bridge.kt                服务与发现的单一持有者，UI/Service 共享状态
│   ├── NetUtils.kt              本机地址、广播地址、网络类型
│   ├── Prefs.kt                 设置持久化（token、端口、设备名、语言、三个开关…）
│   ├── LocaleHelper.kt          按设置包装 Context，实现中英切换
│   ├── ProtocolConstants.kt     ⚙ 由 docs/constants.json 生成，别手改
│   │
│   │                            ── 以下是 v2（零信任）那一层 ──
│   ├── Identity.kt              设备身份：AndroidKeyStore 里的 EC P-256 + 自签证书
│   ├── Tls.kt                   双向 TLS：指纹钉扎的 TrustManager / KeyManager
│   ├── PeerStore.kt             配对表（进程内唯一），v2 的访问控制列表本身
│   ├── Peers.kt                 配对表的记录类型与编解码
│   ├── PairSas.kt               8 位比对码，commit-reveal 绑定会话随机数（§4.2.2）
│   ├── RollingId.kt             发现应答里的滚动 rid，不再广播设备名（§6.1）
│   ├── Base32.kt                指纹的展示编码（去掉 I/O 的字母表）
│   └── Hex.kt                   严格 hex 解码：只认 ASCII，不合法就整串作废
├── service/TransferService.kt   前台服务 + WifiLock/MulticastLock/WakeLock + 授权通知
└── ui/
    ├── MainScreen.kt            Compose 主界面 + 授权确认弹窗
    ├── AppLocale.kt             语言状态，切换后触发界面重建
    ├── Theme.kt                 配色与排版
    └── ScannerScreen.kt         扫码取景框（CameraX 取帧，zxing-core 解码）
docs/
├── PROTOCOL.md                  ★ 传输协议规范（v1 线格式 + v2 加密与身份）
├── LINUX-CLIENT.md              ★ Linux 端实现说明（架构 + 验证清单）
└── constants.json               ★ 两端协议常量的唯一真源
tools/gen_constants.py           从 constants.json 生成两端的常量文件
tests/
├── conformance.py               v1 黑盒一致性套件（两端都必须过）
├── conformance_v2.py            v2 黑盒一致性套件（mTLS + 配对门禁 + SAS）
└── README.md                    怎么跑、覆盖什么、已知偏差
```

## 构建

工具链（已在本机实际编译通过，产物 `app/build/outputs/apk/debug/app-debug.apk`）：

| 组件 | 版本 |
|------|------|
| AGP | 9.3.0（自带 Kotlin 支持，**不需要**单独 apply `kotlin-android`） |
| Kotlin / Compose 编译器插件 | 2.2.10 |
| Gradle | 9.5.0 |
| compileSdk / targetSdk | 37 |
| minSdk | 26（Android 8.0） |
| JDK | 26 可用 |

`gradle/wrapper/gradle-wrapper.jar` **在仓库里**（Gradle 官方现在也是这么建议的：
wrapper 的作用就是「clone 下来立刻能编，不用先装对版本的 Gradle」，不提交它等于
把这件事又推回给使用者）。所以直接用：

```bash
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest    # 纯 JVM 单元测试，不需要设备或模拟器
```

> AGP 9 起 Kotlin 支持内置。如果在 `plugins {}` 里再写 `org.jetbrains.kotlin.android`，
> 会报 `Cannot add extension with name 'kotlin'`。同理 `kotlinOptions {}` 也不用写了，
> `compileOptions` 里的 Java 版本就够。

## 安全边界

**有两套，取决于设置里那个开关。** 先说清楚哪套是哪套，免得误用：

| | v2（加密） | v1（明文） |
|---|---|---|
| 传输 | TLS 1.3 双向认证，自签证书 + SPKI 指纹钉扎 | 明文 HTTP |
| 凭什么信对面 | 对方持有配对表里那把私钥 | 10 位共享 token |
| 挡得住嗅探吗 | 挡得住 | **挡不住** |
| 挡得住中间人吗 | 挡得住（扫码带指纹，或用户比对 8 位 SAS） | 挡不住 |
| 什么网络能开 | 任意，包括公共 Wi-Fi —— v2 存在的目的就是这个 | 只在你信任的网络 |
| 默认 | **两端都默认走这套** | 需要在设置里手动打开 |

设置页上「只接受加密连接」那个开关决定跑哪一套，界面上常驻显示当前实际状态。
细节见 [docs/PROTOCOL.md](docs/PROTOCOL.md) 第二部分，那里有完整的威胁模型
（§1）和「加密之后还剩什么泄露」（§10）。

> **默认只加密**（PROTOCOL.md §8.2 第 3 阶段，两端都已落地）。全新安装开箱即是；
> 升级安装走一次性迁移，把明文关掉的同时在设置页上说一声 —— 旧设备和浏览器界面
> 从此连不上，不说的话用户只会看到「今天开始连不上了」。
>
> **需要的话可以关回去**：设置里把「只接受加密连接」关掉即可，而且**只会被关一次**，
> 重新打开之后不会在下次启动时又被改掉。访客模式（浏览器界面）默认关，
> 升级安装保持原样。

下面这些两套都成立：

- 所有路径参数都经过 canonicalize + 根目录白名单校验，拿到 token 也不能读取
  白名单之外的文件（`Storage.resolve()`）。
- 上传的文件名会被剥掉路径部分并过滤特殊字符（`.` 和 `..` 也挡掉），防止路径穿越。
- token 可以在 App 里随时重新生成，旧的立刻失效——**包括扫码和授权发出去的那些**。
- **授权连接**（`/api/authorize`）是唯一免鉴权的接口，因为它存在的意义就是「还没有 token」。
  约束都在 `AuthRequests` 里：同一时刻只有一个待决请求，被拒绝的地址进冷却，
  超时按拒绝处理，token 只发给持有那个一次性 request id 的请求方，请求方地址取自 socket
  而不是请求参数。整个功能可以在设置里关掉。
- 确认码是**请求方生成、两端同时显示**的。局域网里任何人都能让手机弹这个窗，
  这四位是用户唯一能分辨「弹的是不是我刚点的那一下」的依据。
- 二维码：**v1 的码里是明文 token**，扫码等价于把 PC 的访问权交出去，别截图外发；
  **v2 的码里只有公钥指纹，没有任何秘密**，所以它可以放心当链接传播
  （`afmu://pair?…`），代价是配对时要有人比对 8 位 SAS。
- 「允许写入」开关关掉后，上传/建目录/删除一律 403。
- **删除根目录本身一律 403**。根目录代表整个存储卷，`recursive=1` 删它是不可逆的灾难；
  根目录里的单个文件和子目录仍然可以正常删除。
- 服务不会自己启动：只有用户上次把开关留在 on，重新打开 App 才会恢复服务。
- token 被排除在云备份和「切换到新设备」之外——它是一次配对的共享密钥，
  换设备时应该重新生成，而不是跟着备份漂移。

## 已知限制

- 只支持 IPv4。
- **浏览器界面只有 v1 那一套**（访客模式 + 密码认证）。浏览器不会出示客户端证书，
  自签的服务端证书又只会弹一个吓人的警告，点过去的那一刻中间人防护就没了。
  这不是没做，是做不到 —— 理由写在 `Prefs.guestMode` 的注释和 PROTOCOL.md §9。
- **手机当服务端时一次只提供一种协议**（明文或加密，由开关决定），不像 Linux 端
  能在一个端口上同时服务两者。原因是 Android 的 `SSLSocketFactory` 缺了那个
  「把已读掉的首字节还回去」的重载，首字节分流没法实现（PROTOCOL.md §5.3）。
- 没有断点续传的**上传**。下载两个方向都有：手机当服务端时响应 `Range`，
  手机当客户端从 PC 拉取时也会带 `Range` 续传。
- 大量小文件逐个传输，没有打包优化。
- 手机端的远程浏览器不能递归拉取子目录，「Pull all」只作用于当前目录。
- 网页界面的批量下载是逐个触发浏览器下载，浏览器可能会问一次「是否允许多个下载」。

## 许可证

[LGPL-3.0](COPYING.LESSER)（在 [GPL-3.0](COPYING) 之上附加权限）。

协议规范本身（`docs/PROTOCOL.md`）欢迎照着写第三方实现 —— 那是它存在的目的，
`tests/` 下两套一致性套件就是给这种实现用的验收标准。

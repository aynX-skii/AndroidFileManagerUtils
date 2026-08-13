# 协议一致性测试

对照 [docs/PROTOCOL.md](../docs/PROTOCOL.md) 的黑盒测试，**两套**：

| 文件 | 对照 | 验的是 |
|---|---|---|
| [`conformance.py`](conformance.py) | 第一部分（v1） | **线格式**：字段、状态码、Range、multipart、路径穿越 |
| [`conformance_v2.py`](conformance_v2.py) | 第二部分（v2） | **门禁**：谁能进、进来之后能碰到什么 |

**Android、Linux、Windows 三端都必须通过**——这是几个实现不漂移的唯一保证。

> 套件本身也会漂。`conformance.py` 写在只有 Android 和 Linux 的时候，
> 于是「绝对路径 = 以 `/` 开头」这个假设直接长进了断言里，Windows 服务端
> 一接上来就被判成不合法（它报的是 `C:/…`，那在那个平台上就是绝对路径，
> 而 PROTOCOL.md §3.1 从没要求过首字符是什么）。现在统一走 `is_absolute()`，
> 三种形态都收：POSIX、盘符、UNC。**再加平台时先看一眼这个函数。**

两件事错起来的表现完全不同，所以两套都要有：线格式错了对端立刻报错，
门禁错了**没有任何人会报错**。

只用 Python 3 标准库。v2 那套额外要一个 `openssl` 命令行——mTLS 的客户端
必须有一张证书，而标准库生成不了；没有 openssl 就整套跳过。这和「不装任何
pip 包」不冲突，也是 PROTOCOL.md §12 第 1 步交叉验证指纹用的同一个工具。

> 另外两处测试不在这里，但同属「两端不许漂移」这件事：
>
> - `./gradlew :app:testDebugUnitTest` —— 纯 JVM 单元测试，覆盖不依赖 Android 的部分：
>   base32、SAS、滚动 rid、hex、协议常量、配对表编解码、TLS 钉扎的判定
>   （向量取自 C++ 实现的真实输出）。
> - `afmu-linux` / `afmu-windows` 的 `cmake -DAFMU_TESTS=ON` + `ctest --test-dir build`
>   —— 同一批东西的 C++ 一侧，断言和 Android 那边刻意一一对应。

## 跑

```bash
# 先找设备
python3 tests/conformance.py --discover

# 对着它跑
python3 tests/conformance.py --host 192.168.1.42 --port 8765 --token abc123xyz9

# 只跑某一组
python3 tests/conformance.py --host … --token … -k download
python3 tests/conformance.py --host … --token … -k 澄清

# 连要等真实超时的用例一起跑（多花几分钟）
python3 tests/conformance.py --host … --token … --slow

# v2 那套：不用 token（v2 的身份是那对密钥，没有 token 这回事）
python3 tests/conformance_v2.py --host 192.168.1.42 --port 8765
```

退出码 0 = 全过，1 = 有失败，2 = 连不上。

### v2 那套额外的两件事

**跑之前把对端的访客模式关掉。** 访客模式是 §4.2.4 门禁**唯一**的例外：开着时
未配对连接被允许往下走去过 v1 那道密码门，于是门禁那一组用例会全部跳过 ——
而「跳过」在输出里看起来和「通过」一样无害。脚本会探测并在开头提示当前状态。

**跑完对端会留下一个待决的配对请求**，和 v1 §3.8 那条授权用例一样。
请在对端点「拒绝」，或者等它 60 秒超时。用例本身不需要有人点任何东西 ——
它们验的都是用户决定**之前**的那几步。

## ⚠️ 别对着有真实数据的服务端跑

用例里有 **`删 root 本身 → 403`**：它会对每个 root 发
`POST /api/delete?path=<root>&recursive=1`。
这条用例的意义就是验证服务端**拒绝**这个请求——但如果被测实现的 root 保护有 bug，
那就是把那个 root 整棵删掉。

所以：**先用一个 serveRoots 指向临时目录的隔离配置跑**，确认通过之后，
再考虑要不要对日常配置跑。

Linux 端可以这样起一个隔离实例（不动 `~/.config/afmu/`）：

```bash
S=$(mktemp -d)
mkdir -p "$S/cfg/afmu" "$S/share/inbox" "$S/share/extra"
cat > "$S/cfg/afmu/config.json" <<EOF
{
  "deviceName": "conformance-target",
  "localToken": "test2test9",
  "serverPort": 8865,
  "inboxDir": "$S/share/inbox",
  "downloadDir": "$S/share/inbox",
  "serveRoots": ["$S/share/inbox", "$S/share/extra"],
  "autoStartServer": true, "discoverable": true,
  "readOnly": false, "allowAuthRequests": true
}
EOF
XDG_CONFIG_HOME="$S/cfg" QT_QPA_PLATFORM=offscreen ./build/afmu &

python3 tests/conformance.py --host 127.0.0.1 --port 8865 --token test2test9
```

Windows 端麻烦一点，**没有 `XDG_CONFIG_HOME` 这种口子**：那边的 `QStandardPaths`
走 `SHGetKnownFolderPath`，不看任何环境变量。所以只能备份 → 换配置 → 跑 → 拷回来
（`identity.pem` 别动，动了设备身份就变了）：

```powershell
$B = "$env:TEMP\afmu-backup"; Copy-Item -Recurse "$env:LOCALAPPDATA\afmu" $B
# 写一份 serveRoots 指向临时目录的 config.json，端口 8865，再起 build\afmu.exe
# ……跑完……
Copy-Item -Force "$B\*" "$env:LOCALAPPDATA\afmu\"
```

那份测试配置里**必须带 `"plaintextStage3": true`**。少了它，§8.2 第 3 阶段的
一次性迁移会在启动时把明文关掉，于是 v1 套件第一个请求就被断开，报的是
「服务端在发完响应头之前就断开了」—— 看起来像服务端崩了，其实是它照规矩办事。
`conformance_v2.py` 那套反过来，本来就要只加密 + 访客模式关。

`openssl` 在 Windows 上不一定有；Git for Windows 自带一份，在
`C:\Program Files\Git\usr\bin\openssl.exe`，加进 PATH 即可。

其余写入类用例都关在服务端 inbox 下自建的 `afmu-conformance-<随机>` 目录里，跑完自动删。
服务端是只读模式（`writable=false`）时，写入类用例自动跳过。

## 用例覆盖什么

刻意贴着规范里的**错误路径**写。正常路径两端本来就不容易错，
出事的从来是 §7「v1 的几处澄清」那张表里列的东西——那些条目每一条都曾被实现错过。

| 组 | 重点 |
|---|---|
| §1 发现 | 应答字段与类型、`afmu` 严格等于 1、**应答里绝不含 token** |
| §2.2/2.3 鉴权 | 两种 token 写法、**`?token=` 必须被当作没带**、401、**拒绝带请求体的请求时必须 `Connection: close`**、被拒后不能把 body 当流水线里的下一个请求 |
| §2.4 Host/Origin | DNS 名字的 Host → 403、**宽松写法的 IPv4 也要拒**、跨源 Origin → 403（**端口一起比**）、检查排在 token 之前且覆盖 `GET /` |
| §2.5 下载券 | 签券→凭券下载、**券绑定路径**、伪造/篡改/过期一律拒、券不能签新券、越界路径不签券 |
| §3.1 info | 必填字段与类型、`Cache-Control: no-store` |
| §3.2 list | 根列表 `parent: null`、与 `info.roots` 一致、排序规则、**`mtime` 是秒不是毫秒**、目录 `size` 恒为 0 |
| §3.3 download | Range 四种形式、**畸形 Range 回 416 不是 500**、`filename*=UTF-8''`、HEAD 与 GET 头一致 |
| §3.4 upload | 原始流 / chunked / multipart、**截断不回 `ok:true` 且不留残片**、**没有文件段回 400**、自动改名、越界 `dir` 落 inbox |
| §3.5/3.6 | **必填参数缺失回 400**、非空目录需 `recursive=1`、**删 root 回 403** |
| §4.1/4.2 | `../` 穿越回 404 且不泄露原因、文件名剥路径、**`.` 和 `..` → `unnamed`**、非法字符替换 |
| §2.2 退避 | 连续猜错 token 触发 429 + `Retry-After`、封禁期内正确 token 也挡、成功后清零 |
| §3.8/3.9 | 免鉴权、并发请求回 429 且**不带** `Retry-After`、超时后进冷却且**带** `Retry-After`（`--slow`）、未实现时能被识别（404/401） |

### v2（`conformance_v2.py`）

用一张现生成的 EC P-256 自签证书扮演「一台没见过的新设备」，全程 mTLS。
每次跑都换一张——「未配对」正是要测的那个状态。

| 组 | 重点 |
|---|---|
| §4.2.4 门禁 | 未配对连接除 `/api/pair-v2` 外**一律 403**，`/` 和 `/api/info` 都在内；**同一条连接上连续访问多个路由**（分开连会掩盖「只在握手时判一次」）；错误信息指向配对而不是 token；带上 token 也不改变结论 |
| §4.2.3 线格式 | 明文下配不成对且不留 session；`commit` 长度不对 → 400；**hex 判定严格**（`"11zz"`、全角数字都要整串作废）；未知 step → 400；未知 session → 404 |
| §4.2.3 握手 | 三步走通；已有待决 session 时再 commit → 429；**`na` 对不上 → 400 且整个 session 作废**；作废之后拿正确的 `na` 重试仍然 400；作废确实腾出了位置 |
| §4.2.2 SAS | **服务端回的 SAS 与本地独立算出的逐字相同** —— 整套 v2 里最值得跨实现验的一个值 |
| §9 访客 | 不出示客户端证书的加密连接，在访客模式关掉时进不来；开着时止步于密码门 |
| §8.1 降级 | 只加密模式下，明文连接被直接断开，**不回任何 HTTP 报文**（回什么都等于告诉扫端口的人这里有服务） |

## 已知偏差

用 `@case(..., deviation="原因")` 标记。这类用例失败时打印成**偏差**而不是 FAIL，
**不计入退出码**——但每次都会单独列出来，省得有人以为它已经好了。

反过来，标了偏差却**通过**的用例也会被点名，提示去掉标记：可能是实现改好了，
也可能是这条偏差本来就只存在于另一端。

| 用例 | 哪一端 | 情况 |
|---|---|---|
| `截断应回 400，而不是直接断开连接` | Linux (Qt) | 挂 `readChannelFinished` 试过了：信号会触发、阶段也对，但那一刻 socket 已是 `UnconnectedState`，写进去的字节直接丢弃。`QTcpSocket` 不支持 TCP 半关闭，要修得换成 `QSocketNotifier` + 裸 fd 重写连接层。**硬不变量守住了**（不回 `ok:true`、残片会删，由上一条用例单独验证）。详见 PROTOCOL.md §3.4 |

同一件事拆成两条用例是故意的：不变量那条**永远不许红**，
状态码那条只是诊断信息差一点，不是数据安全问题。
Android 端用阻塞 socket，对端半关闭后仍可写，预计能通过这一条。

## 加用例

```python
@case("§3.2 list", "一句话说明期望的行为")
def t_something(ctx: Ctx) -> None:
    ctx.need_write()          # 需要写权限的话加这句，只读服务端会自动跳过
    r = ctx.get("/api/list")
    expect_eq(r.status, 200, "状态码")
```

- `expect` / `expect_eq` 的最后一个参数是**失败时打印的说明**，写清楚"期望什么"。
- **故意制造鉴权失败的用例，每次失败后要调 `ctx.reset_throttle()`。**
  §2.2 的退避在连续失败 5 次后就开始封禁，不清零的话会把后面所有用例一起带崩。
  专门验证退避本身的那条用例除外——它要的就是累积。
- 要检查 `Connection: close`、畸形请求、半关闭这类东西，用 `ctx.conn()` 拿裸连接。
  `http.client` 会把这些正好要测的细节替你抹平。
- 用例之间不共享状态，顺序无关；写文件一律写进 `ctx.scratch`。

# 协议一致性测试

对照 [docs/PROTOCOL.md](../docs/PROTOCOL.md) v1 的黑盒测试。
**Android 端和 Linux 端必须都通过**——这是两个实现不漂移的唯一保证。

只用 Python 3 标准库，不装任何东西。

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
```

退出码 0 = 全过，1 = 有失败，2 = 连不上。

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

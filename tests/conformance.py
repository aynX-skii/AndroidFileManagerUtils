#!/usr/bin/env python3
"""
AFMU 协议一致性测试 —— 对照 docs/PROTOCOL.md v1。

对着一个**正在运行的** AFMU 服务端跑，两端实现（Android / Linux）必须都通过。
只用标准库，不装任何东西。

    python3 conformance.py --host 192.168.1.42 --port 8765 --token abc123xyz9

不知道地址就先发现一下：

    python3 conformance.py --discover

用例故意贴着规范里的**错误路径**写：那一节（PROTOCOL.md §7「v1 的几处澄清」）
列的每一条都曾经被实现错过，而正常路径反而不容易错。

写入类用例全部在服务端 inbox 下自建的临时目录里进行，跑完自动删除。
只读模式（`writable=false`）下会自动跳过写入类用例。
"""

from __future__ import annotations

import argparse
import json
import os
import re
import socket
import sys
import time
import uuid
from dataclasses import dataclass, field
from typing import Any, Callable

DISCOVERY_PORT = 8766
PROBE = b"AFMU-DISCOVER/1\n"
DEFAULT_TIMEOUT = 10.0

# ---------------------------------------------------------------- HTTP 客户端
# 用裸 socket 而不是 http.client：好几个用例要检查的正是 http.client 会替我们
# 抹平的东西 —— 401 有没有带 Connection: close、畸形 Range 会不会冒成 500、
# 半关闭连接之后服务端怎么回。


@dataclass
class Response:
    status: int
    reason: str
    headers: dict[str, str]
    body: bytes

    def header(self, name: str) -> str | None:
        return self.headers.get(name.lower())

    @property
    def json(self) -> Any:
        try:
            return json.loads(self.body.decode("utf-8"))
        except Exception:
            return None

    @property
    def closes(self) -> bool:
        return (self.header("connection") or "").lower() == "close"


class Conn:
    """一条连接。默认每个用例开一条新的，测 keep-alive 的用例自己复用。"""

    def __init__(self, host: str, port: int, timeout: float = DEFAULT_TIMEOUT):
        self.sock = socket.create_connection((host, port), timeout=timeout)
        self.sock.settimeout(timeout)
        self.host = host
        self.port = port
        self._buf = b""

    def close(self) -> None:
        try:
            self.sock.close()
        except OSError:
            pass

    def __enter__(self) -> "Conn":
        return self

    def __exit__(self, *_: object) -> None:
        self.close()

    # -- 发送 ---------------------------------------------------------------

    def send_raw(self, data: bytes) -> None:
        self.sock.sendall(data)

    def half_close(self) -> None:
        """只关写方向。服务端读到 EOF —— 这是触发「请求体截断」的唯一办法。"""
        self.sock.shutdown(socket.SHUT_WR)

    def request(
        self,
        method: str,
        target: str,
        headers: dict[str, str] | None = None,
        body: bytes | None = None,
        token: str | None = None,
        send_body: bool = True,
    ) -> None:
        h = dict(headers or {})
        h.setdefault("Host", f"{self.host}:{self.port}")
        if token is not None:
            h.setdefault("X-AFMU-Token", token)
        if body is not None and "Transfer-Encoding" not in h:
            h.setdefault("Content-Length", str(len(body)))
        head = f"{method} {target} HTTP/1.1\r\n"
        head += "".join(f"{k}: {v}\r\n" for k, v in h.items())
        head += "\r\n"
        self.send_raw(head.encode("latin-1"))
        if body is not None and send_body:
            self.send_raw(body)

    # -- 接收 ---------------------------------------------------------------

    def _read_more(self) -> bool:
        try:
            chunk = self.sock.recv(65536)
        except socket.timeout:
            raise TimeoutError("等响应超时")
        if not chunk:
            return False
        self._buf += chunk
        return True

    def read_response(self, want_body: bool = True) -> Response:
        while b"\r\n\r\n" not in self._buf:
            if not self._read_more():
                raise EOFError("服务端在发完响应头之前就断开了")
        head, _, rest = self._buf.partition(b"\r\n\r\n")
        self._buf = rest

        lines = head.decode("latin-1").split("\r\n")
        m = re.match(r"HTTP/1\.[01] (\d{3})(?: (.*))?", lines[0])
        if not m:
            raise ValueError(f"状态行不合法: {lines[0]!r}")
        status, reason = int(m.group(1)), (m.group(2) or "")

        headers: dict[str, str] = {}
        for line in lines[1:]:
            if ":" in line:
                k, _, v = line.partition(":")
                headers[k.strip().lower()] = v.strip()

        body = b""
        if want_body:
            length = headers.get("content-length")
            if length is not None:
                need = int(length)
                while len(self._buf) < need:
                    if not self._read_more():
                        break
                body, self._buf = self._buf[:need], self._buf[need:]
            elif headers.get("transfer-encoding", "").lower() == "chunked":
                body = self._read_chunked()
        return Response(status, reason, headers, body)

    def _read_chunked(self) -> bytes:
        out = b""
        while True:
            while b"\r\n" not in self._buf:
                if not self._read_more():
                    return out
            line, _, self._buf = self._buf.partition(b"\r\n")
            size = int(line.split(b";")[0].strip() or b"0", 16)
            if size == 0:
                return out
            while len(self._buf) < size + 2:
                if not self._read_more():
                    return out
            out += self._buf[:size]
            self._buf = self._buf[size + 2 :]


# ------------------------------------------------------------------ 测试框架


class Skip(Exception):
    """这个用例在当前环境下不适用（比如服务端是只读的）。"""


SLOW = False  # --slow 打开；标了 slow 的用例要等真实的超时，动辄一两分钟


@dataclass
class Case:
    section: str
    name: str
    fn: Callable[["Ctx"], None]
    slow: bool = False
    #: 非空表示「已知偏差」：某个实现做不到，原因已经查清并记录在规范里。
    #: 失败不算失败（不影响退出码），但会单独列出来；**通过了反而要提醒**——
    #: 说明实现改好了，或者另一端本来就做得到，标记该摘了。
    deviation: str = ""


REGISTRY: list[Case] = []


def case(
    section: str, name: str, slow: bool = False, deviation: str = ""
) -> Callable[[Callable[["Ctx"], None]], Callable[["Ctx"], None]]:
    def wrap(fn: Callable[["Ctx"], None]) -> Callable[["Ctx"], None]:
        REGISTRY.append(Case(section, name, fn, slow, deviation))
        return fn

    return wrap


def expect(cond: bool, msg: str) -> None:
    if not cond:
        raise AssertionError(msg)


def expect_eq(got: Any, want: Any, what: str) -> None:
    if got != want:
        raise AssertionError(f"{what}: 期望 {want!r}，实到 {got!r}")


@dataclass
class Ctx:
    host: str
    port: int
    token: str
    timeout: float
    info: dict[str, Any] = field(default_factory=dict)
    scratch: str = ""  # 服务端上的临时目录绝对路径

    def conn(self) -> Conn:
        return Conn(self.host, self.port, self.timeout)

    def get(self, target: str, **kw: Any) -> Response:
        with self.conn() as c:
            c.request("GET", target, token=self.token, **kw)
            return c.read_response()

    def post(self, target: str, body: bytes | None = None, **kw: Any) -> Response:
        with self.conn() as c:
            c.request("POST", target, body=body if body is not None else b"", token=self.token, **kw)
            return c.read_response()

    def reset_throttle(self) -> None:
        """
        成功校验一次即清零失败计数（§2.2「成功即清零」）。

        故意制造鉴权失败的用例**必须**在每次失败后调它，否则连续失败会超过
        宽限次数、触发退避，把后面所有用例一起带崩。
        专门验证退避本身的那条用例除外 —— 它要的就是累积。
        """
        self.get("/api/info")

    @property
    def writable(self) -> bool:
        return bool(self.info.get("writable", False))

    def need_write(self) -> None:
        if not self.writable:
            raise Skip("服务端是只读模式")
        if not self.scratch:
            raise Skip("没能建立临时目录")


def q(value: str) -> str:
    """百分号编码。规范 §2.3：'+' 不当空格，空格必须是 %20。"""
    safe = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-_.~"
    return "".join(ch if ch in safe else "".join(f"%{b:02X}" for b in ch.encode()) for ch in value)


# -------------------------------------------------------------- §1 设备发现


def discover(timeout: float = 2.0) -> list[dict[str, Any]]:
    found: dict[str, dict[str, Any]] = {}
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    sock.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
    sock.settimeout(0.25)
    try:
        for target in ("255.255.255.255", "127.0.0.1"):
            try:
                sock.sendto(PROBE, (target, DISCOVERY_PORT))
            except OSError:
                pass
        deadline = time.time() + timeout
        while time.time() < deadline:
            try:
                data, addr = sock.recvfrom(4096)
            except socket.timeout:
                continue
            except OSError:
                break
            try:
                obj = json.loads(data.decode("utf-8").strip())
            except Exception:
                continue
            if not isinstance(obj, dict):
                continue
            obj["_host"] = addr[0]
            found[f"{addr[0]}:{obj.get('port')}"] = obj
    finally:
        sock.close()
    return list(found.values())


@case("§1 发现", "应答是单行 JSON，afmu 和 port 必须有")
def t_discovery_shape(ctx: Ctx) -> None:
    peers = [p for p in discover() if p["_host"] in (ctx.host, "127.0.0.1")]
    if not peers:
        raise Skip("没收到本机/目标的发现应答（广播可能被网络拦了）")
    p = peers[0]
    expect_eq(p.get("afmu"), 1, "afmu 版本字段")
    expect(isinstance(p.get("port"), int) and p["port"] > 0, f"port 必须是正整数，实到 {p.get('port')!r}")
    # name / os 从 §1.5 起是可选的 —— 只在配对模式下才给
    if "os" in p:
        expect(p["os"] in ("android", "linux"), f"给了 os 就得是 android/linux，实到 {p['os']!r}")


@case("§1.5 发现", "常态应答不含设备名和系统")
def t_discovery_no_metadata(ctx: Ctx) -> None:
    """
    规范 §1.5。往 UDP 8766 发一个包，局域网里任何人都能收到
    「这台机器叫 icelab、跑 Linux」—— 一次不需要任何凭证的信息泄露，
    而且发生在用户完全不知情的时候。

    只有用户显式点了「允许被发现」才短暂公开，那是界面上的操作，
    这里测不了；这条用例守的是**默认**行为。
    """
    peers = [p for p in discover() if p["_host"] in (ctx.host, "127.0.0.1")]
    if not peers:
        raise Skip("没收到发现应答")
    for p in peers:
        leaked = [k for k in ("name", "os") if k in p]
        expect(
            not leaked,
            f"常态发现应答泄露了 {leaked}（§1.5）—— 这些只该在配对模式下出现: {p}",
        )


@case("§1.5 发现", "常态应答不含指纹；给了 rid 就得是 8 位 hex")
def t_discovery_rid(ctx: Ctx) -> None:
    """
    v2 草案 §6.1 的滚动 `rid`。它是加在 v1 应答上的**可选**字段，所以这条用例
    对没实现它的服务端只做形状检查、不要求存在。

    真正守的是另一半：常态应答里**绝不能有 `fp`**。指纹泄露不损失访问权，但它
    长期不变 —— 谁抓到一次，此后就能算出这台设备每个时间窗的 rid，追踪能力完整
    （§6.2 的注）。所以它只能出现在用户主动开启的那 60 秒里。
    """
    peers = [p for p in discover() if p["_host"] in (ctx.host, "127.0.0.1")]
    if not peers:
        raise Skip("没收到发现应答")
    for p in peers:
        expect("fp" not in p, f"常态发现应答泄露了指纹 —— 一次泄露 = 永久可追踪: {p}")
        rid = p.get("rid")
        if rid is None:
            continue  # 没实现 v2 发现，不是错误
        expect(
            isinstance(rid, str) and re.fullmatch(r"[0-9a-f]{8}", rid) is not None,
            f"rid 必须是 8 位小写十六进制，实到 {rid!r}",
        )


@case("§1 发现", "应答里绝不含 token")
def t_discovery_no_token(ctx: Ctx) -> None:
    peers = [p for p in discover() if p["_host"] in (ctx.host, "127.0.0.1")]
    if not peers:
        raise Skip("没收到发现应答")
    for p in peers:
        leaked = [k for k in p if "token" in k.lower()]
        expect(not leaked, f"发现应答泄露了 token 字段: {leaked}")
        expect(ctx.token not in json.dumps(p), "发现应答里出现了 token 的值")


# ---------------------------------------------------------------- §2.2 鉴权


@case("§2.2 鉴权", "无 token → 401")
def t_auth_missing(ctx: Ctx) -> None:
    with ctx.conn() as c:
        c.request("GET", "/api/info")  # 不带 token
        r = c.read_response()
    expect_eq(r.status, 401, "无 token 的状态码")
    expect_eq((r.json or {}).get("ok"), False, "401 响应体的 ok 字段")


@case("§2.2 鉴权", "错 token → 401")
def t_auth_wrong(ctx: Ctx) -> None:
    with ctx.conn() as c:
        c.request("GET", "/api/info", token="x" * len(ctx.token))
        r = c.read_response()
    expect_eq(r.status, 401, "错 token 的状态码")


@case("§2.2 鉴权", "两种写法都认：header / Bearer")
def t_auth_two_ways(ctx: Ctx) -> None:
    a = ctx.get("/api/info")
    expect_eq(a.status, 200, "X-AFMU-Token 头")

    with ctx.conn() as c:
        c.request("GET", "/api/info", headers={"Authorization": f"Bearer {ctx.token}"})
        d = c.read_response()
    expect_eq(d.status, 200, "Authorization: Bearer")


@case("§2.2 鉴权", "?token= 已移除，必须当作没带")
def t_auth_no_query_token(ctx: Ctx) -> None:
    """
    规范 §2.2。凭证进 URL 会落进代理日志、历史和 Referer，而且
    <img src="…?token=…"> 这种不带 Origin 的 GET 正好绕过 §2.4 的跨站防护。
    """
    for target in (
        f"/api/info?token={q(ctx.token)}",
        f"/api/list?token={q(ctx.token)}",
        f"/api/download?path=%2Fx&token={q(ctx.token)}",
    ):
        with ctx.conn() as c:
            c.request("GET", target)  # 只在 query 里带 token，不带任何头
            r = c.read_response()
        ctx.reset_throttle()
        expect_eq(r.status, 401, f"{target} 应被当作没带 token（§2.2）")


@case("§2.3 鉴权", "拒绝带请求体的请求时必须 Connection: close")
def t_auth_reject_closes(ctx: Ctx) -> None:
    """
    规范 §2.3 / §7 澄清表第一条。不发 close 的话，没被读走的请求体会被当成
    流水线里的下一个请求解析，产生一串莫名其妙的 400。
    """
    body = b"A" * 4096
    with ctx.conn() as c:
        c.request("POST", "/api/upload?name=x.bin", body=body)  # 无 token
        r = c.read_response()
        expect_eq(r.status, 401, "无 token 上传的状态码")
        expect(
            r.closes,
            "401 拒绝了一个带请求体的请求，却没有发 Connection: close —— "
            "剩下的请求体会被当成下一个请求（§2.3）",
        )


@case("§2.3 鉴权", "被拒之后连接确实断开，不会把 body 当新请求")
def t_auth_reject_no_pipeline_garbage(ctx: Ctx) -> None:
    """
    要挡住的是「剩下的请求体被当成流水线里的下一个请求」——那会冒出一串
    莫名其妙的 400，甚至真的执行掉 body 里那个 delete。

    连接怎么断不重要：服务端关一个接收缓冲区里还压着未读数据的 socket 时，
    内核发的是 RST 而不是 FIN，这本来就是时序相关的。FIN 和 RST 都算通过，
    唯一不能出现的是**又一个 HTTP 响应**。
    """
    body = b"POST /api/delete?path=%2F HTTP/1.1\r\nHost: x\r\n\r\n" * 4
    with ctx.conn() as c:
        c.request("POST", "/api/upload?name=x.bin", body=body)
        r = c.read_response()
        expect_eq(r.status, 401, "状态码")
        c.sock.settimeout(3.0)
        try:
            extra = c.sock.recv(4096)
        except ConnectionResetError:
            return  # RST：连接没了，body 没被解析，正是我们要的
        except socket.timeout:
            raise AssertionError("服务端既没断开也没再回应；请求体大概被挂起了")
        expect(
            b"HTTP/1." not in extra,
            f"401 之后又冒出了 HTTP 响应，说明剩下的 body 被当成新请求解析了: {extra[:120]!r}",
        )


# ------------------------------------------------------------------ §3.1 info


@case("§2.4 Host", "DNS 名字的 Host → 403")
def t_host_rebinding(ctx: Ctx) -> None:
    """
    规范 §2.4。攻击者页面请求 http://evil.example.com:8765/，域名解析到本机。
    同源策略帮不上忙 —— 源就是攻击者的域名。唯一的分辨点就是 Host 头。
    """
    for bad in (
        "evil.example.com",
        f"evil.example.com:{ctx.port}",
        "attacker.internal:8765",
        "afmu.example.org",
    ):
        with ctx.conn() as c:
            c.request("GET", "/api/info", headers={"Host": bad}, token=ctx.token)
            r = c.read_response()
        expect_eq(r.status, 403, f"Host: {bad} 应被拒（§2.4）")
        expect_eq((r.json or {}).get("ok"), False, "ok")


@case("§2.4 Host", "宽松写法的 IPv4 也要拒")
def t_host_loose_ipv4(ctx: Ctx) -> None:
    """宽松的 IP 解析器正是 rebinding 想要的：只接受四段十进制、每段 0..255。"""
    for bad in ("0x7f.0.0.1", "1.2.3", "2130706433", "127.0.0.1.5", "999.1.1.1", "1.2.3.4.5"):
        with ctx.conn() as c:
            c.request("GET", "/api/info", headers={"Host": f"{bad}:{ctx.port}"}, token=ctx.token)
            r = c.read_response()
        expect_eq(r.status, 403, f"Host: {bad} 是宽松写法，应被拒（§2.4）")


@case("§2.4 Host", "IP 字面量 / localhost / .local 放行")
def t_host_allowed_shapes(ctx: Ctx) -> None:
    for good in (f"{ctx.host}:{ctx.port}", f"localhost:{ctx.port}", f"some-box.local:{ctx.port}"):
        with ctx.conn() as c:
            c.request("GET", "/api/info", headers={"Host": good}, token=ctx.token)
            r = c.read_response()
        expect_eq(r.status, 200, f"Host: {good} 应放行（§2.4）")


@case("§2.4 Origin", "跨源的 Origin → 403，缺失则放行")
def t_origin_check(ctx: Ctx) -> None:
    authority = f"{ctx.host}:{ctx.port}"

    # 缺失：原生客户端就是这样，必须放行
    expect_eq(ctx.get("/api/info").status, 200, "没有 Origin 时应放行（§2.4）")

    # 同源：浏览器界面自己发的
    with ctx.conn() as c:
        c.request("GET", "/api/info", headers={"Origin": f"http://{authority}"}, token=ctx.token)
        same = c.read_response()
    expect_eq(same.status, 200, "同源的 Origin 应放行")

    for bad in (
        "http://evil.example.com",
        f"http://evil.example.com:{ctx.port}",
        "null",
        f"http://{ctx.host}:9999",  # 同主机不同端口 —— 端口必须一起比
    ):
        with ctx.conn() as c:
            c.request("GET", "/api/info", headers={"Origin": bad}, token=ctx.token)
            r = c.read_response()
        expect_eq(r.status, 403, f"Origin: {bad} 应被拒（§2.4）")


@case("§2.4 Host", "检查排在 token 之前，也覆盖 GET /")
def t_host_check_ordering(ctx: Ctx) -> None:
    """这两条和有没有凭证无关，也不能只保护 /api/*：浏览器界面才是入口。"""
    with ctx.conn() as c:
        c.request("GET", "/api/info", headers={"Host": "evil.example.com"})  # 连 token 都不带
        r = c.read_response()
    expect_eq(r.status, 403, "坏 Host 应在 token 检查之前就被拒（不是 401）")

    with ctx.conn() as c:
        c.request("GET", "/", headers={"Host": "evil.example.com"})
        root = c.read_response()
    expect_eq(root.status, 403, "GET / 也必须过 Host 检查（§2.4）")


@case("§3.1 info", "必填字段齐全且类型正确")
def t_info_fields(ctx: Ctx) -> None:
    r = ctx.get("/api/info")
    expect_eq(r.status, 200, "状态码")
    j = r.json or {}
    expect_eq(j.get("ok"), True, "ok")
    for key, typ in (("name", str), ("os", str), ("inbox", str)):
        expect(isinstance(j.get(key), typ), f"{key} 必须是 {typ.__name__}，实到 {j.get(key)!r}")
    expect_eq(j.get("protocol"), 1, "protocol 版本")
    expect(isinstance(j.get("writable"), bool), "writable 必须是布尔")
    roots = j.get("roots")
    expect(isinstance(roots, list) and roots, "roots 必须是非空数组")
    for root in roots:
        expect(isinstance(root.get("name"), str), "root.name")
        expect(isinstance(root.get("path"), str) and root["path"].startswith("/"), "root.path 必须是绝对路径")


@case("§3.1 info", "Cache-Control: no-store")
def t_info_no_store(ctx: Ctx) -> None:
    r = ctx.get("/api/info")
    expect_eq((r.header("cache-control") or "").lower(), "no-store", "Cache-Control")


# ------------------------------------------------------------------ §3.2 list


@case("§3.2 list", "省略 path 与 path=/ 都返回根列表，parent 为 null")
def t_list_root(ctx: Ctx) -> None:
    for target in ("/api/list", "/api/list?path=%2F"):
        r = ctx.get(target)
        expect_eq(r.status, 200, f"{target} 状态码")
        j = r.json or {}
        expect_eq(j.get("ok"), True, "ok")
        expect("parent" in j and j["parent"] is None, f"{target} 的 parent 应为 null，实到 {j.get('parent')!r}")
        expect(isinstance(j.get("entries"), list), "entries 必须是数组")


@case("§3.2 list", "根列表和 info.roots 一致")
def t_list_root_matches_info(ctx: Ctx) -> None:
    roots = {r["path"] for r in ctx.info.get("roots", [])}
    entries = (ctx.get("/api/list").json or {}).get("entries", [])
    listed = {e["path"] for e in entries}
    expect_eq(listed, roots, "根列表与 info.roots")


@case("§3.2 list", "排序：目录在前，然后按文件名小写升序")
def t_list_sort(ctx: Ctx) -> None:
    ctx.need_write()
    base = ctx.scratch
    for name in ("Zebra.txt", "alpha.txt", "M.txt"):
        upload_raw(ctx, base, name, b"x")
    for name in ("zdir", "Adir"):
        r = ctx.post(f"/api/mkdir?path={q(base)}&name={q(name)}")
        expect_eq(r.status, 200, f"mkdir {name}")

    entries = (ctx.get(f"/api/list?path={q(base)}").json or {}).get("entries", [])
    got = [(e["dir"], e["name"]) for e in entries]
    want = sorted(got, key=lambda t: (not t[0], t[1].lower()))
    expect_eq(got, want, "排序")


@case("§3.2 list", "mtime 是 Unix 秒，不是毫秒")
def t_list_mtime_seconds(ctx: Ctx) -> None:
    ctx.need_write()
    upload_raw(ctx, ctx.scratch, "mtime-probe.txt", b"now")
    entries = (ctx.get(f"/api/list?path={q(ctx.scratch)}").json or {}).get("entries", [])
    probe = next((e for e in entries if e["name"] == "mtime-probe.txt"), None)
    expect(probe is not None, "没找到刚上传的文件")
    now = time.time()
    mtime = probe["mtime"]
    expect(
        abs(mtime - now) < 86400,
        f"mtime={mtime} 和当前时间 {int(now)} 差太远 —— 多半是发成了毫秒（§3.2）",
    )


@case("§3.2 list", "目录的 size 恒为 0")
def t_list_dir_size(ctx: Ctx) -> None:
    entries = (ctx.get("/api/list").json or {}).get("entries", [])
    for e in entries:
        if e.get("dir"):
            expect_eq(e.get("size"), 0, f"目录 {e['name']} 的 size")


@case("§3.2 list", "路径不存在 → 404")
def t_list_missing(ctx: Ctx) -> None:
    r = ctx.get(f"/api/list?path={q('/definitely/not/here-' + uuid.uuid4().hex)}")
    expect_eq(r.status, 404, "状态码")
    expect_eq((r.json or {}).get("ok"), False, "ok")


@case("§4.1 越界", "../ 穿越 → 404，且不泄露真实原因")
def t_traversal(ctx: Ctx) -> None:
    root = ctx.info["roots"][0]["path"]
    for probe in (
        f"{root}/../../../../etc",
        "/etc",
        "/etc/passwd",
        f"{root}/../..",
    ):
        r = ctx.get(f"/api/list?path={q(probe)}")
        expect(
            r.status == 404,
            f"{probe} 应该回 404（当作不存在），实到 {r.status}",
        )
        err = ((r.json or {}).get("error") or "").lower()
        for word in ("denied", "forbidden", "outside", "越界", "permission"):
            expect(word not in err, f"错误信息泄露了真实原因: {err!r}（§4.1）")


# -------------------------------------------------------------- §3.3 download


def upload_raw(ctx: Ctx, directory: str, name: str, data: bytes, overwrite: bool = True) -> dict[str, Any]:
    target = f"/api/upload?name={q(name)}&dir={q(directory)}"
    if overwrite:
        target += "&overwrite=1"
    r = ctx.post(target, body=data, headers={"Content-Type": "application/octet-stream"})
    expect_eq(r.status, 200, f"上传 {name} 的状态码（{(r.json or {}).get('error')}）")
    j = r.json or {}
    expect_eq(j.get("ok"), True, "上传的 ok")
    expect(isinstance(j.get("saved"), list) and j["saved"], "saved 必须非空")
    return j


@case("§3.3 download", "响应头齐全，内容字节一致")
def t_download_headers(ctx: Ctx) -> None:
    ctx.need_write()
    payload = bytes(range(256)) * 8  # 2048 字节，覆盖全部字节值
    saved = upload_raw(ctx, ctx.scratch, "dl-basic.bin", payload)["saved"][0]

    r = ctx.get(f"/api/download?path={q(saved)}")
    expect_eq(r.status, 200, "状态码")
    expect_eq(r.body, payload, "下载内容与上传内容")
    expect_eq(r.header("content-length"), str(len(payload)), "Content-Length")
    expect_eq((r.header("accept-ranges") or "").lower(), "bytes", "Accept-Ranges")
    expect(r.header("last-modified") is not None, "缺 Last-Modified")
    cd = r.header("content-disposition") or ""
    expect("attachment" in cd.lower(), f"Content-Disposition 应含 attachment，实到 {cd!r}")
    expect("filename*=UTF-8''" in cd, f"Content-Disposition 缺 RFC 5987 的 filename*，实到 {cd!r}")


@case("§3.3 download", "中文文件名走 filename*=UTF-8''")
def t_download_utf8_name(ctx: Ctx) -> None:
    ctx.need_write()
    name = "测试 文件.txt"
    saved = upload_raw(ctx, ctx.scratch, name, b"zh")["saved"][0]
    r = ctx.get(f"/api/download?path={q(saved)}")
    expect_eq(r.status, 200, "状态码")
    cd = r.header("content-disposition") or ""
    m = re.search(r"filename\*=UTF-8''([^;]+)", cd)
    expect(m is not None, f"缺 filename*，实到 {cd!r}")
    decoded = re.sub(r"%([0-9A-Fa-f]{2})", lambda x: chr(int(x.group(1), 16)), m.group(1))
    decoded = bytes(ord(c) for c in decoded).decode("utf-8", "replace")
    expect(decoded.endswith(".txt") and "测试" in decoded, f"filename* 解出来是 {decoded!r}")


@case("§3.3 download", "Range: bytes=a-b → 206 + Content-Range")
def t_range_basic(ctx: Ctx) -> None:
    ctx.need_write()
    payload = bytes(range(256)) * 4  # 1024
    saved = upload_raw(ctx, ctx.scratch, "dl-range.bin", payload)["saved"][0]

    with ctx.conn() as c:
        c.request("GET", f"/api/download?path={q(saved)}", headers={"Range": "bytes=100-199"}, token=ctx.token)
        r = c.read_response()
    expect_eq(r.status, 206, "状态码")
    expect_eq(r.body, payload[100:200], "分片内容")
    expect_eq(r.header("content-length"), "100", "Content-Length")
    expect_eq(r.header("content-range"), f"bytes 100-199/{len(payload)}", "Content-Range")


@case("§3.3 download", "Range: bytes=N- 开放上界")
def t_range_open(ctx: Ctx) -> None:
    ctx.need_write()
    payload = b"0123456789" * 100
    saved = upload_raw(ctx, ctx.scratch, "dl-open.bin", payload)["saved"][0]
    with ctx.conn() as c:
        c.request("GET", f"/api/download?path={q(saved)}", headers={"Range": "bytes=990-"}, token=ctx.token)
        r = c.read_response()
    expect_eq(r.status, 206, "状态码")
    expect_eq(r.body, payload[990:], "分片内容")
    expect_eq(r.header("content-range"), f"bytes 990-999/{len(payload)}", "Content-Range")


@case("§3.3 download", "Range: bytes=-N 取末尾")
def t_range_suffix(ctx: Ctx) -> None:
    ctx.need_write()
    payload = b"0123456789" * 100
    saved = upload_raw(ctx, ctx.scratch, "dl-suffix.bin", payload)["saved"][0]
    with ctx.conn() as c:
        c.request("GET", f"/api/download?path={q(saved)}", headers={"Range": "bytes=-50"}, token=ctx.token)
        r = c.read_response()
    expect_eq(r.status, 206, "状态码")
    expect_eq(r.body, payload[-50:], "末尾 50 字节")
    expect_eq(r.header("content-range"), f"bytes 950-999/{len(payload)}", "Content-Range")


@case("§3.3 download", "越界 Range → 416 + Content-Range: bytes */total")
def t_range_unsatisfiable(ctx: Ctx) -> None:
    ctx.need_write()
    payload = b"x" * 100
    saved = upload_raw(ctx, ctx.scratch, "dl-416.bin", payload)["saved"][0]
    with ctx.conn() as c:
        c.request("GET", f"/api/download?path={q(saved)}", headers={"Range": "bytes=500-600"}, token=ctx.token)
        r = c.read_response()
    expect_eq(r.status, 416, "状态码")
    expect_eq(r.header("content-range"), f"bytes */{len(payload)}", "Content-Range")
    expect_eq(r.body, b"", "416 不应有响应体")


@case("§7 澄清", "畸形 Range（bytes=zz-）→ 416，不能是 500")
def t_range_malformed(ctx: Ctx) -> None:
    """规范 §7 末尾：数字解析异常不许冒成 500。"""
    ctx.need_write()
    payload = b"y" * 100
    saved = upload_raw(ctx, ctx.scratch, "dl-bad-range.bin", payload)["saved"][0]
    for spec in ("bytes=zz-", "bytes=-", "bytes=abc-def", "bytes=1-x"):
        with ctx.conn() as c:
            c.request("GET", f"/api/download?path={q(saved)}", headers={"Range": spec}, token=ctx.token)
            r = c.read_response()
        expect(
            r.status == 416,
            f"Range: {spec} 应回 416，实到 {r.status}（500 说明解析异常冒上来了）",
        )
        expect_eq(r.header("content-range"), f"bytes */{len(payload)}", f"{spec} 的 Content-Range")


@case("§3.3 download", "HEAD 返回同样的头、无响应体")
def t_head(ctx: Ctx) -> None:
    ctx.need_write()
    payload = b"z" * 777
    saved = upload_raw(ctx, ctx.scratch, "dl-head.bin", payload)["saved"][0]

    g = ctx.get(f"/api/download?path={q(saved)}")
    with ctx.conn() as c:
        c.request("HEAD", f"/api/download?path={q(saved)}", token=ctx.token)
        h = c.read_response(want_body=False)

    expect_eq(h.status, 200, "HEAD 状态码")
    expect_eq(h.header("content-length"), str(len(payload)), "HEAD 的 Content-Length")
    for key in ("content-type", "accept-ranges", "content-disposition"):
        expect_eq(h.header(key), g.header(key), f"HEAD 与 GET 的 {key} 应一致")


@case("§3.3 download", "不存在的文件 → 404")
def t_download_missing(ctx: Ctx) -> None:
    r = ctx.get(f"/api/download?path={q('/nope/' + uuid.uuid4().hex)}")
    expect_eq(r.status, 404, "状态码")


# ---------------------------------------------------------------- §3.4 upload


@case("§2.5 下载券", "签券后能凭券下载，且券只对那一个路径有效")
def t_ticket_roundtrip(ctx: Ctx) -> None:
    ctx.need_write()
    payload = b"ticketed content"
    a = upload_raw(ctx, ctx.scratch, "tk-a.bin", payload)["saved"][0]
    b = upload_raw(ctx, ctx.scratch, "tk-b.bin", b"other")["saved"][0]

    r = ctx.get(f"/api/ticket?path={q(a)}")
    if r.status == 404 and "unknown endpoint" in str((r.json or {}).get("error", "")):
        raise Skip("对端未实现 /api/ticket")
    expect_eq(r.status, 200, f"签券状态码（{(r.json or {}).get('error')}）")
    j = r.json or {}
    ticket = j.get("ticket")
    expect(isinstance(ticket, str) and "." in ticket, f"券的形状不对: {ticket!r}")
    expect(int(j.get("expires", 0)) > 0, "expires 应为正")

    # 凭券下载，**不带任何 token 头**
    with ctx.conn() as c:
        c.request("GET", f"/api/download?path={q(a)}&ticket={q(ticket)}")
        dl = c.read_response()
    expect_eq(dl.status, 200, f"凭券下载失败（{(dl.json or {}).get('error')}）")
    expect_eq(dl.body, payload, "券取回的内容")

    # 同一张券换个路径必须失效 —— 券绑定路径
    with ctx.conn() as c:
        c.request("GET", f"/api/download?path={q(b)}&ticket={q(ticket)}")
        other = c.read_response()
    ctx.reset_throttle()
    expect_eq(other.status, 401, "券被用到别的路径上却放行了（§2.5）")


@case("§2.5 下载券", "伪造和篡改的券一律拒")
def t_ticket_forgery(ctx: Ctx) -> None:
    ctx.need_write()
    path = upload_raw(ctx, ctx.scratch, "tk-forge.bin", b"secret")["saved"][0]

    probe = ctx.get(f"/api/ticket?path={q(path)}")
    if probe.status == 404 and "unknown endpoint" in str((probe.json or {}).get("error", "")):
        raise Skip("对端未实现 /api/ticket")
    real = (probe.json or {})["ticket"]
    exp, _, mac = real.partition(".")

    forged = [
        f"{exp}.{'A' * len(mac)}",                    # MAC 全错
        f"{exp}.{mac[:-1]}{'A' if mac[-1] != 'A' else 'B'}",  # 改 MAC 最后一位
        f"{int(exp) + 3600}.{mac}",                   # 延长有效期，MAC 不变
        f"1.{mac}",                                   # 早就过期
        mac,                                          # 没有分隔点
        f"{exp}.",                                    # 空 MAC
        "..",
        "",
    ]
    for bad in forged:
        with ctx.conn() as c:
            c.request("GET", f"/api/download?path={q(path)}&ticket={q(bad)}")
            r = c.read_response()
        # 每次失败后清零，否则连打 8 个会触发 §2.2 的退避，把后面的用例带崩
        ctx.reset_throttle()
        expect_eq(r.status, 401, f"伪造的券 {bad!r} 被放行了（§2.5）")


@case("§2.5 下载券", "券不能用来签新券，/api/ticket 只认头")
def t_ticket_not_self_serving(ctx: Ctx) -> None:
    ctx.need_write()
    path = upload_raw(ctx, ctx.scratch, "tk-chain.bin", b"x")["saved"][0]
    probe = ctx.get(f"/api/ticket?path={q(path)}")
    if probe.status == 404 and "unknown endpoint" in str((probe.json or {}).get("error", "")):
        raise Skip("对端未实现 /api/ticket")
    ticket = (probe.json or {})["ticket"]

    with ctx.conn() as c:
        c.request("GET", f"/api/ticket?path={q(path)}&ticket={q(ticket)}")  # 不带头
        r = c.read_response()
    ctx.reset_throttle()
    expect_eq(r.status, 401, "券被用来签新券了（§2.5）")


@case("§2.5 下载券", "越界路径不签券，且不泄露它是否存在")
def t_ticket_out_of_bounds(ctx: Ctx) -> None:
    probe = ctx.get("/api/ticket?path=%2Fetc%2Fpasswd")
    if probe.status == 404 and "unknown endpoint" in str((probe.json or {}).get("error", "")):
        raise Skip("对端未实现 /api/ticket")
    expect_eq(probe.status, 404, "越界路径应回 404（§2.5）")
    expect(
        (probe.json or {}).get("ticket") is None,
        "越界路径居然签出了券",
    )


@case("§3.4 upload", "原始字节流 + Content-Length")
def t_upload_raw(ctx: Ctx) -> None:
    ctx.need_write()
    payload = os.urandom(64 * 1024)
    saved = upload_raw(ctx, ctx.scratch, "up-raw.bin", payload)["saved"][0]
    back = ctx.get(f"/api/download?path={q(saved)}")
    expect_eq(back.body, payload, "回读内容")


@case("§3.4 upload", "chunked 传输编码")
def t_upload_chunked(ctx: Ctx) -> None:
    ctx.need_write()
    payload = os.urandom(20000)
    chunks = b""
    for i in range(0, len(payload), 4096):
        part = payload[i : i + 4096]
        chunks += f"{len(part):x}\r\n".encode() + part + b"\r\n"
    chunks += b"0\r\n\r\n"

    with ctx.conn() as c:
        c.request(
            "POST",
            f"/api/upload?name={q('up-chunked.bin')}&dir={q(ctx.scratch)}&overwrite=1",
            headers={"Transfer-Encoding": "chunked", "Content-Type": "application/octet-stream"},
            body=chunks,
            token=ctx.token,
        )
        r = c.read_response()
    expect_eq(r.status, 200, f"状态码（{(r.json or {}).get('error')}）")
    saved = (r.json or {})["saved"][0]
    expect_eq(ctx.get(f"/api/download?path={q(saved)}").body, payload, "回读内容")


@case("§3.4 upload", "multipart/form-data，多个文件段")
def t_upload_multipart(ctx: Ctx) -> None:
    ctx.need_write()
    boundary = "----afmuTest" + uuid.uuid4().hex
    files = {"mp-a.txt": b"first file", "mp-b.bin": os.urandom(3000)}

    body = b""
    body += f"--{boundary}\r\n".encode()
    body += b'Content-Disposition: form-data; name="ignored"\r\n\r\n'
    body += b"a plain field, must be dropped\r\n"
    for name, data in files.items():
        body += f"--{boundary}\r\n".encode()
        body += f'Content-Disposition: form-data; name="file"; filename="{name}"\r\n'.encode()
        body += b"Content-Type: application/octet-stream\r\n\r\n"
        body += data + b"\r\n"
    body += f"--{boundary}--\r\n".encode()

    r = ctx.post(
        f"/api/upload?dir={q(ctx.scratch)}&overwrite=1",
        body=body,
        headers={"Content-Type": f"multipart/form-data; boundary={boundary}"},
    )
    expect_eq(r.status, 200, f"状态码（{(r.json or {}).get('error')}）")
    saved = (r.json or {}).get("saved") or []
    expect_eq(len(saved), 2, f"应该只存两个文件段（普通字段要丢弃），实到 {saved}")
    for path, (_, data) in zip(saved, files.items()):
        expect_eq(ctx.get(f"/api/download?path={q(path)}").body, data, f"{path} 的内容")


def _send_truncated_multipart(ctx: Ctx, victim: str) -> Response | None:
    """
    发一个缺结尾边界的 multipart，然后半关闭连接。
    返回服务端的响应；服务端直接断开则返回 None。

    截断只能靠**半关闭**触发：服务端读到 EOF 才知道 body 没了。
    光发短 body 不关连接的话，两端实现都会一直等到 idle 超时。
    """
    boundary = "----afmuTrunc" + uuid.uuid4().hex
    head = f"--{boundary}\r\n".encode()
    head += f'Content-Disposition: form-data; name="file"; filename="{victim}"\r\n'.encode()
    head += b"Content-Type: application/octet-stream\r\n\r\n"
    partial = head + b"P" * 5000  # 没有结尾边界

    with ctx.conn() as c:
        c.request(
            "POST",
            f"/api/upload?dir={q(ctx.scratch)}&overwrite=1",
            headers={
                "Content-Type": f"multipart/form-data; boundary={boundary}",
                "Content-Length": str(len(partial) + 100_000),  # 承诺了更多却不发
            },
            body=None,
            token=ctx.token,
        )
        c.send_raw(partial)
        c.half_close()
        try:
            return c.read_response()
        except (EOFError, TimeoutError):
            return None


@case("§7 澄清", "截断的上传绝不能回 ok:true，也不能留下残片")
def t_upload_truncated_invariant(ctx: Ctx) -> None:
    """
    规范 §3.4 / §4.3 —— 这是最坏的一种失败方式：回 ok:true 的话客户端会用
    原文件名显示成功，文件静默丢失、用户毫不知情。

    **这是硬不变量**，不管服务端选择回 400 还是直接断开，都必须守住。
    """
    ctx.need_write()
    victim = "trunc-victim.bin"
    r = _send_truncated_multipart(ctx, victim)

    if r is not None:
        expect(
            not (r.status == 200 and (r.json or {}).get("ok") is True),
            f"截断的上传回了 ok:true —— 文件会静默丢失（§3.4）：{r.body[:200]!r}",
        )

    entries = (ctx.get(f"/api/list?path={q(ctx.scratch)}").json or {}).get("entries", [])
    names = [e["name"] for e in entries]
    expect(victim not in names, f"截断的上传留下了 {victim} —— 半个文件冒充了完整文件（§4.3）")
    leftovers = [n for n in names if n.endswith(".afmu-part")]
    expect(not leftovers, f"留下了临时残片: {leftovers}（§4.3 要求中断时删掉）")


@case(
    "§7 澄清",
    "截断应回 400，而不是直接断开连接",
    deviation="Linux/Qt：QTcpSocket 在对端 FIN 时已转 UnconnectedState，响应发不出去",
)
def t_upload_truncated_status(ctx: Ctx) -> None:
    """
    规范 §3.4 明写「回 400 并删掉 .afmu-part」。直接断开虽然守住了不变量
    （见上一条），但客户端分不清「服务端拒绝了」和「网线被拔了」。

    **Linux/Qt 端做不到，原因已经查清**（PROTOCOL.md §3.4「已知偏差」）：
    试过挂 readChannelFinished —— 信号确实触发、phase 也对，但那一刻
    socket 已经是 UnconnectedState，写进去的字节直接丢弃。
    要修得把连接层从 QTcpSocket 换成 QSocketNotifier + 裸 fd。

    Android 端用的是阻塞 socket，对端半关闭之后仍然可写，**应该能通过**。
    真通过了这里会提示摘掉 deviation 标记。
    """
    ctx.need_write()
    r = _send_truncated_multipart(ctx, "trunc-status.bin")
    expect(r is not None, "服务端在截断时直接断开了连接，没有回 400（§3.4）")
    expect_eq(r.status, 400, f"状态码（body={r.body[:200]!r}）")
    expect_eq((r.json or {}).get("ok"), False, "ok 必须是 false")


@case("§7 澄清", "没有文件段 → 400，不能是 ok:true + saved:[]")
def t_upload_no_file_part(ctx: Ctx) -> None:
    ctx.need_write()
    boundary = "----afmuEmpty" + uuid.uuid4().hex
    body = f"--{boundary}\r\n".encode()
    body += b'Content-Disposition: form-data; name="just_a_field"\r\n\r\n'
    body += b"no filename here\r\n"
    body += f"--{boundary}--\r\n".encode()

    r = ctx.post(
        f"/api/upload?dir={q(ctx.scratch)}",
        body=body,
        headers={"Content-Type": f"multipart/form-data; boundary={boundary}"},
    )
    j = r.json or {}
    expect(
        not (r.status == 200 and j.get("ok") is True),
        "一个文件段都没有却回了 ok:true —— 客户端会以为成功了（§3.4）",
    )
    expect_eq(r.status, 400, "状态码")


@case("§3.4 upload", "缺 multipart boundary → 400")
def t_upload_no_boundary(ctx: Ctx) -> None:
    ctx.need_write()
    r = ctx.post(
        f"/api/upload?dir={q(ctx.scratch)}",
        body=b"whatever",
        headers={"Content-Type": "multipart/form-data"},
    )
    expect_eq(r.status, 400, "状态码")


@case("§4.4 upload", "overwrite!=1 时自动改名为「名字 (1).扩展名」")
def t_upload_autorename(ctx: Ctx) -> None:
    ctx.need_write()
    name = "dup.txt"
    first = upload_raw(ctx, ctx.scratch, name, b"one")["saved"][0]
    second = ctx.post(
        f"/api/upload?name={q(name)}&dir={q(ctx.scratch)}",  # 不带 overwrite
        body=b"two",
        headers={"Content-Type": "application/octet-stream"},
    )
    expect_eq(second.status, 200, "第二次上传的状态码")
    other = (second.json or {})["saved"][0]
    expect(other != first, "同名文件没有改名，把第一个覆盖了")
    expect("(1)" in os.path.basename(other), f"改名格式应是「名字 (1).扩展名」，实到 {other!r}")
    expect_eq(ctx.get(f"/api/download?path={q(first)}").body, b"one", "第一个文件不该被动过")


@case("§3.4 upload", "overwrite=1 时确实覆盖")
def t_upload_overwrite(ctx: Ctx) -> None:
    ctx.need_write()
    name = "ow.txt"
    a = upload_raw(ctx, ctx.scratch, name, b"before")["saved"][0]
    b = upload_raw(ctx, ctx.scratch, name, b"after")["saved"][0]
    expect_eq(b, a, "overwrite=1 应写回同一个路径")
    expect_eq(ctx.get(f"/api/download?path={q(a)}").body, b"after", "内容")


@case("§4.2 安全化", "文件名里的路径被剥掉")
def t_name_strip_path(ctx: Ctx) -> None:
    ctx.need_write()
    saved = upload_raw(ctx, ctx.scratch, "../../etc/passwd", b"nope")["saved"][0]
    expect(
        os.path.dirname(saved.rstrip("/")) == ctx.scratch.rstrip("/"),
        f"带路径的文件名逃出了目标目录: {saved!r}",
    )
    expect_eq(os.path.basename(saved), "passwd", "应只留最后一段")


@case("§7 澄清", "文件名恰好是 . 或 .. → unnamed")
def t_name_dots(ctx: Ctx) -> None:
    """
    §7 澄清表第四条。剥路径对它们不起作用（里面没有 /），
    而 File(dir, "..") 指向父目录，不是 dir 里的一个文件。
    """
    ctx.need_write()
    for name in ("..", "."):
        saved = upload_raw(ctx, ctx.scratch, name, b"dots")["saved"][0]
        base = os.path.basename(saved.rstrip("/"))
        expect(
            base.startswith("unnamed"),
            f"文件名 {name!r} 应安全化成 unnamed，实到 {base!r}（路径 {saved!r}）",
        )
        expect(
            saved.rstrip("/") != os.path.dirname(ctx.scratch.rstrip("/")),
            f"文件名 {name!r} 写到了父目录: {saved!r}",
        )


@case("§4.2 安全化", "非法字符替换为下划线")
def t_name_illegal_chars(ctx: Ctx) -> None:
    ctx.need_write()
    saved = upload_raw(ctx, ctx.scratch, 'we:ir<d>"na|me?.txt', b"chars")["saved"][0]
    base = os.path.basename(saved)
    for ch in ':<>"|?*':
        expect(ch not in base, f"文件名里残留了非法字符 {ch!r}: {base!r}（§4.2 兼容 FAT32）")


@case("§3.4 upload", "dir 越界时落到 inbox，不报错")
def t_upload_dir_out_of_bounds(ctx: Ctx) -> None:
    ctx.need_write()
    r = ctx.post(
        f"/api/upload?name={q('stray.txt')}&dir={q('/etc')}&overwrite=1",
        body=b"stray",
        headers={"Content-Type": "application/octet-stream"},
    )
    expect_eq(r.status, 200, "越界的 dir 应静默落到 inbox，而不是报错")
    saved = (r.json or {})["saved"][0]
    expect(not saved.startswith("/etc"), f"文件写到了 /etc: {saved!r}")
    cleanup(ctx, saved)


# ----------------------------------------------------------- §3.5/3.6 目录操作


@case("§3.5 mkdir", "建目录并出现在列表里")
def t_mkdir(ctx: Ctx) -> None:
    ctx.need_write()
    name = "sub-" + uuid.uuid4().hex[:6]
    r = ctx.post(f"/api/mkdir?path={q(ctx.scratch)}&name={q(name)}")
    expect_eq(r.status, 200, f"状态码（{(r.json or {}).get('error')}）")
    created = (r.json or {}).get("path")
    expect(isinstance(created, str) and created.endswith(name), f"返回的 path 不对: {created!r}")
    entries = (ctx.get(f"/api/list?path={q(ctx.scratch)}").json or {}).get("entries", [])
    match = next((e for e in entries if e["name"] == name), None)
    expect(match is not None, "新建的目录没出现在列表里")
    expect_eq(match["dir"], True, "新建项的 dir 标志")


@case("§3.5 mkdir", "缺 path 或 name → 400")
def t_mkdir_bad_args(ctx: Ctx) -> None:
    ctx.need_write()
    expect_eq(ctx.post(f"/api/mkdir?path={q(ctx.scratch)}").status, 400, "缺 name")
    expect_eq(ctx.post(f"/api/mkdir?name={q('x')}").status, 400, "缺 path")


@case("§3.6 delete", "删文件")
def t_delete_file(ctx: Ctx) -> None:
    ctx.need_write()
    saved = upload_raw(ctx, ctx.scratch, "to-delete.txt", b"bye")["saved"][0]
    r = ctx.post(f"/api/delete?path={q(saved)}")
    expect_eq(r.status, 200, f"状态码（{(r.json or {}).get('error')}）")
    expect_eq(ctx.get(f"/api/download?path={q(saved)}").status, 404, "删完还能下载到")


@case("§3.6 delete", "非空目录需要 recursive=1")
def t_delete_recursive(ctx: Ctx) -> None:
    ctx.need_write()
    name = "tree-" + uuid.uuid4().hex[:6]
    created = (ctx.post(f"/api/mkdir?path={q(ctx.scratch)}&name={q(name)}").json or {})["path"]
    upload_raw(ctx, created, "inside.txt", b"content")

    bare = ctx.post(f"/api/delete?path={q(created)}")
    expect(bare.status != 200, "非空目录在没有 recursive=1 时被删掉了")
    expect_eq(ctx.get(f"/api/list?path={q(created)}").status, 200, "目录应该还在")

    r = ctx.post(f"/api/delete?path={q(created)}&recursive=1")
    expect_eq(r.status, 200, f"recursive=1 的状态码（{(r.json or {}).get('error')}）")
    expect_eq(ctx.get(f"/api/list?path={q(created)}").status, 404, "recursive 删完目录还在")


@case("§7 澄清", "删 root 本身 → 403")
def t_delete_root_refused(ctx: Ctx) -> None:
    """§3.6 / §7 澄清表第三条。recursive=1 删 root 是一次不可逆的灾难。"""
    ctx.need_write()
    for root in ctx.info.get("roots", []):
        path = root["path"]
        r = ctx.post(f"/api/delete?path={q(path)}&recursive=1")
        expect_eq(r.status, 403, f"删 root {path} 应回 403，实到 {r.status}")
        expect_eq(ctx.get(f"/api/list?path={q(path)}").status, 200, f"root {path} 居然真被删了")


@case("§3.6 delete", "不存在的路径 → 404")
def t_delete_missing(ctx: Ctx) -> None:
    ctx.need_write()
    r = ctx.post(f"/api/delete?path={q(ctx.scratch + '/gone-' + uuid.uuid4().hex)}")
    expect_eq(r.status, 404, "状态码")


# ------------------------------------------------------------- 方法与路由


@case("§2.3 路由", "未知 /api/ 路由 → 404")
def t_unknown_route(ctx: Ctx) -> None:
    r = ctx.get("/api/does-not-exist")
    expect_eq(r.status, 404, "状态码")
    expect_eq((r.json or {}).get("ok"), False, "ok")


@case("§2.3 方法", "方法不对 → 405")
def t_wrong_method(ctx: Ctx) -> None:
    expect_eq(ctx.post("/api/download?path=%2Fx").status, 405, "POST /api/download")
    with ctx.conn() as c:
        c.request("GET", "/api/upload?name=x", token=ctx.token)
        r = c.read_response()
    expect_eq(r.status, 405, "GET /api/upload")


@case("§2.3 通用", "所有响应都带 Content-Length")
def t_always_content_length(ctx: Ctx) -> None:
    for target in ("/api/info", "/api/list", "/api/nope"):
        r = ctx.get(target)
        expect(r.header("content-length") is not None, f"{target} 的响应缺 Content-Length（§2.3）")


@case("§2.3 通用", "GET 支持 keep-alive，可在一条连接上连发")
def t_keepalive(ctx: Ctx) -> None:
    with ctx.conn() as c:
        for i in range(3):
            c.request("GET", "/api/info", token=ctx.token)
            r = c.read_response()
            expect_eq(r.status, 200, f"第 {i + 1} 个请求")
            expect(not r.closes, f"第 {i + 1} 个 GET 就被 Connection: close 断了")


@case("§2.3 通用", "空格编码为 %20，'+' 不当空格")
def t_plus_not_space(ctx: Ctx) -> None:
    ctx.need_write()
    saved = upload_raw(ctx, ctx.scratch, "a+b c.txt", b"plus")["saved"][0]
    base = os.path.basename(saved)
    expect("+" in base, f"'+' 被当成空格解释了: {base!r}（§2.3）")
    expect(" " in base, f"%20 没被解成空格: {base!r}")
    expect_eq(ctx.get(f"/api/download?path={q(saved)}").body, b"plus", "按原名取回")


# ------------------------------------------------------- §3.8/3.9 可选接口


@case("§3.8 authorize", "免鉴权，且不支持时能被识别（404/401）")
def t_authorize_optional(ctx: Ctx) -> None:
    with ctx.conn() as c:
        c.request("POST", "/api/authorize?name=conformance&os=linux&code=4821&port=1", body=b"")
        r = c.read_response()
    if r.status in (404, 401):
        raise Skip("对端未实现 /api/authorize（客户端应回退到手抄 token）")
    if r.status == 403:
        raise Skip("对端关掉了「允许连接请求」开关")
    if r.status == 429:
        raise Skip("已有待决请求或本地址在冷却中")
    expect_eq(r.status, 200, f"状态码（{(r.json or {}).get('error')}）")
    j = r.json or {}
    expect_eq(j.get("ok"), True, "ok")
    rid = j.get("request")
    expect(isinstance(rid, str) and len(rid) == 32, f"request id 应是 32 位十六进制，实到 {rid!r}")
    expect(int(j.get("expires", 0)) > 0, "expires 应为正")

    # 第二个请求必须被挡住：同一时刻只保留一个待决请求
    with ctx.conn() as c:
        c.request("POST", "/api/authorize?name=conformance2&os=linux&code=1111", body=b"")
        second = c.read_response()
    expect_eq(second.status, 429, "第二个并发请求应回 429（§3.8）")
    # 上一个请求刚登记成功，所以这个 429 一定是「有请求在等用户点」而不是冷却。
    # 等多久取决于用户，报不出准确秒数就不该瞎报一个（§3.8）。
    expect(
        second.header("retry-after") is None,
        f"「已有请求在等用户点」的 429 不该带 Retry-After，实到 {second.header('retry-after')!r}",
    )

    poll = ctx.get(f"/api/authorize?request={q(rid)}")
    expect_eq(poll.status, 200, "轮询状态码")
    expect((poll.json or {}).get("status") in ("pending", "denied", "expired"), "轮询状态")
    print("      提示：对端屏幕上应该弹出了确认码 4821，请点「拒绝」或等它超时")


@case("§3.8 authorize", "超时算软拒绝：该地址进冷却，429 带 Retry-After", slow=True)
def t_authorize_timeout_cooldown(ctx: Ctx) -> None:
    """
    规范 §3.8 冷却表第二行。超时算「软拒绝」：不升级计数，但仍要冷却 ——
    否则「发一个然后挂机等超时」照样能一分钟弹一次。

    要真等一次超时（kAuthTimeoutSec = 60 秒），所以标了 slow。
    前面的用例多半留下了待决请求或冷却，所以先等到能发得出去为止。
    """
    deadline = time.time() + 5 * 60
    first = None
    while time.time() < deadline:
        with ctx.conn() as c:
            c.request("POST", "/api/authorize?name=conformance-slow&os=linux&code=7777", body=b"")
            r = c.read_response()
        if r.status == 200:
            first = r
            break
        if r.status in (404, 401, 403):
            raise Skip("对端未实现或关掉了 /api/authorize")
        wait = int(r.header("retry-after") or 5)
        print(f"      前面的请求还占着（{r.status}），等 {wait}s 再试 …")
        time.sleep(wait + 1)
    if first is None:
        raise Skip("5 分钟内都没能登记上一个请求")

    expires = int((first.json or {}).get("expires") or 60)
    print(f"      等这个请求超时，约 {expires + 3}s …")
    time.sleep(expires + 3)

    with ctx.conn() as c:
        c.request("POST", "/api/authorize?name=conformance-slow2&os=linux&code=8888", body=b"")
        after = c.read_response()
    expect_eq(after.status, 429, "超时之后该地址应在冷却中，而不是又能发一个（§3.8）")
    retry = after.header("retry-after")
    expect(retry is not None, "冷却导致的 429 缺 Retry-After（§3.8）")
    expect(1 <= int(retry) <= 30 * 60, f"Retry-After 超出合理范围: {retry}")


@case("§3.8 authorize", "未知 request id → 404")
def t_authorize_unknown_id(ctx: Ctx) -> None:
    r = ctx.get(f"/api/authorize?request={'f' * 32}")
    if r.status == 401:
        raise Skip("对端未实现 /api/authorize")
    expect_eq(r.status, 404, "状态码")


@case("§3.9 pair", "缺 token → 400")
def t_pair_needs_token(ctx: Ctx) -> None:
    r = ctx.post("/api/pair?port=8765&name=conformance&os=linux")
    if r.status == 404:
        raise Skip("对端未实现 /api/pair")
    expect_eq(r.status, 400, "状态码")


# ------------------------------------------------------------- §3.7 浏览器页


@case("§2.2 退避", "连续猜错 token 触发 429 + Retry-After，成功后清零")
def t_auth_backoff(ctx: Ctx) -> None:
    """
    规范 §2.2「失败退避」。**故意放在最后**：它会把本机地址短暂封禁，
    别的用例插在后面会被误伤。

    只惩罚**连续**失败，所以前面那些穿插着成功请求的用例不会累积计数。
    """
    bad = "x" * len(ctx.token)
    seen_429 = None
    # 宽限 5 次，第 6 次开始封。多打几次以防前面残留了计数。
    for i in range(1, 10):
        with ctx.conn() as c:
            c.request("GET", "/api/info", token=bad)
            r = c.read_response()
        if r.status == 429:
            seen_429 = r
            break
        expect_eq(r.status, 401, f"第 {i} 次错 token 应回 401")

    expect(seen_429 is not None, "连打 9 次错误 token 都没有任何退避（§2.2）")
    retry = seen_429.header("retry-after")
    expect(retry is not None, "429 缺 Retry-After 头")
    wait = int(retry)
    expect(1 <= wait <= 60, f"Retry-After 应在 1..60 秒，实到 {wait}")
    expect_eq((seen_429.json or {}).get("ok"), False, "429 响应体的 ok")

    # 封禁期内即使拿对的 token 也应该被挡住 —— 门都进不去，不比对
    with ctx.conn() as c:
        c.request("GET", "/api/info", token=ctx.token)
        blocked = c.read_response()
    expect_eq(blocked.status, 429, "封禁期内正确的 token 也应回 429，而不是放行")

    time.sleep(wait + 0.5)
    after = ctx.get("/api/info")
    expect_eq(after.status, 200, f"等满 Retry-After={wait}s 之后仍未放行")

    # 成功之后计数应归零：再错一次必须是 401，不是立刻又被封
    with ctx.conn() as c:
        c.request("GET", "/api/info", token=bad)
        again = c.read_response()
    expect_eq(again.status, 401, "成功校验之后计数没有清零（§2.2「成功即清零」）")
    ctx.get("/api/info")  # 收尾：把刚才那一次失败也清掉


@case("§3.7 根路径", "GET / 免鉴权且有响应")
def t_root_page(ctx: Ctx) -> None:
    with ctx.conn() as c:
        c.request("GET", "/")  # 不带 token
        r = c.read_response()
    expect_eq(r.status, 200, "状态码")
    expect(r.header("content-length") is not None, "缺 Content-Length")
    ctype = (r.header("content-type") or "").lower()
    expect("html" in ctype or "text/plain" in ctype, f"Content-Type 应是 html 或 text/plain，实到 {ctype!r}")
    expect(
        ctx.token.encode() not in r.body,
        "首页在免鉴权的情况下把 token 印在了页面里",
    )


# -------------------------------------------------------------------- 主流程


def cleanup(ctx: Ctx, path: str) -> None:
    try:
        ctx.post(f"/api/delete?path={q(path)}&recursive=1")
    except Exception:
        pass


def make_scratch(ctx: Ctx) -> str:
    """在 inbox 下建一个临时目录，所有写入用例都关在里面。"""
    inbox = ctx.info.get("inbox")
    if not isinstance(inbox, str) or not inbox.startswith("/"):
        # inbox 可能是相对路径（Android 走 MediaStore 兜底时），退回第一个可写 root
        roots = ctx.info.get("roots") or []
        inbox = roots[-1]["path"] if roots else ""
    if not inbox:
        return ""
    name = "afmu-conformance-" + uuid.uuid4().hex[:8]
    r = ctx.post(f"/api/mkdir?path={q(inbox)}&name={q(name)}")
    if r.status != 200:
        return ""
    return (r.json or {}).get("path") or ""


GREEN, RED, YELLOW, DIM, RESET = "\033[32m", "\033[31m", "\033[33m", "\033[2m", "\033[0m"


def main() -> int:
    ap = argparse.ArgumentParser(description="AFMU 协议一致性测试（对照 docs/PROTOCOL.md v1）")
    ap.add_argument("--host", help="目标设备 IP")
    ap.add_argument("--port", type=int, default=8765, help="HTTP 端口（默认 8765，以发现应答为准）")
    ap.add_argument("--token", help="目标设备的 token")
    ap.add_argument("--timeout", type=float, default=DEFAULT_TIMEOUT, help="单个请求超时秒数")
    ap.add_argument("--discover", action="store_true", help="只做设备发现然后退出")
    ap.add_argument("-k", "--filter", help="只跑名字里含该子串的用例")
    ap.add_argument("--slow", action="store_true", help="连要等真实超时的用例一起跑（会多花几分钟）")
    ap.add_argument("--no-color", action="store_true")
    args = ap.parse_args()

    global SLOW
    SLOW = args.slow

    if args.no_color or not sys.stdout.isatty():
        globals().update(GREEN="", RED="", YELLOW="", DIM="", RESET="")

    if args.discover:
        peers = discover(2.0)
        if not peers:
            print("没有发现任何设备（广播可能被 AP 隔离拦了）")
            return 1
        for p in peers:
            print(f"  {p['_host']}:{p.get('port')}  {p.get('name')!r}  os={p.get('os')}  afmu={p.get('afmu')}")
        return 0

    if not args.host or not args.token:
        ap.error("需要 --host 和 --token（或用 --discover 先找设备）")

    ctx = Ctx(host=args.host, port=args.port, token=args.token, timeout=args.timeout)

    print(f"目标 {ctx.host}:{ctx.port}")
    try:
        probe = ctx.get("/api/info")
    except Exception as e:
        print(f"{RED}连不上: {e}{RESET}")
        return 2
    if probe.status == 401:
        print(f"{RED}token 不对（401）。用目标设备上显示的那 10 位。{RESET}")
        return 2
    if probe.status != 200:
        print(f"{RED}/api/info 回了 {probe.status}，不像是 AFMU 服务端{RESET}")
        return 2
    ctx.info = probe.json or {}
    print(
        f"对端 {ctx.info.get('name')!r} os={ctx.info.get('os')} "
        f"protocol={ctx.info.get('protocol')} writable={ctx.info.get('writable')}"
    )

    if ctx.writable:
        ctx.scratch = make_scratch(ctx)
        print(f"临时目录 {ctx.scratch or '(建不了，写入类用例将跳过)'}")
    else:
        print(f"{YELLOW}对端只读，写入类用例会跳过{RESET}")
    print()

    cases = REGISTRY
    if args.filter:
        needle = args.filter.lower()
        cases = [c for c in cases if needle in (c.section + c.name).lower()]

    passed = failed = skipped = 0
    failures: list[tuple[Case, str]] = []
    deviations: list[tuple[Case, str]] = []
    fixed: list[Case] = []
    section = ""
    try:
        for c in cases:
            if c.section != section:
                section = c.section
                print(f"{DIM}{section}{RESET}")
            try:
                if c.slow and not SLOW:
                    raise Skip("要等真实超时，用 --slow 打开")
                c.fn(ctx)
            except Skip as e:
                skipped += 1
                print(f"  {YELLOW}skip{RESET} {c.name} {DIM}({e}){RESET}")
            except Exception as e:
                detail = f"{type(e).__name__}: {e}" if not isinstance(e, AssertionError) else str(e)
                if c.deviation:
                    # 已知偏差：原因查清了、记在规范里了，不再当失败报警，
                    # 但也不藏起来 —— 每次都列出来，省得有人以为它已经好了。
                    deviations.append((c, detail))
                    print(f"  {YELLOW}偏差{RESET} {c.name}")
                    print(f"       {DIM}{c.deviation}{RESET}")
                else:
                    failed += 1
                    failures.append((c, detail))
                    print(f"  {RED}FAIL{RESET} {c.name}")
                    print(f"       {RED}{detail}{RESET}")
            else:
                passed += 1
                if c.deviation:
                    # 标了偏差却过了：这一端本来就做得到，或者实现改好了。
                    # 这同样是信息，不提醒的话标记会一直挂着。
                    fixed.append(c)
                    print(f"  {GREEN}ok{RESET}   {c.name} {GREEN}← 已知偏差在这一端不成立{RESET}")
                else:
                    print(f"  {GREEN}ok{RESET}   {c.name}")
    finally:
        if ctx.scratch:
            cleanup(ctx, ctx.scratch)
            print(f"\n{DIM}已清理 {ctx.scratch}{RESET}")

    print(f"\n{'=' * 60}")
    color = GREEN if failed == 0 else RED
    line = f"{color}通过 {passed}  失败 {failed}  跳过 {skipped}{RESET}"
    if deviations:
        line += f"  {YELLOW}已知偏差 {len(deviations)}{RESET}"
    print(line)

    if failures:
        print("\n失败清单：")
        for c, detail in failures:
            print(f"  [{c.section}] {c.name}")
            print(f"      {detail}")

    if deviations:
        print(f"\n{YELLOW}已知偏差（不影响退出码，原因见 PROTOCOL.md）：{RESET}")
        for c, detail in deviations:
            print(f"  [{c.section}] {c.name}")
            print(f"      {DIM}{c.deviation}{RESET}")

    if fixed:
        print(f"\n{GREEN}下面这些标着「已知偏差」却通过了 —— 去掉 deviation= 标记：{RESET}")
        for c in fixed:
            print(f"  [{c.section}] {c.name}")

    return 1 if failed else 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except KeyboardInterrupt:
        print("\n中断")
        sys.exit(130)

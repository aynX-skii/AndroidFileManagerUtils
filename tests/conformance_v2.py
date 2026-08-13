#!/usr/bin/env python3
"""
AFMU v2 一致性测试 —— 对照 docs/PROTOCOL.md 第二部分（零信任网络下的加密与身份）。

对着一个**正在运行的** AFMU 服务端跑，两端实现（Android / Linux）必须都通过。

    python3 conformance_v2.py --host 192.168.1.42 --port 8765

v1 那套（conformance.py）验的是线格式：字段、状态码、Range、multipart。
这一套验的是**门禁**：谁能进、进来之后能碰到什么。两件事错起来的表现完全不同 ——
线格式错了对端立刻报错，门禁错了没有任何人会报错。

## 为什么需要它

v2 的跨实现保障目前只有两样：两端各自的单元测试，和 §15 那几组向量。
它们覆盖的是「算得对不对」（SAS、rid、指纹），没有覆盖「门开得对不对」：

  · 未配对的 TLS 连接是不是真的只能碰 /api/pair-v2
  · 明文下的 pair-v2 是不是真的 400
  · commit 对不上是不是真的作废整个 session，而不是允许换个 na 再试
  · 作废之后拿正确的 na 重试是不是仍然 400

每一条错了都是「门开着」，而门开着不会有任何人报 bug。

## 依赖

只用标准库，外加一个 `openssl` 命令行 —— 需要它是因为标准库生成不了 EC 自签证书，
而 mTLS 的客户端必须有一张。openssl 在 Linux 上基本都在，没有就整套跳过。
这和「不装任何 pip 包」的约束不冲突，也是 §12 第 1 步交叉验证指纹用的同一个工具。

## 会在对端留下什么

配对用例会让对端**弹一个配对确认框**（和 v1 §3.8 的授权用例一样）。
跑完会剩下一个待决 session，请在对端点「拒绝」，或者等它 60 秒自己超时。
用例本身不需要有人点任何东西 —— 它们验的都是用户决定之**前**的那几步。
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import secrets
import shutil
import socket
import ssl
import subprocess
import sys
import tempfile
from dataclasses import dataclass, field
from typing import Any, Callable

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

# 复用 v1 的 HTTP 读写与断言。导入会顺带把 v1 用例注册进它的 REGISTRY，
# 我们不碰那个表，各用各的。
from conformance import (  # noqa: E402
    DEFAULT_TIMEOUT,
    Conn,
    Response,
    Skip,
    expect,
    expect_eq,
    q,
)

# ---- 必须和 ProtocolConstants 逐字一致（由 docs/constants.json 生成的那份） ----

FINGERPRINT_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
SAS_CONTEXT = b"AFMU-SAS-v2"
SAS_LENGTH = 8
NONCE_BYTES = 32
TLS_HELLO_FIRST_BYTE = 22

GREEN, RED, YELLOW, DIM, RESET = "\033[32m", "\033[31m", "\033[33m", "\033[2m", "\033[0m"


# ------------------------------------------------------------------ 加密小工具


def base32(raw: bytes) -> str:
    """
    v2 §3.1 的 base32。**不是** RFC 4648：字母表去掉了 I 和 O，也没有 `=` 填充。

    32 字节 = 256 位不是 5 的整数倍，最后剩 1 位，左对齐补成最后一组 ——
    和 Base32.kt / Identity::toBase32 的处理必须一致，否则指纹最后一个字符会不同。
    """
    out = []
    buffer = bits = 0
    for b in raw:
        buffer = (buffer << 8) | b
        bits += 8
        while bits >= 5:
            bits -= 5
            out.append(FINGERPRINT_ALPHABET[(buffer >> bits) & 0x1F])
    if bits > 0:
        out.append(FINGERPRINT_ALPHABET[(buffer << (5 - bits)) & 0x1F])
    return "".join(out)


def compute_sas(fp_a: bytes, fp_b: bytes, na: bytes, nb: bytes) -> str:
    """
    v2 §4.2.2。指纹按**无符号**排序，随机数不排序（角色固定：发起方 / 应答方）。

    排序按有符号比的话，一半的指纹对会被两端排成相反的顺序，而症状是两个屏幕
    显示不同的码 —— 用户唯一合理的解读是「我正在被攻击」。§15 拿 0x88 专门钉了这个。
    Python 的 bytes 比较本来就是无符号的，这里不会踩，但两端的实现踩过。
    """
    lo, hi = (fp_a, fp_b) if fp_a < fp_b else (fp_b, fp_a)
    digest = hashlib.sha256(SAS_CONTEXT + lo + hi + na + nb).digest()
    return base32(digest)[:SAS_LENGTH]


def spki_fingerprint(cert_der: bytes) -> bytes:
    """
    SHA-256 over the certificate's DER SubjectPublicKeyInfo（v2 §3.1）。

    最常见的翻车是差一层封装：哈希整张证书，或者哈希裸 EC 点。这里走 openssl，
    和 §12 第 1 步交叉验证用的是同一条命令，所以它天然是那个「独立第三方」。
    """
    pem = subprocess.run(
        ["openssl", "x509", "-inform", "DER", "-pubkey", "-noout"],
        input=cert_der, capture_output=True, check=True,
    ).stdout
    der = subprocess.run(
        ["openssl", "pkey", "-pubin", "-outform", "DER"],
        input=pem, capture_output=True, check=True,
    ).stdout
    return hashlib.sha256(der).digest()


@dataclass
class Identity:
    """本机这一侧的 v2 身份：一张 EC P-256 自签证书。"""

    cert_path: str
    key_path: str
    fingerprint: bytes

    @property
    def base32(self) -> str:
        return base32(self.fingerprint)


def make_identity(workdir: str) -> Identity:
    """现生成一张，不复用 —— 每次跑都是一台「没见过的新设备」，正是要测的状态。"""
    cert = os.path.join(workdir, "cert.pem")
    key = os.path.join(workdir, "key.pem")
    subprocess.run(
        [
            "openssl", "req", "-x509", "-newkey", "ec",
            "-pkeyopt", "ec_paramgen_curve:P-256",
            "-keyout", key, "-out", cert,
            "-days", "1", "-nodes",
            "-subj", "/CN=afmu-conformance",
        ],
        capture_output=True, check=True,
    )
    with open(cert, "rb") as f:
        pem = f.read()
    der = subprocess.run(
        ["openssl", "x509", "-outform", "DER"],
        input=pem, capture_output=True, check=True,
    ).stdout
    return Identity(cert, key, spki_fingerprint(der))


# ------------------------------------------------------------------ TLS 连接


class TlsConn(Conn):
    """
    一条 v2 连接：TLS 1.3 + 出示客户端证书。

    **链校验关掉是对的，不是偷懒。** 两端都是自签证书，没有 CA 可链；v2 里
    「可不可信」由指纹钉扎决定，不由 TLS 栈决定（§5.1）。测试这一侧不做钉扎 ——
    它扮演的正是一台还没配对的设备。
    """

    def __init__(self, host: str, port: int, identity: Identity | None,
                 timeout: float = DEFAULT_TIMEOUT):
        super().__init__(host, port, timeout)
        ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_CLIENT)
        ctx.check_hostname = False
        ctx.verify_mode = ssl.CERT_NONE
        ctx.minimum_version = ssl.TLSVersion.TLSv1_3
        if identity is not None:
            ctx.load_cert_chain(identity.cert_path, identity.key_path)
        try:
            self.sock = ctx.wrap_socket(self.sock, server_hostname=host)
        except (ssl.SSLError, OSError) as e:
            super().close()
            raise ConnectionError(f"TLS 握手失败: {e}") from e
        self.peer_cert_der: bytes = self.sock.getpeercert(binary_form=True) or b""

    @property
    def peer_fingerprint(self) -> bytes:
        return spki_fingerprint(self.peer_cert_der) if self.peer_cert_der else b""


# ------------------------------------------------------------------ 测试框架
# 和 v1 同构，但用自己的注册表：两套用例的前置条件完全不同，混在一张表里跑
# 会让「跳过」的原因难以解释。


@dataclass
class Case:
    section: str
    name: str
    fn: Callable[["Ctx"], None]


REGISTRY: list[Case] = []


def case(section: str, name: str) -> Callable[[Callable[["Ctx"], None]], Callable[["Ctx"], None]]:
    def wrap(fn: Callable[["Ctx"], None]) -> Callable[["Ctx"], None]:
        REGISTRY.append(Case(section, name, fn))
        return fn

    return wrap


@dataclass
class Ctx:
    host: str
    port: int
    timeout: float
    identity: Identity
    #: 对端的 SPKI 指纹，从握手拿的。SAS 要用。
    peer_fp: bytes = b""
    #: 对端允不允许明文（探测出来的）。决定明文相关的用例跳不跳。
    plaintext_open: bool = False
    #: 对端的访客模式开着没有（探测出来的）。见 [detect_guest]。
    guest_open: bool = False
    #: 跨用例传递的配对状态，见 t_pair_* 那一组。
    shared: dict[str, Any] = field(default_factory=dict)

    def tls(self) -> TlsConn:
        return TlsConn(self.host, self.port, self.identity, self.timeout)

    def tls_anonymous(self) -> TlsConn:
        """不出示客户端证书 —— 浏览器就是这样连的（v2 §9 访客模式）。"""
        return TlsConn(self.host, self.port, None, self.timeout)

    def plain(self) -> Conn:
        return Conn(self.host, self.port, self.timeout)

    def tls_get(self, target: str, **kw: Any) -> Response:
        with self.tls() as c:
            c.request("GET", target, **kw)
            return c.read_response()

    def tls_post(self, target: str, **kw: Any) -> Response:
        with self.tls() as c:
            c.request("POST", target, body=b"", **kw)
            return c.read_response()


# ---------------------------------------------------- §4.2.4 未配对连接的门禁


#: 未配对连接一个都不许碰。`/` 在内：v1 §3.7 说它免鉴权，但「免鉴权」不等于
#: 「未配对也能看」—— 它会报设备名，而 §6.1 刚把设备名从发现应答里拿掉。
GATED = ["/", "/api/info", "/api/list", "/api/download?path=/", "/api/ticket?path=/"]

#: 访客模式是 §4.2.4 门禁**唯一**的例外，所以它开着的时候这一组用例不适用：
#: 未配对连接被允许往下走去过 v1 那道密码门（那不是把门开大了 —— 明文连接
#: 本来就走那道门，访客模式只是让它也能加密）。要验门禁就得先把访客模式关掉。
GUEST_OFF_ONLY = "访客模式开着：§4.2.4 允许未配对连接去过密码那道门，门禁用例不适用"


@case("§4.2.4 门禁", "未配对的 TLS 连接：除 pair-v2 外一律 403")
def t_gate_everything(ctx: Ctx) -> None:
    if ctx.guest_open:
        raise Skip(GUEST_OFF_ONLY)
    for target in GATED:
        r = ctx.tls_get(target)
        expect_eq(r.status, 403, f"未配对访问 {target}")


@case("§4.2.4 门禁", "同一条连接上连着碰多个路由，答案一致")
def t_gate_same_connection(ctx: Ctx) -> None:
    # 分开连每次都新握手，掩盖了「只在握手时判一次」这个实现方式的问题。
    # 复用一条连接才测得到 §4.2.4「每个请求重新问一次配对表」。
    if ctx.guest_open:
        raise Skip(GUEST_OFF_ONLY)
    with ctx.tls() as c:
        for target in GATED:
            c.request("GET", target)
            r = c.read_response()
            expect_eq(r.status, 403, f"同一连接上访问 {target}")
            if r.closes:
                raise AssertionError(f"访问 {target} 之后连接被关了，无法验证后续请求")


@case("§4.2.4 门禁", "403 的正文说清楚是「没配对」，不是「token 不对」")
def t_gate_message(ctx: Ctx) -> None:
    if ctx.guest_open:
        raise Skip(GUEST_OFF_ONLY)
    r = ctx.tls_get("/api/info")
    body = (r.json or {})
    expect_eq(body.get("ok"), False, "错误响应的 ok 字段")
    text = str(body.get("error", "")).lower()
    expect(
        "pair" in text,
        f"错误信息应指向配对这条路，实到 {body.get('error')!r}",
    )


@case("§4.2.4 门禁", "带上一个 token 也不改变结论")
def t_gate_token_is_not_a_key(ctx: Ctx) -> None:
    # v2 下 token 不是通行证。就算猜对了也一样进不去 —— 这里用一个必然错的值，
    # 验的是「有没有 token 这件事根本不参与判断」：答案必须和不带时逐字相同。
    if ctx.guest_open:
        raise Skip(GUEST_OFF_ONLY)
    r = ctx.tls_get("/api/info", token="0000000000")
    expect_eq(r.status, 403, "未配对 + 假 token")


@case("§4.2.4 门禁", "访客模式开着时：未配对连接止步于密码门，不是直接放行")
def t_gate_guest_still_needs_password(ctx: Ctx) -> None:
    # 访客模式的例外只到「可以去敲密码门」为止。敲不开还是进不去 ——
    # 否则「加密的访客」就成了比明文访客更宽的一条路，那才是真的开了口子。
    if not ctx.guest_open:
        raise Skip("访客模式关着，这条不适用")
    r = ctx.tls_get("/api/info", token="0000000000")
    expect_eq(r.status, 401, "未配对 + 错 token（访客模式开着）")


# ------------------------------------------------------- §4.2.3 pair-v2 线格式


@case("§4.2.3 pair-v2", "明文下的 pair-v2 被拒，且不留下 session")
def t_pair_plaintext_refused(ctx: Ctx) -> None:
    """
    §4.2.3 的原话是「明文下调它一律 400」。**具体状态码取决于哪道门先拦下它**，
    而那由对端的配置决定，不由这个接口决定：

      · 访客模式开着 + token 正确 → 真的走到 handlePairV2，`400`
      · 访客模式关着            → 访客门禁先拦，`403`
      · 没带 token             → token 检查先拦，`401`

    三种都是拒绝，三种都不会建出 session。所以这里断言的是**不变量**而不是状态码：
    钉死 400 会让一半的合法配置误报，而真正不能破的是「明文配不成对」。
    两端在这一点上行为一致（各自的路由顺序相同），实测确认过。
    """
    if not ctx.plaintext_open:
        raise Skip("对端已禁用明文，这条不适用")
    na = secrets.token_bytes(NONCE_BYTES)
    commit = hashlib.sha256(na).hexdigest()
    with ctx.plain() as c:
        c.request("POST", f"/api/pair-v2?step=commit&commit={commit}", body=b"")
        r = c.read_response()
    expect(
        r.status in (400, 401, 403),
        f"明文 pair-v2 应被拒（400/401/403），实到 {r.status}",
    )
    body = r.json or {}
    expect(
        "session" not in body and "nb" not in body,
        f"明文下不该建出 session，响应却是 {body!r}",
    )


@case("§4.2.3 pair-v2", "commit 长度不对 → 400")
def t_pair_bad_commit_length(ctx: Ctx) -> None:
    for bad in ("", "ab", "ab" * 31, "ab" * 33):
        r = ctx.tls_post(f"/api/pair-v2?step=commit&commit={bad}")
        expect_eq(r.status, 400, f"commit={bad[:8]}…（{len(bad)} 字符）")


@case("§4.2.3 pair-v2", "hex 判定：非 ASCII 十六进制字符整串作废")
def t_pair_hex_strict(ctx: Ctx) -> None:
    # §4.2.3 的框注：两端必须逐字一致地严格。Qt 的 fromHex 会跳过看不懂的字符，
    # Kotlin 的 digitToInt 认任意 Unicode 数字 —— 两个平台默认答案都不对。
    for bad in ("zz" * 32, "ab" * 31 + "z0", "١١" + "ab" * 31):
        r = ctx.tls_post(f"/api/pair-v2?step=commit&commit={q(bad)}")
        expect_eq(r.status, 400, f"commit={bad[:6]}… 应整串作废")


@case("§4.2.3 pair-v2", "未知 step → 400")
def t_pair_unknown_step(ctx: Ctx) -> None:
    r = ctx.tls_post("/api/pair-v2?step=nonsense")
    expect_eq(r.status, 400, "未知 step")


@case("§4.2.3 pair-v2", "轮询未知 session → 404（客户端要当 expired，不要重试）")
def t_pair_unknown_session(ctx: Ctx) -> None:
    r = ctx.tls_get("/api/pair-v2?session=" + "00" * 16)
    expect_eq(r.status, 404, "未知 session")


# --------------------------------------------- §4.2.3 三步握手（会弹配对确认框）


@case("§4.2.3 握手", "commit → 200，返回 session 与 32 字节的 nb")
def t_pair_commit(ctx: Ctx) -> None:
    na = secrets.token_bytes(NONCE_BYTES)
    commit = hashlib.sha256(na).hexdigest()
    r = ctx.tls_post(
        f"/api/pair-v2?step=commit&commit={commit}"
        f"&name={q('conformance-v2')}&os=linux&port=1"
    )
    expect_eq(r.status, 200, "commit 的状态码")
    body = r.json or {}
    expect_eq(body.get("ok"), True, "ok 字段")
    session = body.get("session")
    nb_hex = body.get("nb")
    expect(isinstance(session, str) and session, "缺 session")
    expect(isinstance(nb_hex, str) and len(nb_hex) == 64, f"nb 应是 64 位 hex，实到 {nb_hex!r}")
    bytes.fromhex(nb_hex)  # 不是 hex 就在这里炸
    expect(body.get("expires") == 60, f"expires 应是 60，实到 {body.get('expires')!r}")
    ctx.shared["session"] = session
    ctx.shared["na"] = na
    ctx.shared["nb"] = bytes.fromhex(nb_hex)
    print(f"       {DIM}提示：对端屏幕上应该弹出了配对框{RESET}")


@case("§4.2.3 握手", "已有待决 session 时再 commit → 429")
def t_pair_single_pending(ctx: Ctx) -> None:
    if "session" not in ctx.shared:
        raise Skip("上一条没拿到 session")
    na = secrets.token_bytes(NONCE_BYTES)
    r = ctx.tls_post(f"/api/pair-v2?step=commit&commit={hashlib.sha256(na).hexdigest()}")
    expect_eq(r.status, 429, "第二个并发 commit")


@case("§4.2.3 握手", "na 与 commit 对不上 → 400，且整个 session 作废")
def t_pair_bad_reveal(ctx: Ctx) -> None:
    session = ctx.shared.get("session")
    if not session:
        raise Skip("没有可用的 session")
    wrong = secrets.token_bytes(NONCE_BYTES).hex()
    r = ctx.tls_post(f"/api/pair-v2?step=reveal&session={q(session)}&na={wrong}")
    expect_eq(r.status, 400, "错误的 na")

    # 作废是这一条的重点：允许重试就等于允许一直换 na 试下去，commit 白做了。
    right = ctx.shared["na"].hex()
    again = ctx.tls_post(f"/api/pair-v2?step=reveal&session={q(session)}&na={right}")
    expect_eq(again.status, 400, "作废之后拿正确的 na 重试")
    ctx.shared.pop("session", None)


@case("§4.2.3 握手", "作废确实腾出了位置：可以重新 commit")
def t_pair_slot_released(ctx: Ctx) -> None:
    na = secrets.token_bytes(NONCE_BYTES)
    r = ctx.tls_post(
        f"/api/pair-v2?step=commit&commit={hashlib.sha256(na).hexdigest()}"
        f"&name={q('conformance-v2')}&os=linux&port=1"
    )
    expect_eq(r.status, 200, "作废之后的新 commit")
    body = r.json or {}
    ctx.shared["session"] = body.get("session")
    ctx.shared["na"] = na
    ctx.shared["nb"] = bytes.fromhex(body["nb"])


@case("§4.2.2 SAS", "reveal → 服务端回的 SAS 与本地独立算出的逐字相同")
def t_pair_sas_matches(ctx: Ctx) -> None:
    session = ctx.shared.get("session")
    if not session:
        raise Skip("没有可用的 session")
    expect(bool(ctx.peer_fp), "没拿到对端指纹，SAS 无从计算")

    r = ctx.tls_post(
        f"/api/pair-v2?step=reveal&session={q(session)}&na={ctx.shared['na'].hex()}"
    )
    expect_eq(r.status, 200, "reveal 的状态码")
    theirs = (r.json or {}).get("sas")

    # 这一行就是整套 v2 里最值得跨实现验的东西：本地按 §4.2.2 独立算，
    # 不参考服务端回的任何值。对不上说明两端对「指纹怎么排序、随机数怎么拼」
    # 的理解有分歧 —— 而现场症状是两个屏幕显示不同的码。
    mine = compute_sas(ctx.identity.fingerprint, ctx.peer_fp, ctx.shared["na"], ctx.shared["nb"])
    expect_eq(theirs, mine, "服务端回的 SAS 与本地算出的")
    print(f"       {DIM}SAS = {mine[:4]}-{mine[4:]}{RESET}")


@case("§4.2.3 握手", "轮询自己的 session → pending")
def t_pair_poll(ctx: Ctx) -> None:
    session = ctx.shared.get("session")
    if not session:
        raise Skip("没有可用的 session")
    r = ctx.tls_get(f"/api/pair-v2?session={q(session)}")
    expect_eq(r.status, 200, "轮询状态码")
    body = r.json or {}
    expect_eq(body.get("status"), "pending", "尚未有人决定时的 status")
    expect("token" not in body, "v2 的响应里不该有 token —— 身份就是那对密钥")


# ------------------------------------------------------------------ §8.1 降级


@case("§9 访客", "不出示客户端证书的加密连接：访客模式关掉时进不来")
def t_anonymous_tls(ctx: Ctx) -> None:
    # 浏览器永远不会出示客户端证书。访客模式**开着**时这是必须放行的（否则
    # 「HTTPS 的访客模式」根本没法用）；关掉时它就是一个身份不明的连接，
    # 不该比出示了陌生证书的对端待遇更好 —— 后者只能碰 pair-v2。
    try:
        with ctx.tls_anonymous() as c:
            c.request("GET", "/api/info")
            r = c.read_response()
    except (EOFError, ConnectionError, TimeoutError, OSError):
        return  # 直接断开，也是一种「进不来」
    expect(
        r.status in (401, 403),
        f"匿名加密连接访问 /api/info 应被拒，实到 {r.status}",
    )


# ------------------------------------------------------------------ §8.1 降级


@case("§8.1 降级", "明文端口的行为与设置一致")
def t_downgrade_consistent(ctx: Ctx) -> None:
    if ctx.plaintext_open:
        raise Skip("对端允许明文（升级安装的默认），这条只在只加密模式下有意义")
    # 只加密模式下：不回 400、不回任何 HTTP 报文。回什么都等于告诉扫端口的人
    # 这里有个 HTTP 服务。
    try:
        with ctx.plain() as c:
            c.request("GET", "/api/info")
            r = c.read_response()
    except (EOFError, ConnectionError, TimeoutError, OSError):
        return  # 断开，正是期望的
    raise AssertionError(f"明文已禁用，但服务端回了 HTTP {r.status}")


# -------------------------------------------------------------------- 主流程


def probe_plaintext(host: str, port: int, timeout: float) -> bool:
    """对端还听不听明文？决定几条用例跳不跳，本身不是断言。"""
    try:
        with Conn(host, port, timeout) as c:
            c.request("GET", "/api/info")
            c.read_response()
        return True
    except Exception:
        return False


def detect_guest(ctx: Ctx) -> bool:
    """
    对端的访客模式开着没有？没有接口直说，但门禁的答案本身就说了：

      · 关着 → 未配对连接一律 `403`，错误信息指向配对
      · 开着 → 未配对连接被放去过密码门，没带 token 就是 `401`

    探测而不是让用户拿参数指定：写错了会让整组用例静默跳过，
    而「跳过」看起来和「通过」一样无害。
    """
    r = ctx.tls_get("/api/info")
    return r.status == 401


def main() -> int:
    ap = argparse.ArgumentParser(
        description="AFMU v2 一致性测试（对照 docs/PROTOCOL.md 第二部分）"
    )
    ap.add_argument("--host", required=True, help="目标设备 IP")
    ap.add_argument("--port", type=int, default=8765, help="HTTP 端口（默认 8765）")
    ap.add_argument("--timeout", type=float, default=DEFAULT_TIMEOUT, help="单个请求超时秒数")
    ap.add_argument("-k", "--filter", help="只跑名字里含该子串的用例")
    ap.add_argument("--no-color", action="store_true")
    args = ap.parse_args()

    if args.no_color or not sys.stdout.isatty():
        globals().update(GREEN="", RED="", YELLOW="", DIM="", RESET="")

    if shutil.which("openssl") is None:
        print(f"{YELLOW}没有 openssl，整套跳过 —— mTLS 的客户端证书生成不了{RESET}")
        return 0

    workdir = tempfile.mkdtemp(prefix="afmu-v2-")
    try:
        identity = make_identity(workdir)
        print(f"目标 {args.host}:{args.port}")
        print(f"本机指纹 {identity.base32[:20]}…（每次跑都是新的一张证书）")

        ctx = Ctx(host=args.host, port=args.port, timeout=args.timeout, identity=identity)
        ctx.plaintext_open = probe_plaintext(args.host, args.port, args.timeout)

        try:
            with ctx.tls() as c:
                ctx.peer_fp = c.peer_fingerprint
        except Exception as e:
            print(f"{RED}TLS 握手不通：{e}{RESET}")
            print(f"{DIM}对端可能没启用 v2（身份未就绪），或者只提供明文。{RESET}")
            return 2
        if not ctx.peer_fp:
            print(f"{RED}握手成功但对端没出示证书 —— 这不是一个 v2 服务端{RESET}")
            return 2

        ctx.guest_open = detect_guest(ctx)
        print(f"对端指纹 {base32(ctx.peer_fp)[:20]}…")
        print(f"明文     {'开着' if ctx.plaintext_open else '已禁用'}")
        print(f"访客模式 {'开着' if ctx.guest_open else '关着'}")
        if ctx.guest_open:
            print(
                f"{YELLOW}提示：访客模式开着，§4.2.4 的门禁用例会跳过。"
                f"要完整验证门禁，把对端的访客模式关掉再跑一次。{RESET}"
            )
        print()

        cases = REGISTRY
        if args.filter:
            needle = args.filter.lower()
            cases = [c for c in cases if needle in (c.section + c.name).lower()]

        passed = failed = skipped = 0
        failures: list[tuple[Case, str]] = []
        section = ""
        for c in cases:
            if c.section != section:
                section = c.section
                print(f"{DIM}{section}{RESET}")
            try:
                c.fn(ctx)
            except Skip as e:
                skipped += 1
                print(f"  {YELLOW}skip{RESET} {c.name} {DIM}({e}){RESET}")
            except Exception as e:
                detail = str(e) if isinstance(e, AssertionError) else f"{type(e).__name__}: {e}"
                failed += 1
                failures.append((c, detail))
                print(f"  {RED}FAIL{RESET} {c.name}")
                print(f"       {RED}{detail}{RESET}")
            else:
                passed += 1
                print(f"  {GREEN}ok{RESET}   {c.name}")

        print(f"\n{'=' * 60}")
        color = GREEN if failed == 0 else RED
        print(f"{color}通过 {passed}  失败 {failed}  跳过 {skipped}{RESET}")

        if failures:
            print("\n失败清单：")
            for c, detail in failures:
                print(f"  [{c.section}] {c.name}")
                print(f"      {detail}")

        if ctx.shared.get("session"):
            print(
                f"\n{YELLOW}对端还留着一个待决的配对请求。"
                f"请在那边点「拒绝」，或者等它 60 秒超时。{RESET}"
            )
        return 1 if failed else 0
    finally:
        shutil.rmtree(workdir, ignore_errors=True)


if __name__ == "__main__":
    try:
        sys.exit(main())
    except KeyboardInterrupt:
        print("\n中断")
        sys.exit(130)

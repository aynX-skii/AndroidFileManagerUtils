package com.aynux.afmu.core

/**
 * 本文件由 docs/constants.json 生成，**不要手改**。
 * 改值请编辑 docs/constants.json，然后跑 tools/gen_constants.py。
 *
 * 这些值必须和 Linux 端的 ProtocolConstants.h 逐字一致 —— 它们是同一份 JSON 生成的。
 */
object ProtocolConstants {

    // ---- 线格式 ---------------------------------------------------------
    // PROTOCOL.md §1 / §2。改这些等于改协议，要同时升 protocolVersion。

    /** 发现应答的 afmu 字段、/api/info 的 protocol 字段 */
    const val PROTOCOL_VERSION = 1

    /** 首选 HTTP 端口。客户端不能硬编码它，要以发现应答里的 port 为准（§2.1） */
    const val DEFAULT_HTTP_PORT = 8765

    /** UDP 发现端口 */
    const val DISCOVERY_PORT = 8766

    /** 探测包只校验前缀，后面的版本号留作以后扩展（§1.1） */
    const val PROBE_PREFIX = "AFMU-DISCOVER"

    /** 探测包的完整载荷 */
    const val PROBE_PAYLOAD = "AFMU-DISCOVER/1\n"

    /** 推荐的 token 头（§2.2） */
    const val TOKEN_HEADER = "X-AFMU-Token"

    /** 落盘前的临时后缀。传输中断时删掉，绝不留下半个文件冒充完整文件（§4.3） */
    const val PART_SUFFIX = ".afmu-part"

    /** 配对二维码的载荷前缀（§5） */
    const val PAIR_URI_PREFIX = "afmu://pair?"

    /** 服务端 socket 空闲超时（§2.3） */
    const val SOCKET_TIMEOUT_SEC = 120

    // ---- token -------------------------------------------------------
    // PROTOCOL.md §2.2。token 是手抄的，所以字母表去掉了易混字符。

    /** 去掉了 i l o 0 1 —— 这串要用户看着屏幕敲到另一台机器上 */
    const val TOKEN_ALPHABET = "abcdefghjkmnpqrstuvwxyz23456789"

    /** 约 49 bit 熵；配合失败退避足够 */
    const val TOKEN_LENGTH = 10

    // ---- 失败退避 --------------------------------------------------------
    // PROTOCOL.md §2.2「失败退避」。两端不一致的话，同一个网络里表现不同，排查时会以为是网络问题。

    /** 前几次失败不惩罚 —— token 是手抄的，打错很正常 */
    const val AUTH_FAIL_GRACE = 5

    /** 退避上限，避免一次误操作把自己锁死太久 */
    const val AUTH_BACKOFF_MAX_SEC = 60

    /** 这么久没有新的失败就把记录忘掉 */
    const val AUTH_FAIL_FORGET_SEC = 900

    // ---- 授权请求 --------------------------------------------------------
    // PROTOCOL.md §3.8。这是唯一免鉴权的接口，所有防滥用参数都在这里。

    /** 等待用户决定的上限。两端不一致会出现『一端还在轮询、另一端已经把请求丢掉』的窗口 */
    const val AUTH_TIMEOUT_SEC = 60

    /** 结果在超时之后再多留这么久，让最后一刻做的决定也能被取走 */
    const val AUTH_RESULT_RETENTION_EXTRA_SEC = 30

    /** 单地址被拒后的基础冷却，按拒绝次数翻倍 */
    const val DENY_COOLDOWN_SEC = 60

    /** 单地址冷却上限 */
    const val DENY_COOLDOWN_MAX_SEC = 1800

    /** 超时算软拒绝：固定冷却，不参与升级计数 —— 对方没做错什么，是用户没来得及看 */
    const val TIMEOUT_COOLDOWN_SEC = 60

    /** 全局冷却基数。不看是谁在请求 —— 这是唯一能挡住换 IP 刷屏的东西 */
    const val GLOBAL_COOLDOWN_SEC = 10

    /** 全局冷却上限 */
    const val GLOBAL_COOLDOWN_MAX_SEC = 300

    /** 这么久没有新的拒绝就把升级计数忘掉 */
    const val REFUSAL_FORGET_SEC = 1800

    // ---- 配对模式 --------------------------------------------------------
    // PROTOCOL.md §1.5。常态下发现应答不含设备名。

    /** 用户点『允许被发现』之后，应答带上 name/os 的时长 */
    const val PAIRING_MODE_SEC = 60

    // ---- 设备身份（v2） ----------------------------------------------------
    // PROTOCOL-v2-DRAFT.md §3。v1 还用不到，但指纹的定义两端必须一模一样 —— 差一层封装就永远对不上，而症状是「证书明明对却一直不匹配」，极难查。

    /** 不用 RSA：生成快、握手包小、Android KeyStore 原生支持 */
    const val IDENTITY_CURVE = "P-256"

    /** 20 年。钉扎之后有效期本来就没有意义，只是别让 TLS 栈以过期为由拒绝 */
    const val IDENTITY_VALIDITY_DAYS = 7300

    /** base32 字母表，去掉 I 和 O（0 和 1 本来就不在里面）—— 这串要用户对着屏幕比对 */
    const val FINGERPRINT_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"

    /** 展示时每几个字符空一格 */
    const val FINGERPRINT_GROUP_SIZE = 5

    // ---- 下载券 ---------------------------------------------------------
    // PROTOCOL.md §2.5。浏览器 <a href> 带不了自定义头，用它顶替 ?token=。

    /** 约束的是**开始**下载，不是传完 —— 否则等于禁止下载大文件 */
    const val TICKET_TTL_SEC = 10

    /** base64url 截断长度，22 字符 = 132 bit */
    const val TICKET_MAC_CHARS = 22

    /** 域分隔前缀，保证这个 MAC 不会被当成同一把钥匙算出来的别的用途的 MAC */
    const val TICKET_DOMAIN = "afmu-dl-v1\n"
}

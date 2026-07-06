package top.yukonga.scripta.sandbox

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.scripta.editor.CodeEditor
import top.yukonga.scripta.editor.EditorLanguage
import top.yukonga.scripta.editor.rememberCodeEditorController
import java.io.IOException

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        setContent {
            val controller = rememberCodeEditorController()
            var text by remember { mutableStateOf(SAMPLE_YAML) }
            var language by remember { mutableStateOf(EditorLanguage.Yaml) }
            var wrap by remember { mutableStateOf(false) }
            var readOnly by remember { mutableStateOf(false) }
            var openedName by remember { mutableStateOf<String?>(null) }

            val context = LocalContext.current
            val scope = rememberCoroutineScope()
            // SAF 文档选择器：读一次即用，不需要 takePersistableUriPermission（回调期间的临时授权已够）。
            // MIME 传 */*——.yaml/.log/.kt 等常被系统报成非 text/* 类型，用 text/* 过滤反而会把想打开的
            // 文件挡在选择器外；这里一律按 UTF-8 解码，放开筛选让所有文件可见。
            val openDocument = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                uri ?: return@rememberLauncherForActivityResult
                scope.launch {
                    try {
                        // 文件可达数 MB，读取与文件名查询都放 IO 线程，别卡住 UI；回到主线程再写 state。
                        val (name, content) = withContext(Dispatchers.IO) {
                            val display = queryDisplayName(context, uri)
                            val body = context.contentResolver.openInputStream(uri)
                                ?.bufferedReader()?.use { it.readText() }
                                ?: throw IOException("无法读取文件")
                            display to body
                        }
                        openedName = name
                        language = languageForName(name)
                        text = content
                    } catch (e: Exception) {
                        Toast.makeText(context, "打开失败: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            }

            // 全屏深色底：系统栏透明后，状态栏/导航栏区域露出的是这层底色而非白色 window 背景。
            // 各系统栏的让位下沉到子元素（工具栏让状态栏、编辑器让导航栏+键盘），使两栏底色与相邻内容无缝。
            Column(
                Modifier
                    .fillMaxSize()
                    .background(Color(0xFF1E1E1E))
            ) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF2D2D30))
                        .statusBarsPadding() // 工具栏底色铺进状态栏，内容下移让开状态栏
                        .padding(8.dp)
                ) {
                    BasicText(
                        text = "  打开  ",
                        style = TextStyle(color = Color(0xFF56A3F5), fontSize = 13.sp),
                        modifier = Modifier.clickable { openDocument.launch(arrayOf("*/*")) },
                    )
                    BasicText(
                        text = "  加载 3MB 配置  ",
                        style = TextStyle(color = Color(0xFFE0E0E0), fontSize = 13.sp),
                        modifier = Modifier.clickable {
                            text = bigYaml(20_000); language = EditorLanguage.Yaml; openedName = null
                        },
                    )
                    BasicText(
                        text = if (wrap) "  换行: 开  " else "  换行: 关  ",
                        style = TextStyle(color = Color(0xFF6FCF97), fontSize = 13.sp),
                        modifier = Modifier.clickable { wrap = !wrap },
                    )
                    BasicText(
                        text = if (readOnly) "  只读: 开  " else "  只读: 关  ",
                        style = TextStyle(color = Color(0xFFE0A458), fontSize = 13.sp),
                        modifier = Modifier.clickable { readOnly = !readOnly },
                    )
                    // 已打开的文件名占满剩余宽度、单行省略，绝不把上面的按钮挤出屏幕。
                    openedName?.let {
                        BasicText(
                            text = it,
                            style = TextStyle(color = Color(0xFF858585), fontSize = 13.sp),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            softWrap = false,
                            modifier = Modifier
                                .weight(1f)
                                .padding(start = 4.dp),
                        )
                    }
                }
                CodeEditor(
                    controller = controller,
                    initialText = text,
                    language = language,
                    softWrap = wrap,
                    readOnly = readOnly,
                    // 让开底部导航栏，再让开键盘；imePadding 消费在导航栏之后，底部取二者较大值（关键盘=导航栏、开键盘=键盘）。
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .navigationBarsPadding()
                        .imePadding(),
                )
            }
        }
    }
}

/** 查询 SAF 文档的显示名，用于语言判定与工具栏展示；查不到返回 null。 */
private fun queryDisplayName(context: Context, uri: Uri): String? =
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
        if (c.moveToFirst()) c.getString(0) else null
    }

/** 按扩展名判定语言：.yaml/.yml 走 YAML 高亮，其余按纯文本（避免把 .json/.kt 误高亮成 YAML）。 */
private fun languageForName(name: String?): EditorLanguage {
    val ext = name?.substringAfterLast('.', "")?.lowercase()
    return if (ext == "yaml" || ext == "yml") EditorLanguage.Yaml else EditorLanguage.PlainText
}

/**
 * 生成 ~3MB 的 Mihomo/Clash.Meta 风格配置（性能演示用）。所有 server 取自 RFC 5737 文档保留网段
 * （192.0.2.x / 198.51.100.x / 203.0.113.x）或 .invalid 域名，永不可路由；password/uuid/url 均为占位，
 * 不含任何真实连接。[nodes] 为生成的代理节点数（每节点约一行）。
 */
private fun bigYaml(nodes: Int): String = buildString {
    append(BIG_YAML_HEADER)
    append("\n\nproxies:\n")
    val regions = listOf(
        "🇭🇰 香港" to "192.0.2",
        "🇸🇬 新加坡" to "198.51.100",
        "🇯🇵 日本" to "203.0.113",
        "🇺🇸 美国" to "192.0.2",
        "🇹🇼 台湾" to "198.51.100",
        "🇰🇷 韩国" to "203.0.113",
    )
    for (i in 0 until nodes) {
        val (region, net) = regions[i % regions.size]
        val ip = "$net.${i % 254 + 1}"
        val host = "node-$i.example.invalid"
        val port = 10000 + i % 50000
        val name = "$region 节点 ${(i + 1).toString().padStart(4, '0')}"
        val uuid = "00000000-0000-4000-8000-${(i % 1_000_000_000_000L).toString().padStart(12, '0')}"
        val line = when (i % 5) {
            0 -> "  - {name: \"$name\", type: ss, server: $ip, port: $port, cipher: aes-256-gcm, password: \"example-pw-$i\", udp: true}"
            1 -> "  - {name: \"$name\", type: vmess, server: $ip, port: $port, uuid: $uuid, alterId: 0, cipher: auto, udp: true}"
            2 -> "  - {name: \"$name\", type: trojan, server: $host, port: $port, password: \"example-pw-$i\", sni: $host, skip-cert-verify: true, udp: true}"
            3 -> "  - {name: \"$name\", type: vless, server: $ip, port: $port, uuid: $uuid, tls: true, network: ws, servername: $host, udp: true}"
            else -> "  - {name: \"$name\", type: hysteria2, server: $host, port: $port, password: \"example-pw-$i\", sni: $host, up: \"50 Mbps\", down: \"200 Mbps\"}"
        }
        append(line); append('\n')
    }
    append('\n'); append(BIG_YAML_FOOTER); append('\n')
}

private val BIG_YAML_HEADER = """
    # ===== Mihomo / Clash.Meta 示例配置 =====
    # 仅用于编辑器演示：server 取自 RFC 5737 文档保留网段 / .invalid 域名，永不可路由；
    # password / uuid / url 均为占位，无任何真实连接。

    mixed-port: 7890
    allow-lan: true
    mode: rule
    log-level: info
    ipv6: true
    unified-delay: true
    tcp-concurrent: true
    external-controller: 127.0.0.1:9090
    global-client-fingerprint: chrome

    profile:
      store-selected: true
      store-fake-ip: true

    sniffer:
      enable: true
      sniff:
        HTTP: {ports: [80, 8080-8880], override-destination: true}
        TLS: {ports: [443, 8443]}
        QUIC: {ports: [443, 8443]}

    tun:
      enable: true
      stack: mixed
      dns-hijack: ["any:53", "tcp://any:53"]
      auto-route: true
      auto-detect-interface: true

    dns:
      enable: true
      ipv6: true
      enhanced-mode: fake-ip
      fake-ip-range: 198.18.0.1/16
      fake-ip-filter: ["*.lan", "+.local", "time.*.com", "ntp.*.com"]
      default-nameserver: [192.0.2.53, 198.51.100.53]
      nameserver: ["https://dns.example.invalid/dns-query", "tls://dns.example.invalid"]
      proxy-server-nameserver: ["https://doh.example.invalid/dns-query"]
""".trimIndent()

private val BIG_YAML_FOOTER = """
    proxy-groups:
      - {name: 节点选择, type: select, proxies: [自动选择, 香港自动, 日本自动, 美国自动, DIRECT]}
      - {name: 自动选择, type: url-test, include-all: true, tolerance: 20, interval: 300, url: "https://cp.example.invalid/generate_204"}
      - {name: 香港自动, type: url-test, include-all: true, interval: 300, filter: "(?i)香港|hk|hong ?kong"}
      - {name: 日本自动, type: url-test, include-all: true, interval: 300, filter: "(?i)日本|jp|japan"}
      - {name: 美国自动, type: url-test, include-all: true, interval: 300, filter: "(?i)美国|us|united states"}
      - {name: 漏网之鱼, type: select, proxies: [节点选择, DIRECT]}

    rules:
      - GEOIP,private,DIRECT,no-resolve
      - GEOSITE,category-ads-all,REJECT
      - GEOSITE,cn,DIRECT
      - GEOIP,CN,DIRECT
      - DOMAIN-SUFFIX,example.invalid,节点选择
      - MATCH,漏网之鱼
""".trimIndent()

private val SAMPLE_YAML = """
    # scripta — YAML 示例（覆盖常见语法场景）
    name: scripta
    version: 0.1.0
    released: 2026-07-06
    stable: true
    maintainer: null                 # 空值(null)

    # 标量类型
    scalars:
      int: 42
      float: 3.14159
      hex: 0x1F
      bools: [true, false, yes, no]  # 流式列表
      quoted: "含 : 冒号与 # 井号的字符串"
      single: '单引号里用 '' 转义单引号'
      plain: 直接量字符串不需要引号

    # 嵌套映射 + 块状列表
    editor:
      virtualized: true
      language: yaml
      features:
        - 自绘渲染
        - 视口虚拟化
        - 自管 IME(拼音 composing)
        - 软换行
      gestures: {tap: 落光标, long_press: 选词, pinch: 双指缩放}

    # 锚点 & 引用 & 合并
    defaults: &defaults
      timeout: 30
      retries: 3
    service_a:
      <<: *defaults
      name: alpha
    service_b:
      <<: *defaults
      name: beta
      retries: 5                     # 覆盖默认值

    # 多行字符串
    literal_block: |
      第一行保留换行
      第二行
        这一层缩进也会保留
    folded_block: >
      这几行会被折叠
      成一行(换行变空格)
      最终是一段文本。

    # 国际化 / emoji / 长行
    i18n:
      中文: 输入测试(拼音 composing)
      emoji: "🇭🇰 🇯🇵 🇺🇸 😀 👨‍👩‍👧"
      long_line: 这是一行非常非常非常长的文本用来测试横向滚动与自动换行 aaaaaaaa bbbbbbbb cccccccc dddddddd eeeeeeee ffffffff gggggggg hhhhhhhh iiiiiiii jjjjjjjj
""".trimIndent()

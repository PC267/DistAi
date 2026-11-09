package com.distai.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.OpenableColumns
import android.provider.Settings
import android.view.View
import android.widget.*
import org.json.JSONObject
import java.io.File
import kotlin.concurrent.thread

@SuppressLint("UseSwitchCompatOrMaterialCode", "SetTextI18n")
class MainActivity : Activity() {

    private lateinit var info: TextView
    private lateinit var statusCard: View
    private lateinit var statusText: TextView
    private lateinit var pulse: TextView
    private lateinit var clusterView: ClusterView
    private lateinit var workerSwitch: Switch
    private lateinit var nodesTitle: TextView
    private lateinit var nodesBox: LinearLayout
    private lateinit var modelSpinner: Spinner
    private lateinit var customUrl: EditText
    private lateinit var progress: ProgressBar
    private lateinit var downloadBtn: Button
    private lateinit var ctxField: EditText
    private lateinit var coordBtn: Button
    private lateinit var apiStatus: TextView
    private lateinit var chatBtn: Button
    private lateinit var logView: TextView
    private lateinit var modeReplica: RadioButton
    private lateinit var coordChoice: Spinner

    private lateinit var discovery: Discovery
    private var nodes = listOf<Node>()
    private val excluded = mutableSetOf<String>()
    private var modelItems = listOf<Any>()
    @Volatile private var downloading = false
    private var replicaHosts = listOf<String>()
    private var coordKeys = listOf<String>()
    private var nodesShown = listOf<String>()
    private var modelLabels = listOf<String>()

    @Volatile private var localTokS = 0.0
    private val pulseAnim by lazy {
        android.animation.ObjectAnimator.ofFloat(pulse, "alpha", 1f, 0.25f).apply {
            duration = 700; repeatCount = android.animation.ValueAnimator.INFINITE
            repeatMode = android.animation.ValueAnimator.REVERSE
        }
    }

    private val prefs by lazy { getSharedPreferences("distai", MODE_PRIVATE) }
    
    private var isZh = true 
    private val REQ_PICK_MODEL = 1001

    private val stateListener: () -> Unit = { render() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        
        // 初始化语言状态
        isZh = prefs.getBoolean("isZh", true)

        info = findViewById(R.id.info)
        statusCard = findViewById(R.id.statusCard)
        statusText = findViewById(R.id.statusText)
        pulse = findViewById(R.id.pulse)
        clusterView = findViewById(R.id.clusterView)
        workerSwitch = findViewById(R.id.workerSwitch)
        nodesTitle = findViewById(R.id.nodesTitle)
        nodesBox = findViewById(R.id.nodes)
        modelSpinner = findViewById(R.id.model)
        customUrl = findViewById(R.id.customUrl)
        progress = findViewById(R.id.progress)
        downloadBtn = findViewById(R.id.download)
        ctxField = findViewById(R.id.ctx)
        coordBtn = findViewById(R.id.coordBtn)
        apiStatus = findViewById(R.id.apiStatus)
        chatBtn = findViewById(R.id.chatBtn)
        logView = findViewById(R.id.log)
        modeReplica = findViewById(R.id.modeReplica)
        coordChoice = findViewById(R.id.coordChoice)

        if (prefs.getBoolean("replica", false)) modeReplica.isChecked = true
        modeReplica.setOnCheckedChangeListener { _, v -> prefs.edit().putBoolean("replica", v).apply() }
        
        coordChoice.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: android.widget.AdapterView<*>?, v: View?, pos: Int, id: Long) {
                coordKeys.getOrNull(pos)?.let { prefs.edit().putString("coord", it).apply() }
            }
            override fun onNothingSelected(p: android.widget.AdapterView<*>?) {}
        }

        findViewById<Button>(R.id.benchBtn).setOnClickListener { onBench() }
        if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)

        workerSwitch.setOnClickListener {
            NodeService.send(this, if (workerSwitch.isChecked) NodeService.ACTION_WORKER_START else NodeService.ACTION_WORKER_STOP)
        }

        findViewById<CheckBox>(R.id.moeFast).apply {
            isChecked = prefs.getBoolean("moefast", false)
            setOnCheckedChangeListener { _, v -> prefs.edit().putBoolean("moefast", v).apply() }
        }
        findViewById<CheckBox>(R.id.autoStart).apply {
            isChecked = prefs.getBoolean("autostart", true)
            setOnCheckedChangeListener { _, v -> prefs.edit().putBoolean("autostart", v).apply() }
        }
        findViewById<CheckBox>(R.id.keepScreen).apply {
            isChecked = prefs.getBoolean("keepscreen", true)
            applyKeepScreen(isChecked)
            setOnCheckedChangeListener { _, v -> prefs.edit().putBoolean("keepscreen", v).apply(); applyKeepScreen(v) }
        }

        findViewById<Button>(R.id.battery).setOnClickListener { askBatteryExemption() }
        findViewById<Button>(R.id.freeRam).setOnClickListener { onFreeRam(it as Button) }
        findViewById<Button>(R.id.addIp).setOnClickListener {
            val f = findViewById<EditText>(R.id.manualIp)
            val txt = f.text.toString().trim()
            if (txt.isNotEmpty()) {
                val host = txt.substringBefore(':')
                val port = txt.substringAfter(':', "").toIntOrNull() ?: Native.RPC_PORT
                discovery.addManual(host, port)
                prefs.edit().putStringSet("manual", (prefs.getStringSet("manual", emptySet()).orEmpty() + "$host:$port")).apply()
                f.text.clear()
                f.clearFocus()
            }
        }
        downloadBtn.setOnClickListener { onDownload() }
        coordBtn.setOnClickListener { onCoordinator() }
        chatBtn.setOnClickListener { startActivity(Intent(this, ChatActivity::class.java)) }
        findViewById<Button>(R.id.clearLog).setOnClickListener { NodeState.clearLog() }
        
        // 浏览本地文件
        findViewById<Button>(R.id.browseModel).setOnClickListener {
            val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
                type = "*/*"
                addCategory(Intent.CATEGORY_OPENABLE)
            }
            startActivityForResult(intent, REQ_PICK_MODEL)
        }
        
        // 删除本地模型
        findViewById<Button>(R.id.deleteModel).setOnClickListener {
            val item = modelItems.getOrNull(modelSpinner.selectedItemPosition)
            if (item is File) {
                AlertDialog.Builder(this)
                    .setTitle(if (isZh) "删除模型" else "Delete Model")
                    .setMessage(if (isZh) "确定要永久删除 ${item.name} 吗？" else "Are you sure you want to permanently delete ${item.name}?")
                    .setPositiveButton(if (isZh) "删除" else "Delete") { _, _ ->
                        if (item.exists() && item.delete()) {
                            refreshModels()
                            toast(if (isZh) "已删除" else "Deleted")
                        } else {
                            toast(if (isZh) "删除失败，可能文件已损坏或无权操作" else "Failed to delete file")
                        }
                    }
                    .setNegativeButton(if (isZh) "取消" else "Cancel", null)
                    .show()
            } else {
                toast(if (isZh) "只能删除本地导入的模型文件 (📁)" else "Can only delete local imported models")
            }
        }

        // 国旗点击事件
        findViewById<View>(R.id.flagZh).setOnClickListener {
            if (!isZh) {
                isZh = true
                prefs.edit().putBoolean("isZh", true).apply()
                applyLanguage()
                render()
                renderNodes()
                renderCoordChoice()
            }
        }
        findViewById<View>(R.id.flagEn).setOnClickListener {
            if (isZh) {
                isZh = false
                prefs.edit().putBoolean("isZh", false).apply()
                applyLanguage()
                render()
                renderNodes()
                renderCoordChoice()
            }
        }

        ctxField.setText(prefs.getInt("ctx", 4096).toString())

        discovery = Discovery(this) { nodes = it; renderNodes(); refreshModels() }
        prefs.getStringSet("manual", emptySet()).orEmpty().forEach {
            discovery.addManual(it.substringBefore(':'), it.substringAfter(':').toIntOrNull() ?: Native.RPC_PORT)
        }
        refreshModels()

        if (savedInstanceState == null && prefs.getBoolean("autostart", true) && !NodeState.workerRunning) {
            NodeService.send(this, NodeService.ACTION_WORKER_START)
        }
        
        applyLanguage()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_PICK_MODEL && resultCode == Activity.RESULT_OK) {
            data?.data?.let { uri ->
                thread {
                    try {
                        val fileName = getFileNameFromUri(uri) ?: "local_model_${System.currentTimeMillis()}.gguf"
                        val destDir = ModelStore.list(this).firstOrNull()?.parentFile 
                            ?: File(getExternalFilesDir(null), "models").also { if (!it.exists()) it.mkdirs() }
                        val destFile = File(destDir, fileName)
                        contentResolver.openInputStream(uri)?.use { input ->
                            destFile.outputStream().use { output -> input.copyTo(output) }
                        }
                        runOnUiThread {
                            refreshModels(destFile)
                            Toast.makeText(this, if (isZh) "本地模型已导入" else "Local model imported", Toast.LENGTH_SHORT).show()
                        }
                    } catch (e: Exception) {
                        runOnUiThread {
                            Toast.makeText(this, if (isZh) "导入失败: ${e.message}" else "Import failed: ${e.message}", Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
        }
    }

    private fun applyLanguage() {
        findViewById<View>(R.id.flagZh).alpha = if (isZh) 1f else 0.35f
        findViewById<View>(R.id.flagEn).alpha = if (isZh) 0.35f else 1f

        findViewById<TextView>(R.id.workerTitle).text = if (isZh) "此手机作为工作节点" else "THIS PHONE AS WORKER"
        findViewById<TextView>(R.id.workerHint).text = if (isZh) "提供CPU和RAM用于计算。请在局域网内所有手机上开启。" else "Provides CPU and RAM for compute. Enable on all network phones."
        workerSwitch.text = if (isZh) "开启工作节点" else "Worker active"
        findViewById<CheckBox>(R.id.autoStart).text = if (isZh) "应用启动时自动运行" else "Auto-start worker on app launch"
        findViewById<CheckBox>(R.id.keepScreen).text = if (isZh) "保持屏幕常亮 (防休眠)" else "Keep screen on (prevents standby)"
        findViewById<Button>(R.id.battery).text = if (isZh) "关闭电池优化" else "Disable battery optimization"
        findViewById<Button>(R.id.freeRam).text = if (isZh) "释放内存 (结束后台应用)" else "Free RAM (closes background apps)"

        findViewById<TextView>(R.id.coordTitle).text = if (isZh) "协调器" else "COORDINATOR"
        findViewById<TextView>(R.id.coordHint).text = if (isZh) "选择节点，加载模型并将层分配到各手机。" else "Selects nodes, loads model, and distributes layers across phones."
        findViewById<EditText>(R.id.manualIp).hint = if (isZh) "手动输入IP (例如 192.168.1.23)" else "Manual IP (e.g. 192.168.1.23)"
        findViewById<Button>(R.id.addIp).text = if (isZh) "添加" else "Add"
        
        findViewById<TextView>(R.id.modelTitle).text = if (isZh) "模型" else "Model"
        customUrl.hint = if (isZh) "...或输入 .gguf 链接" else "...or URL to a .gguf file"
        downloadBtn.text = if (isZh) "下载模型" else "Download model"
        
        findViewById<TextView>(R.id.ctxTitle).text = if (isZh) "上下文 (token): " else "Context (tokens): "
        
        findViewById<RadioButton>(R.id.modeSplit).text = if (isZh) "Split模式: 大模型切分至多台手机" else "Split: large model split across phones"
        modeReplica.text = if (isZh) "Replica模式: 模型复制到每台手机 (多用户)" else "Replica: model copied to each phone (multi-user)"
        
        findViewById<CheckBox>(R.id.moeFast).text = if (isZh) "快速MoE: 选8个中的6个专家 (提速12-25%，质量相同)" else "Fast MoE: 6 of 8 experts (Qwen3+12-25% tok/s, same quality)"
        
        findViewById<TextView>(R.id.coordChoiceTitle).text = if (isZh) "协调器: " else "Coordinator: "
        findViewById<Button>(R.id.benchBtn).text = if (isZh) "节点测速" else "Benchmark node speed"
        chatBtn.text = if (isZh) "打开对话" else "Open chat"
        
        findViewById<TextView>(R.id.logTitle).text = if (isZh) "日志" else "Log"
        findViewById<Button>(R.id.clearLog).text = if (isZh) "清空" else "Clear"
        
        nodesTitle.text = if (isZh) "发现节点: 0" else "Nodes found: 0"
    }

    private val statusTick = object : Runnable {
        override fun run() {
            discovery.refreshAll()
            thread(name = "stato") {
                WorkerStats.sample()
                CoordStats.sample()
                localTokS = if (NodeState.apiReady) StatusServer.tokS("127.0.0.1", Native.API_PORT) ?: 0.0 else 0.0
                runOnUiThread { render(); renderNodes() }
            }
            window.decorView.postDelayed(this, 2500)
        }
    }

    override fun onStart() {
        super.onStart()
        NodeState.addListener(stateListener)
        discovery.start()
        window.decorView.post(statusTick)
        render()
    }

    override fun onStop() {
        pulseAnim.cancel()
        window.decorView.removeCallbacks(statusTick)
        NodeState.removeListener(stateListener)
        discovery.stop()
        super.onStop()
    }

    private fun render() {
        renderStatus()
        info.text = Native.summary(this) + SelfBench.describe(SelfBench.result).let { if (it.isEmpty()) "" else "\n$it" } +
            if (SelfBench.running) (if (isZh) "\n⏳ 设备测速中..." else "\n⏳ bench in progress...") else ""
        
        workerSwitch.isChecked = NodeState.workerRunning
        coordBtn.text = if (NodeState.coordinatorRunning) 
            (if (isZh) "停止分布式大模型" else "Stop Distributed LLM") 
        else 
            (if (isZh) "启动分布式大模型" else "Start Distributed LLM")
            
        coordBtn.backgroundTintList = android.content.res.ColorStateList.valueOf(
            if (NodeState.coordinatorRunning) 0xFFC62828.toInt() else 0xFF2E7D32.toInt())
        chatBtn.isEnabled = NodeState.apiReady
        
        // 汉化底层返回的意大利语
        val routerInfo = NodeState.router?.status()?.optJSONArray("replicas")?.let { a ->
            val list = (0 until a.length()).map { a.getJSONObject(it) }
            "\n" + (if (isZh) "副本就绪" else "Replicas ready") + " ${list.count { it.optBoolean("healthy") }}/${list.size}: " +
                list.joinToString(" · ") { "${it.optString("host")} ${if (it.optBoolean("healthy")) "✓" else "…"} (${it.optInt("served")} resp.)" }
        }.orEmpty()
        
        apiStatus.text = when {
            NodeState.apiReady ->
                "✅ ${NodeState.coordinatorInfo}\nOpenAI API: http://${Native.localIp()}:${Native.API_PORT}/v1 " +
                    (if (isZh) "(局域网PC可用)" else "(usable from PC)") + routerInfo
            NodeState.coordinatorRunning -> (if (isZh) "⏳ 正在向节点分发模型..." else "⏳ Distributing model...") + " (${NodeState.coordinatorInfo})$routerInfo"
            else -> ""
        } + if (NodeState.planInfo.isNotEmpty() && (NodeState.coordinatorRunning || NodeState.router != null))
            "\n\n📋 " + NodeState.planInfo
                .replace("piano: ", if (isZh) "计划: " else "plan: ")
                .replace(" questo telefono ", if (isZh) " 此设备 " else " this phone ")
                .replace(" stimato", if (isZh) " 预估" else " est")
                .replace(" necessario per la RAM", if (isZh) " (需要内存)" else " (RAM required)")
                .replace("troppo poco per ", if (isZh) "内存不足: " else "too little for ")
                .replace(Regex("su (\\d+) nodi")) { match -> if (isZh) "在 ${match.groupValues[1]} 个节点上" else "on ${match.groupValues[1]} nodes" }
            else ""
            
        logView.text = NodeState.logText().lines().takeLast(40).joinToString("\n")
    }

    private fun renderRing() {
        val palette = intArrayOf(0xFF1E88E5.toInt(), 0xFF8E24AA.toInt(), 0xFF00A870.toInt(), 0xFFF9A825.toInt(), 0xFFE53935.toInt(), 0xFF546E7A.toInt())
        val plan = NodeState.plan?.takeIf { NodeState.coordinatorRunning || NodeState.apiReady }
        val segs = if (plan != null) {
            listOf(ClusterView.Seg(NodeState.planCoord, plan.needMb[0], plan.localLayers, CoordStats.cpuPct, palette[0])) +
                plan.remote.mapIndexed { i, n ->
                    val live = nodes.firstOrNull { it.endpoint == n.endpoint } ?: n
                    ClusterView.Seg(n.name.removePrefix("PC "), plan.needMb[i + 1], plan.remoteSplit[i],
                        if (live.clients > 0) live.cpuPct else 0, palette[(i + 1) % palette.size])
                }
        } else {
            val me = Native.localIp()
            nodeGroups().mapIndexedNotNull { i, g ->
                val n = g.first()
                val mb = if (isSelf(n, me)) WorkerStats.rssMb else n.workMb
                val cpu = if (isSelf(n, me)) WorkerStats.cpuPct else n.cpuPct
                if (mb > 0) ClusterView.Seg(n.name.removePrefix("PC "), mb, 0, cpu, palette[i % palette.size]) else null
            }
        }
        val tok = if (NodeState.apiReady) localTokS else nodes.firstOrNull { it.role == "coordinatore" && it.apiReady }?.tokS ?: 0.0
        val ringText = if (plan != null) "${plan.layers} layer" else (if (isZh) "worker 权重" else "weights in workers")
        clusterView.set(segs, tok, ringText)
    }

    private fun renderStatus() {
        renderRing()
        findViewById<TextView>(R.id.statusTitle).text = if (isZh) "集群状态" else "CLUSTER STATUS"
        
        val myIp = Native.localIp()
        val others = nodeGroups().filter { g -> g.none { isSelf(it, myIp) } }
        val names = others.joinToString(", ") { g -> g.first().name.removePrefix("PC ") }
        val devices = "📱 " + (if (isZh) "已连接设备" else "Connected devices") + ": ${others.size + 1}" + 
            if (names.isNotEmpty()) (if (isZh) " (此设备 + $names)" else " (this + $names)") else (if (isZh) " (仅此设备)" else " (this one only)")
            
        val remoteCoord = nodes.firstOrNull { it.role == "coordinatore" && !isSelf(it, myIp) }
        val tok = { t: Double -> if (t > 0) " · ${"%.1f".format(t)} tok/s" else "" }
        
        val (model, color) = when {
            NodeState.apiReady -> "🧠 " + (if (isZh) "模型运行中" else "Model running") + " ✓ · ${NodeState.coordinatorInfo}${tok(localTokS)}" to 0xFFE8F5E9.toInt()
            NodeState.coordinatorRunning -> "⏳ " + (if (isZh) "正在加载模型..." else "Loading model...") + " · ${NodeState.coordinatorInfo}" to 0xFFFFF8E1.toInt()
            remoteCoord?.apiReady == true ->
                "🧠 " + (if (isZh) "模型运行中" else "Model running") + " ✓ · ${remoteCoord.name}${tok(remoteCoord.tokS)}" to 0xFFE8F5E9.toInt()
            remoteCoord != null -> "⏳ ${remoteCoord.name} " + (if (isZh) "正在加载模型" else "is loading model") to 0xFFFFF8E1.toInt()
            else -> "⚪ " + (if (isZh) "未启动模型" else "No model started") to 0xFFFFFFFF.toInt()
        }
        
        val busy = WorkerStats.clients > 0
        val worker = when {
            !NodeState.workerRunning -> "⏸ " + (if (isZh) "此手机的 Worker 已关闭" else "This phone's worker is off")
            busy -> "⚙️ " + (if (isZh) "正在为集群工作 · 权重" else "Working for cluster · weights") + " ${gb(WorkerStats.rssMb)} · CPU ${WorkerStats.cpuPct}%"
            else -> "💤 " + (if (isZh) "Worker 就绪，等待协调器" else "Worker ready, waiting for coordinator")
        }
        
        val viaPc = others.map { it.first().name }.filter { it.endsWith("(USB del PC)") || it.contains("(USB)") || it.contains("(via PC)") }
        val hint = if (viaPc.isEmpty()) null else
            "🔌 ${viaPc.joinToString { it.replace(" (USB del PC)", "").replace(" (USB)", "") }} " + 
                (if (isZh) "通过PC(USB)连接：建议开启USB网络共享以降低延迟" else "connected via PC (USB): enable USB Tethering for less latency")
                
        val onlyUsb = if (others.isNotEmpty() && others.all { g -> g.all { it.usb || it.cable } })
            "🔌 " + (if (isZh) "仅通过USB连接：若有多台设备，请置于同一局域网" else "Connected via USB only: put on same network to work with others") else null
            
        val wired = if (others.isNotEmpty() && !Native.wiredLink() && others.none { g -> g.any { it.usb || it.cable } })
            "💡 " + (if (isZh) "推荐使用线缆直连以获得高带宽" else "Cable recommended: 15 to 100 times the Wi-Fi bandwidth") else null
            
        statusText.text = listOfNotNull(devices, model, worker, hint, onlyUsb, wired).joinToString("\n")
        statusCard.backgroundTintList = android.content.res.ColorStateList.valueOf(color)
        val active = NodeState.coordinatorRunning || (busy && WorkerStats.cpuPct >= 10)
        pulse.setTextColor(if (active || color != 0xFFFFFFFF.toInt()) 0xFF2E7D32.toInt() else 0xFF9E9E9E.toInt())
        if (active && !pulseAnim.isRunning) pulseAnim.start()
        if (!active && pulseAnim.isRunning) { pulseAnim.cancel(); pulse.alpha = 1f }
    }

    private fun gb(mb: Long) = if (mb >= 1024) "%.1f GB".format(mb / 1024.0) else "$mb MB"

    private fun nodeGroups(): List<List<Node>> = nodes.groupBy { it.devId.ifEmpty { it.endpoint } }.values.toList()

    private fun renderNodes() {
        var ram = 0L
        val sel = selectedNodes()
        val groups = nodeGroups()
        val keys = groups.map { g -> g.joinToString { it.endpoint } }
        
        if (keys != nodesShown) {
            nodesShown = keys
            nodesBox.removeAllViews()
            for (g in groups) addNodeRow(g)
        } else {
            groups.forEachIndexed { i, g ->
                (nodesBox.getChildAt(i) as? CheckBox)?.let { cb ->
                    val t = withLinkIcons(g, nodeText(g))
                    if (cb.text.toString() != t.toString()) cb.text = t
                }
            }
        }
        
        sel.groupBy { it.devId.ifEmpty { it.endpoint } }.values.forEach { ram += it.first().freeMb }
        val devices = sel.map { it.devId.ifEmpty { it.endpoint } }.distinct().size
        nodesTitle.text = (if (isZh) "发现节点: ${groups.size} · 已选 $devices" else "Nodes found: ${groups.size} · selected $devices") +
            if (ram > 0) " · RAM ≈ ${"%.1f".format(ram / 1024.0)} GB" else ""
        renderCoordChoice()
    }

    private fun withLinkIcons(g: List<Node>, text: String): CharSequence = icons(
        buildList {
            if (g.any { it.role == "coordinatore" }) add(R.drawable.ic_home)
            if (g.any { !it.usb && !it.cable }) add(R.drawable.ic_wifi)
            if (g.any { it.usb || it.cable }) add(R.drawable.ic_usb)
        }, text)

    private fun icons(icons: List<Int>, text: CharSequence, sb: android.text.SpannableStringBuilder = android.text.SpannableStringBuilder()): CharSequence {
        val size = (resources.displayMetrics.scaledDensity * 18).toInt()
        for (res in icons) {
            val d = getDrawable(res)!!.apply { setBounds(0, 0, size, size) }
            val start = sb.length
            sb.append("\u00A0")
            sb.setSpan(android.text.style.ImageSpan(d, android.text.style.DynamicDrawableSpan.ALIGN_BASELINE),
                start, sb.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.append(" ")
        }
        return sb.append(text)
    }

    private fun nodeText(g: List<Node>): String {
        val n = g.first()
        val mem = if (n.ramMb > 0) " · RAM ${"%.1f".format(n.ramMb / 1024.0)} GB" else ""
        val role = g.map { it.role }.firstOrNull { it.isNotEmpty() && it != "coordinatore" }?.let { " · $it" }.orEmpty()
        val speed = g.mapNotNull { Cluster.bench[it.endpoint] }.maxByOrNull { it.gbps }
            ?.let { " · ${"%.1f".format(it.gbps)} GB/s" }
            ?: g.firstOrNull { it.benchGbps > 0 }?.let { " · bench ${"%.1f".format(it.benchGbps)} GB/s ${"%.0f".format(it.benchGflops)} GFLOPS" }
            ?: ""
        val rtt = g.mapNotNull { Cluster.bench[it.endpoint]?.rttMs }.minOrNull()?.let { " · rtt ${"%.0f".format(it)} ms" }.orEmpty()
        val act = g.maxByOrNull { it.clients }?.takeIf { it.clients > 0 }
            ?.let { "\n● " + (if (isZh) "使用中" else "in use") + " · weights ${gb(it.workMb)} · CPU ${it.cpuPct}%" }.orEmpty()
        return "${n.name}\n${g.joinToString(" + ") { it.endpoint }}$mem" +
            "${if (n.cpu.isNotEmpty()) " · ${n.cpu}" else ""}$role$speed$rtt$act"
    }

    private fun addNodeRow(g: List<Node>) {
        val cb = CheckBox(this)
        cb.text = withLinkIcons(g, nodeText(g))
        cb.isChecked = g.none { it.endpoint in excluded }
        cb.setOnCheckedChangeListener { _, v ->
            g.forEach { if (v) excluded.remove(it.endpoint) else excluded.add(it.endpoint) }
            renderNodes()
        }
        nodesBox.addView(cb)
    }

    private fun renderCoordChoice() {
        val coordNodes = nodes.filter { it.canCoord && it.devId.isNotEmpty() && !isSelf(it, Native.localIp()) }.distinctBy { it.devId }
        val keys = listOf(Cluster.COORD_AUTO, Cluster.COORD_SELF) + coordNodes.map { it.devId }
        if (keys == coordKeys) return
        val labels = listOf(
            if (isZh) "自动 (优先PC，否则最快)" else "Auto (PC if available, else fastest)", 
            if (isZh) "此手机" else "This phone"
        ) + coordNodes.map { "${it.name}${if (it.x86) " (PC)" else ""}" }
        coordKeys = keys
        coordChoice.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, labels)
        val saved = prefs.getString("coord", Cluster.COORD_AUTO)
        coordChoice.setSelection(keys.indexOf(saved).coerceAtLeast(0))
    }

    private fun clusterNodes(): Pair<Node, List<Node>> {
        val myIp = Native.localIp()
        val self = Discovery.refresh(Node("127.0.0.1", Native.RPC_PORT, Native.deviceName()))
            .copy(name = "${Native.deviceName()} ${if (isZh) "(此设备)" else "(this)"}", freeMb = Native.memInfo(this).second)
        val others = selectedNodes().filter { !isSelf(it, myIp) }.filter { n ->
            Discovery.reachable(n).also { if (!it) NodeState.log("coord", (if (isZh) "节点无法访问: " else "node unreachable: ") + n.endpoint) }
        }.map { Discovery.refresh(it) }
        return self to others
    }

    private fun onBench() {
        if (NodeState.coordinatorRunning) return toast(if (isZh) "请先停止 LLM" else "Stop the LLM first")
        toast(if (isZh) "正在测试节点速度..." else "Benchmarking nodes...")
        thread(name = "bench") {
            val (self, others) = clusterNodes()
            Cluster(this).measure(listOf(self) + others)
            runOnUiThread { renderNodes() }
        }
    }

    private fun selectedNodes() = nodes.filter { it.endpoint !in excluded }

    private fun isSelf(n: Node, myIp: String?) =
        n.host == myIp || (n.host == "127.0.0.1" && n.port == Native.RPC_PORT) || n.devId == selfDevId

    private val selfDevId by lazy { "${Native.deviceName()}/${Native.memInfo(this).first}" }

    data class RemoteModel(val name: String, val size: Long, val node: Node)

    private fun getFileNameFromUri(uri: Uri): String? {
        var result: String? = null
        if (uri.scheme == "content") {
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (index != -1) result = cursor.getString(index)
                }
            }
        }
        if (result == null) {
            result = uri.path
            val cut = result?.lastIndexOf('/')
            if (cut != -1 && cut != null) result = result?.substring(cut + 1)
        }
        return result
    }

    private fun refreshModels(select: File? = null) {
        val local = ModelStore.list(this)
        val missing = ModelStore.presets.filter { p -> local.none { it.name == p.url.substringAfterLast('/') } }
        val onPc = nodes.filter { it.canCoord && it.x86 && it.models.isNotEmpty() && !isSelf(it, Native.localIp()) }
            .sortedBy { if (it.usb) 1 else 0 }
            .flatMap { n -> n.models.map { (name, size) -> RemoteModel(name, size, n) } }
            .filter { r -> local.none { it.name == r.name } }.distinctBy { it.name }
        val items = local + onPc + missing.filter { p -> onPc.none { it.name == p.url.substringAfterLast('/') } }
        
        val labels = items.map {
            when (it) {
                is File -> "📁 ${it.name} (${"%.1f".format(it.length() / 1e9)} GB)"
                is RemoteModel -> "🖥 ${it.name} (${"%.1f".format(it.size / 1e9)} GB, " + (if (isZh) "在 " else "on ") + "${it.node.name.removeSuffix(" (USB)").removeSuffix(" (cavo)")})"
                else -> "⬇ ${(it as ModelStore.Preset).label}"
            }
        }
        if (select == null && labels == modelLabels) return
        val previous = modelLabels.getOrNull(modelSpinner.selectedItemPosition)
        modelItems = items
        modelLabels = labels
        modelSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, labels)
        val idx = select?.let { f -> modelItems.indexOfFirst { it is File && it.path == f.path } }
            ?: labels.indexOf(previous)
        if (idx >= 0) modelSpinner.setSelection(idx)
    }

    private fun onDownload() {
        if (downloading) { downloading = false; return }
        val url = customUrl.text.toString().trim().ifEmpty {
            (modelItems.getOrNull(modelSpinner.selectedItemPosition) as? ModelStore.Preset)?.url
        }
        if (url == null) { toast(if (isZh) "所选模型已在手机上" else "The selected model is already on the phone"); return }
        downloading = true
        downloadBtn.text = if (isZh) "取消下载" else "Cancel download"
        progress.visibility = View.VISIBLE
        progress.isIndeterminate = true
        thread(name = "download") {
            val result = runCatching {
                ModelStore.download(this, url, { !downloading }) { done, total ->
                    runOnUiThread {
                        if (total > 0) {
                            progress.isIndeterminate = false
                            progress.max = 1000
                            progress.progress = (done * 1000 / total).toInt()
                        }
                        downloadBtn.text = (if (isZh) "取消 (" else "Cancel (") + "${done / 1048576} / ${if (total > 0) total / 1048576 else "?"} MB)"
                    }
                }
            }
            runOnUiThread {
                downloading = false
                downloadBtn.text = if (isZh) "下载模型" else "Download model"
                progress.visibility = View.GONE
                result.onSuccess { customUrl.text.clear(); refreshModels(it); toast((if (isZh) "已下载 " else "Downloaded ") + it.name) }
                    .onFailure { toast((if (isZh) "下载错误: " else "Download error: ") + it.message) }
            }
        }
    }

    private fun onCoordinator() {
        if (NodeState.coordinatorRunning) {
            // 【修复】：加入 try-catch 防止停止时抛出异常导致 App 闪退退出
            thread {
                try {
                    Cluster(this).stop()
                } catch (e: Exception) {
                    e.printStackTrace()
                }
                
                NodeService.send(this, NodeService.ACTION_REPLICA_STOP)
                
                val hosts = replicaHosts
                replicaHosts = emptyList()
                try {
                    hosts.forEach { StatusServer.post(it, "/replica/stop") }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
            return
        }
        
        val item = modelItems.getOrNull(modelSpinner.selectedItemPosition)
        val remote = item as? RemoteModel
        val model = item as? File ?: remote?.let { File(cacheDir, it.name) }
            ?: return toast(if (isZh) "请选择已下载的模型" else "Select a downloaded model")
        val ctx = ctxField.text.toString().toIntOrNull()?.coerceIn(512, 131072) ?: 4096
        prefs.edit().putInt("ctx", ctx).apply()

        if (modeReplica.isChecked) {
            if (remote != null) return toast(if (isZh) "请先下载模型" else "Download model first")
            return startReplicas(model, ctx)
        }
        val choice = prefs.getString("coord", Cluster.COORD_AUTO) ?: Cluster.COORD_AUTO
        coordBtn.isEnabled = false
        thread(name = "cluster") {
            val (self, others) = clusterNodes()
            val cluster = Cluster(this)
            val remoteInfo = remote?.let { r ->
                NodeState.log("cluster", "${r.name} " + (if (isZh) "位于" else "is on") + " ${r.node.name}: " + (if (isZh) "PC将作为协调器" else "PC will coordinate"))
                Cluster.remoteInfo(r.node, r.name, r.size, cacheDir)?.second?.let { it to r.node.devId }
                    ?: run { NodeState.log("cluster", (if (isZh) "无法读取头部 " else "cannot read header ") + r.name); null }
            }
            val start = if (remote != null && remoteInfo == null) null else cluster.plan(model, ctx, self, others, choice, remoteInfo)
            runOnUiThread {
                coordBtn.isEnabled = true
                renderNodes()
                if (start == null) toast(if (isZh) "启动失败，请查看日志" else "Start failed, see log")
            }
            if (start != null) {
                val experts = if (prefs.getBoolean("moefast", false) && start.info.arch == "qwen3moe") 6 else 0
                cluster.launch(start, experts = experts)
                cluster.tuneSpeculative(start)
            }
        }
    }

    private fun startReplicas(model: File, ctx: Int) {
        val myIp = Native.localIp() ?: return toast(if (isZh) "无网络" else "No network")
        val candidates = selectedNodes().filter { it.host != myIp && it.host != "127.0.0.1" }
        coordBtn.isEnabled = false
        thread(name = "replicas") {
            val info = Gguf.read(model)
            fun fits(freeMb: Long) = info != null && SplitPlan.make(info, ctx * 2, freeMb, emptyList()) is SplitPlan.Ok
            val localOk = fits(Native.memInfo(this).second)
            val remote = candidates.map { Discovery.refresh(it) }.filter { n ->
                val ok = Discovery.reachable(n) && fits(n.freeMb)
                if (!ok) NodeState.log("coord", "${n.host} " + (if (isZh) "已排除: 无法访问或内存不足" else "excluded: unreachable or low RAM") + " (${n.freeMb} MB free)")
                ok
            }
            val source = "http://$myIp:${Native.STATUS_PORT}/files/${Uri.encode(model.name)}"
            val started = remote.filter { n ->
                StatusServer.post(n.host, "/replica/start",
                    JSONObject().put("model", model.name).put("source", source).put("ctx", ctx))
            }.map { it.host }
            runOnUiThread {
                coordBtn.isEnabled = true
                if (info == null) return@runOnUiThread toast(if (isZh) "无法读取 GGUF 文件" else "Cannot read GGUF")
                val hosts = (if (localOk) listOf("127.0.0.1") else emptyList()) + started
                if (hosts.isEmpty()) return@runOnUiThread toast(if (isZh) "内存不足，请使用拆分模式" else "Model doesn't fit, use Split mode")
                if (!localOk) NodeState.log("coord", if (isZh) "内存不足，仅作为路由" else "no RAM for replica: router only")
                replicaHosts = started
                Keepalive.pin(started)
                if (localOk) NodeService.send(this, NodeService.ACTION_REPLICA_START) {
                    putExtra(NodeService.EXTRA_MODEL, model.path)
                    putExtra(NodeService.EXTRA_CTX, ctx)
                }
                NodeService.send(this, NodeService.ACTION_ROUTER_START) {
                    putStringArrayListExtra(NodeService.EXTRA_HOSTS, ArrayList(hosts))
                    putExtra(NodeService.EXTRA_MODEL, model.name)
                }
                NodeState.log("coord", (if (isZh) "副本已启动于: " else "replicas started on: ") + hosts.joinToString())
            }
        }
    }

    @SuppressLint("BatteryLife")
    private fun onFreeRam(btn: Button) {
        btn.isEnabled = false
        toast(if (isZh) "正在释放内存..." else "Freeing RAM...")
        thread {
            val r = MemoryCleaner.free(this)
            runOnUiThread { btn.isEnabled = true; toast(r.describe()) }
        }
    }

    private fun askBatteryExemption() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        if (pm.isIgnoringBatteryOptimizations(packageName)) return toast(if (isZh) "已忽略电池优化" else "Already exempt")
        startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
    }

    private fun applyKeepScreen(on: Boolean) {
        if (on) window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
}

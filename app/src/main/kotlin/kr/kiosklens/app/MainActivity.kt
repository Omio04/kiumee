package kr.kiosklens.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.view.PreviewView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kr.kiosklens.app.camera.CameraController
import kr.kiosklens.app.di.AndroidLogger
import kr.kiosklens.app.di.Graph
import kr.kiosklens.app.di.GraphFactory
import kr.kiosklens.app.ui.RecognitionStatus
import kr.kiosklens.app.ui.UiTexts
import kr.kiosklens.core.model.ScreenSnapshot
import kr.kiosklens.domain.session.Phase
import kr.kiosklens.overlay.RecognitionView
import kr.kiosklens.overlay.ViewOverlay
import kr.kiosklens.vision.ScanStatus

/**
 * 화면 하나짜리 앱: 카메라 미리보기 + 노란 테두리 + 큰 상태 문구 + "말하기" / "그만하기" 버튼.
 * 로직은 없다. 조립은 [GraphFactory](실구현만), 흐름은 :domain 의 SessionRunner.
 * 본문 글자 20sp 이상, 핵심 버튼 72dp 이상 (설계서 10장).
 */
class MainActivity : AppCompatActivity() {
    private val work = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val ui = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val logger = AndroidLogger()

    private lateinit var overlay: ViewOverlay
    private lateinit var status: TextView
    private lateinit var note: TextView
    private lateinit var startBtn: Button
    private lateinit var stopBtn: Button
    private lateinit var settingsBtn: Button
    private lateinit var preview: PreviewView

    private var graph: Graph? = null
    private var camera: CameraController? = null
    private var caption: String? = null

    // 확인용 (디버그 빌드만): 카메라가 인식한 글자 · 버튼을 미리보기 위에 그리고, 위쪽 줄에 인식 상태를 보여 준다
    private var recognition: RecognitionView? = null
    private var debugLine: TextView? = null
    private var showRecognition = true
    private var lastScan = ScanStatus.SCANNING
    private var lastSnapshot: ScreenSnapshot? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)          // 안내 중 화면이 꺼지지 않게
        setContentView(buildUi())
        ensurePermissions()
    }

    // ---------------- 권한 ----------------

    private val needed = arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)

    private fun ensurePermissions() {
        if (needed.all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }) init()
        else requestPermissions(needed, REQ)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ) return
        if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) init()
        else {
            status.text = getString(R.string.need_permission)
            settingsBtn.visibility = View.VISIBLE
            startBtn.isEnabled = false
        }
    }

    // ---------------- 시작 ----------------

    private fun init() {
        if (graph != null) return
        settingsBtn.visibility = View.GONE
        val g = GraphFactory.create(this, work, overlay)
        graph = g
        g.setCaptionListener { text -> runOnUiThread { caption = text; render() } }

        camera = CameraController(this, g.frameSink, logger) { w, h ->
            runOnUiThread { overlay.setAnalysisSize(w, h); recognition?.setAnalysisSize(w, h) }
        }.also { it.start(this, preview) }
        if (BuildConfig.SERVER_URL.isBlank()) { note.text = getString(R.string.need_server); note.visibility = View.VISIBLE }
        if (BuildConfig.DEBUG) {
            ui.launch { g.snapshots.collect { s -> lastSnapshot = s; recognition?.show(s); renderDebug() } }
            ui.launch { g.scanStatus.collect { st -> lastScan = st; renderDebug() } }
        }
        g.runner.start()
        ui.launch { g.runner.state.collect { s ->
            if (s.phase == Phase.LISTENING) caption = null
            render()
        } }
        render()
    }

    private fun render() {
        val g = graph ?: return
        val phase = g.runner.state.value.phase
        status.text = UiTexts.status(phase, caption)
        startBtn.isEnabled = UiTexts.canStart(phase)
        stopBtn.visibility = if (UiTexts.canStop(phase)) View.VISIBLE else View.INVISIBLE
    }

    private fun renderDebug() {
        debugLine?.text = RecognitionStatus.text(lastScan, lastSnapshot, showRecognition)
    }

    override fun onDestroy() {
        camera?.stop(); graph?.close()
        ui.cancel(); work.cancel()
        super.onDestroy()
    }

    // ---------------- 화면 만들기 (XML 없이) ----------------

    private fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    private fun buildUi(): View {
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        val match = ViewGroup.LayoutParams.MATCH_PARENT

        preview = PreviewView(this).apply { scaleType = PreviewView.ScaleType.FILL_CENTER }.also { root.addView(it, FrameLayout.LayoutParams(match, match)) }
        if (BuildConfig.DEBUG) recognition = RecognitionView(this).also { root.addView(it, FrameLayout.LayoutParams(match, match)) }   // 노란 테두리보다 아래
        overlay = ViewOverlay(this).also { root.addView(it, FrameLayout.LayoutParams(match, match)) }

        status = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f); setTextColor(Color.WHITE); typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER; setPadding(dp(16), dp(16), dp(16), dp(8))
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE                  // TalkBack 이 상태 변화를 읽어 준다
            text = ""
        }
        note = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f); setTextColor(Color.rgb(255, 214, 0)); gravity = Gravity.CENTER
            setPadding(dp(16), 0, dp(16), dp(8)); visibility = View.GONE
        }
        settingsBtn = bigButton(getString(R.string.open_settings), Color.WHITE, Color.BLACK, 72).apply {
            visibility = View.GONE
            setOnClickListener { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) }
        }
        startBtn = bigButton(getString(R.string.start), Color.rgb(255, 214, 0), Color.BLACK, 88).apply {
            contentDescription = "말하기. 누르고 원하는 것을 말씀하세요"
            setOnClickListener { graph?.runner?.pressStart() }
        }
        stopBtn = bigButton(getString(R.string.stop), Color.rgb(60, 60, 60), Color.WHITE, 88).apply {
            visibility = View.INVISIBLE; setOnClickListener { graph?.runner?.pressStop() }
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; setPadding(dp(12), dp(4), dp(12), dp(16))
            addView(stopBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(8) })
            addView(startBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 2f))
        }
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.argb(230, 0, 0, 0))
            addView(status); addView(note); addView(settingsBtn); addView(row)
        }
        root.addView(panel, FrameLayout.LayoutParams(match, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
        if (BuildConfig.DEBUG) {
            debugLine = TextView(this).apply {
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f); setTextColor(Color.WHITE); setBackgroundColor(Color.argb(170, 0, 0, 0))
                setPadding(dp(12), dp(36), dp(12), dp(8))                                       // 위 상태 표시줄 아래로
                text = "[확인용] 카메라를 켜는 중"
                setOnClickListener {
                    showRecognition = !showRecognition
                    recognition?.visibility = if (showRecognition) View.VISIBLE else View.INVISIBLE
                    renderDebug()
                }
            }.also { root.addView(it, FrameLayout.LayoutParams(match, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP)) }
        }
        return root
    }

    private fun bigButton(label: String, bg: Int, fg: Int, heightDp: Int) = Button(this).apply {
        text = label; setTextSize(TypedValue.COMPLEX_UNIT_SP, 28f); setTextColor(fg); typeface = Typeface.DEFAULT_BOLD
        setBackgroundColor(bg); minHeight = dp(heightDp); minimumHeight = dp(heightDp); isAllCaps = false
    }

    private companion object { const val REQ = 100 }
}

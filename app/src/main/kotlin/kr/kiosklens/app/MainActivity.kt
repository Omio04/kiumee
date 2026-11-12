package kr.kiosklens.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.view.PreviewView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kr.kiosklens.app.camera.CameraController
import kr.kiosklens.app.di.AndroidLogger
import kr.kiosklens.app.di.Graph
import kr.kiosklens.app.di.GraphFactory
import kr.kiosklens.app.net.LocalNetwork
import kr.kiosklens.app.ui.MicButtonView
import kr.kiosklens.app.ui.MicRipple
import kr.kiosklens.app.ui.RecognitionStatus
import kr.kiosklens.app.ui.UiTexts
import kr.kiosklens.core.model.ScreenSnapshot
import kr.kiosklens.domain.session.Phase
import kr.kiosklens.overlay.RecognitionView
import kr.kiosklens.overlay.ViewOverlay
import kr.kiosklens.vision.ScanStatus

/**
 * 화면 하나짜리 앱: 카메라 미리보기 + 노란 테두리 + 아래 가운데 동그란 마이크 버튼 하나.
 * 2026-11-12 화면 개편: "말하기" · "그만하기" 버튼과 상태 문구(자막)를 없애고 마이크 버튼 하나로.
 * - 누르기 = 말하기 (안내 중이면 말 보태기) · 길게 누르기 = 처음부터 · 듣는 동안 둘레에 파동
 * - 상태 문구는 화면에 안 쓰고 버튼 설명(TalkBack)으로만 읽는다. 권한 · 서버 안내는 문제가 있을 때만 보인다
 * 로직은 없다. 조립은 [GraphFactory](실구현만), 흐름은 :domain 의 SessionRunner. 핵심 버튼 72dp 이상 (설계서 10장).
 */
class MainActivity : AppCompatActivity() {
    private val work = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val ui = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val logger = AndroidLogger()

    private lateinit var overlay: ViewOverlay
    private lateinit var note: TextView
    private lateinit var micButton: MicButtonView
    private lateinit var settingsBtn: Button
    private lateinit var preview: PreviewView

    private var graph: Graph? = null
    private var camera: CameraController? = null

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

    /**
     * 디버그 빌드: 서버가 같은 와이파이의 노트북 · 에뮬레이터의 Mac(사설 IP)이면 Android 17+ 로컬 네트워크 권한을 받는다.
     * 없으면 서버 요청이 조용히 막혀 6초 뒤 "연결이 잠시 불안정해요"로 끝난다. 카메라 · 마이크와 달리 없어도 앱은 켜진다.
     */
    private fun ensureLocalNetworkPermission() {
        if (Build.VERSION.SDK_INT < 37 || !LocalNetwork.isLocal(BuildConfig.SERVER_URL)) return       // 37 = Android 17
        if (checkSelfPermission(Manifest.permission.ACCESS_LOCAL_NETWORK) == PackageManager.PERMISSION_GRANTED) return
        requestPermissions(arrayOf(Manifest.permission.ACCESS_LOCAL_NETWORK), REQ_LOCAL)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_LOCAL) {
            if (grantResults.firstOrNull() != PackageManager.PERMISSION_GRANTED) {
                note.text = getString(R.string.need_local_network); note.visibility = View.VISIBLE
            }
            return
        }
        if (requestCode != REQ) return
        if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) init()
        else {
            note.text = getString(R.string.need_permission); note.visibility = View.VISIBLE
            settingsBtn.visibility = View.VISIBLE
            micButton.isEnabled = false; micButton.alpha = 0.4f
        }
    }

    // ---------------- 시작 ----------------

    private fun init() {
        if (graph != null) return
        settingsBtn.visibility = View.GONE
        note.visibility = View.GONE
        micButton.isEnabled = true; micButton.alpha = 1f
        val g = GraphFactory.create(this, work, overlay)
        graph = g

        camera = CameraController(this, g.frameSink, logger) { w, h ->
            runOnUiThread { overlay.setAnalysisSize(w, h); recognition?.setAnalysisSize(w, h) }
        }.also { it.start(this, preview) }
        if (BuildConfig.SERVER_URL.isBlank()) { note.text = getString(R.string.need_server); note.visibility = View.VISIBLE }
        if (BuildConfig.DEBUG) ensureLocalNetworkPermission()
        if (BuildConfig.DEBUG) {
            ui.launch { g.snapshots.collect { s -> lastSnapshot = s; recognition?.show(s); renderDebug() } }
            ui.launch { g.scanStatus.collect { st ->
                lastScan = st
                if (st != ScanStatus.READY) recognition?.clear()                    // 카메라를 돌리면 옛 테두리를 바로 지운다
                renderDebug()
            } }
        }
        g.runner.start()
        ui.launch { g.runner.state.collect { render() } }
        ui.launch { g.voiceLevel.collect { micButton.level = it } }                // 목소리 크기 → 파동 크기
        render()
    }

    private fun render() {
        val g = graph ?: return
        val phase = g.runner.state.value.phase
        micButton.listening = phase == Phase.LISTENING
        val description = UiTexts.micDescription(phase)                          // 자막 대신 TalkBack이 읽는 설명
        if (micButton.contentDescription != description) micButton.contentDescription = description
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

        // 문제가 있을 때만 보이는 안내 (권한 · 서버 주소 · 로컬 네트워크). 평소 화면에는 글자가 없다
        note = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f); setTextColor(Color.WHITE); gravity = Gravity.CENTER
            typeface = Typeface.DEFAULT_BOLD; setBackgroundColor(Color.argb(200, 0, 0, 0))
            setPadding(dp(16), dp(12), dp(16), dp(12)); visibility = View.GONE
        }
        settingsBtn = bigButton(getString(R.string.open_settings), Color.WHITE, Color.BLACK, 72).apply {
            visibility = View.GONE
            setOnClickListener { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) }
        }
        micButton = MicButtonView(this).apply {
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE                  // 설명이 바뀌면 TalkBack이 읽어 준다
            contentDescription = UiTexts.micDescription(Phase.IDLE)
            setOnClickListener { graph?.runner?.pressStart() }                             // 말하기 (안내 중이면 말 보태기)
            setOnLongClickListener {                                                       // 처음부터 (예전 "그만하기")
                performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                graph?.runner?.pressStop(); true
            }
        }
        val micSize = (dp(MIC_DP) * MicRipple.MAX_SCALE).toInt()                          // 파동이 퍼질 자리까지
        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            addView(note, LinearLayout.LayoutParams(match, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(settingsBtn, LinearLayout.LayoutParams(match, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(dp(16), dp(8), dp(16), 0) })
            addView(micButton, LinearLayout.LayoutParams(micSize, micSize))
        }
        ViewCompat.setOnApplyWindowInsetsListener(bottom) { v, insets ->                 // 시스템 내비게이션 바에 가리지 않게
            v.setPadding(0, 0, 0, insets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom)
            insets
        }
        root.addView(bottom, FrameLayout.LayoutParams(match, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
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

    private companion object {
        const val REQ = 100
        const val REQ_LOCAL = 101
        /** 마이크 버튼(원) 지름. 어르신이 누르기 쉽게 크게 (핵심 버튼 72dp 이상) */
        const val MIC_DP = 96
    }
}

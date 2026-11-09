package kr.kiosklens.app.di

import android.util.Log
import kr.kiosklens.core.port.Logger

/** 발화 · 화면 글자 원문은 호출하는 쪽에서 넘기지 않는다(계약). 성능 구간은 tag = "KL_PERF". */
class AndroidLogger : Logger {
    override fun d(tag: String, message: String) { Log.d(tag, message) }
    override fun w(tag: String, message: String, error: Throwable?) { Log.w(tag, message, error) }
    override fun e(tag: String, message: String, error: Throwable?) { Log.e(tag, message, error) }
}

package kr.kiosklens.app

import android.app.Activity
import android.os.Bundle
import android.widget.TextView

/** 1일차 자리 표시. 2일차에 Compose 화면(말하기 버튼 + 상태 문구 + 종료 버튼)으로 교체. */
class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(TextView(this).apply { text = getString(R.string.app_name) })
    }
}

package me.capcom.smsgateway.ui.styles

import android.content.Context
import androidx.annotation.ColorRes
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import me.capcom.smsgateway.R
import me.capcom.smsgateway.domain.ProcessingState

/* 문자 상태를 «어떻게 부르고 어떤 색으로 보일지» 정하는 곳 — 한 자리다.
 *
 * 🔴 2026-09-06 고침: 여기 색이 원본 그대로 «보라·틸»이었다. 앱 색을 파랑으로 바꿔도
 *    이 값은 코틀린에 박혀 있어 안 따라왔다. 게다가 **실패가 청록색**이었다 —
 *    「안 됐다」가 「됐다」처럼 보였다. 이제 뜻과 색이 맞는다:
 *      됐다=초록 · 하는 중=파랑 · 안 됐다=빨강 · 멈춤/기다림=회색 · 취소하는 중=주황
 *
 * 값은 res/values/colors.xml 의 인슈어 키트 토큰을 가리킨다 — 여기서 색을 지어내지 않는다.
 * 폰이 다크면 안드로이드가 values-night 의 «같은 이름»으로 바꿔 준다(옛 방식은 그게 안 됐다).
 */

/** 「도착」·「보내는 중」처럼 사람이 읽는 이름 */
@get:StringRes
val ProcessingState.labelRes: Int
    get() = when (this) {
        ProcessingState.Pending -> R.string.im_state_pending
        ProcessingState.Cancelling -> R.string.im_state_cancelling
        ProcessingState.Cancelled -> R.string.im_state_cancelled
        ProcessingState.Processed -> R.string.im_state_sending
        ProcessingState.Sent -> R.string.im_state_sent
        ProcessingState.Delivered -> R.string.im_state_delivered
        ProcessingState.Failed -> R.string.im_state_failed
    }

/** 칩 글자색 */
@get:ColorRes
val ProcessingState.fgRes: Int
    get() = when (this) {
        ProcessingState.Delivered -> R.color.im_ok_fg
        ProcessingState.Sent, ProcessingState.Processed -> R.color.im_on_tint
        ProcessingState.Failed -> R.color.im_danger_fg
        ProcessingState.Cancelling -> R.color.im_warn_fg
        ProcessingState.Pending, ProcessingState.Cancelled -> R.color.im_hold_fg
    }

/** 칩 바탕색 */
@get:ColorRes
val ProcessingState.bgRes: Int
    get() = when (this) {
        ProcessingState.Delivered -> R.color.im_ok_bg
        ProcessingState.Sent, ProcessingState.Processed -> R.color.im_blue_tint
        ProcessingState.Failed -> R.color.im_danger_bg
        ProcessingState.Cancelling -> R.color.im_warn_bg
        ProcessingState.Pending, ProcessingState.Cancelled -> R.color.im_hold_bg
    }

/* 옛 이름 — 아직 이 값을 쓰는 화면들(수신함·기록·받는 사람)이 있어 남긴다.
 * 이제 «키트 색»을 돌려준다. 화면 것(Context)이 있어야 다크 판까지 맞으므로 함수로 받는다. */
fun ProcessingState.color(context: Context): Int =
    ContextCompat.getColor(context, fgRes)

package app.qichi.core.ui

import app.qichi.shared.model.AiFailReason
import app.qichi.shared.model.fromWireOrNull

/** AI 没回答时在原位置说的话，以及给不给「重试」（P16-08）。 */
data class AiFailText(val text: String, val canRetry: Boolean)

/**
 * [reason] 是服务端给的失败种类（AiFailReason 的 wireName）；旧服务端没有、或不认识的值，照旧说「没有得到回答」。
 * 额度用完、问题太长时重试也没用，不给「重试」。
 */
fun aiFailText(reason: String?): AiFailText = when (reason?.let { fromWireOrNull<AiFailReason>(it) }) {
    AiFailReason.Quota -> AiFailText("这个月的 AI 额度用完了，下个月 1 号恢复", canRetry = false)
    AiFailReason.Unreachable -> AiFailText("连不上 AI 服务，过一会儿再试", canRetry = true)
    AiFailReason.Provider -> AiFailText("AI 服务出错了，没有得到回答", canRetry = true)
    AiFailReason.TooLong -> AiFailText("问题连同带上的资料太长了，换个短一点的问法", canRetry = false)
    AiFailReason.Other, null -> AiFailText("没有得到回答", canRetry = true)
}

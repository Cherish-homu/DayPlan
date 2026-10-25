package com.jsy.dayplan.ui

import com.jsy.dayplan.vm.SendState

/** 角色状态优先级：发送中 > 失败 > 待确认草稿 > 刚刚保存 > 待机。 */
object ChanStatusLogic {
    fun from(sendState: SendState, pendingCount: Int, lastSaved: Int?): ChanStatus = when {
        sendState is SendState.Sending -> ChanStatus(ChanMood.THINKING)
        sendState is SendState.Failed -> ChanStatus(
            ChanMood.ERROR,
            message = sendState.message,
            retryable = sendState.retryable,
        )

        pendingCount > 0 -> ChanStatus(ChanMood.DRAFT_READY, count = pendingCount)
        lastSaved != null -> ChanStatus(ChanMood.SAVED, count = lastSaved)
        else -> ChanStatus()
    }
}

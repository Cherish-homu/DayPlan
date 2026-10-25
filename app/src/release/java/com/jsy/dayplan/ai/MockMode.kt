package com.jsy.dayplan.ai

/** 是否允许演示（Mock）通道：debug 变体 true，release 变体 false。 */
object MockMode {
    const val AVAILABLE = false

    /** release 不提供演示通道。 */
    fun demoTransport(): ChatTransport? = null
}

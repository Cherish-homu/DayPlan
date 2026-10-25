package com.jsy.dayplan.ai

/** debug 变体允许演示（Mock）通道；工厂方法隔离 debug 专有类。 */
object MockMode {
    const val AVAILABLE = true

    fun demoTransport(): ChatTransport? = MockDeepSeekTransport()
}

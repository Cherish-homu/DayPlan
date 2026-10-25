package com.jsy.dayplan.data

/**
 * AI 执行边界的内存视图：
 * - token 在清空对话/恢复数据时递增，用于拒绝已经跨过入口检查的旧任务；
 * - generation/sessionId 是当前进程认为有效的 AI 边界，DB 中的 ai_epoch 是权威持久事实。
 *
 * 解析规则（[resolve]）：
 * - DB 世代更高 → 以 DB 为准（恢复已在事务中推进，偏好/内存可能尚未追上）；
 * - DB 与内存同世代但会话不同 → 以内存为准（清空尚未落库，由 dirty 标记/下次提交修复）；
 * - 其余情况以内存为准；两者都没有时用调用方偏好兜底。
 */
object AiExecutionGate {
    @Volatile
    private var tokenValue: Long = 0

    @Volatile
    var generation: Int = -1
        private set

    @Volatile
    var sessionId: String = ""
        private set

    fun current(): Long = tokenValue

    fun invalidate() {
        synchronized(this) { tokenValue += 1 }
    }

    /** 更新当前内存边界；bumpToken=true 时同时作废旧任务。 */
    fun set(generation: Int, sessionId: String, bumpToken: Boolean = false) {
        synchronized(this) {
            this.generation = generation
            this.sessionId = sessionId
            if (bumpToken) tokenValue += 1
        }
    }

    fun resolve(
        dbGeneration: Int?,
        dbSessionId: String?,
        fallbackGeneration: Int,
        fallbackSessionId: String,
    ): Pair<Int, String> {
        val memoryGen = generation
        val memorySession = sessionId
        return when {
            // 首次使用/测试新数据库：没有持久边界时，偏好是当前上下文的来源
            dbGeneration == null -> fallbackGeneration to fallbackSessionId
            memoryGen < 0 -> dbGeneration to (dbSessionId?.takeIf { it.isNotBlank() } ?: fallbackSessionId)
            dbGeneration > memoryGen ->
                dbGeneration to (dbSessionId?.takeIf { it.isNotBlank() } ?: memorySession)
            dbGeneration == memoryGen && memorySession.isNotBlank() && memorySession != dbSessionId ->
                memoryGen to memorySession
            else -> memoryGen to memorySession
        }
    }
}

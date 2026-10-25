package com.jsy.dayplan.ai

import android.content.Context
import com.jsy.dayplan.ai.attach.AndroidPdfPageRenderer
import com.jsy.dayplan.ai.attach.AttachmentContextStore
import com.jsy.dayplan.ai.attach.AttachmentPlanner
import com.jsy.dayplan.ai.attach.AttachmentStore
import com.jsy.dayplan.core.AppClock
import com.jsy.dayplan.data.PlanRepository
import com.jsy.dayplan.reminder.ReminderCoordinator
import java.io.File

/**
 * AI 模块装配：所有文件位于 filesDir/ai/（不参与业务 JSON 备份；
 * Android 云备份也通过 data_extraction_rules 排除）。
 */
class AiContainer(
    context: Context,
    val repo: PlanRepository,
    val coordinator: ReminderCoordinator,
    val clock: AppClock,
    /** 生产默认 Android Keystore；单元测试注入内存实现。 */
    keyCipher: KeyCipher = AndroidKeyStoreCipher(),
) {
    val settings = AiSettingsStore(context)
    val keyStore = SecureKeyStore.default(context, keyCipher)

    @Volatile
    var transport: ChatTransport = HttpTransport()
        private set

    val client: DeepSeekClient = DeepSeekClient(
        apiKeyProvider = { keyStore.loadApiKey() },
        transport = object : ChatTransport {
            override suspend fun call(request: TransportRequest): TransportResponse =
                transport.call(request)
        },
    )

    private val baseDir = File(context.filesDir, "ai")
    val chatStore = ChatStore(File(baseDir, "chat_history.json"))
    val batchStore = BatchStore(File(baseDir, "batches"))
    val attachmentStore = AttachmentStore(File(baseDir, "attachments"))
    val contextStore = AttachmentContextStore(File(baseDir, "context"))
    val planner = AttachmentPlanner(
        pdfRenderer = AndroidPdfPageRenderer(),
        previewDir = File(baseDir, "attachments"),
    )
    val executor = PlanDraftExecutor(
        repo = repo,
        coordinator = coordinator,
        settings = settings,
        store = batchStore,
    )
    val controller = ChatController(
        client = client,
        settings = settings,
        keyStore = keyStore,
        clock = clock,
        repo = repo,
        executor = executor,
        batchStore = batchStore,
        history = chatStore,
        planner = planner,
        contextStore = contextStore,
    )

    /** debug 变体的演示通道替换点（release 不可达）。 */
    fun overrideTransportForDebug(t: ChatTransport): Boolean {
        if (!com.jsy.dayplan.ai.MockMode.AVAILABLE) return false
        transport = t
        return true
    }

    /** 测试注入：只用于单元测试捕获请求/模拟错误，不暴露给界面。 */
    @androidx.annotation.VisibleForTesting
    internal fun setTransportForTest(t: ChatTransport) {
        transport = t
    }

    /** 启动时清理：过期批次、过期材料上下文与未被引用的附件缓存（默认保留 7 天）。 */
    suspend fun pruneOnStart(nowMs: Long = System.currentTimeMillis()) {
        // DB ai_epoch 是权威事实；偏好只是缓存。
        // 1) 有未落库的本地边界（清空会话）且 DB 没有更高世代 → 用本地值修复 DB；
        // 2) 否则采用 DB 的世代/会话，绝不用旧偏好覆盖已提交的新边界。
        val epoch = repo.currentAiEpoch()
        when {
            settings.boundaryDirty -> {
                if (epoch != null && epoch.dataGeneration > settings.dataGeneration) {
                    settings.markBoundaryPersisted(epoch.dataGeneration, epoch.sessionId)
                    com.jsy.dayplan.data.AiExecutionGate.set(
                        epoch.dataGeneration, epoch.sessionId, bumpToken = false,
                    )
                } else {
                    val gen = settings.dataGeneration
                    val session = settings.chatSessionId
                    repo.setAiEpoch(gen, session)
                    settings.markBoundaryPersisted(gen, session)
                    com.jsy.dayplan.data.AiExecutionGate.set(gen, session, bumpToken = false)
                }
            }

            epoch != null -> {
                if (settings.dataGeneration != epoch.dataGeneration ||
                    settings.chatSessionId != epoch.sessionId
                ) {
                    settings.markBoundaryPersisted(epoch.dataGeneration, epoch.sessionId)
                }
                com.jsy.dayplan.data.AiExecutionGate.set(
                    epoch.dataGeneration, epoch.sessionId, bumpToken = false,
                )
            }

            else -> {
                val gen = settings.dataGeneration
                val session = settings.chatSessionId
                repo.setAiEpoch(gen, session)
                settings.markBoundaryPersisted(gen, session)
                com.jsy.dayplan.data.AiExecutionGate.set(gen, session, bumpToken = false)
            }
        }
        repo.pruneAiCommits(nowMs - 180L * 24 * 3600 * 1000)
        batchStore.prune(nowMs = nowMs)
        contextStore.prune(
            nowMs = nowMs,
            keepMillis = 7L * 24 * 3600 * 1000,
            currentSessionId = settings.chatSessionId,
        )
        val referenced = batchStore.all()
            .filter { it.isPending }
            .flatMapTo(mutableSetOf()) { it.attachmentIds } + contextStore.referencedIds()
        attachmentStore.prune(keepMillis = 7L * 24 * 3600 * 1000, nowMs = nowMs, referenced = referenced)
    }
}

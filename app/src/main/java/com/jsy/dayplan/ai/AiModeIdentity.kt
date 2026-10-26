package com.jsy.dayplan.ai

/** 模式属于应用设置；不能由模型按本轮回复类型猜测或自行切换。 */
object AiModeIdentity {
    private val modeQuestions = setOf(
        "现在是什么模式", "当前是什么模式", "目前是什么模式",
        "你是什么模式", "你现在是什么模式", "你当前是什么模式", "你目前是什么模式",
        "你现在是助手模式还是聊天模式", "现在是助手模式还是聊天模式",
        "你现在是日程助手还是普通聊天", "现在是日程助手还是普通聊天",
        "你现在是日程助手模式还是普通聊天模式", "现在是日程助手模式还是普通聊天模式",
        "当前是日程助手还是普通聊天", "当前模式是什么",
    )

    fun isModeQuestion(text: String): Boolean {
        val normalized = text.trim().removePrefix("请问")
            .replace(Regex("[\\s，。！？?!：:、；;“”\"‘’]"), "")
        return normalized in modeQuestions
    }

    fun reply(config: AiConfig): String = if (config.chatMode == AiConfig.MODE_PLAIN) {
        "我是日序 AI 助手，直接聊天就好。我不会读取或修改你的日程，也不会生成可保存的草稿。" +
            "需要安排日程、待办或倒数日时，可以在首页手动新增。"
    } else {
        "当前是“日程助手”模式，可以整理日程、待办和倒数日，也可以闲聊；闲聊时仍然是日程助手。" +
            (if (config.autoSave) "符合条件的明确新增会按你的设置自动保存，其他安排先生成待确认草稿。"
             else "安排会先生成草稿，由你确认后保存。") +
            "只有点击上方“普通聊天”才会切换模式。"
    }
}

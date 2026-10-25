package com.jsy.dayplan.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 角色素材署名、许可与本次修改说明；入口与设置页共用。 */
@Composable
fun ChanAssetLicenseDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("DeepSeek 娘素材许可与修改说明") },
        text = {
            Column(
                Modifier
                    .heightIn(max = 480.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(
                    "素材：codex-deepseek-pet 的 assets/whale 成品立绘" +
                        "（portrait.png / idle-front.png）。\n" +
                        "作者：YunYueSama\n" +
                        "仓库：https://github.com/YunYueSama/codex-deepseek-pet\n" +
                        "commit：7661c8b304c5400701f91da01b1a643a207331de\n" +
                        "许可：大肥鱼项目署名许可 1.0（非 MIT）。\n\n" +
                        "本次修改说明：\n" +
                        "· 从 idle-front.png 左右两帧切出睁眼/闭眼全身帧；\n" +
                        "· 按两帧共同内容包围盒 (197,132)-(796,952) 裁切、缩放，" +
                        "保持脚底锚点一致；\n" +
                        "· 从闭眼帧同区域导出闭眼头像；\n" +
                        "· 适配呼吸、眨眼、出现、收起/展开与状态反馈动效。\n\n" +
                        "本应用仅使用上述成品立绘，未搬运参考图、字体、Electron 程序或桌宠插件代码；" +
                        "图片打包为本地资源，显示时不联网。该形象为社区二创，非 DeepSeek 官方产品形象；" +
                        "个人自用，不据此推断底层第三方设计均获商用授权。\n\n" +
                        "完整许可文本（大肥鱼项目署名许可 1.0）：\n" +
                        "Copyright (c) 2026 YunYueSama\n" +
                        "作者：YunYueSama\n" +
                        "仓库：https://github.com/YunYueSama/codex-deepseek-pet\n\n" +
                        "1. 适用范围\n" +
                        "本许可适用于本仓库中由项目作者拥有或有权授予许可的所有材料，包括源代码、文档、角色设定、图片、设计图、动画与其他资源（以下称“材料”）。" +
                        "第三方材料及另有许可声明的部分不在本授权范围内，详见 ASSET_LICENSE.md。\n\n" +
                        "2. 授予的权利\n" +
                        "在遵守以下条件的前提下，任何人均可免费使用、复制、修改、合并、展示、发布、分发、再许可及出售材料或其衍生作品，" +
                        "包括用于商业目的，无需另行向项目作者申请许可。修改后的作品无需公开源代码。\n\n" +
                        "3. 必须保留的署名\n" +
                        "使用、展示、发布或分发材料或其衍生作品时，必须清楚标明以下两项：\n" +
                        "作者：YunYueSama\n" +
                        "仓库：https://github.com/YunYueSama/codex-deepseek-pet\n" +
                        "署名可以放在作品的 README、关于页面、致谢、随附说明或图片/视频发布说明等使用者或接收者容易找到的位置；" +
                        "无需在角色画面上添加水印。私人使用时，保留随附的署名与许可文件即可。" +
                        "署名不得仅写“来源网络”或只列作者而省略仓库地址。\n" +
                        "分发材料或其实质性部分时，须同时保留本版权声明及完整许可文本。" +
                        "修改后的作品须说明经过修改，不得将修改内容冒称为原作者作品。" +
                        "再许可不得取消原材料的上述署名和许可保留义务；不得暗示原作者或 DeepSeek 官方认可或代言衍生作品。\n\n" +
                        "4. 第三方权利与旧版本\n" +
                        "本许可不授予第三方参考作品、字体、依赖、商标或其他第三方内容的额外权利。此类内容仍遵守各自的授权；" +
                        "对衍生素材的授权仅及于作者有权授权的贡献，不替代底层参考作品可能需要的许可。使用 AI 生成或处理素材不扩大授权范围。\n" +
                        "此前已按 MIT 或其他许可发布的版本，其已授予的权利不因本许可变更而撤销；请以取得的相应版本所附许可为准。\n\n" +
                        "5. 免责声明\n" +
                        "材料按“现状”提供，不作任何明示或默示保证，包括适销性、特定用途适用性或不侵权的保证。" +
                        "在适用法律允许的范围内，作者或版权持有人不对因使用材料或与材料有关的行为所产生的任何索赔、损害或其他责任负责。",
                    fontSize = 11.sp,
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("知道了") } },
    )
}

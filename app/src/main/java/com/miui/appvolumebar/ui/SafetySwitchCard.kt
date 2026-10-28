package com.miui.appvolumebar.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.text.BasicText
import androidx.compose.ui.text.TextStyle

/**
 * 安全模式总开关。
 *
 * 这是最后一道保险：打开后三个系统进程（systemui / plugin / misound）启动时
 * 会读到 master_switch=off，直接 return，不做任何注入。
 *
 * 比卸载模块轻得多——不用重启、不用重装，点一下再重启 SystemUI 就恢复原状。
 */
@Composable
fun SafetySwitchCard(
    safeModeOn: Boolean,
    onToggle: () -> Unit,
) {
    val bg = if (safeModeOn) Color(0x33FF5252) else Color(0x2219A974)
    val titleColor = if (safeModeOn) Color(0xFFD32F2F) else Color(0xFF12805C)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(bg)
            .clickable { onToggle() }
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            BasicText(
                text = if (safeModeOn) "⚠ 安全模式：已禁用注入" else "✓ 模块正常运行",
                style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Medium, color = titleColor)
            )
            BasicText(
                text = if (safeModeOn) {
                    "点此恢复。当前三个系统进程都不会做任何修改"
                } else {
                    "点此禁用全部注入（出问题时第一时间点这里）"
                },
                style = TextStyle(fontSize = 12.sp, color = Color(0xFF888888))
            )
        }
        BasicText(
            text = if (safeModeOn) "恢复" else "禁用",
            style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Medium, color = titleColor)
        )
    }
}

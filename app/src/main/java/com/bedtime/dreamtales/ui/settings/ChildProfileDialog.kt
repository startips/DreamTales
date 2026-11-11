package com.bedtime.dreamtales.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.FilledTonalButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bedtime.dreamtales.core.StoryPersonalization
import com.bedtime.dreamtales.core.model.ChildProfile

/**
 * 宝宝专属主角档案编辑弹窗。
 */
@Composable
fun ChildProfileDialog(
    profile: ChildProfile,
    onSave: (ChildProfile) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember(profile) { mutableStateOf(profile.name) }
    var age by remember(profile) { mutableStateOf(profile.ageLabel) }
    var interests by remember(profile) { mutableStateOf(profile.interests) }
    var todayNote by remember(profile) { mutableStateOf(profile.todayNote) }
    var enabled by remember(profile) { mutableStateOf(profile.isEnabled) }

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = MaterialTheme.shapes.extraLarge,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(text = "👶", fontSize = 22.sp)
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "宝宝专属主角",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold
                )
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "填写后，AI 会把宝宝写成童话主角。例如：诺诺在森林里遇见一只迷路的小恐龙。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 18.sp
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "启用宝宝主角",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "关闭后仍可继续生成普通童话",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = enabled,
                        onCheckedChange = { enabled = it },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                            checkedTrackColor = MaterialTheme.colorScheme.primary
                        )
                    )
                }

                ProfileField(
                    value = name,
                    onValueChange = { name = it },
                    label = "宝宝昵称（必填）",
                    placeholder = "例如：诺诺"
                )
                ProfileField(
                    value = age,
                    onValueChange = { age = it },
                    label = "年龄",
                    placeholder = "例如：4岁 / 3-6岁"
                )
                ProfileField(
                    value = interests,
                    onValueChange = { interests = it },
                    label = "喜欢的事物",
                    placeholder = "例如：小恐龙、积木、画画"
                )
                ProfileField(
                    value = todayNote,
                    onValueChange = { todayNote = it },
                    label = "今天的小事件（可选）",
                    placeholder = "例如：今天在幼儿园学会了分享"
                )

                if (profile.name.isNotBlank() || profile.interests.isNotBlank() || profile.isEnabled) {
                    Spacer(modifier = Modifier.height(2.dp))
                    FilledTonalButton(
                        onClick = { onSave(ChildProfile()) },
                        shape = CircleShape,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = "清除已保存的宝宝档案",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val sanitized = StoryPersonalization.sanitize(
                        ChildProfile(
                            name = name,
                            ageLabel = age,
                            interests = interests,
                            todayNote = todayNote,
                            isEnabled = enabled
                        )
                    )
                    onSave(
                        sanitized.copy(
                            isEnabled = enabled && sanitized.name.isNotBlank()
                        )
                    )
                },
                enabled = name.isNotBlank(),
                shape = CircleShape,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                )
            ) {
                Text("保存档案", style = MaterialTheme.typography.labelLarge)
            }
        },
        dismissButton = {
            FilledTonalButton(
                onClick = onDismiss,
                shape = CircleShape,
            ) {
                Text("取消", style = MaterialTheme.typography.labelLarge)
            }
        }
    )
}

@Composable
private fun ProfileField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    placeholder: String
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = {
            Text(label, style = MaterialTheme.typography.labelMedium)
        },
        placeholder = {
            Text(
                placeholder,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
            )
        },
        singleLine = true,
        shape = MaterialTheme.shapes.medium,
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = MaterialTheme.colorScheme.onSurface,
            unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
            focusedBorderColor = Color.Transparent,
            unfocusedBorderColor = Color.Transparent,
            disabledBorderColor = Color.Transparent,
            errorBorderColor = Color.Transparent
        ),
        modifier = Modifier.fillMaxWidth()
    )
}

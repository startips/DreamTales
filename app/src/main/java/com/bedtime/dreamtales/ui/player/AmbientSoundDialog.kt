package com.bedtime.dreamtales.ui.player

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.SuggestionChipDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bedtime.dreamtales.core.audio.AmbientMix
import com.bedtime.dreamtales.core.audio.AmbientPreset

/**
 * 多轨白噪音自由混音台。
 * 支持最多 5 轨同时播放、独立音量与自定义配方保存。
 */
@Composable
fun AmbientSoundDialog(
    currentMix: Map<String, Float>,
    userPresets: List<AmbientPreset>,
    onMixChanged: (Map<String, Float>) -> Unit,
    onSavePreset: (String, Map<String, Float>) -> Boolean,
    onDismiss: () -> Unit
) {
    var presetName by remember { mutableStateOf("") }
    var savedMessage by remember { mutableStateOf<String?>(null) }

    val allPresets = remember(userPresets) {
        AmbientMix.defaultPresets() + userPresets
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = MaterialTheme.shapes.extraLarge,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(text = "🌿", fontSize = 22.sp)
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "助眠多轨混音台",
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
                    text = "自由叠加大自然声音，例如 40% 森林细雨 + 20% 夏夜虫鸣 + 15% 摇篮音盒。建议总音量低于人声。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 18.sp
                )

                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    item {
                        SuggestionChip(
                            onClick = {
                                onMixChanged(emptyMap())
                                savedMessage = null
                            },
                            label = { Text("🚫 关闭", style = MaterialTheme.typography.labelSmall) },
                            shape = CircleShape,
                            colors = SuggestionChipDefaults.suggestionChipColors(
                                containerColor = MaterialTheme.colorScheme.surfaceContainer,
                                labelColor = MaterialTheme.colorScheme.onSurface
                            )
                        )
                    }
                    items(allPresets, key = { it.name }) { preset ->
                        SuggestionChip(
                            onClick = {
                                onMixChanged(preset.mix)
                                savedMessage = "已应用「${preset.name}」"
                            },
                            label = { Text("🎵 ${preset.name}", style = MaterialTheme.typography.labelSmall) },
                            shape = CircleShape,
                            colors = SuggestionChipDefaults.suggestionChipColors(
                                containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f),
                                labelColor = MaterialTheme.colorScheme.primary
                            )
                        )
                    }
                }

                AmbientMix.TRACKS.forEach { track ->
                    val volume = currentMix[track.id] ?: 0f
                    val enabled = volume >= AmbientMix.MIN_AUDIBLE_VOLUME
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.medium,
                        colors = CardDefaults.cardColors(
                            containerColor = if (enabled) {
                                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
                            } else {
                                MaterialTheme.colorScheme.surfaceContainer
                            }
                        ),
                        border = BorderStroke(
                            1.dp,
                            if (enabled) MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                            else MaterialTheme.colorScheme.outlineVariant
                        )
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 10.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(text = track.emoji, fontSize = 18.sp)
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = track.title,
                                        style = MaterialTheme.typography.titleSmall,
                                        color = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                        fontWeight = if (enabled) FontWeight.Bold else FontWeight.Medium
                                    )
                                }
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = if (enabled) "${(volume * 100).toInt()}%" else "关闭",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Switch(
                                        checked = enabled,
                                        onCheckedChange = { checked ->
                                            val updated = currentMix.toMutableMap()
                                            if (checked) {
                                                updated[track.id] = 0.25f
                                            } else {
                                                updated.remove(track.id)
                                            }
                                            onMixChanged(updated)
                                            savedMessage = null
                                        },
                                        colors = SwitchDefaults.colors(
                                            checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                                            checkedTrackColor = MaterialTheme.colorScheme.primary
                                        )
                                    )
                                }
                            }

                            if (enabled) {
                                Slider(
                                    value = volume.coerceIn(0.05f, 1f),
                                    onValueChange = { value ->
                                        val updated = currentMix.toMutableMap()
                                        updated[track.id] = value
                                        onMixChanged(updated)
                                        savedMessage = null
                                    },
                                    valueRange = 0.05f..1f,
                                    colors = SliderDefaults.colors(
                                        thumbColor = MaterialTheme.colorScheme.primary,
                                        activeTrackColor = MaterialTheme.colorScheme.primary,
                                        inactiveTrackColor = MaterialTheme.colorScheme.surfaceContainerHighest
                                    ),
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }
                    }
                }

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.medium,
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                    ),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp)
                    ) {
                        Text(
                            text = "保存我的配方",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedTextField(
                                value = presetName,
                                onValueChange = {
                                    presetName = it
                                    savedMessage = null
                                },
                                placeholder = {
                                    Text(
                                        "例如：雨夜森林",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                                    )
                                },
                                singleLine = true,
                                shape = CircleShape,
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedTextColor = MaterialTheme.colorScheme.onSurface,
                                    unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
                                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
                                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                                    unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant
                                ),
                                modifier = Modifier.weight(1f)
                            )
                            Button(
                                onClick = {
                                    val success = onSavePreset(presetName, currentMix)
                                    savedMessage = if (success) {
                                        presetName = ""
                                        "配方已保存到上方快捷区"
                                    } else {
                                        "请先开启至少一轨并填写配方名称"
                                    }
                                },
                                enabled = currentMix.isNotEmpty() && presetName.isNotBlank(),
                                shape = CircleShape,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.primary,
                                    contentColor = MaterialTheme.colorScheme.onPrimary
                                )
                            ) {
                                Text("保存", style = MaterialTheme.typography.labelMedium)
                            }
                        }
                        savedMessage?.let { message ->
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = message,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onDismiss,
                shape = CircleShape,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                )
            ) {
                Text("完成", style = MaterialTheme.typography.labelLarge)
            }
        }
    )
}

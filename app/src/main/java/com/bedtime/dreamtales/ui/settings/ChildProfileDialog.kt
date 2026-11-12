package com.bedtime.dreamtales.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bedtime.dreamtales.core.StoryPersonalization
import com.bedtime.dreamtales.core.model.ChildProfile
import com.bedtime.dreamtales.core.model.ChildProfilePolicy

/**
 * 多宝宝专属主角档案管理弹窗（支持最多 5 个宝宝、切换今晚主角、编辑与删除）。
 */
@Composable
fun ChildProfileDialog(
    profiles: List<ChildProfile>,
    onSelectProfile: (Long?) -> Unit,
    onSaveProfile: (ChildProfile, Boolean) -> Unit,
    onDeleteProfile: (Long) -> Unit,
    onDismiss: () -> Unit
) {
    var editingProfile by remember {
        mutableStateOf(
            if (profiles.isEmpty()) ChildProfile(isEnabled = true) else null
        )
    }
    var pendingDelete by remember { mutableStateOf<ChildProfile?>(null) }

    val activeEdit = editingProfile
    if (activeEdit == null) {
        val noneSelected = profiles.none { it.isEnabled }
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
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = "点选一位宝宝作为「今晚主角」，AI 会把 Ta 写进新生成的睡前童话（最多 ${ChildProfilePolicy.MAX_PROFILES} 个档案）。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        lineHeight = 18.sp
                    )

                    // 暂不启用选项（保留已有档案，仅暂时不注入提示词）
                    Card(
                        onClick = { onSelectProfile(null) },
                        shape = MaterialTheme.shapes.medium,
                        colors = CardDefaults.cardColors(
                            containerColor = if (noneSelected) {
                                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                            } else {
                                MaterialTheme.colorScheme.surfaceContainer
                            }
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(text = "🌙", fontSize = 18.sp)
                            Spacer(modifier = Modifier.width(10.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "暂不启用宝宝主角",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = if (noneSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                    fontWeight = if (noneSelected) FontWeight.Bold else FontWeight.Medium
                                )
                                Text(
                                    text = "生成通用风格童话，不代入特定宝宝",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            if (noneSelected) {
                                Icon(
                                    imageVector = Icons.Default.Check,
                                    contentDescription = "已选中",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }

                    profiles.forEach { profile ->
                        val isSelected = profile.isEnabled
                        Card(
                            onClick = { onSelectProfile(profile.id) },
                            shape = MaterialTheme.shapes.medium,
                            colors = CardDefaults.cardColors(
                                containerColor = if (isSelected) {
                                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
                                } else {
                                    MaterialTheme.colorScheme.surfaceContainer
                                }
                            ),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(start = 14.dp, end = 6.dp, top = 10.dp, bottom = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(38.dp)
                                        .clip(CircleShape)
                                        .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(text = profile.displayAvatar, fontSize = 20.sp)
                                }
                                Spacer(modifier = Modifier.width(10.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            text = profile.displayName,
                                            style = MaterialTheme.typography.titleSmall,
                                            color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                            fontWeight = FontWeight.Bold,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        if (profile.ageLabel.isNotBlank()) {
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Surface(
                                                shape = CircleShape,
                                                color = MaterialTheme.colorScheme.surfaceContainerHighest
                                            ) {
                                                Text(
                                                    text = profile.ageLabel,
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                                )
                                            }
                                        }
                                    }
                                    val detailText = listOfNotNull(
                                        profile.interests.takeIf { it.isNotBlank() }?.let { "喜欢：$it" },
                                        profile.todayNote.takeIf { it.isNotBlank() }
                                    ).joinToString(" · ")
                                    Text(
                                        text = detailText.ifBlank {
                                            if (isSelected) "✨ 今晚主角" else "轻触设为今晚主角"
                                        },
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                IconButton(
                                    onClick = { editingProfile = profile },
                                    modifier = Modifier.size(34.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Edit,
                                        contentDescription = "编辑档案",
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                                IconButton(
                                    onClick = { pendingDelete = profile },
                                    modifier = Modifier.size(34.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.DeleteOutline,
                                        contentDescription = "删除档案",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        }
                    }

                    if (ChildProfilePolicy.canAddMore(profiles.size)) {
                        FilledTonalButton(
                            onClick = { editingProfile = ChildProfile(isEnabled = true) },
                            shape = CircleShape,
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(
                                imageVector = Icons.Default.Add,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("添加宝宝档案", style = MaterialTheme.typography.labelLarge)
                        }
                    } else {
                        Text(
                            text = "已达 ${ChildProfilePolicy.MAX_PROFILES} 个宝宝档案上限",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
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
    } else {
        ChildProfileEditorDialog(
            initialProfile = activeEdit,
            canGoBackToList = profiles.isNotEmpty(),
            onSave = { saved, makeSelected ->
                onSaveProfile(saved, makeSelected)
                editingProfile = null
            },
            onCancel = {
                if (profiles.isNotEmpty()) {
                    editingProfile = null
                } else {
                    onDismiss()
                }
            }
        )
    }

    pendingDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            shape = MaterialTheme.shapes.extraLarge,
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            title = {
                Text(
                    text = "删除「${target.displayName}」的档案？",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Text(
                    text = "删除后不再以该宝宝为主角生成故事，但已有故事与全部陪伴总时长会完好保留。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        val id = target.id
                        pendingDelete = null
                        onDeleteProfile(id)
                    },
                    shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError
                    )
                ) {
                    Text("确认删除", style = MaterialTheme.typography.labelLarge)
                }
            },
            dismissButton = {
                FilledTonalButton(
                    onClick = { pendingDelete = null },
                    shape = CircleShape
                ) {
                    Text("取消", style = MaterialTheme.typography.labelLarge)
                }
            }
        )
    }
}

@Composable
private fun ChildProfileEditorDialog(
    initialProfile: ChildProfile,
    canGoBackToList: Boolean,
    onSave: (ChildProfile, Boolean) -> Unit,
    onCancel: () -> Unit
) {
    var avatarEmoji by remember(initialProfile) { mutableStateOf(initialProfile.displayAvatar) }
    var name by remember(initialProfile) { mutableStateOf(initialProfile.name) }
    var age by remember(initialProfile) { mutableStateOf(initialProfile.ageLabel) }
    var interests by remember(initialProfile) { mutableStateOf(initialProfile.interests) }
    var todayNote by remember(initialProfile) { mutableStateOf(initialProfile.todayNote) }
    var setAsTonightHero by remember(initialProfile) {
        mutableStateOf(if (initialProfile.id == 0L) true else initialProfile.isEnabled)
    }

    AlertDialog(
        onDismissRequest = onCancel,
        shape = MaterialTheme.shapes.extraLarge,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(text = avatarEmoji, fontSize = 22.sp)
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = if (initialProfile.id == 0L) "添加宝宝档案" else "编辑宝宝档案",
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

                // 头像 Emoji 选择栏
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    ChildProfilePolicy.AVATAR_OPTIONS.forEach { emoji ->
                        val selected = (emoji == avatarEmoji)
                        Surface(
                            shape = CircleShape,
                            color = if (selected) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.surfaceContainer
                            },
                            modifier = Modifier
                                .size(38.dp)
                                .clip(CircleShape)
                                .clickable { avatarEmoji = emoji }
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(text = emoji, fontSize = 18.sp)
                            }
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "设为今晚主角",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "生成新故事和陪伴统计将关联到该宝宝",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = setAsTonightHero,
                        onCheckedChange = { setAsTonightHero = it },
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
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val sanitized = StoryPersonalization.sanitize(
                        ChildProfile(
                            id = initialProfile.id,
                            name = name,
                            avatarEmoji = avatarEmoji,
                            ageLabel = age,
                            interests = interests,
                            todayNote = todayNote,
                            isEnabled = setAsTonightHero
                        )
                    )
                    val active = setAsTonightHero && sanitized.name.isNotBlank()
                    onSave(sanitized.copy(isEnabled = active), active)
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
                onClick = onCancel,
                shape = CircleShape
            ) {
                Text(
                    if (canGoBackToList) "返回" else "取消",
                    style = MaterialTheme.typography.labelLarge
                )
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

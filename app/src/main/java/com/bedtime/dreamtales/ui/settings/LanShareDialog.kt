package com.bedtime.dreamtales.ui.settings

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.bedtime.dreamtales.data.share.LanShareDevice
import com.bedtime.dreamtales.data.share.LanShareManager
import com.bedtime.dreamtales.widget.DreamTalesWidgetProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 家庭局域网互传：同一 Wi-Fi 下分享故事书单、封面与家长音色样本。
 */
@Composable
fun LanShareDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val manager = remember { LanShareManager(context) }

    var mode by remember { mutableIntStateOf(0) } // 0 发送 / 1 接收
    var serving by remember { mutableStateOf(false) }
    var serverPort by remember { mutableStateOf(0) }
    var scanning by remember { mutableStateOf(false) }
    var devices by remember { mutableStateOf<List<LanShareDevice>>(emptyList()) }
    var importingDevice by remember { mutableStateOf<LanShareDevice?>(null) }
    var progress by remember { mutableIntStateOf(0) }
    var message by remember { mutableStateOf<String?>(null) }

    DisposableEffect(Unit) {
        onDispose { manager.release() }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                manager.stopServing()
                manager.stopDiscovery()
                serving = false
                scanning = false
                serverPort = 0
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = MaterialTheme.shapes.extraLarge,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(text = "📡", fontSize = 22.sp)
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "家庭局域网互传",
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
                    .heightIn(max = 440.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "两台设备连接同一 Wi-Fi，即可互传故事文本、封面和音色样本。全程仅在家庭局域网内传输，不经过云端。请只在可信的家庭 Wi-Fi 下使用。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 18.sp
                )

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = mode == 0,
                        onClick = { mode = 0 },
                        label = { Text("📤 发送分享", style = MaterialTheme.typography.labelSmall) },
                        shape = CircleShape,
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primary,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimary
                        )
                    )
                    FilterChip(
                        selected = mode == 1,
                        onClick = { mode = 1 },
                        label = { Text("📥 接收导入", style = MaterialTheme.typography.labelSmall) },
                        shape = CircleShape,
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primary,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimary
                        )
                    )
                }

                if (mode == 0) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.medium,
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                        ),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Text(
                                text = if (serving) "🟢 正在分享" else "⚪ 尚未开启分享",
                                style = MaterialTheme.typography.titleSmall,
                                color = if (serving) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                fontWeight = FontWeight.SemiBold
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = if (serving) {
                                    "已通过 mDNS 广播，端口 $serverPort。请让另一台设备打开 DreamTales → 设置 → 家庭互传 → 接收导入。"
                                } else {
                                    "开启后，另一台设备可在接收列表中看到本机。分享包不包含任何 API Key 或账号信息。"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(10.dp))
                            Button(
                                onClick = {
                                    message = null
                                    if (serving) {
                                        manager.stopServing()
                                        serving = false
                                        serverPort = 0
                                    } else {
                                        if (!hasLocalLan(context)) {
                                            message = "当前未连接 Wi-Fi，请先连接家庭 Wi-Fi 后再开启分享"
                                        } else {
                                            scope.launch {
                                                try {
                                                    val info = withContext(Dispatchers.IO) {
                                                        manager.startServing()
                                                    }
                                                    serverPort = info.port
                                                    serving = true
                                                } catch (e: Exception) {
                                                    message = "开启分享失败：${e.message}"
                                                }
                                            }
                                        }
                                    }
                                },
                                shape = CircleShape,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.primary,
                                    contentColor = MaterialTheme.colorScheme.onPrimary
                                )
                            ) {
                                Text(
                                    text = if (serving) "停止分享" else "开启局域网分享",
                                    style = MaterialTheme.typography.labelLarge
                                )
                            }
                        }
                    }
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Button(
                            onClick = {
                                message = null
                                if (scanning) {
                                    manager.stopDiscovery()
                                    scanning = false
                                } else if (!hasLocalLan(context)) {
                                    message = "当前未连接 Wi-Fi，请先连接家庭 Wi-Fi 后再扫描"
                                } else {
                                    devices = emptyList()
                                    scanning = true
                                    manager.startDiscovery(
                                        onDeviceFound = { device ->
                                            scope.launch {
                                                if (devices.none { it.name == device.name && it.host == device.host && it.port == device.port }) {
                                                    devices = devices + device
                                                }
                                            }
                                        },
                                        onError = { error ->
                                            scope.launch {
                                                message = error
                                                scanning = false
                                            }
                                        }
                                    )
                                }
                            },
                            shape = CircleShape,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary,
                                contentColor = MaterialTheme.colorScheme.onPrimary
                            )
                        ) {
                            if (scanning) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(14.dp),
                                    color = MaterialTheme.colorScheme.onPrimary,
                                    strokeWidth = 2.dp
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("停止扫描", style = MaterialTheme.typography.labelLarge)
                            } else {
                                Text("扫描附近设备", style = MaterialTheme.typography.labelLarge)
                            }
                        }
                        if (scanning) {
                            Text(
                                text = "正在发现同 Wi-Fi 设备…",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    if (devices.isEmpty() && !scanning) {
                        Text(
                            text = "未发现设备。请确认对方已在本页开启「局域网分享」，且两台设备连接同一个 Wi-Fi。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    devices.forEach { device ->
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = MaterialTheme.shapes.medium,
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                            ),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 14.dp, vertical = 10.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "📱 ${device.displayName}",
                                        style = MaterialTheme.typography.titleSmall,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        text = "${device.host}:${device.port}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Spacer(modifier = Modifier.width(8.dp))
                                OutlinedButton(
                                    onClick = {
                                        importingDevice = device
                                        progress = 0
                                        message = null
                                        scope.launch {
                                            try {
                                                val result = manager.downloadAndImport(device) { value ->
                                                    scope.launch { progress = value }
                                                }
                                                message = "接收完成：故事 +${result.storiesImported}（跳过 ${result.storiesSkipped}），音色 +${result.voicesImported}（跳过 ${result.voicesSkipped}）"
                                                DreamTalesWidgetProvider.refresh(context)
                                            } catch (e: Exception) {
                                                message = "接收失败：${e.message}"
                                            } finally {
                                                importingDevice = null
                                                progress = 0
                                            }
                                        }
                                    },
                                    shape = CircleShape,
                                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                                    enabled = importingDevice == null
                                ) {
                                    Text("接收", style = MaterialTheme.typography.labelMedium)
                                }
                            }
                        }
                    }

                    if (importingDevice != null) {
                        LinearProgressIndicator(
                            progress = { progress / 100f },
                            modifier = Modifier.fillMaxWidth(),
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = "正在接收 ${importingDevice?.displayName} 的数据… $progress%",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }

                message?.let { text ->
                    Text(
                        text = text,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        lineHeight = 18.sp
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
}

/**
 * 家庭互传依赖局域网：Wi-Fi / 有线网络可用时才允许扫描或广播。
 * 蜂窝网络下 mDNS 无法被同一家庭网络中的设备发现。
 */
private fun hasLocalLan(context: Context): Boolean {
    return try {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = manager.activeNetwork ?: return false
        val capabilities = manager.getNetworkCapabilities(network) ?: return false
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    } catch (e: Exception) {
        false
    }
}

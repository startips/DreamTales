package com.bedtime.dreamtales.core.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

enum class BatchDownloadPermission {
    ALLOWED,
    OFFLINE,
    CELLULAR_BLOCKED
}

/**
 * 批量离线下载网络策略检查（不新增权限，复用 ACCESS_NETWORK_STATE）。
 */
object NetworkDownloadPolicy {

    fun evaluate(
        wifiOnly: Boolean,
        isConnected: Boolean,
        isUnmetered: Boolean
    ): BatchDownloadPermission {
        if (!isConnected) return BatchDownloadPermission.OFFLINE
        if (wifiOnly && !isUnmetered) return BatchDownloadPermission.CELLULAR_BLOCKED
        return BatchDownloadPermission.ALLOWED
    }

    fun checkCurrentNetwork(context: Context, wifiOnly: Boolean): BatchDownloadPermission {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return BatchDownloadPermission.OFFLINE
        val network = cm.activeNetwork ?: return BatchDownloadPermission.OFFLINE
        val caps = cm.getNetworkCapabilities(network) ?: return BatchDownloadPermission.OFFLINE
        val isConnected = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        val isUnmetered = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) ||
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
        return evaluate(
            wifiOnly = wifiOnly,
            isConnected = isConnected,
            isUnmetered = isUnmetered
        )
    }
}

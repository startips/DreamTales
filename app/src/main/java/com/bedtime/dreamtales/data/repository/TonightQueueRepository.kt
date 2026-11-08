package com.bedtime.dreamtales.data.repository

import android.content.Context
import com.bedtime.dreamtales.data.local.AppDatabase
import com.bedtime.dreamtales.data.local.entity.QueueItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

/**
 * 「今晚故事单」仓库。当前版本只维护一个队列。
 */
class TonightQueueRepository(context: Context) {

    private val dao = AppDatabase.getInstance(context).queueDao()

    fun getQueueFlow(): Flow<List<QueueItem>> = dao.getQueueFlow()

    suspend fun getQueueSync(): List<QueueItem> = withContext(Dispatchers.IO) {
        dao.getQueueSync()
    }

    suspend fun getStoryIdsSync(): List<Long> = withContext(Dispatchers.IO) {
        dao.getStoryIdsSync()
    }

    suspend fun add(storyId: Long) = withContext(Dispatchers.IO) {
        if (storyId <= 0L) return@withContext
        val existing = dao.getQueueSync().any { it.storyId == storyId }
        if (!existing) {
            dao.insert(QueueItem(storyId = storyId, position = dao.getMaxPosition() + 1))
        }
    }

    suspend fun remove(storyId: Long) = withContext(Dispatchers.IO) {
        dao.deleteByStoryId(storyId)
    }

    suspend fun clear() = withContext(Dispatchers.IO) {
        dao.clear()
    }

    suspend fun move(storyId: Long, direction: Int) = withContext(Dispatchers.IO) {
        val ids = dao.getStoryIdsSync().toMutableList()
        val index = ids.indexOf(storyId)
        val target = index + direction
        if (index < 0 || target !in ids.indices) return@withContext
        val item = ids.removeAt(index)
        ids.add(target, item)
        dao.replaceAll(ids)
    }

    /**
     * 清理已经不存在于故事库里的队列项。
     */
    suspend fun pruneMissing(existingStoryIds: Set<Long>) = withContext(Dispatchers.IO) {
        val ids = dao.getStoryIdsSync()
        val valid = ids.filter { it in existingStoryIds }
        if (valid.size != ids.size) dao.replaceAll(valid)
    }
}

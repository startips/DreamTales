package com.bedtime.dreamtales.core.story

/**
 * 经典故事播种的纯逻辑：去重与修复历史上因并发播种产生的重复行。
 * 与 Android/Room 解耦，便于单元测试。
 */
object ClassicSeedPolicy {

    /**
     * 找出需要删除的重复经典故事行：同一标题下保留 id 最小（最早入库）的一行，
     * 其余视为并发播种产生的副本。只处理种子标题，绝不触碰用户自建/导入的同名故事。
     *
     * @param entries 现有故事 (标题, id)，顺序不限
     * @param seedTitles 官方种子故事标题集合
     */
    fun duplicateIdsToRemove(entries: List<Pair<String, Long>>, seedTitles: Set<String>): List<Long> {
        val normalizedSeeds = seedTitles.map { it.trim() }.toSet()
        return entries
            .filter { it.first.trim() in normalizedSeeds }
            .groupBy { it.first.trim() }
            .values
            .filter { it.size > 1 }
            .flatMap { group -> group.sortedBy { it.second }.drop(1).map { it.second } }
    }

    /**
     * 计算还缺哪些种子故事（按标题幂等），避免重复插入。
     */
    fun missingSeeds(
        existingTitles: List<String>,
        seedTitles: List<String>
    ): List<String> {
        val existing = existingTitles.map { it.trim() }.toSet()
        return seedTitles.map { it.trim() }.filterNot { it in existing }
    }
}
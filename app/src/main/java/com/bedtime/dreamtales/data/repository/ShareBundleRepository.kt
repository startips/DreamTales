package com.bedtime.dreamtales.data.repository

import android.content.Context
import androidx.room.withTransaction
import com.bedtime.dreamtales.core.share.ShareBundleProtocol
import com.bedtime.dreamtales.core.share.ShareManifest
import com.bedtime.dreamtales.core.share.ShareStoryEntry
import com.bedtime.dreamtales.core.share.ShareVoiceEntry
import com.bedtime.dreamtales.data.local.AppDatabase
import com.bedtime.dreamtales.data.local.entity.Story
import com.bedtime.dreamtales.data.local.entity.VoiceProfile
import com.google.gson.GsonBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

data class ShareImportResult(
    val storiesImported: Int,
    val storiesSkipped: Int,
    val voicesImported: Int,
    val voicesSkipped: Int,
    val coverImported: Int
)

/**
 * 家庭局域网共享包构建与导入。
 * 分享包为标准 ZIP：manifest.json + 封面图片 + 音色样本。
 */
class ShareBundleRepository(private val context: Context) {

    private val db = AppDatabase.getInstance(context)
    private val storyDao = db.storyDao()
    private val voiceDao = db.voiceProfileDao()
    private val gson = GsonBuilder().setPrettyPrinting().create()

    suspend fun buildBundle(deviceName: String): File = withContext(Dispatchers.IO) {
        val stories = storyDao.getAllStoriesSync()
        // 自定义克隆音色必须带有可用样本，否则分享到其他设备也无法合成
        val voices = voiceDao.getAllVoiceProfilesSync().filter { voice ->
            voice.cloudVoiceId == "mimo:default" ||
                (voice.localSamplePath.isNotBlank() && File(voice.localSamplePath).exists())
        }

        val buildDir = File(context.cacheDir, "share_build").apply {
            deleteRecursively()
            mkdirs()
        }
        val coversDir = File(buildDir, "covers").apply { mkdirs() }
        val voicesDir = File(buildDir, "voices").apply { mkdirs() }

        val storyEntries = stories.mapIndexed { index, story ->
            val coverName = copyIfExists(story.coverImagePath, coversDir, "cover_$index.jpg")
            ShareStoryEntry(
                title = story.title,
                targetAge = story.targetAge,
                content = story.content,
                durationSeconds = story.durationSeconds,
                dateLabel = story.dateLabel,
                iconEmoji = story.iconEmoji,
                isFavorite = story.isFavorite,
                coverFileName = coverName
            )
        }

        val voiceEntries = voices.mapIndexed { index, voice ->
            val sampleName = if (voice.localSamplePath.isNotBlank()) {
                copyIfExists(voice.localSamplePath, voicesDir, "voice_$index.wav")
            } else null
            ShareVoiceEntry(
                name = voice.name,
                cloudVoiceId = voice.cloudVoiceId,
                sampleFileName = sampleName,
                isDefault = voice.isDefault
            )
        }

        val manifest = ShareManifest(
            deviceName = deviceName,
            exportedAt = System.currentTimeMillis(),
            stories = storyEntries,
            voices = voiceEntries
        )
        File(buildDir, ShareBundleProtocol.MANIFEST_ENTRY)
            .writeText(gson.toJson(manifest), Charsets.UTF_8)

        val zipFile = File(context.cacheDir, "dreamtales_share.zip")
        if (zipFile.exists()) zipFile.delete()
        ZipOutputStream(zipFile.outputStream().buffered()).use { zip ->
            buildDir.walkTopDown()
                .filter { it.isFile }
                .forEach { file ->
                    val relative = file.relativeTo(buildDir).invariantSeparatorsPath
                    zip.putNextEntry(ZipEntry(relative))
                    file.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
        }

        buildDir.deleteRecursively()
        zipFile
    }

    suspend fun importBundle(bundleFile: File): ShareImportResult = withContext(Dispatchers.IO) {
        val importDir = File(context.cacheDir, "share_import_${System.currentTimeMillis()}").apply {
            deleteRecursively()
            mkdirs()
        }

        try {
            unzipSafely(bundleFile, importDir)
            val manifestFile = File(importDir, ShareBundleProtocol.MANIFEST_ENTRY)
            if (!manifestFile.exists()) {
                throw IllegalArgumentException("共享包缺少 manifest.json，可能不是月眠分享文件")
            }
            val manifest = gson.fromJson(
                manifestFile.readText(Charsets.UTF_8),
                ShareManifest::class.java
            ) ?: throw IllegalArgumentException("共享包清单解析失败")

            val existingTitles = storyDao.getAllStoriesSync().map { it.title }.toSet()
            val existingVoices = voiceDao.getAllVoiceProfilesSync().map { it.name to it.cloudVoiceId }.toSet()
            val plan = ShareBundleProtocol.planImport(
                manifest = manifest,
                existingTitles = existingTitles,
                existingVoiceKeys = existingVoices.map { (name, cloudId) -> "$name|$cloudId" }.toSet()
            )

            var coverImported = 0
            var voicesImported = 0
            db.withTransaction {
                plan.storiesToImport.forEach { index ->
                    val entry = manifest.stories[index]
                    val story = Story(
                        title = entry.title.trim().ifBlank { "家庭共享故事" },
                        targetAge = entry.targetAge.ifBlank { "3-6岁" },
                        content = entry.content.trim(),
                        durationSeconds = if (entry.durationSeconds > 0) entry.durationSeconds else 180,
                        isAiGenerated = true,
                        dateLabel = entry.dateLabel.ifBlank { "家庭共享" },
                        iconEmoji = entry.iconEmoji.ifBlank { "🌙" },
                        isFavorite = entry.isFavorite,
                        createdAt = System.currentTimeMillis() - index
                    )
                    val storyId = storyDao.insertStory(story)

                    val coverName = entry.coverFileName
                    if (!coverName.isNullOrBlank()) {
                        val source = File(importDir, coverName)
                        if (source.exists()) {
                            val coverDir = File(context.filesDir, "story_covers").apply { mkdirs() }
                            val target = File(coverDir, "cover_$storyId.jpg")
                            source.copyTo(target, overwrite = true)
                            storyDao.updateCoverImagePath(storyId, target.absolutePath)
                            coverImported++
                        }
                    }
                }

                plan.voicesToImport.forEach { index ->
                    val entry = manifest.voices[index]
                    val sampleDir = File(context.filesDir, "voice_samples").apply { mkdirs() }
                    var localSamplePath = ""
                    val sampleName = entry.sampleFileName
                    if (!sampleName.isNullOrBlank()) {
                        val source = File(importDir, sampleName)
                        if (source.exists() && source.length() > 0) {
                            val target = File(sampleDir, "voice_sample_${System.currentTimeMillis()}_$index.wav")
                            source.copyTo(target, overwrite = true)
                            localSamplePath = target.absolutePath
                        }
                    }
                    voiceDao.insertVoiceProfile(
                        VoiceProfile(
                            name = entry.name.ifBlank { "家庭共享音色" },
                            cloudVoiceId = entry.cloudVoiceId.ifBlank { "mimo:clone" },
                            localSamplePath = localSamplePath,
                            isDefault = false,
                            createdAt = System.currentTimeMillis() + index
                        )
                    )
                    voicesImported++
                }

                // 若本机尚无默认音色，则把首个导入音色设为默认，避免出现“未设置音色”
                if (voiceDao.getDefaultVoiceProfileSync() == null) {
                    voiceDao.getAllVoiceProfilesSync().firstOrNull()?.let { voice ->
                        voiceDao.setDefaultVoiceProfile(voice.id)
                    }
                }
            }

            ShareImportResult(
                storiesImported = plan.storyImportCount,
                storiesSkipped = plan.storySkipCount,
                voicesImported = voicesImported,
                voicesSkipped = plan.voiceSkipCount,
                coverImported = coverImported
            )
        } finally {
            importDir.deleteRecursively()
        }
    }

    private fun copyIfExists(sourcePath: String?, targetDir: File, targetName: String): String? {
        if (sourcePath.isNullOrBlank()) return null
        val source = File(sourcePath)
        if (!source.exists() || source.length() == 0L) return null
        val target = File(targetDir, targetName)
        source.copyTo(target, overwrite = true)
        return "${targetDir.name}/$targetName"
    }

    private fun unzipSafely(zipFile: File, targetDir: File) {
        val targetRoot = targetDir.canonicalFile
        ZipInputStream(zipFile.inputStream().buffered()).use { zip ->
            var entry: ZipEntry? = zip.nextEntry
            while (entry != null) {
                val name = entry.name
                if (name.isNotBlank()) {
                    val outFile = File(targetDir, name)
                    if (!outFile.canonicalPath.startsWith(targetRoot.path + File.separator)) {
                        throw SecurityException("共享包包含非法路径")
                    }
                    if (entry.isDirectory) {
                        outFile.mkdirs()
                    } else {
                        outFile.parentFile?.mkdirs()
                        outFile.outputStream().buffered().use { output ->
                            zip.copyTo(output)
                        }
                    }
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
    }
}

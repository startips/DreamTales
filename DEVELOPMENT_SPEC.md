# 儿童睡前伴读应用 (DreamTales) 完整开发规范与实施方案

本规范专为自主编码智能体（如 `opencode`）编写，严格定义了项目的架构分层、系统底层行为、数据模型、API 交互逻辑及 UI 规范。

---

## 1. 项目基础与核心约束

### 1.1 技术栈选型
- **开发语言**：Kotlin 2.0+（纯原生开发）
- **UI 框架**：Jetpack Compose (Material3)
- **多媒体内核**：AndroidX Media3 (ExoPlayer + 媒体会话 MediaSession)
- **本地存储**：Jetpack Room (SQLite ORM) + 协程挂起函数 (Coroutines Flow)
- **网络通信**：Retrofit2 + OkHttp3 (含文件流式下载与重试机制)
- **系统架构**：单活动架构 (Single-Activity) + 现代干净架构 (Clean Architecture)

### 1.2 架构与代码设计原则
- **显式报错（Fail-Fast），拒绝静默降级**：
  - 缺少 API 密钥、权限被拒、麦克风分贝过低、网络请求失败等场景，必须向 UI 暴露明确的错误状态与用户提示，严禁静默吞掉异常或使用假数据兜底。
- **离线优先（Local-First）**：
  - 云端合成的音频必须下载到应用私有沙盒存储（`context.filesDir`），播放器仅直接消费本地绝对路径文件。
- **暗光物理工程**：
  - 全局禁止使用 `#FFFFFF` 纯白色。背景统一使用 `#121212`，字体使用暖灰色 `#9E9E9E`，重点强调色使用暖月黄 `#E8C587`。

---

## 2. 工程目录结构规范

```
DreamTales/
├── app/
│   ├── build.gradle.kts
│   └── src/main/
│       ├── AndroidManifest.xml
│       └── java/com/bedtime/dreamtales/
│           ├── MainActivity.kt
│           ├── core/
│           │   ├── constants/        // 全局常量（衰减步进、音量曲率等）
│           │   ├── theme/            // 暖暗主题、色彩与排版
│           │   └── utils/            // 音频分贝计算、权限辅助
│           ├── data/
│           │   ├── api/              // Retrofit 接口（阿里百炼/通义语音克隆 API）
│           │   ├── local/            // Room 数据库、DAO 与实体类
│           │   │   ├── AppDatabase.kt
│           │   │   ├── dao/
│           │   │   └── entity/
│           │   └── repository/       // 数据仓储（录音落盘、音色合成与拉取）
│           ├── service/
│           │   ├── StoryPlaybackService.kt  // Media3 前台媒体服务
│           │   └── FlipSensorManager.kt     // 双传感器扣屏感应器
│           └── ui/
│               ├── home/             // 首页（今日精选、当前音色标签）
│               ├── record/           // 家长音色录入引导与上传
│               ├── player/           // 极暗沉浸式播放器（含倒计时环）
│               └── navigation/       // Compose 导航路由定义
├── build.gradle.kts
├── settings.gradle.kts
└── gradle/
```

---

## 3. 核心系统底层服务规范

### 3.1 翻转扣屏感应器 (`FlipSensorManager.kt`)
- **硬件依赖**：加速度传感器（`Sensor.TYPE_ACCELEROMETER`）+ 距离传感器（`Sensor.TYPE_PROXIMITY`）。
- **防抖判定算法**：
  - 当 $Z$ 轴加速度满足 $Z < -8.5\,\text{m/s}^2$，且 $|X| < 3.0\,\text{m/s}^2$、$|Y| < 3.0\,\text{m/s}^2$（表明手机平整且正面朝下）。
  - 同时距离传感器报告遮挡（`distance == 0.0`）。
  - 满足双重条件且持续 300 毫秒以上时，发布状态 `isFaceDown = true`。
  - 任一条件解除且持续 200 毫秒以上时，发布状态 `isFaceDown = false`。
- **动作执行**：
  - `isFaceDown = true` 时，将当前窗口属性 `window.attributes.screenBrightness = 0.0f`，并挂载全屏透明事件拦截层（防止误触）。
  - `isFaceDown = false` 时，平滑恢复至 5%（`0.05f`）暖暗亮度。

### 3.2 前台音频播放服务 (`StoryPlaybackService.kt`)
- **基础实现**：继承 `androidx.media3.session.MediaSessionService`。
- **清单配置**：
  ```xml
  <service
      android:name=".service.StoryPlaybackService"
      android:exported="false"
      android:foregroundServiceType="mediaPlayback">
      <intent-filter>
          <action android:name="androidx.media3.session.MediaSessionService" />
      </intent-filter>
  </service>
  ```
- **渐衰与减速协程控制器**：
  - 提供方法 `startSleepTimer(durationMinutes: Int)`。
  - 在剩余时间进入最后 5 分钟阶段启动步进衰减循环（每 20 秒执行一次）：
    - 语速递减：从 `1.0f` 平滑降至 `0.75f`（步进：`-0.02f`）。
    - 音量衰减：从 `1.0f` 降至 `0.0f`（按照对数声学衰减曲线）。
  - 倒计时归零：调用 `player.pause()`，停止前台通知，释放局部唤醒锁（`PARTIAL_WAKE_LOCK`）。

---

## 4. 声音克隆与数据持久化

### 4.1 本地存储实体（Room Entity）

#### `VoiceProfile.kt`（音色档案）
```kotlin
@Entity(tableName = "voice_profiles")
data class VoiceProfile(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,                  // 例如："爸爸的晚安声"、"妈妈的轻柔声"
    val cloudVoiceId: String,          // 云端生成的专属音色 ID
    val localSamplePath: String,       // 本地 30 秒原始录音路径
    val isDefault: Boolean = false,    // 是否为当前选中的活跃音色
    val createdAt: Long = System.currentTimeMillis()
)
```

#### `Story.kt`（故事库）
```kotlin
@Entity(tableName = "stories")
data class Story(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,                 // 故事标题
    val targetAge: String,             // 适用年龄（"2-4岁", "5-7岁", "8岁以上"）
    val content: String,               // 纯文本内容（按段落划分，无悬疑问句）
    val durationSeconds: Int           // 预估播放时长
)
```

#### `StoryAudioCache.kt`（本地已合成音频映射）
```kotlin
@Entity(
    tableName = "story_audio_caches",
    indices = [Index(value = ["storyId", "voiceProfileId"], unique = true)]
)
data class StoryAudioCache(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val storyId: Long,
    val voiceProfileId: Long,
    val localFilePath: String,         // 本地 MP3 文件绝对路径
    val isComplete: Boolean = false,
    val createdAt: Long = System.currentTimeMillis()
)
```

### 4.2 声音克隆 API 客户端规范（以阿里百炼 CosyVoice 为基准）
- **环境变量与凭据管理**：
  - 严禁硬编码。在 `core/constants/AppConfig.kt` 中从加密偏好设置（`EncryptedSharedPreferences`）读取 API 密钥。
  - 若密钥为空，必须在启动网络请求前显式抛出 `IllegalStateException("未配置有效的百炼 API Key，请进入设置界面填入")`。
- **录音采集硬门槛**：
  - 录音采集参数：采样率 `16000Hz`，单声道，16 位 PCM。
  - 录音时长限制：15 秒 $\le$ 时长 $\le$ 30 秒。
  - 分贝有效性校验：录音过程中计算分贝值（RMS）。若平均分贝 $< 35\text{dB}$，提示“声音太轻，请靠近麦克风重新录制”并阻止上传。

---

## 5. UI 界面流转与交互规范

### 5.1 首页 (`HomeScreen.kt`)
- 顶部常驻音色状态条：“当前讲故事人：[爸爸]（点击切换/录入新音色）”。
- 中间展示“今日睡前推荐”卡片列表（横向轻扫排版，无无限信息流）。
- 点击故事卡片：
  - 检查该故事在当前音色下是否已存在本地缓存：
    - 若已缓存：直接启动 `StoryPlaybackService` 并跳转至沉浸式播放器。
    - 若未缓存：弹出轻量进度模态窗，调用合成接口拉取 MP3 写入本地，完成后自动播放。

### 5.2 录音引导页 (`RecordVoiceScreen.kt`)
- 界面中央大字号展示 3 句安抚引导语：
  > 1. “天黑了，小星星打了个哈欠，准备闭上眼睛了。”
  > 2. “森林里的小树叶都盖上了月光的被子，安安静静的。”
  > 3. “宝贝，安心睡吧，爸爸/妈妈一直在你身边守护着你。”
- 录音按钮附带波形呼吸动效，实时显示录音有效分贝指示条。
- 录制满 15 秒后激活“完成录制”按钮；提供本地回放试听与上传注册确认。

### 5.3 极暗播放页 (`SleepPlayerScreen.kt`)
- 背景色采用深度暖黑 `#121212`，字体全部使用暗灰 `#9E9E9E`。
- 中央展示环形倒计时组件（支持点击快速切换 15 / 30 / 45 分钟）。
- 底部显示醒目防误触提示：“将手机屏幕朝下扣在床头，屏幕将自动彻底熄灭”。
- 双指轻点屏幕任意位置即触发播放/暂停。

---

## 6. opencode 执行实施路线（按阶段推进）

### 阶段一：基础骨架与核心配置
1. 生成 `settings.gradle.kts` 与 `app/build.gradle.kts`，配置 Compose、Media3、Room、Retrofit 依赖。
2. 配置 `AndroidManifest.xml`（补齐 `RECORD_AUDIO`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MEDIA_PLAYBACK`, `WAKE_LOCK` 权限）。
3. 建立主题与配色系统 `Theme.kt`。

### 阶段二：底层传感器与前台服务
1. 实现 `FlipSensorManager.kt`，接入加速度计与距离传感器双重监听与防抖逻辑。
2. 实现 `StoryPlaybackService.kt`，接入 Media3 ExoPlayer，实现定时器语速递减与音量下滚逻辑。

### 阶段三：持久层与网络层
1. 编写 Room 实体类与 DAO 接口，并在首次初始化时自动向数据库预插入 2 篇精选睡前故事文本。
2. 封装 API 客户端与声音克隆数据仓储（包含分贝检测、文件下载与本地落盘）。

### 阶段四：UI 界面拼装与端到端闭环
1. 实现 Compose 界面：`HomeScreen`、`RecordVoiceScreen`、`SleepPlayerScreen`。
2. 串联 Compose 导航逻辑，确保在真实安卓设备上可一键运行调试。

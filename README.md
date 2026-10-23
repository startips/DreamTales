<div align="center">

# 🌙 DreamTales (绘梦童话)

**专为睡前场景打造的现代 Android 智能伴读绘本应用**

*让温润的声音与童话插画，温柔伴宝入梦*

[![GitHub Release](https://img.shields.io/github/v/release/startips/DreamTales?style=flat-square&color=E8C587)](https://github.com/startips/DreamTales/releases/latest)
[![Android Min SDK](https://img.shields.io/badge/Android-8.0%2B%20(API%2026%2B)-brightgreen?style=flat-square)](https://android.com)
[![Kotlin](https://img.shields.io/badge/Kotlin-100%25-orange?style=flat-square&logo=kotlin)](https://kotlinlang.org)
[![Compose](https://img.shields.io/badge/Jetpack%20Compose-M3-4285F4?style=flat-square&logo=jetpackcompose)](https://developer.android.com/jetpack/compose)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue?style=flat-square)](LICENSE)

[📥 下载最新 APK](https://github.com/startips/DreamTales/releases/latest) · [✨ 功能特性](#-核心功能亮点) · [⚙️ 本地构建](#%EF%B8%8F-本地构建与调试) · [🔄 动态更新](#-动态更新机制)

</div>

---

## 📖 项目简介

**DreamTales** 是一款专为年轻家庭睡前陪伴场景设计的现代 Android 绘本应用。不仅收录了大量精选经典安抚童话，还融合了前沿的 AI 故事创作、家长真人声音即时克隆、唯美绘本插图生成与暗光卧室物理护眼声学工程。

遵循 **Local-First（本地优先）** 架构原则，所有故事文本、音频与绘本均完整持久化于本地私有沙盒中，断网也可随时秒开点播，守护宁静温馨的入眠时光。

---

## ✨ 核心功能亮点

### 1. 🎙️ 专属家长声音克隆与高清离线伴读
- **20 秒真人音色克隆**：家长仅需朗读一段简短导引，即可瞬间克隆父母专属音色。每当夜幕降临，即使家长加班或出差，宝宝依然能听着熟悉的爸爸/妈妈声音安心入眠。
- **本地沙盒预缓存**：统一音频离线落盘管理，文库点播 0 毫秒秒开，彻底告别夜间网络缓冲卡顿。

### 2. 📖 智能故事检索与文库收录
- **自然语言童话创作/检索**：输入任意想要讲的主题（如“勇敢探索的小松鼠”、“讲礼貌的小白兔”），大模型智能推荐 3 张候选卡片供家长二次预览确认。
- **正文阅读与点播解耦**：点击卡片即刻静心阅读全文字稿，点击播放键独立点播伴读，满足不同陪伴需求。
- **后台静默合成**：收录新童话后原位静默生成伴读音频，绝不打断当前浏览，其他故事点播即点即播。

### 3. 🎨 专属绘本插画与相册选图
- **AI 绘本插图生成**：一键调用生图大模型为故事生成量身定制的唯美插画，支持硬超时拦截与防卡死提示。
- **零权限原生相册自定义**：通过 Android PhotoPicker 免权限直接选取家庭照片或自制插画，内置智能采样（防 OOM 内存溢出）与 EXIF 旋转角度自动矫正。

### 4. 🌙 卧室暗光护眼工程与双模自适应
- **顶栏一触即切**：深度暖夜（深邃暗调，杜绝卧室强白光刺激）与绘本米黄（羊皮纸温润色，适合日间亲子共读）一键顺畅轮换。
- **自适应开屏宣传动效**：开屏背景与中心天体动态感应当前外观——日间升起温暖朝阳 `☀️`，夜间升起温润金月 `🌙`，底层系统主题无缝平滑过渡，杜绝黑白闪烁。
- **物理扣屏防误触熄屏**：结合手机加速度计与距离传感器，将手机屏幕朝下扣放在床头柜上，立即极暗防误触熄屏，拿起即刻亮屏恢复。

### 5. ⏱️ 睡前声学渐弱与轻量倒计时胶囊
- **轻量底栏交互胶囊**：音量、白噪音、睡眠定时统一收拢于底栏胶囊药丸，主界面只留唯美绘本插图与核心播控。
- **对数级声学衰减**：定时最后 5 分钟自动开启对数衰减曲线，语速与音量平滑渐弱，助宝宝悄然进入深睡。
- **双轨助眠白噪音混音**：支持搭配独立声道的森林细雨 `🌧️`、宁静海浪 `🌊`、壁炉柴火 `🔥`、夏夜虫鸣 `🌾` 与摇篮音盒 `🎶`。

### 6. 🔄 开源动态在线升级与发版
- **GitHub Releases 一键升级**：客户端内置语义化版本对比，发现新版弹窗一键下载与 `FileProvider` 安全覆盖安装。
- **国内镜像源高速回退**：内置多源加速节点（官方直链 ➡️ ghfast ➡️ ghproxy），解决国内部分网络无法直连 GitHub 的问题。

---

## 🛠️ 技术栈架构

| 模块 | 技术选型 | 说明 |
| :--- | :--- | :--- |
| **语言** | Kotlin 100% | 纯 Kotlin 编写，全面拥抱协程 (Coroutines) 与响应式流 (Flow) |
| **UI 框架** | Jetpack Compose + Material 3 | 现代化声明式 UI，组件原子化解耦与流畅动效过渡 |
| **架构规范** | MVVM + Repository + Local-First | 领域模型驱动，状态单一数据源 (Single Source of Truth) |
| **音频引擎** | AndroidX Media3 / ExoPlayer | 前台服务保活、通知栏多媒体播控、双轨独立混音、平滑寻址防抖 |
| **本地存储** | Room Database (v4) | 故事数据、音色特征、音频缓存元数据持久化，平滑升级迁移 |
| **安全加密** | AndroidX Security Crypto | `EncryptedSharedPreferences` 对本地配置进行 AES-256 加密存储 |
| **网络通讯** | Retrofit 2 + OkHttp 3 | RESTful API 通讯、流式断点下载、智能限流与避让拦截器 |
| **传感器** | SensorManager | 物理加速度计与距离传感器融合识别扣屏熄屏手势 |
| **自动化发布** | GitHub Actions | 标签自动触发云端编译打包与 Release 资产发布 |

---

## 📲 下载与安装

您可以直接前往 Releases 页面获取最新编译好的安装包：

- **GitHub Releases 页面**：[https://github.com/startips/DreamTales/releases](https://github.com/startips/DreamTales/releases)
- **最新版 APK 直接下载**：[DreamTales-v2.0.0-debug.apk](https://github.com/startips/DreamTales/releases/download/v2.0.0/DreamTales-v2.0.0-debug.apk)

> 支持 Android 8.0 (API 26) 及更高版本（兼容鸿蒙 HarmonyOS、小米 HyperOS、vivo OriginOS、OPPO ColorOS 等主流系统）。

---

## ⚙️ 本地构建与调试

本项目已完成 API 密钥脱敏隔离，克隆项目后无需担心任何敏感信息泄露：

### 1. 克隆代码仓库
```bash
git clone https://github.com/startips/DreamTales.git
cd DreamTales
```

### 2. 配置本地私密变量 (`local.properties`)
项目根目录下复制模板：
```bash
cp local.properties.example local.properties
```
打开 `local.properties` 并填入您的 Android SDK 路径与相应的 API 密钥：
```properties
sdk.dir=/Users/your_name/Library/Android/sdk

# 语音合成与声音克隆 Key
DEFAULT_MIMO_API_KEY=your_mimo_api_key

# 故事生成与插画大模型 API
DEFAULT_CPA_BASE_URL=https://your-api-endpoint.com/v1/
DEFAULT_CPA_API_KEY=your_cpa_api_key
DEFAULT_CPA_MODEL=gemini-3.5-flash-lite
```
*(注：`local.properties` 已被 `.gitignore` 严格忽略，绝不会被意外上传)*

### 3. 运行自动化测试与编译打包
```bash
# 运行核心逻辑单元测试
./gradlew testDebugUnitTest

# 编译 Debug 安装包
./gradlew assembleDebug

# 直接安装到已连接的测试手机
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

---

## 🚀 自动化发版工作流 (GitHub Actions)

项目内置了自动化 CI/CD 工作流（`.github/workflows/release.yml`）。发版流程非常简便：

1. **设置 GitHub Repository Secrets**：
   在 GitHub 仓库 **Settings ➡️ Secrets and variables ➡️ Actions** 中添加您的密钥（如 `DEFAULT_MIMO_API_KEY`、`DEFAULT_CPA_API_KEY` 等）。
2. **打 Tag 并推送到仓库**：
   ```bash
   git tag v2.0.1
   git push origin v2.0.1
   ```
3. GitHub Actions 将自动拉起 Ubuntu 容器，构建出最新的 APK 并自动创建 GitHub Release 发版。App 客户端将即刻感知新版本并弹窗提示更新！

---

## 🔒 隐私与安全承诺

- **Local-First 数据安全**：用户录制的声音切片、自定义绘本封面、收藏的故事书单均保存在应用私有沙盒目录中，不经任何第三方中转。
- **敏感配置防泄露**：源码中 0 硬编码凭证，编译期通过 Gradle 注入，全链路提交历史均经过密钥清洗。
- **免权限图片选取**：采用系统原生 PhotoPicker 机制，不需要向用户索取读取整机相册的大宽泛权限。

---

## 📄 开源许可证

本项目采用 [Apache License 2.0](LICENSE) 许可证开源，欢迎学习、交流与提交 PR。

---

<div align="center">
  <sub>Made with ❤️ for peaceful bedtime stories and sweet dreams.</sub>
</div>

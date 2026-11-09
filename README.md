# 英文老师（EnCoach）

一个「选中就讲」的 Android 英语学习工具。在 X、Instagram、Chrome 里读到不懂的英文，选中它，从系统选择菜单点「英文老师」，直接弹出一张半屏解析卡片，不离开原来的 App。

- 纯 Kotlin + 平台 API：**没有 androidx，没有 Compose，没有任何第三方依赖**（JSON 用系统自带的 `org.json`）。
- 界面全中文，XML 布局，不用 ViewBinding。
- 技术参数：compileSdk 34 / targetSdk 34 / minSdk 26，Kotlin 1.9.24，AGP 8.5.2，Gradle 8.7，JDK 17，包名 `com.encoach.app`。

---

## 一、三个功能

| 功能 | 输入 | 输出 |
| --- | --- | --- |
| 生词解析 | 英文单词 / 短语 | 词性与音标、常见释义、**它在这句话里的意思**、常见搭配、一个例句、易混词 |
| 长句拆解 | 英文长句 | 主干（谁做什么）、每段修饰成分挂在哪个词上、断句位置、直译 vs 顺译、句型标志词、这句为什么难 |
| 汉译英 + 批改 | 中文 / 英文 | 中文 →【最推荐】【很随意】【正式一点】三版并逐条说明；英文 → 错误清单、错在哪、更自然的说法、中式英语成因、最后一句「要点」 |

## 二、两个入口

**A. 选词入口（卡片）**

任意 App 里选中文字 → 系统选择菜单 →「英文老师」→ 半屏卡片。

- 收到文字自动判断：**含中文 → 功能 3（汉译英）**；**纯英文 → 功能 1（生词解析）**。
- 卡片顶部 [讲解] [拆句] [批改] 可随时切换，切换即重新解析。
- 卡片可滚动、长按复制全文，底部 [重新生成] [复制] [关闭]，关闭直接回到原 App。

**B. 主界面**

输入框 + 四个按钮（生词 / 拆句 / 汉译英 / 批改）+ 结果区 + 右上角「设置」（API Key、baseUrl、模型名）。

---

## 三、目录结构

```
encoach/
├── app/
│   ├── build.gradle                     # 模块配置（无第三方依赖）
│   ├── proguard-rules.pro
│   └── src/main/
│       ├── AndroidManifest.xml          # 声明 PROCESS_TEXT 入口
│       ├── java/com/encoach/app/
│       │   ├── MainActivity.kt          # 主界面
│       │   ├── ProcessTextActivity.kt   # 选词卡片
│       │   ├── SettingsActivity.kt      # 设置弹窗
│       │   ├── DeepSeekClient.kt        # HttpURLConnection + SSE 流式解析
│       │   ├── Prompts.kt               # 三个功能的全部提示词
│       │   ├── Prefs.kt                 # SharedPreferences 读写
│       │   └── Mode.kt
│       └── res/                         # 布局、颜色、主题、图标
├── .github/workflows/build.yml          # GitHub Actions 云编译
├── build.gradle
├── settings.gradle
├── gradle.properties
└── README.md
```

---

## 四、出 APK 的两种方式

### 方式 1：GitHub Actions 云编译（推荐，不用装 Android Studio）

1. 新建一个 GitHub 仓库，把本目录所有文件推上去，**默认分支叫 `main`**。
2. push 到 `main` 后，打开仓库的 **Actions** 页，左侧选 **Build APK**，会看到一次自动运行的构建。
3. 构建完成后，点进这次运行，页面底部 **Artifacts** 里下载 `app-debug.zip`，解压得到 **`app-debug.apk`**。
4. 也可以手动跑：Actions → Build APK → 右侧 **Run workflow**。

**打 tag 自动发 Release**：本地执行

```bash
git tag v1.0.0
git push origin v1.0.0
```

工作流会把 `app-debug.apk` 直接附到对应的 GitHub Release 里，别人点开 Release 就能下载安装包。

> 工作流做的事：JDK 17 → `android-actions/setup-android` → 安装 platform 34 / build-tools 34.0.0 → 固定 Gradle 8.7 → `gradle assembleDebug` → 上传 artifact → tag 时发 Release。debug 签名，不需要配置任何密钥。

### 方式 2：本地 / Android Studio

环境要求：JDK 17、Android SDK Platform 34、Gradle 8.7。

```bash
# 命令行，直接出 APK
gradle assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk
```

用 Android Studio 打开：

1. `File → Open`，选中本目录（`encoach`），不要选 `app` 子目录。
2. 如果提示没有 Gradle Wrapper，先在项目根目录跑一次 `gradle wrapper --gradle-version 8.7`，再 Sync。
3. `Settings → Build, Execution, Deployment → Build Tools → Gradle` 里把 **Gradle JDK** 设成 17。
4. `Build → Build Bundle(s) / APK(s) → Build APK(s)`。

> 仓库里故意没有放 `gradle-wrapper.jar`（二进制文件不便生成）。CI 用 `gradle/actions/setup-gradle` 固定了 8.7，本地有 Gradle 8.7 就能直接构建；想要 wrapper 就跑一次上面的 `gradle wrapper` 命令。

---

## 五、装到 Android 15 手机

1. 把 `app-debug.apk` 传到手机（微信文件传输、数据线、网盘都行）。
2. 用文件管理器点开 APK。首次会提示「为了安全，你的手机不允许安装未知来源的应用」→ 点「设置」→ 允许该来源（文件管理器 / 浏览器）安装 → 返回继续安装。
3. 如果弹出「Play 保护机制」拦截，选「仍然安装」。
4. 装好后桌面会出现「英文老师」图标。

命令行安装（需要 adb 和 USB 调试）：

```bash
adb install -r app-debug.apk
```

---

## 六、首次配置（必做）

打开 App → 右上角「设置」：

| 项目 | 默认值 | 说明 |
| --- | --- | --- |
| API Key | 空 | 去 https://platform.deepseek.com 注册并创建，形如 `sk-...` |
| 接口地址 baseUrl | `https://api.deepseek.com` | 必须是 OpenAI 兼容接口。填 `https://api.deepseek.com`、`https://api.deepseek.com/v1`、或完整的 `.../v1/chat/completions` 都能识别 |
| 模型名 | `deepseek-chat` | 换服务商时改成对方的模型名 |

填完点「保存」，回主界面即可使用。

> 用别的 OpenAI 兼容服务（自建中转、硅基流动、OpenRouter 等）也行：把 baseUrl 和模型名换成对方的即可，只要它支持 `stream: true` 的 `/v1/chat/completions`。

---

## 七、隐私说明

这是要明确说清楚的一点：

- **你选中的文字，会原样发送到你配置的那个 API**（默认是 DeepSeek 官方接口），否则没法做解析。发什么由你选中什么决定，App 不会额外上传剪贴板、通讯录、位置、设备信息。
- **API Key 只存在这台手机上**：写在应用私有的 SharedPreferences 里（`/data/data/com.encoach.app/shared_prefs/`），只有本 App 能读。
- 清单里设置了 `android:allowBackup="false"`，所以密钥不会随系统备份或换机迁移被传到云端。
- App 里没有任何统计、埋点、广告 SDK，也没有自建服务器；除了你配的那个 API，不连接任何地址。
- GitHub Actions 构建出来的 APK 是**空的密钥**，密钥要你自己在手机上填，不会进仓库。
- 卸载 App 会同时清掉密钥。

---

## 八、常见问题

**1. 选择菜单里没有「英文老师」？**

`ACTION_PROCESS_TEXT` 是系统能力，但**只有用系统文本选择菜单的 App 才会显示**。Chrome、系统浏览器、记事本、大部分原生应用都支持；**X（Twitter）和 Instagram 用的是自己画的选择菜单，经常不显示第三方文本操作**，这是对方的实现限制，App 这边改不了。

遇到不支持的 App：先复制文字，再打开「英文老师」粘到输入框，用四个按钮处理，功能完全一样。

**2. 提示 401 / API Key 无效**

Key 复制错了、过期了，或账户没余额。到设置里重新粘贴，注意前后不要带空格。

**3. 提示 404**

baseUrl 填错。只填到域名即可（如 `https://api.deepseek.com`），路径 App 会自动补。

**4. 一直转圈 / 超时**

大陆网络访问 `api.deepseek.com` 有时不稳。换网络，或在设置里换成你能连通的 OpenAI 兼容中转地址。

**5. 卡片只显示一半 / 被键盘顶住**

卡片高度是屏幕的 64%，小屏设备会自动收缩。如果系统字体调得特别大，可以在系统设置里临时调小字体。

**6. 想改讲解风格**

所有提示词集中在 `app/src/main/java/com/encoach/app/Prompts.kt`，改完重新构建即可，不需要动别的代码。

---

## 九、已知限制

- 只支持 `text/plain` 的选中文本；图片、PDF 里的文字选不中，自然也用不了。
- 流式输出用 `HttpURLConnection` + 单线程 Executor 实现，没有断线重连；网络抖动会直接报错，点「重新生成」即可。
- 没有历史记录、没有生词本——每次解析都是一次性的，结果靠「复制」保存。
- 卡片是 Activity（对话框主题），不是系统浮层，所以在极少数定制 ROM 上可能出现圆角或高度不理想。

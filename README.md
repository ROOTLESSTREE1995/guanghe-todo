# 光合待办

**把消息，变成下一步。**

面向班主任的 Android 通知整理助手：将微信、QQ、钉钉、企业微信的文字通知整理为待办或请假返校跟踪，并通过系统提醒和桌面小组件展示。事项保存在本机，可选择接入自己的 DeepSeek API Key，无需部署服务端。

当前版本：**0.3.0 测试版**。支持 Android 8.0（API 26）及以上。

## 功能

- **自动整理：** 识别任务、需跟进事项和请假，直接保存，无需逐条确认；保留消息原文，支持随时编辑和删除。
- **中文时间：** 将“明早 9 点”“下周一下午三点半”“半小时后”等换算成具体时间。相对时间以消息接收时刻和时区为准，不随排队处理延迟而变化。
- **待办管理：** 今日、逾期、未来、等待反馈、时间待补和已完成；支持新增、编辑、完成、重开和撤销状态操作。
- **请假返校：** 自动建立返校跟踪，支持改期和到点核查提醒；核查后由老师标记“已返校”。
- **两个桌面小组件：** 待办事项与请假返校独立展示，数据变化自动更新，不必打开 App 查看。
- **来源控制：** 选择需要接收的应用，可按通知标题关键词筛选会话；支持手动粘贴消息。
- **本地提醒：** 普通待办可提前提醒，请假在预计返校时间提醒；修改、完成或删除后同步调整提醒。
- **界面：** 清晰文字层级、克制配色、深色模式和减少动态效果支持。

## 开始使用

1. 构建并安装 APK，或安装项目提供的测试安装包。测试包使用开发签名，尚未上架应用商店。
2. 首次打开可体验虚构示例；示例不调用 DeepSeek、不生成手机提醒，也不进入桌面小组件。
3. 在“设置 → 手机连接”中开启通知访问和提醒通知，需要准时提醒时开启相应权限。按手机系统需要调整后台电池限制。
4. 在“通知来源”中选择应用。会话关键词每行一个，按通知标题匹配；留空表示接收所选应用实际发出的所有通知。打开“开始接收通知”并保存。
5. 如需 AI 整理，在“DeepSeek 智能整理”填写自己的 API Key 和账户可用模型，点击“保存并测试连接”，再开启“使用 DeepSeek 识别”。连接测试只发送固定测试文字。
6. 后续符合筛选条件的通知会自动整理为事项。关闭云端识别时使用本地关键词规则，识别能力较有限；也可直接新增或粘贴消息。

通知接收和云端识别在全新安装时默认关闭。较新 Android 系统可能对侧载应用的通知访问显示“受限设置”，需按系统应用详情页指引处理。

## 桌面小组件

| 组件 | 内容 |
| --- | --- |
| 待办事项 | 未完成任务与跟进，按到期时间排列，包含未来安排和时间待补事项；显示已到期、等反馈、暂定时间标记。 |
| 请假返校 | 待返校学生及预计返校时间，到期显示“待核查”；姓名或时间缺失时保留事项文字和待补提示。 |

在 App 的“设置 → 桌面小组件”点击“添加”，按桌面提示放置。也可长按桌面空白处，进入小组件列表选择“光合待办”；部分小米桌面需进入“安卓小部件”。两种组件可分别添加和调整大小。

组件支持列表滚动、每页 50 条分页、多实例、深色模式和空状态。点标题打开对应列表，点记录打开该事项；完成任务或确认返校仍在 App 中操作。通知整理、编辑、状态修改、删除事项或原消息后，组件自动刷新。

组件读取本机数据库，不额外调用 DeepSeek。除数据变化后的刷新外，系统每 30 分钟兜底更新，并为日期或截止状态变化安排本地刷新；系统省电限制或强制停止可能导致更新延后。

## 时间与提醒规则

DeepSeek 负责从原文摘出时间短语，手机校验摘录并换算日期。每个事项单独处理截止时间；请假使用预计返校时间。

- “明早 9 点”对应消息接收日期的次日 09:00。
- 只有日期或时段时采用默认时刻并标注“暂定”：日期 18:00、早上/上午 09:00、中午 12:00、下午 15:00、晚上 20:00。
- “放学后”“尽快”等缺少具体作息的表达仍自动保存，时间留空，不设置到点提醒；可在“时间待补”里补充。
- 身份不明确时不推断学生姓名。请假跟踪和返校提醒不代替请假审批，也不会自动确认学生已返校。

## 权限与数据流

```text
所选应用的系统通知 / 手动粘贴
    → 本地筛选、去重和 SQLite 保存
    → 本地关键词整理，或用户开启的 DeepSeek 云端识别
    → 本地事项、系统提醒、桌面小组件
```

| 权限或设置 | 用途 |
| --- | --- |
| 通知访问 | 接收其他应用发布的通知；应用内再按来源和会话标题筛选。 |
| 提醒通知 | 显示待办和返校核查提醒。 |
| 准时提醒 | 按计划安排精确提醒；未授权时使用系统允许的非精确提醒，可能延迟。 |
| 网络访问 | 用户开启云端识别或主动测试连接时调用 DeepSeek 官方 HTTPS 接口。 |
| 开机接收 | 设备重启后恢复待处理队列和提醒。 |

API Key 由使用者自行填写，通过 Android Keystore 加密保存；界面不回显已保存的明文。云端识别会将符合条件的**通知标题、正文、接收时间、时区和星期**发送给 DeepSeek，费用由相应 DeepSeek 账户承担。项目没有自建数据收集服务端。

消息与事项保存在应用私有目录；普通事项数据库并未额外加密。系统备份与迁移备份已关闭，当前没有导出或恢复功能，卸载会清除本地数据。小组件和系统提醒可能直接显示事项内容。详见 [隐私与数据说明](PRIVACY.md)。

## 构建与测试

需要 JDK 17、Android SDK Platform 35 和 Build Tools 35.0.0。项目使用 Gradle Wrapper 8.11.1、Android Gradle Plugin 8.9.2；Java 原生服务配合本地 WebView 界面，无第三方运行时依赖。

在项目根目录运行（将路径替换为自己的安装位置）：

```sh
export JAVA_HOME=/path/to/jdk-17
export ANDROID_HOME=/path/to/android-sdk
./gradlew assembleDebug lintDebug testDebugUnitTest
```

APK 输出至 `app/build/outputs/apk/debug/app-debug.apk`。也可使用 Android Studio 打开项目根目录构建。

macOS（Apple Silicon / Intel）及 Linux x64 可使用独立工具链脚本。脚本从发行方下载 JDK 与 Android SDK 并验证下载校验和；`--accept-sdk-licenses` 表示接受 Android SDK 许可：

```sh
python3 scripts/setup-toolchain.py --directory .toolchain --accept-sdk-licenses
BANXU_TOOLCHAIN="$PWD/.toolchain" scripts/build.sh
```

前端测试需要 Node.js 18 或更高版本，无需安装 npm 依赖：

```sh
node --test tests/*.test.cjs
```

发布安装包需自行配置发布签名。不同签名的安装包不能直接覆盖安装；不要将 API Key 或签名私钥提交到仓库。

### 浏览器预览

```sh
python3 -m http.server 8765 --bind 127.0.0.1 --directory app/src/main/assets
```

打开 `http://127.0.0.1:8765`。预览使用独立的本地示例适配器，不读取手机通知、不触发系统提醒、不调用 DeepSeek、不保存 API Key。原生通知和小组件需在 Android 环境中验证。

## 项目结构

```text
app/src/main/java/cn/banxu/app/
  MainActivity.java                      WebView、JS 桥接和系统设置入口
  CaptureService.java                    通知接收、来源筛选和逐消息提取
  Store.java / Repository.java           SQLite、事项和处理队列
  SecureSettings.java                    配置和 API Key 加密
  DeepSeekClient.java                    DeepSeek HTTPS / JSON 客户端
  ChineseTimeParser.java / ExtractedTime.java  中文时间换算和摘录校验
  ExtractionJobService.java              后台队列与重试
  ReminderScheduler.java / ReminderReceiver.java / BootReceiver.java
  WidgetModel.java / WidgetUpdater.java  小组件筛选、分页和刷新
  TodoWidgetProvider.java / LeaveWidgetProvider.java
  WidgetListService.java                 Android 8–11 列表兼容
app/src/main/assets/                     应用界面和浏览器预览适配器
app/src/main/res/                        Android 图标、小组件和主题资源
app/src/test/                           Java 单元测试
tests/                                  前端逻辑和小组件入口测试
scripts/                                构建与工具链安装脚本
branding/                               图标设计记录
```

## 验证与当前限制

开发验证覆盖 Android 15 模拟器和 Android 16 小米 14。现有单元测试包含 33 项 Java 测试、23 项前端测试；小组件已有模拟器中的原生数据更新、筛选、分页、尺寸、深色和空状态检查。具体版本、方法和未覆盖范围见 [验证记录](VERIFICATION.md)。

- 通知访问不是聊天记录接口。未产生通知、隐藏正文、历史消息、图片、语音和附件无法从通知文字恢复；安卓保护的敏感内容也可能不可读。
- AI 和本地规则都可能漏识别或误识别；事项保留原文和时间推断依据，可编辑或删除。
- 已实现精确内容去重；跨群转发语义合并、自动关联改期或取消、学生与家长昵称名册尚未实现。
- 不同手机的后台、省电、通知和精确闹钟策略会影响接收、提醒及组件刷新。强制停止应用后，应重新打开一次恢复调度；长期灭屏与重启行为仍需更多设备验证。
- 删除原消息会一并删除关联事项并取消提醒；如只想停止处理一条消息，应留意“忽略”和“删除”的区别。
- 当前只有 Android 版；没有跨设备同步、数据导出或恢复。

## 版本记录

- **0.3.0：** 新增待办事项、请假返校两个原生桌面小组件及 App 内添加入口。
- **0.2.0：** 识别结果自动进入待办或返校跟踪；增强中文时间解析，旧版非示例“待确认”事项自动迁移。
- **0.1.1：** 统一“光合待办”品牌名称、图标与界面标识。
- **0.1.0：** 通知接收、本地保存、DeepSeek 提取、事项管理和系统提醒。

## 参与开发与许可

欢迎通过 Issue 报告问题或提交 Pull Request，请使用虚构消息复现问题，不要上传学生、家长的真实通知、API Key 或设备备份。开发约定见 [CONTRIBUTING.md](CONTRIBUTING.md)。

项目采用 [MIT License](LICENSE)。图标设计与生成提示词见 [品牌设计记录](branding/DESIGN.md)。

## 实现参考

- [Android 通知监听](https://developer.android.com/reference/android/service/notification/NotificationListenerService)
- [Android 闹钟与提醒](https://developer.android.com/develop/background-work/services/alarms)
- [Android 小组件](https://developer.android.com/develop/ui/views/appwidgets/overview)
- [AGP 8.9 构建兼容性](https://developer.android.com/build/releases/agp-8-9-0-release-notes)
- [DeepSeek API 入门](https://api-docs.deepseek.com/quick_start)
- [DeepSeek JSON 输出](https://api-docs.deepseek.com/guides/json_mode/)

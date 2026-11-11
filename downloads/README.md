# 真机测试安装包

## 最新：0.2.1 诊断版

[下载 0.2.1 APK](https://github.com/victorianoruben18-dot/ai-roundtable-android/raw/refs/heads/feat/android-mvp/downloads/ai-roundtable-0.2.1-debug.apk)

来源提交：`23ca8ce382d01fe4a1f24161fff309caff3b5bf6`；versionCode 3。签名与旧版相同，可覆盖更新。

本次隔离“自动发送诊断”与“只读诊断”，延长输入区采样，并记录控件的 SET_TEXT/CLICK 能力。增加可见、可用、非密码且明确支持 SET_TEXT 的唯一自定义输入框候选。根因仍需真机结果确认，不能宣称发送已成功。

安装后先将本服务关闭再开启。请用新讨论、ChatGPT 普通新聊天，确认一次并在目标 App 等待最多 15 秒。若失败，返回圆桌点“复制自动发送诊断”，把内容发来；无需先运行只读诊断。已经发送的问题不要重复发送。

23 项 JVM 测试、构建、静态检查及签名验证通过；未执行真机验证。

## 历史版本：0.2.0 诊断版

[下载 0.2.0 APK](https://github.com/victorianoruben18-dot/ai-roundtable-android/raw/refs/heads/feat/android-mvp/downloads/ai-roundtable-0.2-debug.apk)

来源代码提交：`eff2c57e6d5528ef9260081229ff2e30e8d7e56a`。最低 Android 8.0。版本号 0.2.0 / versionCode 2，已验证签名与 0.1.0 相同。可以直接覆盖更新以保留本地记录。

本版修正“输入前要求发送按钮存在”的问题，加入页面等待、输入后重新找发送按钮、分步骤诊断和只读诊断按钮。每次只尝试填入一次、点击一次；不确定结果继续暂停。尚未经真实手机验证，不代表已兼容 ChatGPT / DeepSeek。

请安装后确认首页显示“0.2.0 诊断版”，检查无障碍服务仍开启。先在 ChatGPT 选择“聊天”页并打开空白聊天，清除测试草稿。回圆桌停止旧讨论，创建只含 ChatGPT 的 1 轮讨论，待发送内容使用“请只回复：收到测试。”。确认自动发送一次，留在 ChatGPT 等最多 15 秒，再返回圆桌。若失败，点“复制诊断信息”，反馈是否输入、是否发送及诊断文本；若已发出，不要再次发送同一问题。

若只想采集结构，可点“只读页面诊断”，打开 ChatGPT 等约 3 秒后返回。此操作不填入、不发送，也不记录问题正文或聊天记录。

在 GitHub 文件页可使用 Download raw file（下载原始文件）。下载 APK 后在 Android 文件管理器中点击安装，按提示允许对应来源安装应用。APK 不需要解压。

## 旧版本

[0.1.0 APK](https://github.com/victorianoruben18-dot/ai-roundtable-android/raw/refs/heads/feat/android-mvp/downloads/ai-roundtable-0.1-debug.apk)，来源提交 `d1d7c8327e059b2698e59c7f0366715001b66a39`。旧版已报告空输入框时无法自动发送，保留供比对。

# Bark for Android

Bark 的 Android 版，推送走 FCM（Firebase Cloud Messaging），推送 API、参数、加密格式与 iOS 版一致，历史记录导出的 JSON 两端可以互相导入。针对小米 HyperOS 做了额外适配。

## 前提

- **自建 bark-server，并开启 FCM。** 公共服务器 `api.day.app` 只给 iOS 推送，Android 设备注册会失败。服务端需要包含 `device_type=android` 支持的版本，启动时加 `--fcm-credentials /path/service-account.json`（或环境变量 `BARK_SERVER_FCM_CREDENTIALS`）。
- **服务端和 App 用同一个 Firebase 项目。** FCM token 只对签发它的项目有效。
- 设备需要 Google 服务。国行 HyperOS 要在「设置 → 更多设置 → 谷歌基础服务」里打开，且中国大陆网络下 FCM 可能连不上；海外版 HyperOS 可直接使用。

## 配置 Firebase

1. 在 [Firebase 控制台](https://console.firebase.google.com/) 新建项目，添加 Android 应用，包名填 `me.fin.bark`（或你在 `app/build.gradle.kts` 里改过的 `applicationId`）。
2. 下载 `google-services.json` 放到 `android/app/`。该文件已加入 `.gitignore`。没有它也能编译，但 App 收不到推送，首页会提示「未配置 Firebase」。
3. 「项目设置 → 服务账号 → 生成新的私钥」，得到给服务端用的 service account JSON。

## 构建

需要 JDK 21 和 Android SDK（compileSdk 37）。

```bash
cd android
./gradlew :app:assembleDebug          # 产物：app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:testDebugUnitTest      # 单元测试
./gradlew :app:lintDebug
```

想让新安装的 App 默认指向你的服务器，可以在构建时传入：

```bash
./gradlew :app:assembleDebug -Pbark.defaultServer=https://bark.example.com
```

iOS 的多语言文案改动后，运行 `python3 -I tools/gen_strings.py` 重新生成 `strings_ios.xml`。Android 独有的文案在 `values*/strings.xml` 里手动维护。

## HyperOS 设置建议

App 被强行停止、或被手机管家清理后，FCM 无法唤醒它，推送会延迟到下次打开 App。小米设备首页有「HyperOS 设置检查」卡片，可以一键跳到对应设置：

- 允许自启动
- 省电策略设为「无限制」
- 打开悬浮通知、锁屏通知（国行 HyperOS 默认关闭第三方应用的悬浮通知）
- 需要重要警告、时效性通知绕过勿扰时，授予勿扰权限

另外建议在最近任务里锁定 Bark。

焦点通知和超级岛：`level=timeSensitive`、`level=critical` 和 `call=1` 的推送会附带 HyperOS 焦点通知参数。只有经过小米白名单审核的应用才会显示成焦点通知；未审核时系统忽略这些参数，按普通通知显示。

## 与 iOS 的差异

- **铃声：** Android 8 以后通知声音按渠道固定，所以渠道本身静音，由 App 自己播放 `sound` 指定的铃声。普通推送会遵守静音模式和勿扰；`critical` 用闹钟音频通道播放，静音模式下也会响，`volume` 是相对闹钟音量的比例。
- **自定义铃声：** 支持任意 Android 能播放的音频格式，不需要转成 caf。
- **副标题：** Android 通知没有副标题行，副标题以粗体显示在正文第一行。
- **图标：** `icon` 显示为通知右侧的大图标。
- **暂未实现：** 平板/折叠屏双栏布局、手表端、快捷方式发推送、捐赠、代码块高亮、小组件按分组筛选。

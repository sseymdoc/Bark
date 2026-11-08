# Bark for Android

Bark 的 Android 版，推送走 FCM（Firebase Cloud Messaging），推送 API、参数、加密格式与 iOS 版一致，历史记录导出的 JSON 两端可以互相导入。针对小米 HyperOS 做了额外适配。

## 前提

- **自建 bark-server，并开启 FCM。** 公共服务器 `api.day.app` 只给 iOS 推送，Android 设备注册会失败。服务端需要包含 `device_type=android` 支持的版本，启动时加 `--fcm-credentials /path/service-account.json`（或环境变量 `BARK_SERVER_FCM_CREDENTIALS`）。
- **服务端和 App 用同一个 Firebase 项目。** FCM token 只对签发它的项目有效。
- FCM 需要 Google 服务。国行 HyperOS 要在「设置 → 更多设置 → 谷歌基础服务」里打开，且中国大陆网络下 FCM 可能连不上；收不到时由下面的 SSE 兜底。

## SSE 兜底

服务端会把每条 Android 推送同时发给 FCM 和 SSE（Server-Sent Events）。App 在前台服务里和每个服务器保持一条 `GET /stream` 长连接，两边哪条先到就显示哪条，后到的按 `_msgid` 丢弃。

- 默认打开，可在「设置 → 后台连接」关闭。打开时通知栏有一条常驻通知（「后台连接」类别，可在系统设置里折叠）。
- 没有 Google 服务、拿不到 FCM token 时，App 用 `sse:` 开头的 token 注册，之后只走 SSE。
- 断线期间的推送由服务端暂存（每台设备最近 24 小时、最多 100 条），重连后补发。暂存在内存里，服务端重启会丢。
- 连接需要 key 加上 App 注册时的 device token，只知道 key 的人读不到推送内容。
- 需要新版 bark-server（`/info` 里有 `"sse": true`）；旧服务端返回 404，App 每 30 分钟重试一次，不影响 FCM。
- 支持 `http://` 的自建服务器（和 iOS 一样允许明文 HTTP）。

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

## 监控

「监控」tab 的数据来自「服务器」页的默认服务器，不需要另配地址或密钥：服务端在同一个 HTTPS 端口上同时提供 Bark、token-monitor Hub 和 Beszel 快照时，添加这个地址（设为默认）即可。

- App 请求 `GET /api/stats/stream`（SSE）、`GET /api/history` 和 `GET /api/beszel/snapshot`（每 30 秒），带请求头 `X-Bark-Key` 和 `X-Bark-Device-Token`，即 `/stream` 用的那组凭据。服务端核对后再转给 Hub，Hub Secret 只留在服务端。
- 只支持 HTTPS 服务器。普通 bark-server 把 `/api/...` 当成推送，返回 400，监控 tab 会提示该服务器未提供监控；返回 401/403 时提示设备未被认可。
- 快照或样本超过 3 分钟显示过期，未采集的指标显示 `—`；快照不带 `serverTime` 时按响应的 `Date` 头计算。
- 旧版保存的 Hub 地址、加密的 Hub Secret 和 Beszel 登录信息在升级后首次启动时删除。

## HyperOS 设置建议

App 被强行停止、或被手机管家清理后，FCM 无法唤醒它，推送会延迟到下次打开 App。小米设备首页有「HyperOS 设置检查」卡片，可以一键跳到对应设置：

- 允许自启动
- 省电策略设为「无限制」
- 打开悬浮通知、锁屏通知（国行 HyperOS 默认关闭第三方应用的悬浮通知）
- 需要重要警告、时效性通知绕过勿扰时，授予勿扰权限

另外建议在最近任务里锁定 Bark。

焦点通知和超级岛：`level=timeSensitive`、`level=critical` 和 `call=1` 的推送会附带 HyperOS 焦点通知参数。只有经过小米白名单审核的应用才会显示成焦点通知；未审核时系统忽略这些参数，按普通通知显示。

## 与 iOS 的差异

- **铃声：** Android 8 以后通知声音按渠道固定，所以渠道本身静音，由 App 自己播放 `sound` 指定的铃声。普通推送会遵守静音模式和勿扰；`critical` 用闹钟音频通道播放，静音模式下也会响，`volume` 是相对闹钟音量的比例。如果某个通知类别在系统设置里被设了提示音（HyperOS 改过通知设置后常见），这个类别就只响系统提示音，Bark 不再播放 `sound` 指定的铃声，避免响两次；想用 `sound`，把该类别的提示音设为「无」。
- **自定义铃声：** 支持任意 Android 能播放的音频格式，不需要转成 caf。
- **副标题：** Android 通知没有副标题行，副标题以粗体显示在正文第一行。
- **图标：** `icon` 显示为通知右侧的大图标。
- **暂未实现：** 平板/折叠屏双栏布局、手表端、快捷方式发推送、捐赠、代码块高亮、小组件按分组筛选。

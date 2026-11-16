# 课表 · 南职版（Java / Android）

一个**无广告、完全免费**的 Android 课表应用，默认适配南宁职业技术大学教务系统，并额外集成了学校「第二课堂」的常用功能。

> 本项目仅供学习与技术交流，**禁止任何形式的商业售卖**。任何人以任何名义向你售卖本软件或本软件的功能，都是骗子。
> 遇到问题或有想法，欢迎提 Issue，也欢迎直接 PR。

---

## 功能

### 课表
- 输入教务账号密码即可一键获取课表，支持导入 PyQt 课表 JSON
- 左右滑动切周，滑动时相邻周实时跟手显示
- 空课时长按可「添加课程」，或直接查这个时段能报的二课活动
- 每节课前 30 分钟提醒，可在设置里关闭

### 第二课堂
- **空课找二课**：在课表空档长按 → 「寻找二课活动」，按**活动时间**筛出这个空档能参加的活动
- **活动列表**：模块 / 状态 / 名单 / 名额 四个筛选，按截止时间或积分排序，支持搜索
- **状态标签**：报名中、审核制、有名单、已无名额、剩 N 名额、模块差 N 分、距截止 N 小时
- **卡片配色**：有名单淡紫、无名额淡红，一眼分辨
- **项目**页：可申请的二课项目
- **我的**页：毕业积分进度（各模块「我的 / 下限 / 学院平均」）、我的活动（报名中 / 未开始 / 活动中 / 已结束）
- **签到签退 / 取消报名**：跳转官方页面完成，需要定位的签到走系统定位
- **时间提醒**：给已报名活动设活动前 30 分钟通知
- **我的学习课程**：内置官方课程页，可切换「手动 / 自动」模式，自动完成观看计时与评价

### 更新
- 设置页显示当前版本，提供「检查更新」按钮
- 每次打开应用自动检查（多镜像回退：jsDelivr → gitmirror → GitHub raw）
- 完全免费：仓库里一个 `update.json` 即可作为更新源，不需要服务器

---

## 下载安装

到 [Releases](../../releases/latest) 下载最新 APK（`kebiao-vX.YZ.apk`），Android 8.0（API 26）及以上可直接安装。

覆盖安装会**保留**账号、设置和登录状态（同一签名）。

---

## 构建

1. 用 Android Studio 打开本目录，按提示配置本机 Android SDK（IDE 会生成不入库的 `local.properties`）
2. 使用 JDK 17+ 执行：

```powershell
.\gradlew.bat :app:assembleDebug
```

产物：`app/build/outputs/apk/debug/app-debug.apk`

正式签名（可选）：在根目录建不入库的 `key.properties`：

```properties
storeFile=C:/path/to/your-release-key.jks
storePassword=你的密钥库密码
keyAlias=你的密钥别名
keyPassword=你的密钥密码
```

再执行 `.\gradlew.bat :app:assembleRelease`。

---

## 更新机制（自建免费更新源）

仓库根目录的 `update.json` 就是更新源：

```json
{
  "versionCode": 113,
  "versionName": "1.13",
  "url": "https://github.com/ZT-chen666/KeBiao/releases/download/v1.13/kebiao-v1.13.apk",
  "mirror": "https://ghproxy.net/https://github.com/ZT-chen666/KeBiao/releases/download/v1.13/kebiao-v1.13.apk",
  "notes": "本次更新说明"
}
```

客户端读取顺序：`cdn.jsdelivr.net` → `raw.gitmirror.com` → `raw.githubusercontent.com`，任一成功即用；`mirror` 用于国内下载备用。

发版用仓库里的 `publish.ps1`：

```powershell
$env:GITHUB_TOKEN = "ghp_xxx"
.\publish.ps1 -Apk .\kebiao-v1.13.apk -Version 1.13 -VersionCode 113 -Notes "更新说明"
```

---

## 参与开发

详见 [CONTRIBUTING.md](CONTRIBUTING.md)。简单说：

- **报 bug / 提需求**：开 Issue，说清机型、系统版本、复现步骤，最好带截图
- **改代码**：Fork → 建分支 → 提交 → 开 PR
- **只提想法**：也欢迎，直接在 Issue 里说就行

## 联系方式

- 作者一：2406609483@qq.com
- 作者二：3188615046@qq.com

## 许可

[MIT](LICENSE)。使用本仓库代码请保留原作者信息。

## 隐私

见 [PRIVACY.md](PRIVACY.md)。账号密码只在你自己的设备上输入，只发送给你所配置的学校系统，不经过任何第三方服务器。

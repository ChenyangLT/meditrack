# 药准时 / MediTrack

离线优先的用药提醒与记录应用（Android）。**到点提醒；没看见，就再提醒一次。**

[官网](https://chenyanglt.github.io/meditrack/) · [下载 APK](https://github.com/ChenyangLT/meditrack/releases) · [更新日志](https://github.com/ChenyangLT/meditrack/releases) · MIT

> **免责声明**：本应用只做用药记录与提醒，**不提供任何诊断、治疗或用药建议**。请严格遵照医生或药师的处方服药；任何剂量调整、停药或换药请先咨询专业医疗人员。提醒功能依赖系统闹钟与通知服务，在省电模式、后台限制或权限被收回等情况下可能延迟或失效，请勿把它当作唯一的用药保障手段。

## 功能

| | |
| --- | --- |
| **到点提醒** | 精确闹钟；可选「闹钟级提醒」（Android 唯一不受省电模式影响的闹钟）；后台守护服务防止「打开应用才提醒」；每 15 分钟一次心跳自检，任何一个闹钟被系统丢掉最多延误一个周期 |
| **解锁补提醒** | 解锁或回到应用时，自动检查所有「已过时间、还没记录」的药（含已判未服药、只吃了一半），合并成一条提醒；次数 1–10，**从第一次解锁提醒开始算** |
| **免打扰时段** | 一个时间段内不会有声音、震动，也不会在锁屏上弹出；默认顺延到时段结束后再提醒一次，也可只在通知栏静默显示 |
| **服药记录** | 一个药可挂多个时间；六种重复规则（每天 / 隔天 / 每周指定 / 每 N 天 / 吃 X 天停 Y 天 / 每月几号）；数量步进（液体 0.5）；库存与低库存提醒 |
| **历史与统计** | 月历状态点、依从率、连续服药天数、按药筛选 |
| **桌面小组件** | 单一可缩放条目，按紧急度排序（未服药 &gt; 即将服用 &gt; 稍后 &gt; 已服用），刷新间隔 10 秒–10 分钟 |
| **适老与无障碍** | 大字（系统 × 应用总字号封顶 1.6×，布局自动换行而不是压缩）、高对比、简化模式、一键适老预设 |
| **提醒声音** | 五种内置铃声（清铃 / 三音上行 / 柔和木琴 / 双哔 / 渐强钟声）随安装包分发，**由应用用闹钟音频流自己播放**，不依赖任何品牌的系统铃声库；配合震动，vivo / 小米 / OPPO 行为一致 |
| **更新检查** | 启动时自动向 GitHub 查一次新版本（12 小时一次，可关闭），有新版本弹窗提示但**不强制更新**；也可手动「立即检查更新」 |
| **数据自主** | 备份文件夹**可选**（「下载」/ 网盘 / SD 卡），应用内直接列出这些备份、**最新在最上面**、点一条即导入；换文件夹时已有备份自动复制过去。另有 CSV 导出可交给医生 |

## 隐私

数据只写在本机数据库：没有账号、没有云同步、没有统计 SDK。

**全应用只有一处联网。** 从 1.8.0 起，为了告诉你有没有新版本，应用会依次尝试下面三个公开地址，**拿到一个就不再试下一个**：

```
GET https://chenyanglt.github.io/meditrack/version.json                                    # 自家站点的清单
GET https://cdn.jsdelivr.net/gh/ChenyangLT/meditrack@main/docs/version.json                # 同一份文件的镜像
GET https://api.github.com/repos/ChenyangLT/meditrack/releases/latest                      # GitHub 官方接口
```

三个地址都是公开的 GET：没有参数、没有请求体、没有标识符，也不含任何关于你和你的用药的内容；下载更新由浏览器完成，应用自己不会下载任何东西。不想要它联网就在 **设置 → 更新** 里关掉自动检查。

安装包权限可以用 `aapt2 dump permissions` 自己核对：除更新检查所需的 `INTERNET`，其余全是闹钟、通知、震动、生物识别一类本地权限。

## 安装

从 [Releases](https://github.com/ChenyangLT/meditrack/releases) 下载 APK（Android 8.0+，约 3.4 MB）。

安装包使用 Android 通用调试签名，因此可以覆盖安装并保留数据；调试签名是公开的，请把它当作自用与测试的分发方式。

## 开发

```powershell
gradle :app:testDebugUnitTest                # 278 个单元测试
gradle :app:assembleDebug :app:assembleRelease
python tools/verify_migration.py             # 数据库迁移：同一库 v1→v4 逐列比对 Room schema
python tools/verify_dao_sql.py              # 全部 @Query 在真实 schema 上编译 + 行为断言
python tools/generate_tones.py               # 重新合成五个内置铃声（numpy + ffmpeg，OGG 共约 40 KB）
python tools/verify_release_reflection.py    # release APK 的 dex 里，Gson 注解与 DTO 类名是否都还在
python tools/update_version_manifest.py      # 发布后更新 docs/version.json（应用检查更新的第一顺位地址）
```

> `verify_release_reflection.py` 不是可选项：R8 会删掉只被反射使用的 `@SerializedName`，一旦漏了 keep 规则，
> 备份与更新检查会在**正式版上静默解析失败**（1.8.0 就栽在这里），而单元测试和 debug 构建都发现不了。

- **架构**：数据库是唯一事实来源，闹钟只是提示；每次触发（闹钟 / 心跳 / 开机 / 启动 / 解锁）都从数据库重算并重排全部提醒，因此任何一条路径丢了闹钟都能自愈
- **数据库**：Room，当前 schema v4，迁移纯增量、不丢历史
- **可诊断**：每次提醒决策都记录在 `reminder_events`（触发原因 / 投递或跳过 / 跳过原因 / 系统延迟毫秒），设置页的「最近的提醒决策」可直接回答「为什么 8 点没提醒我」

## 文档

| 文档 | 内容 |
| --- | --- |
| [工程说明](docs/工程说明.md) | 完整设计说明：需求取舍、提醒管线、可靠性与纠错能力、测试与验证方式（原 README 全文） |
| [解锁补提醒说明](docs/解锁补提醒说明.md) | 「熄屏期间提醒被抵掉」的根因分析与真机实测记录 |
| [通知重写说明](docs/通知重写说明.md) | 通知通道、自检与前台守护的历史背景 |
| [官网源码](docs/index.html) | 单页站点，不引用任何第三方脚本、字体或统计代码 |

## 许可

MIT © ChenyangLT

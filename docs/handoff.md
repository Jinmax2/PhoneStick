# PhoneStick 开发交接（更新至 2.4.3）

> 设备：Redmi K20 Pro (raphael)，Android 17 / SDK 37，kernel 4.14.357，Magisk 31.1
> 工程：`PhoneStick/` 2.4.3（versionCode 25）
> 交付：APK 只发 GitHub Releases（仓库内不存 APK，也没有本地 `交付/` 目录）
> 配套：`README.md`（总览）、`docs/browse.md`（镜像内文件浏览的方案与验证）。
> 注意：早年的 `动态镜像研究.md` / `排查报告.md` / `Windows迁移调试指南.md` /
> `进度与交接.md` 是当时交付包里的**外部文件，没有进仓库**，本文引用它们的地方
> 都可以在本文件与 `docs/troubleshooting.md`、`docs/windows.md` 里找到结论。

---

## 零、当前状态（2.4.3）

| 项目 | 状态 |
|---|---|
| 挂载 / 卸载 U 盘 | ✅ 真机验证 |
| 挂载状态检测 | ✅ 2.4.3 起校验「function 已链进 configuration + UDC 已绑定」，`lun.0/file` 残留不再误报已挂载（原 P2） |
| 稀疏镜像创建（含裸盘） | ✅ 4GB 约 2 秒，8GB 实测 126MB 占用 |
| 容量 / 实际占用显示 | ✅ `8.00 GB · 已用 264 MB` / `2.00 GB · 未写入` |
| 释放未使用空间 | ✅ FAT32 / exFAT / NTFS / ext4，MBR / GPT / 无分区表，**多分区逐个处理** |
| 镜像内文件浏览 | ✅ 真机验证（Xiaomi 15 / Android 17 / kernel 6.12）：FAT32 / exFAT / ext4 挂载读写、GPT 与超级软盘布局、NTFS 无驱动时内置解析器只读、U 盘占用时卸载后可浏览且退出自动恢复；PC 侧 16 个单测 |
| 中文界面 | ✅ 布局 + 对话框 + 菜单 + 通知 |
| 状态栏对比度 | ✅ 浅色模式深色图标、夜间模式深色底浅色图标，显式声明不再依赖系统强制反转（MI 6 双模式回归） |
| 默认读写挂载 | ✅ `switchReadOnly` 默认关闭 |
| 自建 ConfigFS gadget 兜底 | ❌ 已删除（原 Strategy 2，实测从未成功过） |
| F-Droid | ❌ 已放弃（元数据停更指上游，2.4.3 起删除，只从 GitHub Releases 发 APK） |
| CI | ✅ GitHub Actions：JVM 单测（含测试镜像）+ assembleDebug |
| 遗留 | 仅 arm64（有意为之，项目只面向手机）；扩展/逻辑分区不展开；浏览多分区只开最大分区 |

### 版本历史

| 版本 | 主要改动 |
|---|---|
| 2.3.1 | 镜像移到 `/data/media/0`（SELinux 可写）、MBR 分区数动态计算、格式化失败上报、Windows 驱动冲突定位 |
| 2.3.2 | 稀疏镜像（`truncate`），创建秒级、解决 >2GB OOM；显示实际占用 |
| 2.3.3 | 中文界面、默认读写、**释放未使用空间**（FAT32，`pstrim` 打洞） |
| 2.3.4 | 修复文件管理器永远显示旧大小（MediaStore/FUSE 滞后） |
| 2.3.5 | 修复裸盘镜像不被识别为稀疏（`used > 0` 判定错误） |
| 2.3.6 | 回收支持 exFAT / NTFS / ext4（初版，布局识别不全） |
| 2.3.7 | 回收补全 GPT / 无分区表支持，修正 ext4 位图寻址 |
| 2.4.0 | 多分区镜像逐个回收（GPT 全条目 / MBR 4 主分区）+ 尾部零块回收；回收入口对所有镜像开放 |
| 2.4.1 | ext4 `BLOCK_UNINIT` 组参与回收 + 零块扫描（inode 表/保留 GDT 可压缩）；新增 --stats/--map 诊断 |
| 2.4.2 | **镜像内文件浏览**：查看/添加/删除/读出/新建/重命名；内核 loop 挂载读写 + 无驱动时内置 NTFS 只读解析器；与 USB gadget 互斥处理（退出后自动恢复挂载） |
| 2.4.3 | 挂载状态误报修复（P2）；删除 Strategy 2 兜底与死代码；F-Droid 元数据移除；GitHub Actions CI；pstrim 主机侧回归脚本；删 `tools/pstrim.c` 旧副本；浅色模式状态栏图标反色修复 |

> 以下「一 ～ 十二」按时间顺序保留了每一轮的排查过程与实测数据，
> 结论仍然有效，但**当前状态以本节为准**。

---

## 一、这一轮解决的核心问题

**Windows 主机完全看不到虚拟 U 盘，而 Linux / macOS 正常。**

根因不在手机、也不在 PhoneStick：手机把 `mass_storage,adb` 组合成
`05C6:9015`，大容量存储接口落在 `MI_01`（class 08/06/50）；这台 Windows 装了
手机厂的 ADB 驱动包，其中 OPLUS 的 `oem213.inf` 有一条按**硬件 ID**
认领 `USB\VID_05C6&PID_9015&MI_01` 的规则，于是 Windows 用 WinUSB 打开了
这个 MS 接口，USBSTOR 从未 attach → 没有磁盘。Linux/macOS 按接口类匹配，
所以同一份描述符在那边正常。

已在**主机侧**修复：备份并移除该驱动包后，`MI_01` 重新落到 `usbstor.inf`，
盘符立刻出现，adb 不受影响。

完整证据、修复命令、以及"为什么 App 侧绕不开"见
**`排查报告.md` 第十五节**；操作版见 **`Windows迁移调试指南.md` 5.5 节**。

---

## 二、当前验证状态

| 项目 | 状态 | 证据 |
|---|---|---|
| 手机侧 LUN / UDC / 配置链接 | ✅ | `lun.0/file` 有值，UDC `configured`，`function2 -> mass_storage.0` |
| Windows 枚举出 MS 接口 | ✅ | `USB\VID_05C6&PID_9015&MI_01`，compatible ID `Class_08&SubClass_06&Prot_50` |
| 修复后接口绑定 | ✅ | `Service=USBSTOR`，`DriverInfPath=usbstor.inf`（Microsoft） |
| 主机出盘 | ✅ | `E:` / `PSTEST` / FAT32 / 1021 MB |
| 主机写入真正落盘 | ✅ | 写入后手机侧 `grep -a` 镜像原始字节命中写入内容 |
| adb 不受影响 | ✅ | 修复全程 `adb devices` 正常 |
| 修复是否持久 | ✅ | 手机侧强制 UDC 解绑/重绑（完整重新枚举）后，盘符与 adb 都恢复 |
| App UI 的卸载 → 重挂流程 | ⏳ 未实测 | 本轮只做了 shell 层重新枚举 |
| App 内建「Create Blank Image」GUI | ⏳ 未实测（P3） | 底层 shell 已单独验证过 |
| SAF `content://` 导入 | ⏳ 未实测（P4） | — |
| >2GB 镜像 OOM | ⏳ 未实测（P5） | — |
| 卸载后 USB 配置恢复 | ⏳ 待确认（P6） | — |

---

## 三、这台 Windows 机器现在的状态（重要）

- `oem213.inf`（OPLUS, Inc. 的 Android ADB 驱动包）**已从驱动仓库删除**。
- 删除前已导出备份到 `tools/driver-backup/`（`android_winusb.inf` + `.cat` + 两个 DLL）。
- 需要还原时（例如以后接 OPPO / 一加 手机提示缺驱动）：

  ```powershell
  .\tools\fix-windows-usb-driver.ps1 -Restore
  ```

- 这台机器上另一个 INF `oem106.inf`（vivo, Inc.）负责 `MI_00` 的 ADB，
  **没有动**，所以删 OPLUS 那份不影响 adb。
- 手机已经从这台电脑拔掉；下次插上时应当直接出盘，不需要再做任何操作。

---

## 四、遗留问题与新发现

### 已改动代码：无

本轮**没有修改 `PhoneStick/` 源码**，`git status` 与本轮开始时完全一致
（工作区里原本就有那批 2.3.1 改动：`ImageStore.kt` 未跟踪、`.gitattributes` 未跟踪，
以及若干 M 文件，其中 `usbmountr.txt` / `gradlew` 的差异属打包与行尾产物，属正常）。

### 新发现（都已写进 `排查报告.md` 第十五节）

| # | 发现 | 影响 |
|---|---|---|
| A | App 自带的 `swy` gadget（Strategy 2）在 Android 17 上执行 `ln -s functions/mass_storage.0 configs/c.1/...` 报 `Operation not permitted` | Strategy 2 在这台设备上**根本挂不起来**，它作为 Strategy 1 的兜底是坏的，排查时会误导 |
| B | `setprop sys.usb.config mass_storage`（单 MS，理论上 `05C6:F000`）被 gadget HAL 忽略，主机侧仍是 `9015 + adb + MI_01` | 不能靠"去掉 adb"绕开 Windows 驱动冲突 |
| C | `/config/usb_gadget/g1/idProduct` 用 magisk 域写入返回 `Permission denied` | 不能改 PID 绕开 OEM INF |
| D | 这台机器 `sys.usb.state` **恒为空**（挂载成功时也空） | 不要用它判断挂载状态；`getMountStatus()` 继续用 `lun.0/file` + 配置链接即可 |
| E | `adb shell su -c "a; b; c"` 里分号后的命令经常以 `shell` 域执行，不是 root | 排查时曾误判成"SELinux 不让 root 读 configfs"；见指南 6.5 |
| F | 手机侧 `mount -t vfat` 回读正在被 gadget 使用的镜像会 `EINVAL`（整盘和 offset 都不行） | 校验"写入是否落盘"用 `grep -a` 镜像原始字节更可靠 |

### 待办（建议顺序）

1. ~~**P2**：`getMountStatus()` 只检查 `lun.0/file` 非空就报告已挂载~~ → **2.4.3 已修**：
   校验「function 链进 configuration（链接名或解析目标）+ gadget 绑定了 UDC」，
   老sysfs 路径看 `android0/enable`。`sys.usb.state` 依旧不参与判定（发现 D）。
2. ~~**Strategy 2 决策**~~ → **2.4.3 已删**：实测从未成功过（发现 A），兜底比主路径更糟。
   `unmountImage` 保留对老版本 swy 残留目录的清理。
3. **Windows 提示**：App 无法判断主机 OS，但可以在挂载成功后提示
   "如果电脑上没有盘，运行 `tools\diagnose-windows-usb.ps1`"。
4. 手机 `/data/local/tmp` 还有上一轮遗留的调试文件（`probe*.log`、`runfix.sh`、
   `_mbr.bin`、`pscheck/` 等）。`tools/cleanup-device.sh` 已扩充覆盖这些名字，
   下次连上手机跑一次即可：

   ```bash
   adb push tools/cleanup-device.sh /data/local/tmp/
   adb shell su -c "sh /data/local/tmp/cleanup-device.sh -n"   # 先预览
   adb shell su -c "sh /data/local/tmp/cleanup-device.sh"
   ```

   `phonestick_drive.img.bak` 和 `phonestick_prefs_backup.xml` 是备份，脚本**故意保留**。

---

## 五、回到 Linux 开发机后怎么继续

```bash
unzip PhoneStick-2.3.1-dev-20261026.zip -d phonestick-dev
cd phonestick-dev

# Git 仓库是完整的（浅克隆，1 个提交），可直接看 diff / 打补丁
git -C PhoneStick status
git -C PhoneStick diff --stat

# Windows 打包会丢可执行位，务必先补
chmod +x PhoneStick/gradlew

# 构建（首次要能访问 jitpack.io）
cd PhoneStick
./gradlew assembleRelease
```

注意：

- `PhoneStick/local.properties` **不在包里**，Linux 上如果没设 `ANDROID_HOME`
  需要自己建：`sdk.dir=/opt/android-sdk` 之类。
- 装机前确认签名：官方 2.3.0 与本仓库 `app/release.jks` 不同，
  `adb install -r` 会失败，先 `adb uninstall mingww64.phonestick`。
- `tools/*.ps1` 是 Windows 专用，Linux 上用不到；
  `tools/fix-windows-usb-driver.ps1` 的备份目录 `driver-backup/`
  没有打进压缩包（属于那台 Windows 机器，留在原机即可）。
- shell 脚本均为 LF；`.gitattributes` 已固定 `*.sh text eol=lf`。

### 三条快速回归（在 Windows 上）

```powershell
powershell -ExecutionPolicy Bypass -File .\tools\diagnose-windows-usb.ps1   # 应输出 drive E: PSTEST
adb shell su -c "cat /config/usb_gadget/g1/functions/mass_storage.0/lun.0/file"
Get-Disk | Where-Object BusType -eq 'USB'
```

> 双击/直接执行 `.ps1` 可能被默认执行策略拦住，加
> `powershell -ExecutionPolicy Bypass -File` 前缀最稳。

---

## 六、本轮文件变更

| 文件 | 变更 |
|---|---|
| `tools/diagnose-windows-usb.ps1` | **新增**：只读诊断（adb → LUN → 配置链接 → MS 接口绑定 → 盘符） |
| `tools/fix-windows-usb-driver.ps1` | **新增**：备份 + 移除抢占 MS 接口的 OEM ADB 驱动包；`-Restore` 还原 |
| `tools/driver-backup/` | **新增**：上述脚本导出的驱动包备份（未打进压缩包） |
| `tools/cleanup-device.sh` | 扩充清理名单（probe*、runfix.sh、pscheck 等），支持目录与 configfs 探针目录，备份文件显式保留 |
| `Windows迁移调试指南.md` | 新增 5.5 节；判读表 / 故障对照表 / 工具清单 / P1 更新；6.5 增加 `su -c` 分号坑 |
| `排查报告.md` | 新增第十五节（根因、证据、修复、三条绕不开的路）；第十三节加 P7 |
| `进度与交接.md` | **新增**：本文件 |
| `PhoneStick/` | 未改动 |
| `交付/` | 未改动 |

---

## 七、时间线（本轮）

| 时间 | 事件 |
|---|---|
| 11:20:54 | App 走 Strategy 1 挂载成功（`mass_storage,adb`，LUN 指向 qqq.img） |
| 11:21:40 | Windows 枚举 `05C6:9015`，`MI_01` 被 `oem213.inf` 绑成 WinUSB |
| 11:24–11:40 | 排查：确认接口类、INF 规则、HAL/ConfigFS 限制 |
| 11:47 | 实测 Strategy 2（`swy`）失败：`ln -s` EPERM |
| 11:50 | 实测 `mass_storage` 单组合被 HAL 忽略 |
| 11:57 | `fix-windows-usb-driver.ps1` 执行：备份 → 删除 `oem213.inf` → 重扫 |
| 11:58 | 盘符 `E:`（PSTEST，FAT32，1021MB）出现，adb 正常 |
| 11:59 | 主机写入 → 手机侧 grep 镜像，确认落盘 |
| 12:02 | UDC 解绑/重绑后重新枚举，盘符与 adb 均恢复（修复持久） |
| 12:10 | 清理临时文件、补文档、打包 |

---

## 八、2.3.2：稀疏镜像（2026-10-26 下午，已真机验证）

### 做了什么

把镜像创建从 `fallocate`/分块 `dd` 改成 **`truncate` 稀疏分配**：
逻辑容量还是用户选的大小，物理占用随主机写入量增长。
`fallocate`/`dd` 保留为「取消勾选稀疏」时的预分配路径。

| 文件 | 改动 |
|---|---|
| `ImageCreator.kt` | 新增 `sparse` 参数，默认 `truncate -s <bytes>`，失败回落预分配 |
| `ImageStore.kt` | `Info` 增加 `allocatedBlocks`/`usedBytes`/`isSparse`（`stat %b`），新增 `formatSizeWithUsage()` |
| `ImageFilesAdapter.kt` / `MainActivity.kt` | 显示「容量 · 实际占用」 |
| `ImageChooserActivity.kt` / `dialog_create_image.xml` | 开关改为 "Sparse image (allocate on demand)"，加说明文案 |
| `app/build.gradle` | 2.3.2 / versionCode 14 |

### 验证结果（同一台 K20 Pro）

- 8GB / FAT32 稀疏镜像：点击创建后 **约 2 秒**完成，`du` 仅 **126MB**（FAT 表成本）
- UI：`8.00 GB · 126.17 MB used`
- App 内挂载 → 主机 `sdb` 8.00 GiB、`sdb1` vfat；写 100MB 用时 5.4s，md5 一致，手机 `du` 精确 +100MB
- **P5（>2GB 镜像 OOM）关闭**
- 测试后已还原：镜像删除、LUN 空、`sys.usb.config=adb`

### 产物

- `交付/PhoneStick-2.3.2-sparse.apk`（已装机验证，vc14）
- `交付/源码改动-2.3.2.patch`（含 2.3.1 以来的全部改动，12 个文件）
- `动态镜像研究.md` 第八节：实现细节与验证数据；附录含 qcow2/VDI 不可行的完整论证

### 注意

- 只读开关默认 `checked=true`（既有行为），App 挂出来的盘默认写保护。
- toybox `dd` 没有 `conv=sparse`，手机侧复制镜像一定膨胀；保稀疏请在 PC 上做
  （`cp --sparse=always` / `rsync -S` / `qemu-img convert -O raw`）。

---

## 九、2.3.3：中文化 + 默认读写 + 空间回收（2026-10-26 晚）

### 1. 默认读写

`activity_main.xml` 的 `switchReadOnly` 从 `checked="true"` 改为 `false`，
App 挂载出来的盘默认可写（LUN `ro=0`）。其他语言/逻辑不变。

### 2. 中文化

- `res/values/strings.xml` 全部译为中文（key 不变，de/fr/it/lt 目录未动）；
- 各 layout 里写死的英文（创建/导入/格式化按钮、Splash 的 Root 提示、
  创建对话框的预设容量/文件系统/稀疏开关、列表 "ACTIVE"）全部换成中文；
- Kotlin 里的对话框、Snackbar、菜单、通知文案（`ImageCreator`、`ImageChooserActivity`、
  `ImageFilesAdapter`、`UsbGadgetController`、`MainActivity`）逐条中文化。
- 实测截图文字：`USB 大容量存储状态`、`只读模式`、`挂载 U 盘`、`选择或创建磁盘镜像`、
  `4.00 GB · 63.09 MB used`。

### 3. 释放未使用空间（新功能）

镜像列表 → 每项右上角菜单 → **释放未使用空间**（仅稀疏镜像显示）。

实现：`tools/pstrim.c` 交叉编译成静态 aarch64（700KB），打进 `assets/pstrim`，
运行时释放到 `/data/local/tmp/pstrim` 以 root 执行；解析 MBR+FAT32，
找出 FAT 里值为 0 的空闲簇，按 4K 对齐 `fallocate(PUNCH_HOLE)`，
顺带打掉头部全零 4K 块。不重写数据、不需要额外空间。

实测（4GB 稀疏镜像）：
- 写入 400MB 后删 300MB → 仍占 463.49MB（问题复现）
- 菜单执行后 → **102.06MB**（剩 100MB 保留文件 + FAT 元数据）
- 重新挂载：保留文件 md5 一致，fsck 结构正常

限制：只支持 FAT32；镜像挂载中会提示先卸载。

### 产物

- `交付/PhoneStick-2.3.3-cn.apk`（vc15，已装机）
- `交付/源码改动-2.3.3.patch`（18 个文件，含 2.3.1 以来全部改动）
- `动态镜像研究.md` 第九节：回收方案与被否掉的替代实现

> 注意：patch 不含 `assets/pstrim`（二进制）。重新编译命令：
> `aarch64-linux-gnu-gcc -static -O2 -o pstrim pstrim.c`（源在 `tools/pstrim.c`），
> 产物放到 `app/src/main/assets/pstrim`。

---

## 十、2.3.4：修复「文件管理器显示 2GB」+ 中文化补漏

**根因**：镜像由 root 直接在 `/data/media/0` 下重建，MediaProvider 不知情，
同名文件的旧记录（`_size`）一直没刷新，FUSE/文件管理器就永远显示旧大小。
与「裸盘/稀疏」无关——实测裸盘镜像本身是稀疏的（逻辑 6GB、占用 0）。

**修复**：新增 `ImageStore.syncFuseMetadata()` / `repairFuseMetadata()`，
通过 `/sdcard` 路径做一次同长度 `truncate` 让 MediaProvider 更新记录；
删除/重命名改走 FUSE 路径；列表加载时自愈历史脏记录；
稀疏创建失败不再悄悄回落预分配，改为明确报错。

**实测**：1GB→删→同名重建 3GB，文件管理器立即显示 3GB；删除后 MediaStore
无残留；后台改小后打开列表自动校正。

**补漏**：删除确认框残留的 "Cancel"、以及 7 处 contentDescription 也一并中文化。

产物：`交付/PhoneStick-2.3.4-cn.apk`（vc16）、`交付/源码改动-2.3.4.patch`。

---

## 十一、2.3.5：裸盘镜像的稀疏识别

判定 bug：`used > 0 && used < size * 95%` 把「0 占用」的裸盘镜像误判成非稀疏，
导致既不显示「未写入」，也没有「释放未使用空间」菜单。

改为 `ImageStore.isSparse(size, used) = size > 0 && used < size`，
列表/菜单共用；`used == 0` 显示 `· 未写入`；空镜像点回收直接提示
「该镜像没有可释放的空间」；非 FAT32 的错误文案改为可操作建议。

实测：2GB 裸盘 → `2.00 GB · 未写入` + 有菜单；FAT32 稀疏镜像照常；
预分配镜像不显示菜单。

产物：`交付/PhoneStick-2.3.5-cn.apk`（vc17）、`交付/源码改动-2.3.5.patch`。

---

## 十二、2.3.7：回收功能支持 exFAT / NTFS / ext4

`tools/pstrim.c` 重写为多文件系统工具：
- FAT32（FAT 表）、exFAT（Allocation Bitmap）、NTFS（$Bitmap via MFT runlist）、
  ext4（block bitmap，支持 meta_bg，BLOCK_UNINIT 组跳过）；
- 分区布局支持 MBR / GPT / 超级软盘（无分区表）。

修复的坑：ext4 超块 `incompat` 偏移写错（0x5C→0x60）导致把 RESIZE_INODE 当
META_BG、曾把在用块打洞（md5 抓出）；GPT 头字段偏移 88/96/100→72/80/84；
几何校验混用整盘/分区偏移。设备上 `losetup -o` 读返回 0 字节，FITRIM 路线不可行。

验证：4 FS × 3 布局 = 8 组合（MBR/GPT/超级软盘）全部通过，整理后重新挂载
md5 一致；真机 ext4 镜像 382.57MB → 82.50MB，e2fsck 干净；
现有 fat/exfat/ntfs/ext4 四个真实镜像 dry-run 全部正确识别。

产物：`交付/PhoneStick-2.3.7-cn.apk`（vc19）、`交付/源码改动-2.3.7.patch`。

---

## 十三、2.4.0：多分区镜像回收

之前 `pstrim` 只取第一个分区。像 `aoscrootfs.img`（GPT：300MB FAT32 + 63.7GB ext4）
这种镜像，ext4 那半边永远不会被处理。

改动：
- `locate_partitions()` 收集所有分区（GPT 全条目 / MBR 4 个主分区 / 无表则超级软盘）；
- 每个分区独立识别 FS 并回收，输出 `fs=fat32,ext4 parts=2 trimmed=2`；
- 补上"最后一个分区之后"的尾部零块回收（400MB 镜像里常有 100MB 尾部空占）；
- 零块扫描合并连续段（runs 64022 → 27）；
- 回收入口改为对**所有镜像**显示（非稀疏镜像最需要它：能把 400MB 压回 119MB）。

验证：
- 主机：GPT 双分区 400MB → 119MB、MBR 双分区 331MB → 123MB，两边保留文件 md5 一致、fsck 干净；
- 真机（Xiaomi 15）：同样的 400MB 双分区镜像经 App 回收 **410MB → 119MB**，
  拉回主机后两个分区 md5 一致、`fsck.vfat` / `e2fsck -fn` 干净；
- `aoscrootfs.img` dry-run 显示两个分区都识别（fat32,ext4），但它只有 184MB 物理占用，
  大部分空闲区本来就是洞，实际可释放空间很小。

产物：`交付/PhoneStick-2.4.0-cn.apk`（vc22）、`交付/源码改动-2.4.0.patch`；
`pstrim.c` 已从仓库外的 `tools/` 移到 `PhoneStick/native/`，随代码一起纳入版本管理。

---

## 十四、2.4.1：修复「删了 499MB 却回收不出来」

两个根因：
1. ext4 的 `BLOCK_UNINIT` 块组被整组跳过——格式化后整组空闲，但旧数据块仍实占镜像；
2. inode 表 / 保留 GDT 属于"已分配但全零"的块，按空闲块回收永远碰不到，
   而它们正是 706MB 里的大头。

修复：
- ext4 回收改两遍（先收集元数据保护区，再逐组回收，uninit 组按"除元数据外空闲"处理）；
- 新增零块扫描（SEEK_DATA/SEEK_HOLE 遍历已分配区间，全零 4K 块打洞）；
- 新增 `--stats` / `--map` 诊断开关。

实测：`aoscrootfs.img` **706MB → 5.9MB**（挂载测试后 23MB），两分区可正常挂载读写；
主机回归 ext4 正常/格式化残留、FAT32 全部通过（md5 + fsck + 写入测试）。

产物：`交付/PhoneStick-2.4.1-cn.apk`（vc23）、`交付/源码改动-2.4.1.patch`。

---

## 十五、2.4.3：状态误报修复 + 清理（2026-11-22）

### 1. 挂载状态误报（原 P2）

`getMountStatus()` 原来只要 `lun.0/file` 非空就报已挂载。HAL 重新配置 USB 时
（比如用户插拔数据线后只恢复 adb），会摘掉 function 的 configuration 链接，
但 `lun.0/file` 可能残留 → App 显示"已挂载"、主机却没有盘。

新判定（`UsbGadgetController`）：

- configfs 路径：`<gadget>/UDC` 非空 **且** 该 `mass_storage.N` 已链进某个
  configuration。链接认两遍——先按同名链接（标准 ConfigFS 用法），再按
  `readlink -f` 解析目标兜底（MIUI 等 HAL 起的链接名叫 `function2` 之类，
  名字对不上）。
- 老 sysfs（android_usb）路径：看 `android0/enable == 1`。
- `sys.usb.state` 依旧不参与（发现 D：某些机器恒为空）。

`mountImage()` 成功路径也复用同一判定（`pollGadgetActive`，最多等 5s）：
LUN 拿到文件但 gadget 没生效时明确报失败，而不是让 UI 与主机状态打架。

**真机差分验证（MI 6 / sagit，Android 14，kernel 4.4，configfs `/config`）**
用 `tools/verify-status-logic.sh` 构造"file 残留 + 配置未链接"现场：

| 状态 | 旧逻辑 | 新逻辑 |
|---|---|---|
| A 干净 | UNMOUNTED | UNMOUNTED |
| B `lun.0/file` 有镜像路径、configs/b.1 只有 `f1 -> ffs.adb`、UDC 绑定 | **MOUNTED（误报）** | **UNMOUNTED（正确）** |

C 状态（真实挂载）在 MI 6 上无法构造：这台机器的 HAL 同样忽略
`sys.usb.config` 里的 mass_storage（`sys.usb.state` 停在 `adb`，配置不出现
mass_storage 链接，与发现 B 的 K20 Pro 一致）——该分支由 Xiaomi 15 等
挂载可用的设备覆盖。

### 2. 删除 Strategy 2（自建 swy ConfigFS gadget）

真机证据现在有**两台设备、两个系统版本**：

- Xiaomi 15 / Android 17（发现 A）：`ln -s functions/mass_storage.0 configs/c.1/...` EPERM；
- MI 6 / Android 14（本轮补测）：同样 `ln: ... Operation not permitted`，且
  configfs 里自建 gadget 目录后连清理都要逐级 rmdir（`rm -rf` 无效）。

保留它只会在挂载失败时多绕一条死路、误导排查。`unmountImage` 保留对老版本
swy 残留目录的清理；顺带删了无调用的 `rebindUdc`。

另记：MI 6 的 `/sys/class/android_usb/` 下有 `android1 / android2`（不是
android0）——内核带着老 composite 框架，但 HAL 用的是 configfs。App 的
sysfs 回退候选里只有 android0，这台机器上等于没有 sysfs 兜底；未做深挖，
留作以后想给"HAL 忽略 mass_storage"机型加通路时的线索。

### 2½. 真机全链路验证（一加 15 / PLK110，Android 17，kernel 6.12，KernelSU LKM）

挂载可用的设备类，把 C 状态（真实挂载）补齐：

| 用例 | 结果 | 证据 |
|---|---|---|
| setprop 挂载（复刻 App 策略 1） | ✅ | `sys.usb.state=mass_storage,adb`，HAL 起链接 `f2 -> mass_storage.0` |
| **新状态逻辑在真实挂载下判定** | ✅ | 旧/新逻辑都报 MOUNTED——`f2` 这种"链接名≠函数名"的命名正是 `readlink -f` 兜底的靶子，命中 |
| 主机出盘 | ✅ | `sda 48M / sda1 PSTEST vfat`，挂载回读 `hello.txt` 内容一致 |
| 标志残留 | 注意 | LUN 的 `cdrom/ro` 会**跨会话残留**（上一次 Ventoy 以 CD-ROM 挂过，主机先认成 sr0）。App 的 mountImage 每次都重写 ro/cdrom，所以正常使用不受影响；shell 手测时要自己清 |
| 卸载 | ✅ | file 清空、HAL 摘掉 f2、主机盘消失（/dev/sda 变 0B 空节点属正常） |
| **App 全流程**（选镜像→挂载→卸载） | ✅ | UI"已挂载"↔"未挂载"与主机状态全程一致 |
| **Windows 主机识别**（用户实测） | ✅ | `22D9:2767` + MS@MI_01 在用户 Windows 上直接出盘、可打开——该机器没装抢占 MS 接口的一加/Oplus OEM 驱动，`usbstor.inf` 正常绑定；历史上 OPLUS `oem213.inf` 抢 MI_01 的事故没有复现条件 |
| pstrim 现场残留 | 观察 | g1 之外还有一个 `ps` gadget（上游 com.phonestick 自建，LUN 残留 `/0/DataBackup/Ventoy.img`）；新状态逻辑的 glob 扫所有 gadget，UDC 未绑的会被正确跳过 |

**KernelSU 授权的两个坑**（MI 6 是 Magisk，没这些问题）：

1. KSU 对未授权 uid 内核级隐藏 `su`，App 连请求都发不出去，管理器列表里也
   搜不到——必须先在管理器里出现授权弹窗/手动添加。装机后不弹框的话，
   在管理器"超级用户"页确认。
2. **授权后必须重启 App**（force-stop 再开）。libsu 在进程内缓存 shell，
   授权前创建的非 root shell 会一直被复用，表现为 Root Guard 已过、
   但镜像列表为空、状态判定全部失真。

### 3. 仓库维护

- F-Droid：`metadata/` 删除（元数据停在 2.3.0 且指向上游，早已名存实亡），
  README 明确只从 Releases 安装。
- 删 `tools/pstrim.c`（2.3.7 旧副本，缺 `--stats/--map` 与 ext4 修正），
  唯一权威源码在 `native/pstrim.c`。
- 新增 `.github/workflows/ci.yml`：JVM 单测（用 `tools/make-fs-test-images.sh`
  生成镜像）+ pstrim 回归 + `assembleDebug`。
- 新增 `tools/regression-pstrim.sh`：pstrim 的主机侧回归，本机实测 7/7 通过，
  预分配镜像 49152K → 3140K，md5 + fsck 全绿。注意 `--stats` 自带 dry-run，
  真回收要裸调 `pstrim <image>`。
- 新增 `tools/verify-status-logic.sh`：设备侧 P2 差分验证（clean/stale/
  mount/restore/report 子命令，逐条 adb 调用，避免 USB 重枚举杀 shell）。
- **状态栏修复（反色 + 同色）**：主题没设 `windowLightStatusBar`，浅色模式下
  系统给白色图标压在浅色 `md_theme_surface` 上，几乎不可见（MI 6 实测）；
  深色模式"看着正常"纯靠 MIUI 强制深色，且状态栏（我最初引入的 #17191C）
  与 App 原有夜间调色板（`values-night/colors.xml`，surface #111315）不一致，
  在工具栏下方形成色带——像素采样抓出来的。最终方案：状态栏/导航栏/工具栏
  统一引用 `md_theme_surface`（布局给 AppBarLayout 显式背景，消除 M3
  elevation tint 的色差），夜间补 `windowLightStatusBar=false` +
  `forceDarkAllowed=false` 关掉 ROM 强制深色，让 App 自带的夜间调色板
  独立渲染、任何 ROM 可预期。MI 6 双模式像素采样回归：状态栏与工具栏
  完全同色（浅 #FAFDFD、深 #111315 贯通）。
- README 更新版本表、pstrim 源码路径、已知限制（多分区回收早就是现状）。

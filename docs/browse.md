# 镜像内文件浏览（2.4.2）

> 目标：不拔数据线、不依赖电脑，直接在手机上查看 / 添加 / 删除 / 读出镜像里的文件。
> 覆盖范围与「释放未使用空间」一致：**FAT32 / exFAT / NTFS / ext4**，MBR / GPT / 无分区表。

## 一、能力

| 操作 | 说明 |
|---|---|
| 查看 | 目录浏览（文件夹优先、按名字排序），显示大小与修改时间；路径在标题栏 |
| 添加 | 从系统文件选择器（SAF）多选文件写入当前目录；也可新建文件夹 |
| 删除 | 文件/文件夹（递归）删除，带二次确认 |
| 读出 | 导出到手机任意位置（SAF 创建文档），字节直通不中转临时文件 |
| 其他 | 重命名、当前目录刷新、"镜像信息"（文件系统 / 容量 / 可用 / 访问方式） |

入口两处：
- 主界面右上角菜单 →「浏览文件」（当前选中的镜像）；
- 镜像列表每行右侧 `⋮` →「浏览文件」。

## 二、两条数据通路

```
                  ┌─────────────────────────── 有内核驱动 ───────────────────────────┐
镜像 → VolumeLayout 分区/文件系统识别 →│ losetup -o <分区偏移> → mount -t <驱动> <loop> <挂载点> │→ 可读可写
                  └──────────────────────────────────────────────────────────────────┘
                                     │ 挂载全失败（典型：4.14 内核挂 NTFS）
                                     ▼
                        内置用户态解析器（只读，目前 NTFS）
```

### 1. 内核 loop 挂载（默认，可读写）

* `losetup -f --show -o <offset> <img>`：直接按字节偏移挂到分区起点，
  **不依赖内核解析分区表**，因此"镜像比分区表声明更大"这类脏镜像也能挂上。
* 驱动候选（`FsKind.mountTypes`）：
  * FAT32 → `vfat`
  * exFAT → `exfat` / `sdfat`（三星/vendor 内核用的是后者）
  * NTFS  → `ntfs3`（5.15+）/ `ntfs`（老内核，只读）/ `ufsd`（Paragon，部分国产 ROM）
  * ext4  → `ext4`（同时覆盖 ext2/ext3）
* 每个驱动名先试 `rw`，失败再试 `ro`；`mount` 返回 0 之后还要查
  `/proc/mounts` 的实际挂载选项 —— 实测把只读文件当镜像时，`mount` 会打印
  `WARNING: source write-protected, mounted read-only` 却仍然返回 0，
  此时必须把界面切成只读，否则用户点"导入"会得到一堆 EROFS。
* 挂载点：`/data/local/tmp/.psfs/s<序号>_<nanoTime>`，退出时 `umount -l` + `losetup -d` + `rmdir`。
  进程被杀导致的残留由 `ImageFsMount.cleanupStale()` 在每次打开前清理
  （只处理 `*.psfs/*` 和指向 `/data/media/0/PhoneStick/` 的 loop，不碰系统挂载）。

### 2. 用户态只读解析器（兜底，`fs/NtfsReader.kt`）

4.14 这类老内核基本没有 NTFS 写驱动（`ntfs3` 要 5.15+，老 `ntfs` 驱动多数 ROM 没编译）。
没有它，NTFS 镜像就完全打不开。实现的读取路径：

* 引导扇区 → 簇大小 / MFT 位置 / 记录大小；
* MFT 记录：先做 update sequence 修正（fixup），再遍历属性；
* `$FILE_NAME`：列表里的名字、大小、时间；跳过 DOS 8.3 短名（namespace=2）避免重复；
* `$INDEX_ROOT` + `$INDEX_ALLOCATION`：目录枚举（根节点与叶节点都算，按 MFT 记录号去重）；
* `$DATA`：驻留直接读，非驻留按 runlist 读，稀疏段补零；
* `$MFT` 自身的 runlist 也会解析 —— MFT 碎片化后记录不再连续，按簇号硬算会读错记录。

不支持：NTFS 压缩文件（属性标志 0x0001）、EFS 加密（0x4000），遇到时给明确提示而不是吐乱码。

## 三、为什么二进制读写要绕 su

镜像在 `/data/media/0/PhoneStick` 下，属主 `root:everybody(9997)`、权限 `rw-rw----`，
app 进程既不是属主也不在组里；即使放开权限位，SELinux 也会拦
（`media_rw_data_file` → `untrusted_app`）。所以：

* 列目录 / 删除 / 重命名 / 新建 → `su` 里跑 `find`+`stat`、`rm -rf`、`mv`、`mkdir`；
* 读文件 / 写文件 → libsu 的 `SuFileInputStream` / `SuFileOutputStream`
  （`com.github.topjohnwu.libsu:io`，按需加进来的依赖）；
* 分区与文件系统识别 → `SuRandomAccessFile`，外面包了一层 512KB 窗口缓存
  （`fs/SuBlockIo.kt`）—— libsu 每次 seek 都可能重开 su 管道，逐次小随机读会慢到不可用。

列目录用的是 `find . -mindepth 1 -maxdepth 1 -exec stat -c '%f\t%s\t%Y\t%n' {} +`
（自动分批，几千个文件也不会撑爆参数长度），老 toybox 不支持 `+` 时回落到 shell 通配。

`stat` 的 `\t` 在两种实现上表现不同：toybox 解释成真 TAB，GNU coreutils 的 `-c` 原样输出
`\t` 两个字符。`fs/StatLine.kt` 两种都认，并且只在"模式字段之后"匹配分隔符，
所以文件名里出现 TAB 或 `\t` 都不会被切错（有单测盯住）。

## 四、和 USB gadget 的互斥

内核在 LUN 打开着镜像文件时，拒绝把同一个文件 loop 挂到本机（早期排查记录的 `EINVAL`）。
所以进入浏览页时：

1. 查 LUN 是否正用着这个镜像；
2. 是 → 弹窗"浏览前需要先卸载 U 盘"，用户确认后 `UsbGadgetController.unmountImage()`，
   等 400ms 让内核释放文件引用，再挂载；
3. 退出浏览页时，**如果这次是我们卸载的，会自动按原来的读写/CD-ROM 设置重新挂 U 盘**。
   （`ImageBrowserActivity.onDestroy`，同一线程里先 close 再 remount，避免竞态。）

## 五、支持矩阵

| 文件系统 | 查看 | 读出 | 添加 | 删除 | 依赖 |
|---|---|---|---|---|---|
| FAT32 / FAT16 | ✅ | ✅ | ✅ | ✅ | 内核 `vfat`（Android 必备） |
| exFAT | ✅ | ✅ | ✅ | ✅ | 内核 `exfat` 或 `sdfat` |
| ext4 | ✅ | ✅ | ✅ | ✅ | 内核 `ext4` |
| NTFS | ✅ | ✅ | ✅ | ✅ | 内核 `ntfs3`（5.15+）/ `ufsd` |
| NTFS（无写驱动） | ✅ | ✅ | ❌ 只读 | ❌ 只读 | 内置解析器 |
| ISO9660 | ✅ | ✅ | ❌（光盘） | ❌（光盘） | 内核 `iso9660` |

只读时界面顶部有横幅说明，导入 FAB 隐藏，删除/重命名入口不出现。

## 六、已知限制

* 扩展分区 / 逻辑分区不展开（与 `pstrim` 一致，只认 4 个主分区或 GPT 条目）；
* 多分区镜像只打开**容量最大**的那个可识别分区（EFI 之类的小分区排在后面）；
* 文件名含换行符时目录列表会错行（`stat` 输出按行收集，制表符已经处理）；
* 单个目录几千文件时首次加载需要几秒（一次 `find` + 一次 `stat`，不是逐文件 su）；
* NTFS 写入仍然依赖内核驱动；要彻底绕开，只能内置用户态写实现，代价和风险都不划算。

## 七、离线验证

用户态解析器是纯 Kotlin，可以完全不接手机验证：

```bash
# 1. 造镜像（需要 --privileged 才能 losetup；容器里没有 udev，脚本会自己 mknod 分区节点）
docker build -t ps-fstest -f- <<'EOF'
FROM ps-build:latest
RUN apt-get update && apt-get install -y --no-install-recommends \
    dosfstools exfatprogs ntfs-3g fdisk e2fsprogs util-linux && rm -rf /var/lib/apt/lists/*
EOF
docker run --rm --privileged -v "$PWD/.testdata:/out" -v "$PWD/tools:/tools:ro" \
  ps-fstest bash /tools/make-fs-test-images.sh /out

# 2. 跑测试（16 个用例：MBR/GPT/超级软盘布局识别 + NTFS 列表/读取/UTF-16 名/3MiB 非驻留文件哈希 + stat 行解析）
docker run --rm -v "$PWD/PhoneStick:/work" -v "$PWD/.testdata:/testdata:ro" \
  -w /work ps-build bash -lc './gradlew :app:testReleaseUnitTest \
    -Dps.test.image.fat32=/testdata/fat32.img -Dps.test.image.exfat=/testdata/exfat.img \
    -Dps.test.image.ntfs=/testdata/ntfs.img -Dps.test.image.ext4=/testdata/ext4.img \
    -Dps.test.image.ntfsnopt=/testdata/ntfs-nopt.img -Dps.test.image.gpt=/testdata/gpt-fat32.img'
```

## 八、真机待验证清单

- [ ] FAT32 镜像：浏览 → 导入 → 删除 → 导出，回电脑能看到一致的内容；
- [ ] exFAT 镜像同上（注意内核驱动名是 `exfat` 还是 `sdfat`，日志里有记录）；
- [ ] ext4 镜像同上；
- [ ] NTFS 镜像：内核能挂就读写，挂不上时确认落到内置解析器、只读横幅出现；
- [ ] U 盘挂载中点"浏览文件" → 卸载确认 → 浏览 → 退出后电脑端盘符回来；
- [ ] 浏览页里按返回键逐级上跳，根目录再返回退出；
- [ ] 导入 1GB 以上大文件时观察内存（走流式，不应 OOM）。

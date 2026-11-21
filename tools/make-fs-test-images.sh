#!/bin/bash
# 生成 JVM 单元测试用的镜像（FAT32 / exFAT / NTFS / ext4）。
#
# 为什么需要它：镜像内文件浏览的两条路径里，用户态解析器（NTFS 等）完全
# 可以在 PC 上验证 —— 用真实的 mkfs 建出镜像、塞进真实文件，再让
# `./gradlew test` 去读，比在真机上盲猜可靠得多。
#
# 用法（在容器里以 root 运行，需要 --privileged 才能 losetup）：
#   docker run --rm --privileged -v "$PWD/.testdata:/out" ps-fstest /tools/make-fs-test-images.sh /out
#
# 产物：
#   $OUT/fat32.img  MBR + 分区1(FAT32)  hello.txt / dir1/nested.txt
#   $OUT/exfat.img  MBR + 分区1(exFAT)  同上
#   $OUT/ntfs.img   MBR + 分区1(NTFS)   同上 + big.bin(3MiB 伪随机) + 中文名文件
#   $OUT/ext4.img   MBR + 分区1(ext4)   同上
#   $OUT/ntfs-nopt.img  无分区表（超级软盘）NTFS，用于验证布局识别
set -euo pipefail

OUT=${1:-/tmp/psimg}
SIZE_MB=${SIZE_MB:-48}
mkdir -p "$OUT"

GREETING="hello from phonestick"

# 无论成功失败都把自己建的 loop 摘掉 —— 容器的 /dev 是临时的，
# 泄漏的 loop 会一直挂在宿主内核上，把 max_loop（默认 8）耗光。
CREATED_LOOPS=""
cleanup() {
  for l in $CREATED_LOOPS; do
    [ -n "$l" ] || continue
    umount -l "${l}" 2>/dev/null || true
    umount -l "${l}p1" 2>/dev/null || true
    losetup -d "$l" 2>/dev/null || true
  done
}
trap cleanup EXIT

# 建 MBR（单分区，起始 LBA 2048）+ 格式化 + 挂载填内容
# loop 直接挂在分区偏移上（start=2048 扇区 × 512B = 1MiB），
# 不依赖内核解析分区表——没有 udev 的容器/runner 上也就没有分区节点竞态。
PART_START=$((2048 * 512))
make_image() {
  local name=$1 ptype=$2 mkfs=$3
  local img="$OUT/$name.img"
  rm -f "$img"
  truncate -s "${SIZE_MB}M" "$img"

  printf 'label: dos\nstart=2048, type=%s\n' "$ptype" | sfdisk -q "$img" >/dev/null

  # 从 sfdisk dump 解析分区实际大小（本地化无关），给 mkfs 一个与
  # 分区表严格一致的设备——否则 FS 几何会超出 GPT 条目声明的大小
  # （GPT 尾部留了备份 GPT），pstrim/分区表校验会拒绝
  local part_sectors
  part_sectors=$(sfdisk -d "$img" | grep -oE 'size= *[0-9]+' | grep -oE '[0-9]+' | head -1)
  [ -n "$part_sectors" ] || { echo "无法解析分区大小" >&2; exit 1; }

  local part
  part=$(losetup -f --show -o "$PART_START" --sizelimit $((part_sectors * 512)) "$img")
  CREATED_LOOPS="$CREATED_LOOPS $part"

  case "$mkfs" in
    fat32)  mkfs.vfat -F 32 -n PSTEST "$part" >/dev/null ;;
    exfat)  mkfs.exfat -n PSTEST "$part" >/dev/null ;;
    ntfs)   mkfs.ntfs -F -Q -L PSTEST "$part" >/dev/null 2>&1 ;;
    ext4)   mkfs.ext4 -q -F -L PSTEST "$part" >/dev/null ;;
  esac

  local mnt="$OUT/.mnt"
  mkdir -p "$mnt"
  mount "$part" "$mnt"
  echo "$GREETING" > "$mnt/hello.txt"
  mkdir -p "$mnt/dir1"
  echo "$GREETING/$name" > "$mnt/dir1/nested.txt"
  # 3MiB 固定图案：验证非驻留 $DATA 的 runlist 读取
  head -c 3145728 /dev/urandom > "$mnt/big.bin"
  md5sum "$mnt/big.bin" | awk '{print $1}' > "$img.big.md5"
  if [ "$mkfs" != fat32 ]; then
    echo "中文文件名内容" > "$mnt/中文名.txt"
  fi
  sync
  umount "$mnt"
  losetup -d "$part"
  echo "built $img ($mkfs)"
}

make_image fat32 0c fat32
make_image exfat 07 exfat
make_image ntfs 07 ntfs
make_image ext4 83 ext4

# GPT 布局（FAT32）：验证 GPT 头 / 分区条目解析
GPT_IMG="$OUT/gpt-fat32.img"
rm -f "$GPT_IMG"
truncate -s "${SIZE_MB}M" "$GPT_IMG"
printf 'label: gpt\nstart=2048\n' | sfdisk -q "$GPT_IMG" >/dev/null
# Microsoft basic data
sfdisk --part-type "$GPT_IMG" 1 EBD0A0A2-B9E5-4433-87C0-68B6B72699C7 >/dev/null 2>&1 || true
gpt_sectors=$(sfdisk -d "$GPT_IMG" | grep -oE 'size= *[0-9]+' | grep -oE '[0-9]+' | head -1)
LOOP=$(losetup -f --show -o "$PART_START" --sizelimit $((gpt_sectors * 512)) "$GPT_IMG")
CREATED_LOOPS="$CREATED_LOOPS $LOOP"
mkfs.vfat -F 32 -n PSGPT "$LOOP" >/dev/null
mkdir -p "$OUT/.mnt3"
mount "$LOOP" "$OUT/.mnt3"
echo "$GREETING gpt" > "$OUT/.mnt3/hello.txt"
sync
umount "$OUT/.mnt3"
rmdir "$OUT/.mnt3"
losetup -d "$LOOP"
echo "built $GPT_IMG (gpt + fat32)"

# 无分区表（超级软盘）NTFS：验证"整个文件就是一个数据区"的布局识别
NOPT="$OUT/ntfs-nopt.img"
rm -f "$NOPT"
truncate -s 32M "$NOPT"
mkfs.ntfs -F -Q -L PSNOPT "$NOPT" >/dev/null 2>&1
LOOP=$(losetup -f --show "$NOPT")
CREATED_LOOPS="$CREATED_LOOPS $LOOP"
mkdir -p "$OUT/.mnt2"
mount "$LOOP" "$OUT/.mnt2"
echo "$GREETING nopt" > "$OUT/.mnt2/hello.txt"
sync
umount "$OUT/.mnt2"
losetup -d "$LOOP"
echo "built $NOPT (ntfs, no partition table)"

rm -rf "$OUT/.mnt" "$OUT/.mnt2"
md5sum "$OUT/big.bin" 2>/dev/null || true
ls -l "$OUT"

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
make_image() {
  local name=$1 ptype=$2 mkfs=$3
  local img="$OUT/$name.img"
  rm -f "$img"
  truncate -s "${SIZE_MB}M" "$img"

  printf 'label: dos\nstart=2048, type=%s\n' "$ptype" | sfdisk -q "$img" >/dev/null

  local loop
  loop=$(losetup -fP --show "$img")
  CREATED_LOOPS="$CREATED_LOOPS $loop"
  local part="${loop}p1"
  # 容器里没有 udev，内核只在 sysfs 里登记分区，设备节点要自己建。
  # 复用刚 detach 的 loop 时，内核的分区扫描可能不触发（GitHub runner
  # 实测偶发）——每 10 轮强制 partprobe 重扫一次兜底。
  local i
  for i in $(seq 1 100); do
    [ -b "$part" ] && break
    if [ $((i % 10)) = 0 ]; then
      partprobe "$loop" 2>/dev/null || blockdev --rereadpt "$loop" 2>/dev/null || true
    fi
    devno=$(cat "/sys/class/block/$(basename "$part")/dev" 2>/dev/null || true)
    if [ -n "${devno:-}" ]; then
      mknod "$part" b "${devno%%:*}" "${devno##*:}" 2>/dev/null || true
    fi
    sleep 0.1
  done
  [ -b "$part" ] || { echo "分区节点 $part 没有出现" >&2; exit 1; }

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
  losetup -d "$loop"
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
LOOP=$(losetup -fP --show "$GPT_IMG")
CREATED_LOOPS="$CREATED_LOOPS $LOOP"
for i in $(seq 1 100); do
  [ -b "${LOOP}p1" ] && break
  if [ $((i % 10)) = 0 ]; then
    partprobe "$LOOP" 2>/dev/null || blockdev --rereadpt "$LOOP" 2>/dev/null || true
  fi
  devno=$(cat "/sys/class/block/$(basename "${LOOP}p1")/dev" 2>/dev/null || true)
  [ -n "${devno:-}" ] && mknod "${LOOP}p1" b "${devno%%:*}" "${devno##*:}" 2>/dev/null || true
  sleep 0.1
done
mkfs.vfat -F 32 -n PSGPT "${LOOP}p1" >/dev/null
mkdir -p "$OUT/.mnt3"
mount "${LOOP}p1" "$OUT/.mnt3"
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

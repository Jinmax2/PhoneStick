#!/bin/bash
# pstrim 主机侧回归：编一个本机版 pstrim → 造镜像 → 打洞 → 校验数据与结构。
#
# 为什么需要它：pstrim 是随包的 C 工具，以前只能靠真机 + 手工 fsck/md5 验证。
# 它是纯 POSIX C，本机 gcc 就能编；测试镜像复用 make-fs-test-images.sh，
# 另外再造一个**预分配**镜像（真实回收场景：pstrim 有洞可打，必须显著变小）。
#
# 需要 root（losetup/mount/mkfs）与：gcc dosfstools exfatprogs ntfs-3g e2fsprogs
# 用法：sudo tools/regression-pstrim.sh [工作目录]   # 默认 /tmp/pstrim-regress
set -euo pipefail

HERE=$(cd "$(dirname "$0")" && pwd)
OUT=${1:-/tmp/pstrim-regress}
PSTRIM="$OUT/pstrim"
GREETING="hello from phonestick"
SIZE_MB=48

if [ "$(id -u)" != 0 ]; then
  echo "请以 root 运行：losetup / mount / mkfs 都需要（sudo $0 $*）" >&2
  exit 1
fi

mkdir -p "$OUT"

# 无论成败都摘掉自己建的 loop 并卸载自己的挂载点
CREATED_LOOPS=""
MOUNTED=""
cleanup() {
  for m in $MOUNTED; do umount -l "$m" 2>/dev/null || true; done
  for l in $CREATED_LOOPS; do
    [ -n "$l" ] || continue
    umount -l "${l}p1" 2>/dev/null || true
    losetup -d "$l" 2>/dev/null || true
  done
}
trap cleanup EXIT

echo "== 1. 编译本机版 pstrim =="
gcc -O2 -Wall -o "$PSTRIM" "$HERE/../native/pstrim.c"
echo "built $PSTRIM"

echo "== 2. 生成测试镜像（稀疏）=="
bash "$HERE/make-fs-test-images.sh" "$OUT"

# 额外造一个**预分配**镜像：先写满零再建 FAT32，pstrim 必须能把它压回元数据级大小
echo "== 3. 生成预分配 FAT32 镜像（真实回收场景）=="
DENSE="$OUT/dense-fat32.img"
rm -f "$DENSE" "$DENSE.big.md5"
dd if=/dev/zero of="$DENSE" bs=1M count=$SIZE_MB status=none
printf 'label: dos\nstart=2048, type=0c\n' | sfdisk -q "$DENSE" >/dev/null
PART=$(losetup -f --show -o 1048576 "$DENSE")
CREATED_LOOPS="$CREATED_LOOPS $PART"
mkfs.vfat -F 32 -n PSDENSE "$PART" >/dev/null
mkdir -p "$OUT/.mntd"
mount "$PART" "$OUT/.mntd"
MOUNTED="$MOUNTED $OUT/.mntd"
echo "$GREETING dense" > "$OUT/.mntd/hello.txt"
head -c 3145728 /dev/urandom > "$OUT/.mntd/big.bin"
md5sum "$OUT/.mntd/big.bin" | awk '{print $1}' > "$DENSE.big.md5"
sync
umount "$OUT/.mntd"
MOUNTED=" "
BEFORE=$(du -sk "$DENSE" | awk '{print $1}')
echo "dense image allocated: ${BEFORE}K"

echo "== 4. 逐镜像执行 pstrim 并校验 =="
pass=0; fail=0

# 校验一个镜像：pstrim → 挂载 → 内容比对 → 卸载 → fsck
#   $1 镜像  $2 fs 类型（fat32|exfat|ntfs|ext4）  $3 hello.txt 期望内容
#   $4 布局（mbr|gpt|superfloppy）  $5 是否有 big.bin（yes|no）  $6 是否有中文名（yes|no）
check_image() {
  local img=$1 fs=$2 hello=$3 layout=$4 have_big=$5 have_cn=$6
  local name label
  label="[$(basename "$img")/$fs/$layout]"

  # 注意：--stats 是诊断模式，自带 dry-run（不会真的打洞）。
  # 先 dry 预览一遍，再真刀真枪回收。失败时输出 pstrim 的
  # STATUS 行（诊断信息），不让 set -e 静默吞掉。
  local ok=1
  "$PSTRIM" "$img" --stats > /tmp/ps-stat.out 2>&1 || {
    echo "  $label FAIL: pstrim --stats $(tail -1 /tmp/ps-stat.out)"
    ok=0
  }
  "$PSTRIM" "$img" > /tmp/ps-real.out 2>&1 || {
    echo "  $label FAIL: pstrim 实回收 $(tail -1 /tmp/ps-real.out)"
    ok=0
  }
  [ "$ok" = 1 ] && echo "  $label pstrim ok"

  # 挂载回读（与 make-fs-test-images.sh 一致：MBR/GPT 直接挂 1MiB 分区
  # 偏移，不依赖分区节点扫描，避开无 udev 环境的竞态）
  local part
  if [ "$layout" = superfloppy ]; then
    part=$(losetup -f --show "$img")
  else
    part=$(losetup -f --show -o 1048576 "$img")
  fi
  CREATED_LOOPS="$CREATED_LOOPS $part"
  local mnt="$OUT/.mntck"
  mkdir -p "$mnt"
  mount "$part" "$mnt"
  MOUNTED="$MOUNTED $mnt"

  [ "$(cat "$mnt/hello.txt")" = "$hello" ] || { echo "  $label FAIL: hello.txt 内容不符"; ok=0; }
  if [ "$have_big" = yes ]; then
    local want got
    want=$(cat "$img.big.md5")
    got=$(md5sum "$mnt/big.bin" | awk '{print $1}')
    [ "$got" = "$want" ] || { echo "  $label FAIL: big.bin md5 $got != $want"; ok=0; }
  fi
  if [ "$have_cn" = yes ]; then
    grep -q "中文文件名内容" "$mnt/中文名.txt" || { echo "  $label FAIL: 中文名文件内容不符"; ok=0; }
  fi
  # 打洞后仍可写
  echo "write-after-trim" > "$mnt/.wtest" && grep -q write-after-trim "$mnt/.wtest" \
    || { echo "  $label FAIL: 打洞后写入回读失败"; ok=0; }
  rm -f "$mnt/.wtest"
  sync
  umount "$mnt"
  MOUNTED=" "

  # 结构校验
  case "$fs" in
    fat32) fsck.vfat -fn "$part" >/dev/null || { echo "  $label FAIL: fsck.vfat"; ok=0; } ;;
    exfat) fsck.exfat -n "$part" >/dev/null || { echo "  $label FAIL: fsck.exfat"; ok=0; } ;;
    ntfs)  ntfsfix -n "$part" >/dev/null 2>&1 || { echo "  $label FAIL: ntfsfix"; ok=0; } ;;
    ext4)  e2fsck -fn "$part" >/dev/null || { echo "  $label FAIL: e2fsck"; ok=0; } ;;
  esac

  losetup -d "$part" || true
  # 从清理名单里摘掉这个已释放的 loop（简单起见靠 grep 去重）
  CREATED_LOOPS=$(echo " $CREATED_LOOPS " | sed "s| $part | |" | sed 's/^ //;s/ $//')

  if [ "$ok" = 1 ]; then
    echo "  $label PASS"
    pass=$((pass+1))
  else
    fail=$((fail+1))
  fi
}

check_image "$OUT/fat32.img"      fat32 "$GREETING"            mbr         yes no
check_image "$OUT/exfat.img"      exfat "$GREETING"            mbr         yes yes
check_image "$OUT/ntfs.img"       ntfs  "$GREETING"            mbr         yes yes
check_image "$OUT/ext4.img"       ext4  "$GREETING"            mbr         yes yes
check_image "$OUT/gpt-fat32.img"  fat32 "$GREETING gpt"        gpt         no  no
check_image "$OUT/ntfs-nopt.img"  ntfs  "$GREETING nopt"       superfloppy no  no

# 预分配镜像：必须显著变小 + 全套数据校验（真实回收，不带 --stats 的 dry 语义）
"$PSTRIM" "$DENSE" > /tmp/ps-dense.out 2>&1 || {
  echo "dense FAIL: pstrim 实回收 $(tail -1 /tmp/ps-dense.out)"
  fail=$((fail+1))
}
AFTER=$(du -sk "$DENSE" | awk '{print $1}')
echo "dense image after trim: ${AFTER}K (was ${BEFORE}K)"
if [ "$AFTER" -lt $((BEFORE / 2)) ]; then
  echo "  [dense-fat32] 回收量达标（${BEFORE}K → ${AFTER}K）"
else
  echo "  [dense-fat32] FAIL: 预分配镜像几乎没有变小（${BEFORE}K → ${AFTER}K）"
  fail=$((fail+1))
fi
check_image "$DENSE" fat32 "$GREETING dense" mbr yes no

echo "== 5. dry-run 冒烟 =="
"$PSTRIM" "$OUT/fat32.img" --dry-run --stats >/dev/null && echo "  dry-run ok"

echo
echo "结果：$pass 通过，$fail 失败"
[ "$fail" = 0 ]

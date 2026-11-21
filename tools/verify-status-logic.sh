#!/bin/sh
# 真机差分验证 getMountStatus 的新旧判定逻辑（P2 修复，2.4.3）。
# 在设备上以 root 运行，逐条 adb shell 调用，避免脚本中途被 USB 重枚举杀掉。
#
# 2026-11-22 在 MI 6（Android 14 / kernel 4.4 / configfs b.1）实测：
#   A 干净未挂载       旧=UNMOUNTED 新=UNMOUNTED
#   B file 残留/配置未链接  旧=MOUNTED（误报！）新=UNMOUNTED（正确）
#   （C 真实挂载无法在此设备构造：HAL 忽略 sys.usb.config 里的 mass_storage，
#     与 K20 Pro 上发现 B 一致；C 分支的逻辑由 Xiaomi 15 等可用设备覆盖）
#
# 用法（每条独立 adb 调用，中间 adb 可能闪断几秒，属正常）：
#   adb push tools/verify-status-logic.sh /data/local/tmp/
#   adb shell su -c "sh /data/local/tmp/ps-verify.sh clean"
#   adb shell su -c "sh /data/local/tmp/ps-verify.sh stale /data/media/0/PhoneStick/xx.img"
#   adb shell su -c "sh /data/local/tmp/ps-verify.sh report"     # ← B 状态的差分结论在这
#   adb shell su -c "sh /data/local/tmp/ps-verify.sh restore"
CG=/config/usb_gadget/g1
LUN_FILE=$(ls $CG/functions/mass_storage.*/lun.0/file 2>/dev/null | head -n1)

new_logic() {
  for lun in $CG/functions/mass_storage.*/lun.*; do
    [ -f "$lun/file" ] || continue
    CONTENT=$(cat "$lun/file" 2>/dev/null)
    [ -n "$CONTENT" ] || continue
    GADGET="${lun%/functions/*}"
    FUNC="${lun#*/functions/}"; FUNC="${FUNC%%/*}"
    ACTIVE=0
    if [ -n "$(cat "$GADGET/UDC" 2>/dev/null)" ]; then
      for cfg in "$GADGET"/configs/*/; do
        if [ -e "${cfg}$FUNC" ]; then ACTIVE=1; break 2; fi
        for link in "${cfg}"*; do
          case "$(readlink -f "$link" 2>/dev/null)" in
            */functions/$FUNC) ACTIVE=1; break 2 ;;
          esac
        done
      done
    fi
    if [ "$ACTIVE" = "1" ]; then
      echo "MOUNTED file=$CONTENT"
      return
    fi
  done
  echo "UNMOUNTED"
}

old_logic() {
  for lun in $CG/functions/mass_storage.*/lun.*; do
    [ -f "$lun/file" ] || continue
    CONTENT=$(cat "$lun/file" 2>/dev/null)
    if [ -n "$CONTENT" ]; then
      echo "MOUNTED file=$CONTENT"
      return
    fi
  done
  echo "UNMOUNTED"
}

report() {
  echo "现场: UDC=[$(cat $CG/UDC)] 链接=[$(ls -l $CG/configs/*/ 2>/dev/null | grep -oE '\-> .*' | tr '\n' ' ')] file=[$(cat "$LUN_FILE" 2>/dev/null)]"
  echo "旧逻辑: $(old_logic)"
  echo "新逻辑: $(new_logic)"
}

case "$1" in
  clean)
    echo "" > "$LUN_FILE" 2>/dev/null || true
    echo "lun.0/file 已清空"
    ;;
  stale)
    echo "$2" > "$LUN_FILE" && echo "file 已写入 $2（配置未链接，P2 误报现场）"
    ;;
  mount)
    setprop sys.usb.config mass_storage,adb
    sleep 4
    echo "$2" > "$LUN_FILE" 2>/dev/null || true
    echo "setprop mass_storage,adb + file 已写（真实挂载）"
    ;;
  restore)
    echo "" > "$LUN_FILE" 2>/dev/null || true
    setprop sys.usb.config adb
    echo "已还原：file 清空，sys.usb.config=adb"
    ;;
  report)
    report
    ;;
  *)
    echo "用法: sh $0 clean|stale <img>|mount <img>|restore|report" >&2
    exit 2
    ;;
esac

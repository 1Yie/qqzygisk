#!/system/bin/sh
# One-time install repair for versions which made WeChat's media tree appUid:appUid/0700.
# Run on /data/media, not through the caller's /storage/... FUSE mount.
QQZ_USER_ROOT=${QQZ_USER_ROOT:-/data/user}
QQZ_MEDIA_ROOT=${QQZ_MEDIA_ROOT:-/data/media}

media_repair_log() {
  if command -v ui_print >/dev/null 2>&1; then
    ui_print "$1"
  else
    printf '%s\n' "$1"
  fi
}

repair_wechat_media() (
  PRIVATE_DIR=$1
  USER_ID=${PRIVATE_DIR%/com.tencent.mm}
  USER_ID=${USER_ID##*/}
  case "$USER_ID" in ''|*[!0-9]*) exit 0 ;; esac
  TARGET="$QQZ_MEDIA_ROOT/$USER_ID/Android/media/com.tencent.mm/.qqzygisk"
  [ -e "$TARGET" ] || [ -L "$TARGET" ] || exit 0
  if [ ! -d "$TARGET" ] || [ -L "$TARGET" ]; then
    media_repair_log "! WeChat media path is not a regular directory: $TARGET"
    exit 1
  fi
  APP_UID=$(stat -c %u "$PRIVATE_DIR") || exit 1
  case "$APP_UID" in ''|*[!0-9]*) exit 1 ;; esac
  # AOSP vold PrepareAppDirFromRoot: app uid, AID_MEDIA_RW=1023, directories 02770.
  # find does not follow symlinks; change only regular entries in this known legacy tree.
  if ! find "$TARGET" -type d -exec chown "$APP_UID:1023" '{}' + ||
     ! find "$TARGET" -type f -exec chown "$APP_UID:1023" '{}' + ||
     ! find "$TARGET" -type d -exec chmod 2770 '{}' + ||
     ! find "$TARGET" -type f -exec chmod 0660 '{}' +; then
    media_repair_log "! Failed to repair WeChat media permissions: $TARGET"
    exit 1
  fi
  if command -v restorecon >/dev/null 2>&1; then
    if ! restorecon -RF "$TARGET"; then
      media_repair_log "! Failed to restore WeChat media security labels: $TARGET"
      exit 1
    fi
  fi
  media_repair_log "- Repaired WeChat media directory: $TARGET (uid=$APP_UID, gid=1023)"
)

REPAIR_STATUS=0
for PRIVATE_DIR in "$QQZ_USER_ROOT"/*/com.tencent.mm; do
  [ -d "$PRIVATE_DIR" ] || continue
  repair_wechat_media "$PRIVATE_DIR" || REPAIR_STATUS=1
done
exit "$REPAIR_STATUS"

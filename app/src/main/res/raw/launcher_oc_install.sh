#!/system/bin/sh
# @MARKER@
echo "oc-install: asking the OpenCode app to install/update the runtime (watch the notification)"
exec /system/bin/am start -a android.intent.action.VIEW \
  -n com.opencode.android/.OpenCodeWebActivity \
  --ez com.opencode.android.opencode.FORCE_UPDATE true

set -x
mkdir -p ui-out
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell pm grant com.rado.aimoderator android.permission.POST_NOTIFICATIONS || true
adb logcat -c
adb shell am start -n com.rado.aimoderator/.MainActivity
sleep 20
adb exec-out screencap -p > ui-out/1-start.png
adb shell uiautomator dump /sdcard/ui1.xml && adb pull /sdcard/ui1.xml ui-out/ui1.xml
# натисни „Настройки“ в долното меню (по текст от дъмпа)
B=$(grep -o 'text="Настройки"[^>]*bounds="\[[0-9]*,[0-9]*\]\[[0-9]*,[0-9]*\]"' ui-out/ui1.xml | grep -o '\[[0-9]*,[0-9]*\]\[[0-9]*,[0-9]*\]' | head -1)
if [ -n "$B" ]; then
  X=$(echo $B | sed -E 's/\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\]/\1 \3/' | awk '{print int(($1+$2)/2)}')
  Y=$(echo $B | sed -E 's/\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\]/\2 \4/' | awk '{print int(($1+$2)/2)}')
  adb shell input tap $X $Y; sleep 5
  adb exec-out screencap -p > ui-out/2-settings.png
  adb shell uiautomator dump /sdcard/ui2.xml && adb pull /sdcard/ui2.xml ui-out/ui2.xml
fi
adb logcat -d > ui-out/logcat.txt
grep -iE "AndroidRuntime|FATAL|aimoderator|chromium|WebView" ui-out/logcat.txt | tail -200 > ui-out/logcat-short.txt
exit 0

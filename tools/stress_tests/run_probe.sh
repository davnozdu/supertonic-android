#!/bin/bash
# run_probe.sh <local text file> <provider or ""> <out.json>  -- runs the stressProbe and pulls its results.
P=com.davnozdu.supertonic.tts.fork
A=(adb -s 3B15AQ003F500000)
IN=$1; PROVIDER=$2; OUT=$3; MORE=$4
"${A[@]}" push "$IN" /data/local/tmp/stress-probe.txt >/dev/null
"${A[@]}" shell "su -c 'U=\$(stat -c %u /data/data/$P); cp /data/local/tmp/stress-probe.txt /data/data/$P/cache/stress-probe.txt; chown \$U:\$U /data/data/$P/cache/stress-probe.txt; rm /data/local/tmp/stress-probe.txt; rm -f /data/data/$P/cache/stress-probe-out.json /data/data/$P/cache/stress-probe-decisions.json'"
"${A[@]}" logcat -c
EXTRA=""; [ -n "$PROVIDER" ] && EXTRA="--es provider $PROVIDER"
"${A[@]}" shell "su -c 'am start --activity-clear-task -n $P/com.brahmadeo.supertonic.tts.SpeechDiagnosticsActivity --ez stressProbe true $EXTRA $MORE'" >/dev/null
for i in $(seq 1 900); do
  "${A[@]}" shell "su -c 'test -s /data/data/$P/cache/stress-probe-out.json'" && break
  "${A[@]}" logcat -d -s SpeechCheck:E | grep -q 'STRESS PROBE FAILED' && break
  sleep 2
done
sleep 1
"${A[@]}" logcat -d -v time 'SpeechCheck:*' 'LlmPreparation:*' '*:S' | grep -E 'STRESS PROBE (DONE|FAILED)|cross-check|Rejected|failed' | cut -c1-200
"${A[@]}" shell "su -c 'cat /data/data/$P/cache/stress-probe-out.json'" > "$OUT"
"${A[@]}" shell "su -c 'cat /data/data/$P/cache/stress-probe-decisions.json 2>/dev/null'" > "${OUT%.json}-decisions.json"
echo "saved $OUT"

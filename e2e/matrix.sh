#!/bin/bash
# usage: matrix.sh "paper:26.1.2 paper:26.2 ..."  -> results/<platform>-<version>.json
cd "$(dirname "$0")"
mkdir -p results
for combo in $1; do
  P=${combo%%:*}; V=${combo##*:}
  echo "== $P $V"
  timeout 300 node e2e.mjs "$P" "$V" > "results/$P-$V.raw" 2>&1
  grep -v "punycode\|trace-deprecation" "results/$P-$V.raw" > "results/$P-$V.json"
  # make sure no server is left behind on any port
  powershell -Command "Get-CimInstance Win32_Process -Filter \"name='java.exe'\" | Where-Object { \$_.CommandLine -match 'servers.(paper|folia|spigot)-' } | ForEach-Object { Stop-Process -Id \$_.ProcessId -Force }" > /dev/null 2>&1
done
echo ALL DONE

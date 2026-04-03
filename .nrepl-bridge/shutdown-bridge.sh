#!/usr/bin/env bash
# Kill all nrepl-bridge babashka processes and orphaned pods.
# Usage: bash .nrepl-bridge/shutdown-bridge.sh
# Then run /mcp to restart the bridge.

echo "=== nrepl-bridge kill script ==="
echo "Shell: $SHELL"
echo "Bash version: $BASH_VERSION"
echo "PWD: $(pwd)"
echo ""

# Step 1: Find bb.exe processes running server.bb
echo "[1/4] Querying bb.exe processes via PowerShell..."
ps_output=$(powershell -Command "Get-WmiObject Win32_Process | Where-Object { \$_.Name -eq 'bb.exe' } | ForEach-Object { \$_.ProcessId.ToString() + '|' + \$_.CommandLine }" 2>&1)
ps_exit=$?
echo "  PowerShell exit code: $ps_exit"

if [ $ps_exit -ne 0 ]; then
  echo "  ERROR: PowerShell query failed:"
  echo "  $ps_output"
  echo "  Falling back to tasklist..."
  ps_output=$(tasklist //FI "IMAGENAME eq bb.exe" //FO CSV //NH 2>&1)
  echo "  tasklist output: $ps_output"
fi

echo "  Raw output ($(echo "$ps_output" | wc -l | tr -d ' ') lines):"
echo "$ps_output" | head -20 | sed 's/^/    /'
echo ""

# Step 2: Filter for server.bb and kill
echo "[2/4] Filtering for server.bb processes..."
bridge_pids=""
while IFS= read -r line; do
  line=$(echo "$line" | tr -d '\r')
  [ -z "$line" ] && continue
  pid=$(echo "$line" | cut -d'|' -f1)
  cmd=$(echo "$line" | cut -d'|' -f2-)
  if echo "$cmd" | grep -qi "server\.bb"; then
    echo "  MATCH: PID=$pid CMD=$cmd"
    bridge_pids="$bridge_pids $pid"
  fi
done <<< "$ps_output"

if [ -z "$bridge_pids" ]; then
  echo "  No nrepl-bridge bb.exe processes found."
else
  for pid in $bridge_pids; do
    echo "  Killing PID $pid..."
    kill_output=$(taskkill //F //PID "$pid" 2>&1)
    echo "    Result: $kill_output"
  done
  count=$(echo "$bridge_pids" | wc -w | tr -d ' ')
  echo "  Killed $count bridge process(es)."
fi
echo ""

# Step 3: Find and kill orphaned go-sqlite3 pod processes
echo "[3/4] Querying go-sqlite3 pod processes..."
pod_output=$(powershell -Command "Get-WmiObject Win32_Process | Where-Object { \$_.Name -like '*sqlite3*' -or \$_.CommandLine -like '*go-sqlite3*' } | ForEach-Object { \$_.ProcessId.ToString() + '|' + \$_.Name + '|' + \$_.CommandLine }" 2>&1)
pod_exit=$?
echo "  PowerShell exit code: $pod_exit"

if [ -z "$pod_output" ] || [ "$pod_exit" -ne 0 ]; then
  echo "  No orphaned pod processes found."
else
  echo "  Found pod processes:"
  echo "$pod_output" | sed 's/^/    /'
  while IFS= read -r line; do
    line=$(echo "$line" | tr -d '\r')
    [ -z "$line" ] && continue
    pid=$(echo "$line" | cut -d'|' -f1)
    echo "  Killing pod PID $pid..."
    kill_output=$(taskkill //F //PID "$pid" 2>&1)
    echo "    Result: $kill_output"
  done <<< "$pod_output"
fi
echo ""

# Step 4: Verify nothing remains
echo "[4/4] Verification — checking for survivors..."
survivors=$(powershell -Command "Get-WmiObject Win32_Process | Where-Object { \$_.Name -eq 'bb.exe' -and \$_.CommandLine -like '*server.bb*' } | Select-Object -ExpandProperty ProcessId" 2>&1 | tr -d '\r')
if [ -z "$survivors" ]; then
  echo "  All clear. No bridge processes running."
else
  echo "  WARNING: Survivors found: $survivors"
  echo "  You may need to kill these manually via Task Manager."
fi

echo ""
echo "=== Done. Run /mcp to restart the bridge. ==="

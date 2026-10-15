#!/bin/bash
# DIAGNOSTIC, throwaway branch claude/diag-windows-core-hang: core_tests_integration run alone (no other test beside
# it), with a Java Flight Recording (DIAG_JFR) and the machine sampled every second; everything under profile/$VARIANT.
set -uo pipefail
out="profile/$VARIANT"
mkdir -p "$out"
. tools/ci/lane-flags.sh
if [ "$PLATFORM" = windows ]; then
  # Windows performance counters, one sample a second, as CSV; stopped below
  cat > "$out/counters.txt" <<'COUNTERS'
\Processor(_Total)\% Processor Time
\Processor(_Total)\% Privileged Time
\Processor(_Total)\% User Time
\System\Processor Queue Length
\System\Context Switches/sec
\Memory\Available MBytes
\Memory\Pages/sec
\PhysicalDisk(_Total)\% Disk Time
\PhysicalDisk(_Total)\Disk Reads/sec
\PhysicalDisk(_Total)\Disk Writes/sec
\PhysicalDisk(_Total)\Avg. Disk sec/Read
\PhysicalDisk(_Total)\Avg. Disk sec/Write
\PhysicalDisk(_Total)\Current Disk Queue Length
\Process(java*)\% Processor Time
\Process(java*)\% Privileged Time
\Process(java*)\IO Read Operations/sec
\Process(java*)\IO Write Operations/sec
\Process(java*)\IO Other Operations/sec
\Process(java*)\IO Data Bytes/sec
\Process(java*)\Working Set
\Process(MsMpEng)\% Processor Time
\Process(MsMpEng)\IO Data Bytes/sec
COUNTERS
  pwsh -NoProfile -Command "\$p = Start-Process typeperf -ArgumentList @('-cf', '$out/counters.txt', '-si', '1', '-sc', '1500', '-f', 'CSV', '-o', '$out/perf.csv', '-y') -PassThru -WindowStyle Hidden; \$p.Id | Out-File -Encoding ascii '$out/sampler.pid'"
else
  vmstat -t 1 > "$out/vmstat.txt" 2>&1 &
  echo $! > "$out/sampler.pid"
fi
start=$(date +%s)
bazel test "${tflags[@]}" //core:core_tests_integration --cache_test_results=no --local_test_jobs=1 \
  --test_env=DIAG_JFR=1 2>&1 | tail -15 | tee "$out/bazel.txt"
echo "bazel test took $(( $(date +%s) - start ))s" | tee -a "$out/bazel.txt"
if [ "$PLATFORM" = windows ]; then
  pwsh -NoProfile -Command "Stop-Process -Id (Get-Content '$out/sampler.pid') -ErrorAction SilentlyContinue"
else
  kill "$(cat "$out/sampler.pid")" 2>/dev/null || true
fi
T=$(bazel info "${flags[@]}" bazel-testlogs 2>/dev/null)
cp -r "$T/core/core_tests_integration/." "$out/testlogs/" 2>/dev/null || cp -r bazel-testlogs/core/core_tests_integration "$out/testlogs"
ls -la "$out" "$out/testlogs" "$out/testlogs/test.outputs" 2>/dev/null
grep -E "SUMMARY|JFR" "$out/testlogs/test.log" || true

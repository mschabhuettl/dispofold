#!/usr/bin/env bash
# Compares the Java port of the plugin sp5 with sp5_export.py byte for byte: runs sp5-export's own tests (a checkout
# at the commit in plugins/sp5/tools/ci/SP5_EXPORT_COMMIT) plus test_cases.py of this folder, with
# sp5_compare_plugin.py, which runs the Java port next to every run of sp5_export.py and compares exit code, output,
# dataset, report and warnings. Synthetic data only: the SP5 folders are written by the tests at run time.
# Usage: plugins/sp5/tools/ci/compare.sh <sp5-export checkout> [result.json]   (Python with pytest, JDK 21)
set -euo pipefail
repo=$(cd "$(dirname "$0")/../../../.." && pwd)
checkout=$(cd "${1:?sp5-export checkout}" && pwd)
result=${2:-$repo/backend/target/sp5-compare.json}
here="$repo/plugins/sp5/tools/ci"
(cd "$repo/backend" && ./mvnw -B -q compile dependency:build-classpath -Dmdep.outputFile=target/sp5-classpath.txt \
  -Dmdep.includeScope=runtime)
# The JVM announces JAVA_TOOL_OPTIONS on stderr, which would differ from Python's stderr; it is only for the build.
unset JAVA_TOOL_OPTIONS
export SP5_JAVA="java -XX:TieredStopAtLevel=1 -Xshare:auto -cp $repo/backend/target/classes:$(cat "$repo/backend/target/sp5-classpath.txt") app.dispofold.plugins.sp5.port.Sp5Main"
export SP5_COMPARE_OUT="$result"
cd "$checkout"
PYTHONPATH="$here${PYTHONPATH:+:$PYTHONPATH}" python -m pytest -q -p sp5_compare_plugin -c "$checkout/pyproject.toml" \
  --rootdir "$checkout" tests "$here/test_cases.py"

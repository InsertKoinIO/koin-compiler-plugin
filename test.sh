#!/bin/bash
#
# Run Koin Compiler Plugin tests
#
# Usage:
#   ./test.sh                           # Run all tests
#   ./test.sh --tests "*SingleBasic*"   # Run specific tests
#   ./test.sh -Pupdate.testdata=true    # Update golden files
#

./gradlew :koin-compiler-plugin:test --rerun-tasks "$@"

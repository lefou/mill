#!/usr/bin/env bash

set -eux

# Starting from scratch...
git stash -u
git stash -a

./mill -j 0 contrib.testng.publishLocal # Needed for CaffeineTests
# Run tests
./mill integration.test "mill.integration.local.{AcyclicTests,AmmoniteTests,DocAnnotationsTests}"

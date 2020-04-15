#!/usr/bin/env bash

set -eux

# Starting from scratch...
git stash -u
git stash -a

# Build Mill
./mill -i -j 0 dev.assembly

rm -rf ~/.mill

# Second build & run tests
out/dev/assembly/dest/mill -i -j 0 main.test.compile

out/dev/assembly/dest/mill -i -j 0 par {main,scalalib,scalajslib,scalanativelib,contrib.twirllib,contrib.scalapblib}.test

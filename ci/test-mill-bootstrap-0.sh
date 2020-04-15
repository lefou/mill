#!/usr/bin/env bash

set -eux

# Starting from scratch...
git stash -u
git stash -a

# First build
./mill -i -j 0 all __.publishLocal launcher
cp out/launcher/dest/mill ~/mill-1

# Clean up
git stash -u
git stash -a

rm -rf ~/.mill

# Differentiate first and second builds
echo "Build 2" > info.txt && git add info.txt && git commit -m "Add info.txt"

# Second build
~/mill-1 -i -j 0 par __.publishLocal launcher
cp out/launcher/dest/mill ~/mill-2

# Clean up
git stash -u
git stash -a

rm -rf ~/.mill

# Use second build to run tests using Mill
~/mill-2 -i -j 0 par {main,scalalib,scalajslib,scalanativelib}.test

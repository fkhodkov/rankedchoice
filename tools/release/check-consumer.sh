#!/bin/sh
# Builds and runs tools/release/consumer against a version of the library Maven can resolve: after
# `mvn -Prelease deploy`, which installs it locally too, and before it's published from the portal.
# Usage: tools/release/check-consumer.sh VERSION
set -eu
if [ $# -ne 1 ]; then
    echo "usage: $0 VERSION" >&2
    exit 2
fi
cd "$(dirname "$0")/consumer"
output=$(mvn -q -Drankedchoice.version="$1" clean compile exec:java)
echo "$output"
expected="ruleset quota: droop
elected: [Andrea, Brad]"
case "$output" in
    "$expected"*) echo "consumer check passed for $1" ;;
    *) echo "consumer check FAILED for $1" >&2; exit 1 ;;
esac

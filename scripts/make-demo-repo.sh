#!/usr/bin/env sh
# Builds a small Git repository from test-fixtures/demo-java at data/local-repos/demo-java.
# Register it in CODETRACE as a LOCAL repository with location /repos/demo-java (the path inside the containers).
set -eu
here=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
root=$(dirname "$here")
dest="$root/data/local-repos/demo-java"

rm -rf "$dest"
mkdir -p "$dest"
cp -R "$root/test-fixtures/demo-java/." "$dest/"
git -C "$dest" init -q -b main
git -C "$dest" add -A -f
git -C "$dest" -c user.name=demo -c user.email=demo@example.com -c commit.gpgsign=false commit -q -m "demo-java fixture"
echo "Created $dest at commit $(git -C "$dest" rev-parse HEAD)"

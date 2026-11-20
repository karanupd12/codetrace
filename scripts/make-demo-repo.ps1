# Builds a small Git repository from test-fixtures/demo-java at data/local-repos/demo-java.
# Register it in CODETRACE as a LOCAL repository with location /repos/demo-java (the path inside the containers).
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$dest = Join-Path $root 'data/local-repos/demo-java'

if (Test-Path $dest) { Remove-Item -Recurse -Force $dest }
New-Item -ItemType Directory -Force $dest | Out-Null
Copy-Item -Recurse -Force (Join-Path $root 'test-fixtures/demo-java/*') $dest
git -C $dest init -q -b main
git -C $dest add -A -f
git -C $dest -c user.name=demo -c user.email=demo@example.com -c commit.gpgsign=false commit -q -m "demo-java fixture"
"Created $dest at commit $(git -C $dest rev-parse HEAD)"

#!/usr/bin/env sh
set -ex
if [ -z "$1" ]; then
    echo "Usage: $(basename $0) <output-dir>"
    exit 1
fi
out="$(readlink -f "$1")"
workdir="$(pwd)"
clidir="$(dirname $0)"
cd "$clidir"
version="$(git describe --tags --match "v*")"
echo -n $version > resources/VERSION
bb uberjar cli.jar -m garden
tmpdir="$(mktemp -d)"
mv cli.jar "$tmpdir"
cd "$tmpdir"
curl -o bb.tar.gz -sL https://github.com/babashka/babashka-dev-builds/releases/download/v1.3.186-SNAPSHOT/babashka-1.3.186-SNAPSHOT-linux-amd64-static.tar.gz
tar xzf bb.tar.gz
cat ./bb cli.jar > garden
chmod +x garden
mkdir -p $out
mv garden $out


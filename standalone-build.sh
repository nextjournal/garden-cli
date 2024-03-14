#!/bin/sh
set -ex
if [ -z "$1" ]; then
    echo "Usage: $(basename "$0") <target-dir>"
    exit 1
fi
workdir="$(pwd)"
clidir="$(dirname "$0")"
targetdir="$(readlink -f "$1")"
mkdir -p "$targetdir"
cd "$clidir"
bb_version="$(bb -o "(:min-bb-version (clojure.edn/read-string (slurp \"bb.edn\")))")"
bb uberjar cli.jar -m nextjournal.garden-cli
tmpdir="$(mktemp -d)"
mv cli.jar "$tmpdir"
cd "$tmpdir"
for arch in macos-aarch64 macos-amd64 linux-aarch64-static linux-amd64-static; do
    echo "Building for ${arch}"
    curl -o bb.tar.gz -sL "https://github.com/babashka/babashka/releases/download/v${bb_version}/babashka-${bb_version}-${arch}.tar.gz"
    tar xzf bb.tar.gz
    cat ./bb cli.jar > garden
    chmod +x garden
    tar caf "$targetdir/garden-${arch}.tar.gz" garden
    sha256sum "$targetdir/garden-${arch}.tar.gz" | cut -d " " -f 1 > "$targetdir/garden-${arch}.tar.gz.sha256"
done
cd "$workdir"

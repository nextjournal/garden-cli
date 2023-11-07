#!/bin/sh
set -ex
if [ -z "$1" ]; then
    echo "Usage: $(basename "$0") <target-dir>"
    exit 1
fi
workdir="$(pwd)"
clidir="$(dirname "$0")"
target_dir="$(readlink -f "$1")"
cd "$clidir"
rev="$(git rev-parse HEAD)"
shortRev="$(git rev-parse --short HEAD)"
mkdir -p "$target_dir"
tmpdir="$(mktemp -d)"
cd "$tmpdir"
echo "#!/bin/sh
exec bb -Sdeps '{:deps {io.github.nextjournal/garden-cli {:git/sha \"${rev}\"}}}' -Dnextjournal.garden.rev=${shortRev} -m nextjournal.garden-cli \$@" > garden
chmod +x garden
tar caf "$target_dir/garden.tar.gz" garden
sha256sum "$target_dir/garden.tar.gz" | cut -d " " -f 1 > "$target_dir/garden.tar.gz.sha256"
cd "$workdir"

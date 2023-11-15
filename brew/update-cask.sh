#!/usr/bin/env sh
set -ex
PAT="$1"
template="$(readlink -f "$(dirname "$0")/garden-cask.template")"
VERSION="$(cat "$(readlink -f "$(dirname "$0")/../resources/VERSION")")"
SHA256_AARCH64="$(curl -L "https://github.com/nextjournal/garden-cli/releases/download/v${VERSION}/garden-macos-aarch64.tar.gz.sha256")"
SHA256_AMD64="$(curl -L "https://github.com/nextjournal/garden-cli/releases/download/v${VERSION}/garden-macos-amd64.tar.gz.sha256")"
if [ -z "$VERSION" ]; then
    echo "No version found in $(readlink -f "$(dirname "$0")/../resources/VERSION")"
    exit 1
fi
if [ -z "$SHA256_AARCH64" ]; then
    echo "No version found in $(readlink -f "$(dirname "$0")/../target/garden-macos-aarch64.tar.gz.sha256")"
    echo "Run standalone-build.sh first."
    exit 1
fi
if [ -z "$SHA256_AARCH64" ]; then
    echo "No version found in $(readlink -f "$(dirname "$0")/../target/garden-macos-aarch64.tar.gz.sha256")"
    echo "Run standalone-build.sh first."
    exit 1
fi
cd "$(mktemp -d)"
git clone "https://x-access-token:${PAT}@github.com/nextjournal/homebrew-brew"
cd homebrew-brew
mkdir -p Casks
cp "$template" Casks/garden.rb
sed -i "s/\${VERSION}/${VERSION}/g" Casks/garden.rb
sed -i "s/\${SHA256_AARCH64}/${SHA256_AARCH64}/g" Casks/garden.rb
sed -i "s/\${SHA256_AMD64}/${SHA256_AMD64}/g" Casks/garden.rb
git config user.name "Update"
git config user.email "nextjournal@users.noreply.github.com"
git add Casks/garden.rb
git commit -m "Update garden CLI cask"
git push

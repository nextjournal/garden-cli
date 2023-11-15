#!/usr/bin/env sh
set -ex
PAT="$1"
template="$(readlink -f "$(dirname "$0")/garden-formula.template")"
VERSION="$(cat "$(readlink -f "$(dirname "$0")/../resources/VERSION")")"
LINUX_ARM_SHA="$(curl -L "https://github.com/nextjournal/garden-cli/releases/download/v${VERSION}/garden-linux-amd64-static.tar.gz.sha256")"
LINUX_AMD_SHA="$(curl -L "https://github.com/nextjournal/garden-cli/releases/download/v${VERSION}/garden-linux-amd64-static.tar.gz.sha256")"
MACOS_ARM_SHA="$(curl -L "https://github.com/nextjournal/garden-cli/releases/download/v${VERSION}/garden-macos-aarch64.tar.gz.sha256")"
MACOS_AMD_SHA="$(curl -L "https://github.com/nextjournal/garden-cli/releases/download/v${VERSION}/garden-macos-amd64.tar.gz.sha256")"
cd "$(mktemp -d)"
git clone "https://x-access-token:${PAT}@github.com/nextjournal/homebrew-brew"
cd homebrew-brew
cp "$template" garden.rb
sed -i "s/\${VERSION}/${VERSION}/g" garden.rb
sed -i "s/\${LINUX_ARM_SHA}/${LINUX_ARM_SHA}/g" garden.rb
sed -i "s/\${LINUX_AMD_SHA}/${LINUX_AMD_SHA}/g" garden.rb
sed -i "s/\${MACOS_ARM_SHA}/${MACOS_ARM_SHA}/g" garden.rb
sed -i "s/\${MACOS_AMD_SHA}/${MACOS_AMD_SHA}/g" garden.rb
git config user.name "Update"
git config user.email "nextjournal@users.noreply.github.com"
git add garden.rb
git commit -m "Update garden CLI"
git push

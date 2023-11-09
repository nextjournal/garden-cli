#!/usr/bin/env sh
set -ex
PAT="$1"
template="$(readlink -f "$(dirname "$0")/garden-formula.template")"
VERSION="$(cat "$(readlink -f "$(dirname "$0")/../resources/VERSION")")"
SHA256="$(curl -L "https://github.com/nextjournal/garden-cli/releases/download/v${VERSION}/garden.tar.gz.sha256")"
cd "$(mktemp -d)"
git clone "https://x-access-token:${PAT}@github.com/nextjournal/homebrew-brew"
cd homebrew-brew
cp "$template" garden.rb
sed -i "s/\${VERSION}/${VERSION}/g" garden.rb
sed -i "s/\${SHA256}/${SHA256}/g" garden.rb
git config user.name "Update"
git config user.email "nextjournal@users.noreply.github.com"
git add garden.rb
git commit -m "Update garden CLI"
git push

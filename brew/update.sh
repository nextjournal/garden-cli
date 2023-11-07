#!/usr/bin/env sh
set -ex
VERSION="$1"
SHA256="$2"
PAT="$3"
template="$(readlink -f "$(dirname "$0")/garden.template")"
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

#!/usr/bin/env bash
# Fetch the shared web UI into app/src/main/assets/webui/ (gradle packs it).
#
# The UI lives in exactly ONE place (easydev-org/easystream-webui); every daemon
# embeds/packages it at build time instead of vendoring a copy.
set -Eeuo pipefail
cd "$(dirname "$0")"

REF="${WEBUI_REF:-main}"
PROXY="${HTTPS_PROXY:-${HTTP_PROXY:-}}"
URL="https://codeload.github.com/easydev-org/easystream-webui/tar.gz/refs/heads/${REF}"
# Append the cachebust to the URL itself, not just to a printed line.
#
# Two caches can serve a stale webui and only one of them was being defeated:
# Docker's layer cache (fixed by making the RUN's cache key change) and the CDN
# in front of codeload.github.com, which happily returns the previous tarball for
# the same URL. A query string makes the CDN miss. Verified: without it the APK
# kept carrying the 39538-byte app.js while the repo had 45174 bytes.
if [ -n "${WEBUI_CACHEBUST:-}" ]; then URL="${URL}?cb=${WEBUI_CACHEBUST}"; fi
DEST="app/src/main/assets/webui"

# WEBUI_CACHEBUST exists to defeat a Docker layer cache, not an HTTP one.
#
# The Dockerfile runs `bash build-webui.sh` in a RUN step. Docker caches that
# step on its command string, so a webui change — which is invisible to the
# cache key — was silently skipped: the image kept shipping the previous
# app.js (39538 bytes) while the repo had the new one. Passing a changing value
# (the Containerfile sets it from a build arg) changes the step's cache key.
echo "webui fetch (cachebust=${WEBUI_CACHEBUST:-none})"

rm -rf "${DEST}"; mkdir -p "${DEST}"
tmp="$(mktemp -d)"; trap 'rm -rf "${tmp}"' EXIT
curl -fsSL ${PROXY:+-x "${PROXY}"} -o "${tmp}/webui.tgz" "${URL}"
tar xzf "${tmp}/webui.tgz" -C "${tmp}"
cp "${tmp}"/easystream-webui-*/webui/app.js     "${DEST}/app.js"
cp "${tmp}"/easystream-webui-*/webui/index.html "${DEST}/index.html"
echo "webui ${REF} -> ${DEST} ($(wc -c <"${DEST}/app.js") bytes app.js)"

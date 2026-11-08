#!/bin/sh
set -eu
cd "$(dirname "$0")"
resources="$PWD/ActionDoc AI.app/Contents/Resources"
export ACTIONDOC_OCR_COMMAND="$resources/actiondoc-ocr"
exec "$resources/runtime/bin/java" -Djava.awt.headless=true -jar "$resources/server.jar"

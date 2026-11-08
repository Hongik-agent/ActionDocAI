#!/bin/bash
set -euo pipefail
project_dir="$(cd "$(dirname "$0")/.." && pwd)"
app_dir="$project_dir/ActionDoc AI.app"
build_dir="$project_dir/desktop/build"
mkdir -p "$build_dir/module-cache" "$app_dir/Contents/MacOS" "$app_dir/Contents/Resources"
maven_bin="$(command -v mvn || true)"
if [ -z "$maven_bin" ]; then maven_bin="$HOME/.m2/wrapper/dists/apache-maven-3.9.6-bin/3311e1d4/apache-maven-3.9.6/bin/mvn"; fi
java_build="${JAVA_HOME:-/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home}"
"$maven_bin" -q -f "$project_dir/pom.xml" package
cp "$project_dir/target/actiondoc-ai-0.1.0.jar" "$app_dir/Contents/Resources/server.jar.next"
mv "$app_dir/Contents/Resources/server.jar.next" "$app_dir/Contents/Resources/server.jar"
if [ ! -x "$app_dir/Contents/Resources/runtime/bin/java" ]; then
 "$java_build/bin/jlink" --add-modules java.base,java.desktop,java.instrument,java.logging,java.management,java.naming,java.net.http,java.rmi,java.security.jgss,java.sql,java.xml,jdk.crypto.ec,jdk.unsupported,jdk.localedata,jdk.management --strip-debug --no-header-files --no-man-pages --compress=2 --output "$app_dir/Contents/Resources/runtime"
fi
xcrun swiftc -swift-version 5 -parse-as-library -O -module-cache-path "$build_dir/module-cache" -target arm64-apple-macos14.0 -framework AppKit -framework SwiftUI -framework Carbon -framework Security -lsqlite3 "$project_dir"/desktop/Sources/*.swift -o "$build_dir/ActionDoc.next"
xcrun swiftc -swift-version 5 -parse-as-library -O -module-cache-path "$build_dir/module-cache" -target arm64-apple-macos14.0 -framework Vision -framework ImageIO "$project_dir/desktop/OCR/Main.swift" -o "$build_dir/actiondoc-ocr.next"
mv "$build_dir/ActionDoc.next" "$app_dir/Contents/MacOS/ActionDoc"
mv "$build_dir/actiondoc-ocr.next" "$app_dir/Contents/Resources/actiondoc-ocr"
cp "$project_dir/desktop/Info.plist" "$app_dir/Contents/Info.plist"
codesign --force --deep --sign - "$app_dir"
"$app_dir/Contents/MacOS/ActionDoc" --self-test

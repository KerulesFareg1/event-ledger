#!/bin/sh
#
# Licensed to the Apache Software Foundation (ASF) under one
# or more contributor license agreements. See the NOTICE file
# distributed with this work for additional information
# regarding copyright ownership. The ASF licenses this file
# to you under the Apache License, Version 2.0.

set -eu

BASE_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
WRAPPER_PROPERTIES="$BASE_DIR/.mvn/wrapper/maven-wrapper.properties"
MAVEN_URL=$(sed -n 's/^distributionUrl=//p' "$WRAPPER_PROPERTIES")
MAVEN_VERSION=$(printf '%s' "$MAVEN_URL" | sed -n 's|.*/apache-maven-\([^/]*\)-bin\.zip|\1|p')
MAVEN_HOME="${MAVEN_USER_HOME:-$HOME/.m2}/wrapper/dists/apache-maven-$MAVEN_VERSION"
MAVEN_BIN="$MAVEN_HOME/apache-maven-$MAVEN_VERSION/bin/mvn"

if [ ! -x "$MAVEN_BIN" ]; then
    ARCHIVE="$MAVEN_HOME/apache-maven.zip"
    mkdir -p "$MAVEN_HOME"
    printf '%s\n' "Downloading Maven $MAVEN_VERSION..."
    curl -fsSL "$MAVEN_URL" -o "$ARCHIVE"
    unzip -q "$ARCHIVE" -d "$MAVEN_HOME"
    rm -f "$ARCHIVE"
fi

exec "$MAVEN_BIN" "$@"

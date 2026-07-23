SHELL := /bin/bash

SDK_ROOT ?= $(HOME)/Library/Android/sdk
NDK_VERSION ?= 29.0.14206865
NDK_ROOT ?= $(SDK_ROOT)/ndk/$(NDK_VERSION)
NDK_HOST ?= darwin-x86_64
ANDROID_API ?= 26

GO ?= go
GRADLE ?= ./gradlew
GIT ?= git

# CottenDNS is not vendored in this repository. The engine is checked out at a
# pinned commit and built from its own source tree, so this app and CI always
# build the exact same engine code instead of a hand-ported copy.
CottenDns_REPO := https://github.com/WhiteDNS/CottenDns.git
CottenDns_SHA_FILE := .engine/COTTENDNS_ENGINE_SHA
CottenDns_DIR := .engine/CottenDns
JNI_LIBS_DIR := app/src/main/jniLibs
GO_CACHE := .engine/.gocache

.PHONY: all debug CottenDns clean clean-CottenDns clean-app checkout-CottenDns check-ndk debug-outputs

all: debug

debug: CottenDns
	$(GRADLE) :app:assembleDebug

checkout-CottenDns:
	@test -f "$(CottenDns_SHA_FILE)" || (echo "Missing $(CottenDns_SHA_FILE); set the pinned CottenDNS engine commit."; exit 1)
	@sha="$$(cat $(CottenDns_SHA_FILE))"; \
	if [[ ! "$$sha" =~ ^[0-9a-fA-F]{40}$$ ]]; then \
		echo "$(CottenDns_SHA_FILE) does not contain a full 40-character commit SHA: $$sha"; exit 1; \
	fi; \
	if [[ ! -d "$(CottenDns_DIR)/.git" ]]; then \
		$(GIT) clone "$(CottenDns_REPO)" "$(CottenDns_DIR)"; \
	else \
		$(GIT) -C "$(CottenDns_DIR)" fetch origin; \
	fi; \
	$(GIT) -C "$(CottenDns_DIR)" checkout --detach "$$sha"

check-ndk:
	@test -x "$(NDK_ROOT)/toolchains/llvm/prebuilt/$(NDK_HOST)/bin/aarch64-linux-android$(ANDROID_API)-clang" || (echo "Android NDK not found at $(NDK_ROOT). Install NDK $(NDK_VERSION) or set NDK_ROOT=/path/to/ndk."; exit 1)

CottenDns: checkout-CottenDns check-ndk
	@mkdir -p "$(JNI_LIBS_DIR)" "$(GO_CACHE)"
	cd "$(CottenDns_DIR)" && \
		GOCACHE="$$(cd .. && pwd)/.gocache" \
		NDK_ROOT="$(NDK_ROOT)" \
		NDK_HOST="$(NDK_HOST)" \
		ANDROID_API="$(ANDROID_API)" \
		GO_BIN="$(GO)" \
		OUTPUT_DIR="$$(cd ../.. && pwd)/$(JNI_LIBS_DIR)" \
		bash scripts/build-android-client.sh all

debug-outputs:
	@find app/build/outputs/apk/debug -type f -name '*.apk' -print | sort

clean: clean-app clean-CottenDns

clean-app:
	$(GRADLE) :app:clean

clean-CottenDns:
	rm -rf "$(GO_CACHE)"

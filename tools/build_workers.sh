#!/usr/bin/env bash
set -euo pipefail

sources=$1
output=$2
ndk=$3
ohos_sdk=$4
if [[ -z "$ohos_sdk" && -d /Applications/DevEco-Studio.app/Contents/sdk/default/openharmony/native ]]; then
    ohos_sdk=/Applications/DevEco-Studio.app/Contents/sdk/default/openharmony/native
fi
if [[ ! -x "$ohos_sdk/llvm/bin/clang++" || ! -d "$ohos_sdk/sysroot" ]]; then
    printf '%s\n' 'Set OHOS_NATIVE_SDK to the OpenHarmony native SDK directory.' >&2
    exit 1
fi

case "$(uname -s)" in
    Darwin) host=darwin-x86_64 ;;
    Linux) host=linux-x86_64 ;;
    *) printf '%s\n' 'Build native workers on macOS or Linux.' >&2; exit 1 ;;
esac
android_clang="$ndk/toolchains/llvm/prebuilt/$host/bin/aarch64-linux-android26-clang"
if [[ ! -x "$android_clang" ]]; then
    printf '%s\n' 'Install Android NDK 27.2.12479018 with sdkmanager.' >&2
    exit 1
fi
ohos_clang="$ohos_sdk/llvm/bin/clang++"
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
mkdir -p "$output/arm64-v8a" "$work/include"

page_size_flags=(-Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384)
"$android_clang" -std=c11 -D_GNU_SOURCE -O2 -Wall -Wextra -Werror -fPIE -pie \
    "${page_size_flags[@]}" \
    "$sources/probe.c" -o "$output/arm64-v8a/libcorevision_probe.so"

sed -E 's/(#define _LIBCPP_ABI_NAMESPACE )[[:alnum:]_]+/\1__n1/' \
    "$ohos_sdk/llvm/include/c++/v1/__config_site" > "$work/include/__config_site"
target=(--target=aarch64-linux-ohos --sysroot="$ohos_sdk/sysroot" -fuse-ld=lld)
"$ohos_clang" "${target[@]}" -shared -nostdlib -Wl,-soname,libc++_shared.so \
    -x c /dev/null -o "$work/libc++_shared.so"
"$ohos_clang" "${target[@]}" -std=c++17 -O2 -Wall -Wextra -Werror \
    -I"$work/include" -fPIE -pie -nostdlib++ -ffp-contract=off \
    -Wl,-E,--dynamic-linker=/lib/ld-musl-aarch64.so.1 "${page_size_flags[@]}" \
    -Wl,--no-as-needed,"$work/libc++_shared.so",--as-needed,--unresolved-symbols=ignore-all \
    "$sources/engine.cpp" "$sources/face_feature.cpp" "$sources/ocr_bridge.cpp" "$sources/text_feature.cpp" \
    -o "$output/arm64-v8a/libcorevision_worker.so"

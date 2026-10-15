# NOTE (Galaxian port): CPU `scaling_locked_limits` requires the kernel patch
# described below. Galaxian ships a prebuilt kernel without it, so CPU locks
# fall back to temporary limits until the Galaxian kernel is patched.

# Galaxian kernel build and CPU frequency ownership

The patched kernel `6.1.177-android14-11-lufrz` has been compiled and imported
into `device/nothing/Galaxian-kernel`, including the CPU ownership interface from
`patches/integration/kernel-cpu-lock.patch`. The built binary contains both
`show_scaling_locked_limits` and `store_scaling_locked_limits`.
`BoardConfigKernel.mk` continues to select `TARGET_FORCE_PREBUILT_KERNEL := true`
and now consumes the rebuilt `Image` and matching module distribution.

The import contains 495 files, including 481 `.ko` paths representing 467 distinct
rebuilt modules. No old module was retained. Exact symbol CRCs, vermagic and
partition dependency metadata passed host checks; boot and on-device behavior
have not been tested. Parts detects the interface at runtime and still reports
ordinary frequency controls as temporary on an older kernel without it.

**ROM packaging status: final ROM build PASS; OTA kernel name, timestamp and CPU lock verified.**

## Interface and scope

Each policy exposes `scaling_locked_limits`:

- Read: `0 min max` for unlocked current limits, `1 min max` for a locked range.
- Write `1 min max` to atomically validate, apply and own both bounds, in kHz.
- Write `0` to release ownership and apply the **current** ordinary QoS requests.

The kernel serializes the entire transaction with the policy write semaphore,
requires frequencies from the available OPP table, and attempts to roll back a
failed application. Power HAL and other ordinary frequency-QoS clients may keep
submitting requests, but cannot replace locked policy bounds. The private state
survives app exit and policy CPU hotplug, until explicitly released, the driver
is removed, or the phone reboots. Parts' Restore button also releases the locks.

This overrides CPU cooling caps implemented through frequency QoS, regardless of the
separate global thermal profile setting. It does not overclock, modify the
OPP table, disable emergency shutdown, or remove hardware/firmware constraints.
A fixed software range is not a promise of an exact measured physical clock in
all conditions. The CPU idle mechanism continues to operate.

The lock node has its own SELinux label, `vendor_galaxian_cpu_lock`. Only Parts can
write it; the Power HAL rules for ordinary frequency controls do not grant
ownership changes. The patch uses a private allocation wrapper and does not
change the exported `struct cpufreq_policy` layout or add exported symbols.
That avoids a KMI layout change but does not certify the ABI of an arbitrary
rebuilt kernel against the shipped modules.

## Source versions

These are the bases of this integration; keep the local CPU lock and build
metadata patches when creating a separate kernel build checkout.

| Checkout | Base commit |
| --- | --- |
| `kernel/nothing/galaxian` | `f810d726b10b9ae24a627c5bf317a534c2d9a1c6` |
| `kernel/nothing/galaxian-device-modules` | `a70397a9db3c062ff2087f24881076670b4d22eb` |
| `kernel/nothing/galaxian-modules` | `48e57b184f1b092c9be69f74d6d5bf6e7f80d0cc` |
| `device/nothing/Galaxian-kernel` (original prebuilt base) | `cfd650165cc3dd743e12da86b4dd78bb870bd991` |
| Nothing build infrastructure (`out/kernel-galaxian-build/workspace/build`) | `9331088f57045ca5f136f30f767a2f7db3438f78` |

The kernel's `build.config.constants` selects Android 14 / Linux 6.1 and
`clang-r596125`; that compiler is present in this ROM checkout.

## Isolated Nothing build workspace

The completed kernel build used the separate workspace
`out/kernel-galaxian-build/workspace`, with the official
`NothingOSS/android_kernel_build_nothing_mt6878` build infrastructure at the
revision listed above. Its sources are local shared clones of the three kernel
repositories, with the integration patches applied. The Android ROM's `build`
directory is not replaced.

The complete kernel/module build exited successfully on 2026-09-28 at
20:11:23 UTC. Its build, selection and compatibility evidence is recorded in
`out/kernel-galaxian-build/BUILD-REPORT.md`. The validated 495-file distribution
has since been imported into the ROM's prebuilt source tree. These are host build
and compatibility results, not certification of device-runtime behavior.

`gki_defconfig` contains `CONFIG_CPU_FREQ=y`, so the CPU lock patch needs no
additional Kconfig option. The Parts thermal profile bridge uses the stock vendor
daemon and does not require a MediaTek driver-module source change.

| Kernel workspace path | Source from this ROM checkout |
| --- | --- |
| `kernel-6.1` | `kernel/nothing/galaxian` (with local patches) |
| `kernel_device_modules-6.1` | `kernel/nothing/galaxian-device-modules` |
| `vendor/mediatek/kernel_modules` | `kernel/nothing/galaxian-modules` |

`out/kernel-galaxian-build/prepare-workspace.sh` prepares these checkouts.
`configure-legacy.sh` generates the MediaTek build configuration from
`mgk_64_k61_defconfig` with `Galaxian.config` and `bliss.config`, and connects the
existing ROM compiler/build tools without modifying them. The build uses
`clang-r596125`, thin LTO and the official Android NDK r23c sysroot
(`23.2.8568313`). Generated configuration lives in `workspace/generated/`.

The final recipe in `configure-legacy.sh` also resolves the generic external
module list without changing driver configuration or weakening modpost checks:

- Exclude `fpsgo_cus`: Galaxian disables `MTK_FPSGO_V3` and ships neither `fpsgo.ko`
  nor `game.ko`.
- Build `conninfra_mt6653` immediately after `conninfra`, before its GPS and
  Bluetooth consumers; retain `connfem` before the Bluetooth consumers.
- Replace the generic `wlan/adaptor_mt6653` group with
  `wlan/adaptor_mt6653/build/connac3x_6989_6653`, before both MT6653 Wi-Fi core
  variants. The dedicated subgroup produces the required symbols without
  invoking the malformed parent wrapper or duplicating the generic adapters.

The actual entry point used here is the legacy Nothing/MediaTek path:

```sh
bash out/kernel-galaxian-build/run-legacy.sh
```

This enters the isolated workspace and invokes `build/build.sh` with
`BUILD_CONFIG=generated/build.config`; the MediaTek configuration uses
`kernel_device_modules-6.1/scripts/legacy_build.sh`. The wrapper limits the build
to 12 CPUs and `-j12`. It writes objects under `out/kernel-galaxian-build/output`,
the distribution under `out/kernel-galaxian-build/dist`, and logs under
`out/kernel-galaxian-build/logs/`. `build.status`, `build-provenance.txt` and
`source-revisions.txt` record the attempt and source revisions. A generated
release string or an existing object is not evidence that the complete build
succeeded.

The device-modules repository also contains a Kleaf-oriented `build.sh`, but
that is not the entry point above. The legacy configuration leaves Android to
package boot/vendor_boot/DLKM after a validated import. Before importing, check
the rebuilt kernel against all modules that will ship, including any retained
prebuilt modules; an unchanged public struct alone does not prove compatibility.

## Kernel branding and timestamp

The additional reproducible patches under `patches/integration/` are:

- `kernel-build-metadata.patch`: only `arch/arm64/configs/gki_defconfig`, setting
  `CONFIG_LOCALVERSION="-lufrz"` in `kernel/nothing/galaxian`.
- `device-modules-build-timestamp.patch`: only `scripts/gen_build_config.py` in
  `kernel/nothing/galaxian-device-modules`. Its generated configuration uses the
  current Unix epoch when `SOURCE_DATE_EPOCH` is unset or empty, and defaults
  `GKI_SOURCE_DATE_EPOCH` to that value. Explicit nonempty values, including
  `0`, are preserved independently.

`run-legacy.sh` writes `-android14-11` to the isolated kernel's `localversion`
and creates an empty `.scmversion`, preserving the Android KMI generation while
avoiding an appended Git suffix. The generated release was verified as
`6.1.177-android14-11-lufrz`. These workspace files do not change the original
kernel checkout.

Each wrapper invocation sets both build epochs to its current time, uses UTC,
and records that time in `build-provenance.txt`. For an intentionally repeatable
timestamp, pass `GALAXIAN_BUILD_EPOCH` explicitly:

```sh
GALAXIAN_BUILD_EPOCH=1790624405 bash out/kernel-galaxian-build/run-legacy.sh
```

This override takes precedence over externally supplied `SOURCE_DATE_EPOCH`
when using the wrapper. Direct users of the generated build configuration retain
the explicit epoch behavior described above. The imported binary embeds
`Mon Sep 28 19:40:05 UTC 2026` (epoch `1790624405`). Its actual build identity is
`build-user@build-host`: the official GKI infrastructure overrides the identity
variables exported by the wrapper. The name and timestamp behavior remain saved
in the source patches above, rather than depending on binary editing.

## Imported artifacts and future rebuilds

The previous `device/nothing/Galaxian-kernel` artifacts are preserved in
`out/codex-build/Galaxian/previous-kernel`. The completed import selected only the
original Galaxian module layout from the generic distribution: 481 `.ko` paths,
467 distinct modules and 14 identical copies. All were rebuilt; zero old `.ko`
files were retained. Module dependency metadata was regenerated while preserving
normal and recovery loading order.

The imported legacy-LZ4 `Image` has SHA-256
`8eb0c4edd2be23768f2d8b4264d197baa7015eb7735d3013615ca682bbcd36d5`.
The rebuilt MT6878 DTB was byte-identical to the previous one, so the existing
DTB and DTBO were kept unchanged. The 495 imported files are recorded in
`out/kernel-galaxian-build/import-ready-sha256.json`.

For future rebuilds, preserve the current artifacts before replacement. The ROM
uses these exact destinations:

| Rebuilt artifact | Destination |
| --- | --- |
| ARM64 `Image.lz4`, compressed in legacy LZ4 format | `device/nothing/Galaxian-kernel/Image` |
| system kernel modules and module metadata | `device/nothing/Galaxian-kernel/system_dlkm/` |
| device/vendor modules and module metadata | `device/nothing/Galaxian-kernel/vendor_dlkm/` |
| early boot modules and module metadata | `device/nothing/Galaxian-kernel/vendor_boot/` |
| matching DTB image(s), if changed | `device/nothing/Galaxian-kernel/dtbs/` |
| matching DTBO image, if changed | `device/nothing/Galaxian-kernel/dtbo.img` |

Keep the module partition assignment and required `modules.load`,
`modules.dep`, `modules.alias`, `modules.softdep`, signing and release/version
metadata consistent with the rebuilt kernel. Replacing only `.ko` files while
leaving stale dependency metadata is not a complete import. The CPU lock patch
itself does not modify the DTB/DTBO interface.

The existing prebuilt named `Image` is **legacy LZ4 compressed**, not a raw
ARM64 image (`file` identifies LZ4 v0.1-v0.9). The kernel's compression rule uses
`lz4 -l`; import the matching `Image.lz4` under the ROM's expected filename
`Image`, preserving that format. Do not copy the uncompressed `Image` merely
because its name matches the destination.

After the build and compatibility checks pass, set `matched_kernel_image` to
the actual `Image.lz4` from the matching distribution:

```sh
matched_kernel_image=/absolute/path/to/matched/distribution/Image.lz4
install -m 0644 "$matched_kernel_image" device/nothing/Galaxian-kernel/Image
```

Then run your normal Galaxian ROM build to package the imported Image and matched
modules into its boot/vendor_boot/DLKM images. There is no need to disable
`TARGET_FORCE_PREBUILT_KERNEL` when using this import path. Merely pointing
`TARGET_KERNEL_SOURCE` at another folder while leaving the force-prebuilt flag
set would still package the old Image.

## Verification

`out/kernel-galaxian-build/selected-final-abi.json` validates all 467 selected
modules against the rebuilt kernel and selected module exports, with zero
missing symbols, CRC mismatches, provider conflicts or vermagic mismatches.
`module-metadata/report.json` in the same build directory reports zero metadata
errors. Debug stripping preserved module names, symbol exports, imported CRCs
and dependencies. These checks do not replace a boot or hardware test.

Additional host checks available in the ROM checkout:

```sh
device/nothing/Galaxian/app/parts/tests/run-cpu-tests.sh
python3 device/nothing/Galaxian/app/parts/tests/check-kernel-cpu-lock.py
```

The latter compiles the actual added kernel parser/store/policy functions in a
host harness. It checks competing QoS, unlock behavior, malformed input, driver
failure and rollback. It is not a kernel build, KMI certification or a phone test.

On a phone running the rebuilt kernel and updated ROM, both
`/sys/devices/system/cpu/cpufreq/policy0/scaling_locked_limits` and
`policy4/scaling_locked_limits` must exist. Apply a range in Parts; readback must
show `1 min max`, and `scaling_min_freq`/`scaling_max_freq` must remain that pair
through normal Android boosts and mode changes. Unlock and verify that current
Android requests take effect again. Reboot and verify that both locks are off.

# Galaxian thermal policy bridge

The app requests `sys.galaxianparts.thermal_disabled=1` or `0`. The vendor helper
uses MTK's existing `/dev/socket/thermal_socket`, sending the fixed command
`apply disable_throttling.conf` and confirming the accepted profile using
`/data/vendor/thermal/.current_tp`. It respects the daemon's file lock.

The app reads these properties (all under `vendor.galaxianparts.`):

| Property suffix | Meaning |
| --- | --- |
| `thermal_state` | `changing`, `disabled`, `enabled`, or `error` when actual state is unknown |
| `thermal_profile` | The confirmed `.current_tp` basename, or empty if unreadable/unconfirmed |
| `thermal_error` | `none` or the last failure code |
| `thermal_errno` | Associated OS error number, or 0 for a protocol/profile failure |
| `thermal_result_id` | Completion counter; read before requesting and wait for a different value |

The completion counter is published **after** the profile/error/state and resets
to zero at boot. It advances for successful operations and failures, including
repeated requests for the same mode. A failed disable with successful rollback
reports `enabled` plus the failure code; the UI must permit another disable
attempt. An unknown state must not trap the user in a restore-only dialog.

Failure codes: `current_missing`, `current_denied`, `current_busy`,
`current_invalid`, `profile_missing`, `profile_denied`, `profile_unsafe`,
`snapshot_failed`, `socket_missing`, `socket_denied`, `socket_connect_failed`,
`socket_send_failed`, `socket_timeout`, `apply_unconfirmed`, `restore_missing`,
`restore_failed`, `daemon_stopped`. The helper also logs the code, errno and
observed profile to Android's system log under its executable name.

## Init compatibility

`thermal_core` keeps its stock legacy service name. In current Lineage policy,
the generic `init.svc.*` prefix is a **private** system property. Vendor init
rejects an action that references such a property when
`ro.actionable_compatible_property.enabled=true`; syntactically valid RC files
alone do not detect this. `init.svc.thermal_core` therefore has an exact exported
system property label, readable by vendor init and this helper, writable only
by init. Test both RC syntax **and effective property-read policy**.

The single action runs after boot for each request and every daemon status
change. The helper confirms that the daemon is running, then sends an empty
half-closed socket probe. The daemon's verified receive-zero path parses no
command and closes the client. That close confirms it has completed startup
and reached its accept loop before the helper reads `.current_tp`, which can
otherwise contain a stale profile from a previous process. Real apply commands
also wait for the peer to close, then check the profile file; a socket write
alone is never an application acknowledgment.

## Profile and restore behavior

The stock profile from the current vendor repository has `permanent=No` and
`[LTF-disable-throttling] HW_protection=enabled`. The helper verifies these fields
before applying it. This disables MTK's normal software throttling policy,
including CPU table limits/isolation and skin control. MTK's software emergency
shutdown and LVTS hardware reboot protection remain enabled. Firmware or other
power/voltage/current constraints may still limit frequency. The AIDL thermal
HAL remains running to report temperatures; its disable property is not used.

Vendor profiles encode printable characters 32..122 by adding column modulo10,
wrapping in that 91-character alphabet. The stock `disable_thermal.conf` instead
disables hardware protection and persists across reboots; the helper never
applies it. The helper checks custom profiles first, matching the daemon's
`/data/vendor/thermal` precedence, and rejects invalid overrides.

Before disabling, the helper atomically saves the actual active profile basename
in `/data/vendor/galaxianparts/thermal_previous` with mode0600. Enabling restores
that profile. The helper uses UID0 with all Linux capabilities dropped to read
the daemon's root-owned mode0600 acknowledgment; SELinux limits it to this
protocol and its own restore file. The snapshot survives helper/daemon restarts
and is removed at boot. Neither the request nor the selected disable profile is
persistent. Without a restore point, enabling falls back to `thermal.conf`.

No kernel changes/rebuild are required for this existing vendor interface.
Validation on the built ROM remains necessary. Host tests cover real profile
validation and a simulated socket daemon, including readiness timeouts, locked
or missing acknowledgment files, restore failures, rollback, and atomic snapshots.

The socket protocol was inspected in the current `thermal_core` Build ID
`e0f91001281758f372b93cd86be034ea`: it parses `%50s %50s`, handles `apply`, closes
its client after queuing the command, and updates `.current_tp` after parsing
the profile. Recheck this private protocol when replacing that blob.

The host regression `tests/check_property_access.py` queries effective access
from the compiled binary policy with libsepol, including attributes and
constraints. It resolves actual property contexts (exact match, then longest
prefix), checks every RC property trigger's `vendor_init` read permission,
checks request/diagnostic permissions, and reproduces the old denial by resolving
`init.svc.thermal_core` without its scoped exact context. This reads an offline
policy file only and never interacts with the host kernel's policy.

Pass `--policy`, `--rc` and repeated `--property-contexts` inputs (platform,
current device system_ext, and vendor contexts). If the distribution's libsepol
is older than Android's policy capabilities, use `--libsepol` with the matching
Android host library. The existing Android host `libsepol.a` can be linked into
a temporary shared library for this check; no ROM rebuild is required.

# Galaxian Parts

Nothing Phone (3a) Lite controls under **Settings → System → Galaxian Parts**. The interface
now uses Rodin’s Compose components directly: large collapsing headers, grouped
rounded cards, dynamic Settings colours, animated navigation, live metric tiles,
a battery gauge and a RAM usage bar. CPU, GPU, RAM, battery, temperatures and optional
adaptive control each have their own page.

## Languages and navigation

Language coverage follows the **91 natural locales enabled by this LineageOS
product**, rather than every possible locale understood by Android. The English
base and **85 non-English resource qualifiers** cover those locales; some regional
variants share resources. `res/xml/locales_config.xml` declares the app languages.
Android's three test pseudolocales are excluded from that list.

The English UI has received a wording review, including casing temperature,
RAM actions and controller status labels. Italian resources are retained.
There are 82 newly automatically translated resource sets, plus separately
authored Asturian and Norwegian Nynorsk translations. Selected technical wording received additional
review and corrections. These translations have not received complete
native-speaker review. `tests/check-localizations.py` checks product coverage,
string completeness, formatting placeholders, units, brands and XML structure;
it cannot establish linguistic accuracy. Its companion
`tests/localization-expectations.json` records the product-to-resource mapping.

Adaptive control has separate **Controls** and **Activity log** tabs. Controls contains
the switch, objective, restoration action and current readings. Activity log contains
all retained events, bounded to 120 by the controller. Each tab retains its own
scroll position, and the selected tab uses saved state.

The recovery countdown starts once per continuous episode of unavailable data,
charging, heat or memory pressure. Five-second readings do not restart it. At
zero, the controller still waits for the blocking condition to clear and then
collects a complete fresh baseline; zero never authorizes a change. A new episode
starts a new recovery interval. The timer is hidden while Adaptive is off.

Page navigation uses a separate internal **PartsSubActivity**, matching Settings'
**SubSettings** window navigation. Android handles the transition between the
Home and detail windows, including predictive gesture progress, cancellation,
completion and edge direction. Toolbar Back finishes the detail Activity.
The theme uses the framework `Animation.Activity` window style; the manifest
enables predictive back and RTL. There is no NavHost page animation or general
app back callback. The old NavHost default scaled the page to 70% in the centre,
which did not match Settings' native gesture animation.

Notification links still open Adaptive with the exact proposal ID and never
approve a change. The Home-to-Adaptive stack is retained within the selected
task, and restored Activities do not replay consumed notification requests.
A notification can open a separate Parts task from the one hosted by Settings.
Host tests cover routing, repeated taps, ownership and request restoration;
gesture completion, cancellation, both edges, toolbar Back and return to
Settings still require visual verification on the phone.

English temperature labels use **Phone casing** for the external surface. Metric
labels wrap to keep the full description visible with longer text or larger fonts.
Internal `TYPE_SKIN` identifiers and raw sensor names remain unchanged.

## CPU limits that Android cannot replace

The two CPU clusters expose their actual kernel frequency tables. The range
dialog applies both bounds together. On the patched kernel, **Apply and lock**
uses `scaling_locked_limits` to hold that range in the cpufreq policy itself.
Android’s queued QoS requests cannot replace the locked bounds. Releasing the
lock restores the current QoS aggregate. This works after leaving or closing the
app and needs no periodic userspace writer. Reboot releases the locks.

This can bypass software CPU thermal frequency caps even when the separate
thermal switch is off. Hardware/firmware and electrical limits still apply;
this does not add overclock frequencies. Increased heat/power use and damage are
possible when software thermal limits are bypassed.

**The patched `6.1.177-android14-11-lufrz` kernel has been built and imported**
into the ROM's kernel source package with all 481 selected `.ko` files rebuilt
(467 distinct modules; zero retained old modules). Exact symbol CRCs, vermagic
and module dependency metadata passed host checks. The DTB is byte-identical and
the DTBO is unchanged. On an older kernel lacking the interface, the app still
shows “Updated kernel required” and labels ordinary writes as temporary.
See [KERNEL.md](KERNEL.md) for the build recipe, persistent source patches,
image hash, timestamp and compatibility evidence.

CPU0 and at least one core per cluster remain online in the app’s core controls.
Writes are checked and failures attempt rollback. “Restore initial CPU values”
releases locks and restores the ranges/online states first observed by the app
process. The thermal profile has its own restore action.

## GPU frequency control

The GPU page reads the MediaTek GPUFREQ v2 interface in `/proc/gpufreqv2`.
`gpu_working_opp_table` supplies supported frequencies and `gpufreq_status`
supplies the active/idle clock and effective PPM limits. The range dialog offers
both a minimum and a maximum from that actual table, like the CPU dialog.
Equal bounds are also supported. It never edits GPU voltages.

Ranges use the existing `limit_table` interface: only the DEBUG limiter (ID 1)
is changed, with `set 1 <maximum OPP index> <minimum OPP index>`. Frequency scaling
continues inside the interval. An old fixed-frequency selection is released after
the new range is acknowledged. Automatic restoration releases both this limiter
and the old fixed selector, without disabling other controllers. Restoration
remains available if the frequency table cannot be read.

Requested and effective bounds are displayed separately. The manager checks the
DEBUG row, the full limiter table against the driver's priority rules, the reported
PPM bounds and, while active, the current OPP. An updated DEBUG row alone is not
considered proof that a range took effect. Failed transactions attempt rollback
only while the observed settings remain owned; the proc interface does not offer
an atomic compare-and-swap against external writers. Other limiters can narrow
the result or resolve a conflicting range according to driver priorities. A
powered-down GPU shows idle, rather than presenting a stale clock as active.

PowerHAL boosts app launch for 1500 ms and expensive rendering until the hint
ends through the driver's APIBOOST floor. This replaces the old fixed-OPP actions
and restores the boosts previously removed globally, including in automatic mode.
The selected manual DEBUG ceiling is respected; in automatic mode thermal
ceilings also take precedence. An explicit manual DEBUG floor can lend its higher
aggregate priority to the boost, including over thermal ceilings, as determined
by the driver's existing arbitration. Hint release clears the APIBOOST floor;
it neither rewrites the manual range nor switches to fixed-frequency mode. CPU,
memory and GPU DVFS margin/step hints remain active.

This uses the existing GPUEB firmware controls and needs no kernel rebuild.
Changes are not reapplied at boot. The dialog scrolls its explanation and controls
together for long translations and large fonts. The exact proc node has a dedicated
SELinux label; app code limits writes to DEBUG, while SELinux limits file access,
not individual rows. GPU operation still needs verification on the physical phone.

## Thermal fix

The old vendor init action referenced the private `init.svc.thermal_core`
property. Lineage rejects that action, so the helper never acknowledged the
request. The exact daemon status property is now exported read-only to the
vendor components that need it; other private init properties remain private.

The backend confirms daemon readiness, applies the real MediaTek
`disable_throttling.conf`, waits for the actual profile, and publishes a fresh
completion counter. The UI shows the actual profile and specific failure reason,
allows another disable attempt, and offers restoration independently. It no
longer forces every unknown/error state into a restore-only dialog. This fix
is independent of CPU locking and does not require the ownership interface.

The thermal override resets at reboot and restores the previously active
profile when switched off. The supplied throttling-disable profile retains
emergency shutdown and hardware protection. Details and protocol assumptions:
[thermal/README.md](thermal/README.md).

## RAM and battery

RAM shows physical memory, swap and read-only zRAM statistics, plus explicit
termination of selected/all eligible cached third-party apps. Foreground,
service, persistent, system, shared-UID and this app’s processes are excluded.
Eligibility is checked again before each request; the report distinguishes
requests from observed termination. Android can restart apps.

Battery shows level, charging state, health, temperature, voltage, current and
charge counter. Unsupported measurements show unavailable, not a made-up zero.
Polling stops while each destination is not resumed.

## Temperatures and adaptive control

The Temperatures page combines the Android thermal service, readable kernel
thermal zones and battery temperature. It preserves sensor names and unavailable
values, distinguishes thermal status from a guessed threshold, and shows recent
readings. Galaxian’s HAL exposes its shared SoC maximum under several hardware
labels; those are not independent measurements.

Adaptive control is an original local feedback feature, not MaxManager’s
proprietary engine or an LLM. The public [Max AI description](https://github.com/nader295/Max-Manger#max-ai)
was the user’s feature reference; no MaxManager code, graphics, data or private
components are included. The UI follows Rodin, as the other pages do.

It is off until enabled and shows a foreground notification while running.
Performance, balanced and battery objectives guide proposals for one supported
CPU-ceiling step on one unlocked cluster or one GPU-ceiling step, using measured
load, temperature, battery, memory and classified app-frame observations. **New changes require explicit approval.**
A separate notification opens a review dialog showing the proposed before/after
limit, objective, reason and original readings. Apply starts the measured trial;
Reject leaves the CPU/GPU limits untouched. The same review is available on the
Controls tab if proposal notifications are disabled.

Readings refresh about every **5 seconds**, while the Activity log adds at most
one periodic status card every **60 seconds**. Proposal decisions also have a
60-second minimum interval; actual actions and state changes are recorded when
they happen. There is at most one pending proposal, expiring after 120 seconds.
Its ID is single-use, and approval rechecks conditions and ownership. Changing
the objective, stopping, or incompatible manual/external changes cancel it.
Rejected targets have a ten-minute backoff. A service restart clears proposals;
a notification cannot recreate or approve an old proposal.

Opening Parts to review a proposal preserves the original workload's baseline.
The trial waits for that workload to return before comparing measurements; it
never reports the reduction in load caused merely by opening Parts as a benefit.
It compares observation windows of roughly thirty seconds while discharging,
and abandons an unmeasurable trial after its deadline. Incomparable or missing
telemetry is not a claimed benefit. The journal records measured current,
temperature and frame-jank differences, rejected proposals, trial results and
restorations. It does not claim a proven battery-life gain or infer a workload
from long-term user habits.

CPU and GPU manual writes have priority, including no-op manual requests.
Locked CPU clusters and manually selected GPU ranges/fixed frequencies are
observation-only. A shared GPU coordinator serializes manual and adaptive
transactions, validates the raw controls and OPP table before writing, and tracks
exact ownership. GPU trials change only the maximum by one advertised step;
the minimum remains untouched. Failed trials restore the previous raw settings;
Stop releases a retained adaptive ceiling back to the original automatic
`-1/-1` mode. Temporary gaps between transactions preserve previously verified ownership.
If a new write cannot be verified at all, no successful change is claimed; the
GPU page's explicit automatic-mode action remains available for manual recovery.
The controller does not change the thermal profile or terminate apps. A trial can
restore its prior limit automatically when its evaluation fails; it does not
apply another optimization without approval. No network/model API or boot
autoactivation is involved. Normal Stop attempts to restore values it still
owns; a killed/crashed process cannot guarantee cleanup of CPU/GPU limits. A new
process does not take ownership of an existing GPU range.

### GPU load and frame smoothness

GPU utilization comes from the driver's existing read-only
`/sys/kernel/ged/hal/gpu_utilization` counter. It is unavailable when the GPU is
inactive or the driver data cannot be read. CPU utilization is never used as a
substitute for GPU load. The SELinux policy permits only this exact file and
parent traversal, with no additional GED tuning access. No kernel change is
needed for this telemetry.

The foreground app's classified frame history comes from the existing
SurfaceFlinger `--frametimeline -all` Binder dump. No tracing session is enabled,
statistics are not reset and no shell process is launched. The app's existing
system UID and compositor Binder permissions already permit that read; no new
`android.permission.DUMP` grant or SurfaceFlinger policy rule is added. The read
has a one-second budget and two-MiB cap. PID, layer names and package identities
are used transiently for selection/comparison and are not stored in the journal.

The compositor keeps a finite history (normally 64 display frames), so five-second
polls cover only samples of recent rendering. The UI explicitly labels that
partial coverage. It shows observed frame counts, late/dropped percentage, dropped
count, target rendering FPS, display Hz and the 95th percentile of measured
present-to-present intervals when available. Target FPS is not a claim about
achieved FPS. A 30-FPS game on a 120-Hz display is not considered laggy merely
because those rates differ. One identifiable rendering surface is selected;
SurfaceView and surrounding app UI are not added together. Duplicate, expired,
unclassified, stale or ambiguous data cannot be treated as smooth frames.
Network latency, input latency and every possible game/rendering path are not
measured by this feature.

An increase requires sustained high load on the proposed CPU/GPU and actual
app-attributed missed-frame deadlines. Compositor-only jank does not justify an
app-frequency boost. The trial is kept only if comparable frame samples improve
within temperature and discharge-current budgets. A reduction requires low load
and stable frames, and is retained only with a measured current reduction and
no frame regression. Missing or incomparable frames keep the controller in
observation or roll back the current trial. CPU and GPU share one proposal/trial
at a time; each new optimization still requires Apply in the review dialog.

## Integration and validation

The platform-signed system_ext app has a dedicated SELinux domain. CPU sysfs
permissions are scoped to their exact nodes. Only the vendor helper talks to
the thermal daemon. Product dependencies install the permission XML, init files
and helper automatically. The Settings entry remains restricted to system
callers and the primary user. LunarisDolby and the six original README patches
are unchanged.

Hardware volume-key dispatch now has the scoped `media_session_service` lookup
permission required by `PhoneWindow` and `MediaSessionManager`. The previous
policy denied that lookup, exposing a null-service crash path for both volume
keys. `tests/check-volume-key-access.py` checks the compiled policy, including
the denied lookup in the baseline and the new permission without service
registration rights. Both keys still need on-device verification.

The app also has the scoped `notification_service` lookup needed to create its
foreground and proposal notifications. A denied lookup previously exposed a
null-service path during controller startup. `tests/check-notification-access.py`
checks the compiled policy, the baseline denial and preserved service registration
restrictions. The review remains accessible in the app if notifications are blocked.

Host checks include Compose compilation against Android/AndroidX/SettingsLib;
resource compilation and product-wide locale checks; CPU transaction/lock tests;
GPU OPP parsing, acknowledgement, idle-state, coordinator and exact rollback tests;
frame parser/deduplication/cadence tests; adaptive CPU/GPU decision, approval
and ownership tests; temperature conversion/source tests; kernel logic
checks; simulated thermal daemon tests; full current-device SELinux neverallow
checks and init/property access checks. `tests/run-gpu-tests.sh` covers GPU driver
transactions; `tests/check-gpu-access.py` checks the compiled labels, narrow GPU
permissions and preserved system-service access. `tests/check-frame-gpu-access.py`
checks the exact read-only GPU counter, untouched GED siblings and existing
compositor Binder/socket permissions. Runnable tests are under `tests/`.

The full kernel/module build and 495-file import are complete. The imported
kernel embeds epoch `1790624405` (`Mon Sep 28 19:40:05 UTC 2026`) and the actual
build identity `build-user@build-host`. Results are recorded in
`out/kernel-galaxian-build/BUILD-REPORT.md`; the previous kernel artifacts are in
`out/codex-build/Galaxian/previous-kernel`.

ROM build results and checks of the actual OTA contents are recorded in
`out/codex-build/Galaxian/BUILD-REPORT.md`.
No flashing or on-device tests have been performed. Host CRC/vermagic checks do
not certify runtime behavior.

On the phone, check thermal disable/restore/retry and the diagnostic code if it
fails. With the patched kernel, lock a supported range, leave the app, trigger
normal Android performance modes, and verify the bounds remain locked; then
release the lock and reboot. Check back-gesture completion and cancellation from
both edges, tab scroll positions after switching, RTL and translated layouts,
light/dark themes, both volume keys on each page, RAM app termination and battery
measurements. For GPU control, check a supported min/max range while active,
requested versus effective limits, equal bounds, migration from an old fixed
selection, idle display, automatic restoration and reboot behavior. For adaptive
control, verify five-second readings and one periodic log card per minute; open
a proposal from its notification, reject and approve separate proposals, return
to the original workload for measurement, and check expired or duplicate taps.
Also check the in-app review when its notification channel is disabled. For frame
telemetry, compare a supported game's smooth and visibly stuttering workloads,
verify the correct target FPS/refresh rate, unavailable data and surface/app
changes. Verify CPU and GPU proposals independently, measured results after
returning from review, manual overrides during trials, and Stop's exact return
to automatic GPU control. Host fixtures do not replace these device checks.

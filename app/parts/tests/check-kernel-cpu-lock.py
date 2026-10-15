#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Run the actual kernel lock/parser/policy functions in a host harness; no kernel build."""
from pathlib import Path
import os
import subprocess
import tempfile

root = Path(__file__).resolve().parents[6]
source = (root / "kernel/nothing/galaxian/drivers/cpufreq/cpufreq.c").read_text()


def function(name: str) -> str:
    # Locate a definition (not its forward declaration), and retain the exact function body.
    import re
    definition = re.search(r"^static [^;{}]*\b" + name + r"\([^;{}]*\)\n\{", source, re.M)
    if not definition:
        raise RuntimeError("Kernel function not found: " + name)
    depth = 1
    end = definition.end()
    while depth:
        depth += (source[end] == "{") - (source[end] == "}")
        end += 1
    return source[definition.start():end]


private_types = source[source.index("struct cpufreq_locked_limits {"):source.index("static struct cpufreq_policy_private *cpufreq_policy_private")]
preamble = r'''
#define _GNU_SOURCE
#include <assert.h>
#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>
#include <limits.h>
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <errno.h>
#include <sys/types.h>
#define GFP_KERNEL 0
#define ARRAY_SIZE(a) (sizeof(a) / sizeof((a)[0]))
#define CPUFREQ_BOOST_FREQ 1
#define CPUFREQ_RELATION_L 0
#define CPUFREQ_RELATION_H 1
#define FREQ_QOS_MIN 0
#define FREQ_QOS_MAX 1
#define min(a,b) ((a) < (b) ? (a) : (b))
#define container_of(ptr, type, member) ((type *)((char *)(ptr) - offsetof(type, member)))
#define cpufreq_for_each_valid_entry(pos, table) for ((pos) = (table); (pos)->frequency; (pos)++)
#define pr_debug(...) ((void)0)
#define pr_err(...) ((void)0)
#define trace_cpu_frequency_limits(policy) ((void)0)
#define arch_set_min_freq_scale(cpus, minimum, maximum) ((void)0)
struct cpufreq_cpuinfo { unsigned min_freq, max_freq; };
struct cpufreq_frequency_table { unsigned frequency, flags; };
struct cpufreq_governor { const char *name; };
struct constraints { unsigned min, max; };
struct cpufreq_policy {
    struct cpufreq_cpuinfo cpuinfo;
    struct cpufreq_frequency_table *freq_table;
    struct cpufreq_governor *governor;
    struct constraints constraints;
    unsigned min, max, cpu, related_cpus, policy, cached_target_freq;
};
struct cpufreq_policy_data {
    struct cpufreq_cpuinfo cpuinfo;
    struct cpufreq_frequency_table *freq_table;
    unsigned cpu, min, max;
};
struct driver {
    int (*verify)(struct cpufreq_policy_data *);
    int (*setpolicy)(struct cpufreq_policy *);
};
static bool supports_target = true, boost_enabled;
static int driver_failure, driver_clamp;
static int verify(struct cpufreq_policy_data *data) {
    if (driver_failure) { driver_failure--; return -EIO; }
    if (driver_clamp) { driver_clamp--; data->max = 1200000; }
    return 0;
}
static struct driver actual_driver = { .verify = verify };
static struct driver *cpufreq_driver = &actual_driver;
static bool has_target(void) { return supports_target; }
static int cpufreq_boost_enabled(void) { return boost_enabled; }
static unsigned freq_qos_read_value(struct constraints *values, int kind) {
    return kind == FREQ_QOS_MIN ? values->min : values->max;
}
static unsigned get_cpumask_min_limit(unsigned cpus) { (void)cpus; return INT_MAX; }
static unsigned __resolve_freq(struct cpufreq_policy *policy, unsigned target, int relation) {
    (void)policy; (void)relation; return target;
}
static void cpufreq_governor_limits(struct cpufreq_policy *p) { (void)p; }
static void cpufreq_stop_governor(struct cpufreq_policy *p) { (void)p; }
static void cpufreq_exit_governor(struct cpufreq_policy *p) { (void)p; }
static int cpufreq_init_governor(struct cpufreq_policy *p) { (void)p; return 0; }
static int cpufreq_start_governor(struct cpufreq_policy *p) { (void)p; return 0; }
static char *kstrdup(const char *s, int flags) { (void)flags; return strdup(s); }
static void kfree(void *p) { free(p); }
static int kstrtouint(const char *s, unsigned base, unsigned *out) {
    char *end; unsigned long value;
    if (*s == '-') return -EINVAL;
    errno = 0; value = strtoul(s, &end, base);
    if (errno || *end || end == s || value > UINT_MAX) return -EINVAL;
    *out = value; return 0;
}
static int sysfs_emit(char *buf, const char *fmt, ...) {
    va_list args; va_start(args, fmt); int result = vsprintf(buf, fmt, args); va_end(args); return result;
}
static int cpufreq_set_policy(struct cpufreq_policy *, struct cpufreq_governor *, unsigned);
'''
tests = r'''
int main(void) {
    struct cpufreq_frequency_table table[] = {
        {400000, 0}, {800000, 0}, {1200000, 0}, {1600000, 0}, {2000000, CPUFREQ_BOOST_FREQ}, {0, 0}
    };
    struct cpufreq_governor governor = { "test" };
    struct cpufreq_policy_private storage = { .policy = {
        .cpuinfo = { 400000, 2000000 }, .freq_table = table, .governor = &governor,
        .constraints = { 400000, 1600000 }, .min = 400000, .max = 1600000,
    }};
    struct cpufreq_policy *policy = &storage.policy;
    char buf[100];
    const char *request = "1 800000 1600000\n";
    assert(store_scaling_locked_limits(policy, request, strlen(request)) == (ssize_t)strlen(request));
    assert(policy->min == 800000 && policy->max == 1600000 && storage.locked_limits.enabled);
    policy->constraints = (struct constraints){ 400000, 1200000 };
    assert(!cpufreq_set_policy(policy, &governor, 0));
    assert(policy->min == 800000 && policy->max == 1600000);
    assert(show_scaling_locked_limits(policy, buf) > 0 && !strcmp(buf, "1 800000 1600000\n"));
    puts("PASS queued Android QoS cannot replace locked bounds");
    assert(store_scaling_locked_limits(policy, "0\n", 2) == 2);
    assert(policy->min == 400000 && policy->max == 1200000 && !storage.locked_limits.enabled);
    puts("PASS unlock immediately uses latest real QoS aggregate");
    const char *bad[] = {"", "2", "0 1", "1 1600000 800000", "1 0 1600000", "1 900000 1600000", "1 800000 999999999999999", "1 -1 1600000", "1 800000 1600000 extra", "1 800000 2000000"};
    for (unsigned i = 0; i < ARRAY_SIZE(bad); i++) {
        assert(store_scaling_locked_limits(policy, bad[i], strlen(bad[i])) == -EINVAL);
        assert(!storage.locked_limits.enabled && policy->max == 1200000);
    }
    puts("PASS malformed, overflowing, inverted, unavailable and disabled-boost input rejected");
    boost_enabled = true;
    assert(store_scaling_locked_limits(policy, "1 800000 2000000", 16) == 16);
    assert(policy->max == 2000000);
    driver_failure = 1;
    assert(store_scaling_locked_limits(policy, request, strlen(request)) == -EIO);
    assert(storage.locked_limits.enabled && policy->max == 2000000);
    puts("PASS rejected driver application restores previous locked range");
    driver_clamp = 1;
    assert(store_scaling_locked_limits(policy, request, strlen(request)) == -ERANGE);
    assert(storage.locked_limits.enabled && policy->max == 2000000);
    puts("PASS driver-clamped application fails readback and restores prior range");
    supports_target = false;
    assert(store_scaling_locked_limits(policy, "0", 1) == -EOPNOTSUPP);
    assert(show_scaling_locked_limits(policy, buf) == -EOPNOTSUPP);
    puts("PASS unsupported driver does not advertise usable lock");
    puts("6 kernel CPU lock checks passed");
}
'''
parts = [preamble, private_types, function("cpufreq_policy_private"), function("show_scaling_locked_limits"), function("cpufreq_lock_frequency_valid"), function("cpufreq_parse_locked_limits"), function("store_scaling_locked_limits"), function("cpufreq_set_policy"), tests]
with tempfile.TemporaryDirectory(prefix="galaxian-kernel-cpu-lock-") as directory:
    directory = Path(directory)
    harness = directory / "lock-check.c"
    harness.write_text("\n\n".join(parts))
    subprocess.run([os.environ.get("CC", "cc"), "-std=gnu11", "-Wall", "-Wextra", "-Werror", str(harness), "-o", str(directory / "lock-check")], check=True)
    subprocess.run([str(directory / "lock-check")], check=True)

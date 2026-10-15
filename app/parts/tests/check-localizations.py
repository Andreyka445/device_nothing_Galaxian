#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Validate every Galaxian Parts translation against the product's language scope.

Run from any directory with Python 3. No build output, third-party module,
network service, or installed Android SDK is needed. Product language lists
come from the checked-in Android/Lineage makefiles; the companion JSON records
which resource qualifier implements each locale (English uses the base).

This checks structural correctness, not linguistic quality. Identical English
phrases are review warnings; shared technical names and short cognates are not
proof of missing translation. Android bidi marks are permitted intentionally.
"""
import argparse
from collections import Counter
import json
from pathlib import Path
import re
import unicodedata
import xml.etree.ElementTree as ET

TESTS = Path(__file__).resolve().parent
PARTS = TESTS.parent
TREE = PARTS.parents[4]
ANDROID = "{http://schemas.android.com/apk/res/android}"
ALIASES = {"iw": "he", "in": "id", "tl": "fil"}
FORMAT = re.compile(
    r"%(?:(?P<index>\d+)\$)?(?P<flags>[-#+ 0,(<]*)"
    r"(?P<width>\d+)?(?P<precision>\.\d+)?"
    r"(?P<date>[tT])?(?P<conversion>[bBhHsScCdoxXeEfgGaAn%])")
TEMPORARY = re.compile(r"⟪[^⟫]*⟫|QZX", re.IGNORECASE)
BRANDS = ("Galaxian Parts", "Nothing Phone (3a) Lite", "Dimensity 7300")
UNITS = re.compile(r"(?<![A-Za-z])(?:MHz|kHz|mAh|mA|mV|GiB|MiB|KiB|°C|V)(?![A-Za-z])")
TECHNICAL = re.compile(
    r"\b(?:Galaxian|Parts|CMF|Phone|Dimensity|Android|CPU|CPUs|GPU|NPU|TPU|"
    r"SoC|RAM|zRAM|HAL|MHz|kHz|mAh|mA|mV|GiB|MiB|KiB|V)\b")


def canonical_locale(locale):
    parts = locale.replace("_", "-").split("-")
    parts[0] = ALIASES.get(parts[0].lower(), parts[0].lower())
    return "-".join(parts).lower()


def source_locales(scope):
    result = set()
    for source in scope["locale_sources"]:
        path = TREE / source["path"]
        text = re.sub(r"\\\r?\n", " ", path.read_text())
        variable = re.escape(source["variable"])
        values = re.findall(rf"^\s*{variable}\s*(?::=|\+=|=)\s*(.*?)$",
                            text, re.MULTILINE)
        if not values:
            raise ValueError(f"No {source['variable']} assignment in {path}")
        for value in values:
            for token in value.split("#", 1)[0].split():
                if not re.fullmatch(r"[a-z]{2,3}(?:[_-][A-Za-z0-9]{2,8})*", token):
                    raise ValueError(f"Nonliteral locale {token!r} in {path}; update parser")
                result.add(canonical_locale(token))
    return result


def visible_text(text):
    # Bidi formatting characters are intentional for RTL labels. Do not reject
    # them or let them hide a brand/placeholder check.
    text = "".join(c for c in text if unicodedata.category(c) != "Cf")
    return " ".join(text.replace("\\n", " ").replace("\\'", "'").split())


def placeholders(text, formatted=True):
    if not formatted:
        return Counter()
    result = Counter()
    cursor = 0
    implicit = 0
    previous = None
    for match in FORMAT.finditer(text):
        if "%" in text[cursor:match.start()]:
            raise ValueError("malformed percent placeholder")
        cursor = match.end()
        conversion = match["conversion"]
        if conversion in ("%", "n"):
            result[(conversion,)] += 1
            continue
        flags = match["flags"]
        if match["index"]:
            index = int(match["index"])
        elif "<" in flags:
            if previous is None:
                raise ValueError("relative placeholder without previous argument")
            index = previous
        else:
            implicit += 1
            index = implicit
        if index < 1:
            raise ValueError("placeholder argument indexes start at 1")
        previous = index
        result[(index, "".join(sorted(flags.replace("<", ""))), match["width"] or "",
                match["precision"] or "", match["date"] or "", conversion)] += 1
    if "%" in text[cursor:]:
        raise ValueError("malformed percent placeholder")
    return result


def read_strings(directory, problems):
    strings = {}
    for path in sorted(directory.glob("*.xml")):
        try:
            root = ET.parse(path).getroot()
        except (ET.ParseError, OSError) as error:
            problems.append(f"{path.relative_to(PARTS)}: {error}")
            continue
        for item in root:
            if item.tag != "string" and not (item.tag == "item" and item.get("type") == "string"):
                continue
            name = item.get("name")
            if not name:
                problems.append(f"{path.relative_to(PARTS)}: string without name")
                continue
            if name in strings:
                problems.append(f"{directory.name}: duplicate string ID {name}")
            strings[name] = ("".join(item.itertext()), item.get("formatted") != "false")
    return strings


def check_string(name, original, translated, qualifier, problems, warnings):
    text, formatted = translated
    source, source_formatted = original
    label = f"{qualifier}/{name}"
    if not text.strip():
        problems.append(f"{label}: empty string")
    if TEMPORARY.search(text) or "\ufffd" in text:
        problems.append(f"{label}: temporary marker or Unicode replacement character")
    if formatted != source_formatted:
        problems.append(f"{label}: formatted attribute differs from base")
    try:
        if placeholders(source, source_formatted) != placeholders(text, formatted):
            problems.append(f"{label}: placeholder arguments/types/format differ from base")
    except ValueError as error:
        problems.append(f"{label}: {error}")
    clean_source, clean = visible_text(source), visible_text(text)
    for brand in BRANDS:
        if clean_source.count(brand) != clean.count(brand):
            problems.append(f"{label}: brand {brand!r} changed or duplicated")
    if any(Counter(UNITS.findall(clean))[unit] != count
           for unit, count in Counter(UNITS.findall(clean_source)).items()):
        problems.append(f"{label}: measurement unit changed or duplicated")
    # A source/translation can share single words (e.g. French/English 'Service')
    # or technical labels. Longer identical prose deserves human review only.
    prose = TECHNICAL.sub("", FORMAT.sub("", clean_source))
    if clean == clean_source and len(re.findall(r"[A-Za-z]+", prose)) >= 2 and len(prose) >= 15:
        warnings.append(f"{label}: unchanged English phrase: {clean_source}")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--json-report", type=Path, help="Write full errors and review warnings")
    parser.add_argument("--show-warnings", action="store_true", help="Print all review warnings")
    args = parser.parse_args()
    scope = json.loads((TESTS / "localization-expectations.json").read_text())
    problems, warnings = [], []
    product = source_locales(scope)
    mapping = {canonical_locale(k): v for k, v in scope["locale_to_resource_qualifier"].items()}
    if product != set(mapping):
        problems.append(f"Product locale scope changed: unmapped={sorted(product - set(mapping))}, "
                        f"obsolete={sorted(set(mapping) - product)}")
    pseudo = {canonical_locale(x) for x in scope["pseudo_locales"]}
    natural = product - pseudo
    resources = PARTS / "res"
    base = read_strings(resources / "values", problems)
    for name in scope["required_string_ids"]:
        if name not in base:
            problems.append(f"Base resources lack required string {name}")
    if not base:
        problems.append("Base resources contain no strings")
    for name, original in base.items():
        check_string(name, original, original, "values", problems, [])
    qualifiers = sorted({mapping[x] for x in natural & set(mapping) if mapping[x]})
    for qualifier in qualifiers:
        directory = resources / ("values-" + qualifier)
        localized = read_strings(directory, problems)
        missing, extra = set(base) - set(localized), set(localized) - set(base)
        if missing or extra:
            problems.append(f"{directory.name}: {len(localized)}/{len(base)} strings; "
                            f"missing={sorted(missing)}, extra={sorted(extra)}")
        for name in sorted(set(base) & set(localized)):
            check_string(name, base[name], localized[name], directory.name, problems, warnings)
    # Parse the rest of the resource tree too, including locales_config/drawables.
    for path in sorted(resources.rglob("*.xml")):
        try:
            ET.parse(path)
        except ET.ParseError as error:
            problems.append(f"{path.relative_to(PARTS)}: {error}")
    try:
        manifest = ET.parse(PARTS / "AndroidManifest.xml").getroot()
        application = manifest.find("application")
        if application is None or application.get(ANDROID + "supportsRtl") != "true":
            problems.append("Application must declare android:supportsRtl=true")
        config = application.get(ANDROID + "localeConfig", "") if application is not None else ""
        if not re.fullmatch(r"@xml/[a-z0-9_]+", config):
            problems.append("Application must reference an XML android:localeConfig")
        else:
            root = ET.parse(resources / "xml" / (config[5:] + ".xml")).getroot()
            names = [item.get(ANDROID + "name", "") for item in root.findall("locale")]
            configured = [canonical_locale(name) for name in names]
            if root.tag != "locale-config" or any(not name for name in names):
                problems.append("Invalid locale-config root or nameless locale entry")
            if len(set(configured)) != len(configured):
                problems.append("Duplicate locale-config entries after Android alias normalization")
            if set(configured) != natural:
                problems.append(f"locale-config coverage: missing={sorted(natural - set(configured))}, "
                                f"extra={sorted(set(configured) - natural)}")
    except (ET.ParseError, OSError) as error:
        problems.append(f"Manifest/locale-config: {error}")
    report = {"product_locales": len(product), "natural_locales": len(natural),
              "pseudo_locales": len(pseudo), "translation_qualifiers": len(qualifiers),
              "base_strings": len(base), "errors": problems, "review_warnings": warnings}
    if args.json_report:
        args.json_report.parent.mkdir(parents=True, exist_ok=True)
        args.json_report.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n")
    for problem in problems:
        print("FAIL " + problem)
    if args.show_warnings:
        for warning in warnings:
            print("REVIEW " + warning)
    print(f"{'FAIL' if problems else 'PASS'} {len(base)} base strings, {len(qualifiers)} "
          f"translation qualifiers, {len(natural)} natural + {len(pseudo)} pseudo locales; "
          f"{len(problems)} errors, {len(warnings)} English phrase review warnings.")
    raise SystemExit(bool(problems))


if __name__ == "__main__":
    main()

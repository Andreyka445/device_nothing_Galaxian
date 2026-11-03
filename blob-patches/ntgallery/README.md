# NTGallery prebuilt

`NTGallery.apk` here is **not** the blob from the stock dump. It is Nothing's newer
Gallery, merged from its split-APK bundle into a single APK so it can ship as the
system_ext priv-app.

## Provenance

- Upstream: Nothing Gallery **3.1.2.0819** (`com.nothing.gallery`, versionCode 301002),
  arm64-v8a / 480dpi, taken from the APKMirror APK bundle
  `com.nothing.gallery_3.1.2.0819-301002_1arch_1dpi_24lang_...apkm`
- Bundle SHA-256: `f552ca6ea43a3fd4af657e448edb0c1bcbacd0d4cd50e5dd131d317ca49236eb`
- Merged with APKEditor:

      java -jar APKEditor.jar m -i <bundle>.apkm -o NTGallery.apk

The APK is left **unsigned on purpose**: nothing in `proprietary-files.txt` is marked
`;PRESIGNED`, so the ROM build re-signs every prebuilt with the platform key, exactly
like the rest of the Nothing apps (Gallery, Camera, Experience) already on the device.

## Pinning

`proprietary-files.txt` pins this file by both hashes:

    system_ext/priv-app/NTGallery/NTGallery.apk|<source sha1>|<merged sha1>

| | SHA-1 |
|---|---|
| source (stock dump) `NTGallery.apk` | `b9e2ed081fb0c4102b4d3671cda2410893c05c46` |
| merged (this file) | `d3fb6805a266546e6ccca7fba2c43b7505d59e78` |

On every `extract-files.py` run the framework either keeps this exact file (its hash
matches the fixup hash already present in the tree) or pulls the stock APK from the
dump, copies this file over it via the `nt_gallery_prebuilt_impl` blob fixup, and
verifies the result against the fixup hash. A hash mismatch is a hard failure — the
stock Gallery can never silently replace this build.

## Updating

1. Fetch a newer bundle and verify its SHA-256 against the published listing.
2. Re-merge it (same APKEditor command) over this file.
3. Update the merged SHA-1 in `proprietary-files.txt`.
4. Update the source SHA-1 too only if the stock dump's Gallery changed as well.

# Bootconfig-based Kernel Profile Differentiation

## Status

**Proposed** — awaiting approval before implementation.

## Motivation

Currently, GhostLock matches kernel profiles solely by exact `uname -r` string. However, Android Common Kernel (ACK) means multiple devices share the same kernel version (e.g., `6.6.118`) but have different hardware configurations requiring different exploit parameters. The kernel version alone cannot distinguish these devices.

Solution: Extract bootconfig parameters from `boot.img` (specifically `androidboot.hardware` and `androidboot.product.device`) during profile extraction, embed them in the generated profile, and match against `android.os.Build.HARDWARE` / `android.os.Build.DEVICE` at runtime.

## Design

### 1. Rust Extractor Changes (`tools/extract_rs/`)

#### 1.1 Bootconfig Parsing (`boot.rs`)

Add a `BootConfig` struct and parsing logic:

```rust
pub struct BootConfig {
    pub hardware: Option<String>,      // androidboot.hardware
    pub product_device: Option<String>, // androidboot.product.device
    // Future: pub product_name: Option<String>, // androidboot.product.name
}
```

The Android boot image v3/v4 header contains:
- `bootconfig_size` (u32) at offset 112 (v3) or similar
- Bootconfig data follows the kernel + ramdisk + dtb, aligned to page size

Parse the bootconfig section (simple `key=value\n` format) and extract the relevant keys.

#### 1.2 BootImage Extension

Add `bootconfig: Option<BootConfig>` to `BootImage` struct.

#### 1.3 Profile Output Changes (`report.rs`)

Add bootconfig fields to the generated HOCON profile (`--format conf`):

```hocon
bootconfig {
  hardware = "qcom"           // or actual value
  product_device = "sakura"   // or actual value
}
```

These become top-level keys in the profile, validated by the app at runtime.

#### 1.4 Output Filename Convention

Change the default output filename pattern from:
```
<kernel-release>.conf
```
to:
```
<kernel-release>_<hardware>_<product_device>.conf
```

Where:
- `<hardware>` = sanitized `androidboot.hardware` (e.g., `qcom`, `mtk`, `exynos`)
- `<product_device>` = sanitized `androidboot.product.device` (e.g., `sakura`, `veux`, `fog`)

If bootconfig is unavailable, fall back to `<kernel-release>.conf`.

### 2. Kotlin Changes (`app/` and `profile-core/`)

#### 2.1 Profile Model (`ProfileModel.kt`)

Add bootconfig fields to `CoreProfile`:

```kotlin
data class CoreProfile(
    // ... existing fields ...
    val bootconfigHardware: String?,
    val bootconfigProductDevice: String?,
)
```

#### 2.2 Profile Resolver (`ProfileResolver.kt`)

- Add `bootconfig` to `KnownTopLevel` set
- Update `validateMerged` to accept bootconfig fields (optional)
- Update `nativeValue` to expose bootconfig fields to native if needed

#### 2.3 Builtin Profile Catalog (`BuiltinProfileCatalog.kt`)

- Parse bootconfig fields from profile assets
- Update matching logic: primary key remains `uname -r`, but when multiple profiles share the same release, disambiguate by `bootconfig.hardware` and `bootconfig.product_device`
- The index.conf format may need extension to include hardware/device hints, or we match by filename convention

#### 2.4 Runtime Validation

In the exploit runner (before native launch):
- Read `android.os.Build.HARDWARE` and `android.os.Build.DEVICE`
- Compare against profile's `bootconfig.hardware` and `bootconfig.product_device`
- Mismatch → refuse to run with clear error message

### 3. Index.conf Format Extension

Option A: Extend index entries with optional hardware/device:
```hocon
{ release = "6.6.118-...", file = "6.6.118-..._qcom_sakura.conf", hardware = "qcom", device = "sakura" }
```

Option B: Keep index by release only, but filename encodes hardware/device. At load time, if multiple files match the same release, pick the one matching current device's Build.HARDWARE/DEVICE.

**Decision: Option B** — simpler, no index format change. The catalog loads all profiles for a release and picks the best match at runtime.

### 4. File Naming Convention

New profile files in `app/src/main/assets/kernel_profiles/`:
```
<uname-r>_<hardware>_<product_device>.conf
```

Examples:
```
6.6.118-android15-8-gc44b714366cc-abogki519650608-4k_qcom_sakura.conf
6.6.118-android15-8-ge56cf6b09cca-ab15511674-4k_mtk_veux.conf
```

Templates remain `<major>.<minor>-template.conf` (no hardware/device).

### 5. Backward Compatibility

- Profiles without bootconfig fields remain valid (fields are optional)
- Old profiles without bootconfig match any device with that kernel release (legacy behavior)
- New profiles with bootconfig only match devices reporting matching hardware/device
- Extractor output without bootconfig falls back to old naming

## Affected Files

### Rust (tools/extract_rs/src/)
- `boot.rs` — add BootConfig struct and parsing
- `main.rs` — pass bootconfig to report renderer, generate new filename
- `report.rs` — add bootconfig block to ConfInputs and render_conf

### Kotlin (app/src/main/kotlin/com/ghostlock/app/data/)
- `ProfileModel.kt` — add bootconfig fields to CoreProfile
- `ProfileResolver.kt` — add bootconfig to known keys, validation
- `BuiltinProfileCatalog.kt` — parse bootconfig, implement disambiguation logic
- `AndroidProfileConfigController.kt` — runtime validation against Build.HARDWARE/DEVICE

### Kotlin (profile-core/src/main/kotlin/com/ghostlock/app/data/profile/)
- `ProfileModel.kt` — CoreProfile changes

### Documentation
- `README.md` — update extractor usage examples, mention bootconfig
- `docs/kernel_profiles/README.md` — document new naming convention, matching logic
- `docs/kernel_profiles/PROFILE_SCHEMA.md` — add bootconfig section

## Verification Plan

### Unit Tests

1. **Rust**: Bootconfig parsing from known boot images (add test vectors)
2. **Rust**: Generated profile contains bootconfig block
3. **Rust**: Output filename follows new convention
4. **Kotlin**: ProfileResolver accepts bootconfig fields
5. **Kotlin**: BuiltinProfileCatalog disambiguates by hardware/device
6. **Kotlin**: Runtime validation matches Build.HARDWARE/DEVICE

### Integration Tests

1. Extract profile from boot.img with known bootconfig → verify output
2. Import generated profile → app validates bootconfig matches device
3. Multiple profiles for same kernel release → correct one selected
4. Profile without bootconfig → matches any device (legacy)

### Device Gates

- Test on at least two devices with same kernel version but different hardware
- Verify correct profile selected and exploit succeeds

## Rollout Plan

1. Implement Rust extractor changes (bootconfig parsing, profile output, filename)
2. Implement Kotlin model/resolver/catalog changes
3. Add runtime validation in exploit runner
4. Update documentation
5. Test on multiple devices
6. Regenerate existing profiles with bootconfig (where boot.img available)
7. Add new profiles using new naming convention

## Open Questions

1. **Sanitization**: How to sanitize hardware/device strings for filenames? (Replace non-alphanumeric with `_`, truncate)
2. **Fallback**: What if bootconfig is missing from boot.img? (Fall back to kernel-release-only naming and matching)
3. **Index.conf**: Should we add hardware/device to index for faster lookup? (Defer — catalog is small, linear scan is fine)
4. **Additional fields**: Should we also extract `androidboot.product.name` → `Build.PRODUCT`? (Defer — start with hardware + device)

## References

- Android Boot Image Header v3/v4: `bootconfig_size` field location
- Android bootconfig format: simple `key=value\n` text
- `android.os.Build` fields: HARDWARE, DEVICE, PRODUCT, MODEL, MANUFACTURER
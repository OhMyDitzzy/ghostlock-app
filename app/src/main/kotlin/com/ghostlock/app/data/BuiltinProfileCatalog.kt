package com.ghostlock.app.data

import android.content.Context

/**
 * Runtime catalogue of the bundled profiles, loaded from kernel_profiles/
 * index.conf on first use. Keeps the assets as the single source of truth
 * (replaces the former build-time SupportedKernels code generation).
 */
internal class BuiltinProfileCatalog(context: Context) {
    private val assetLoader = AssetConfigLoader(context)

    private data class Entry(
        val release: String,
        val recommendShizuku: Boolean,
        val fields: Map<String, Long>,
        // Bootconfig fields for device disambiguation when multiple profiles share the same release
        val bootconfigHardware: String?,
        val bootconfigProductDevice: String?,
        // Parsed profile document for direct use
        val document: ValueMap,
    )

    private val entries: List<Entry> by lazy { loadEntries() }

    /* Templates are authoring scaffolds, never a device match: a device whose
     * release is only covered by a template must stay "unsupported". */
    private fun isTemplate(release: String): Boolean = release.endsWith("-template")

    val unames: Set<String> by lazy {
        entries.mapTo(linkedSetOf()) { it.release }.filterTo(linkedSetOf()) { !isTemplate(it) }
    }

    val recommendShizuku: Set<String> by lazy {
        entries.filter { it.recommendShizuku }.mapTo(linkedSetOf()) { it.release }
    }

    val builtin: Map<String, Map<String, Long>> by lazy {
        entries.associateBy({ it.release }, { it.fields })
    }

    private fun loadEntries(): List<Entry> = runCatching {
        val index = readAsset("$BuiltinDirectory/index.conf")
            ?.let { HoconSupport.parseValue(it).asValueMap() } ?: return emptyList()
        val profiles = index["profiles"].asValueList() ?: return emptyList()
        profiles.mapNotNull { raw ->
            val entry = raw.asValueMap() ?: return@mapNotNull null
            val release = entry["release"] as? String ?: ""
            val file = entry["file"] as? String ?: ""
            if (release.isEmpty() || file.isEmpty()) return@mapNotNull null
            val profile = readAsset("$BuiltinDirectory/$file")
                ?.let { HoconSupport.parseValue(it).asValueMap() } ?: return@mapNotNull null
            val fields = flatten(profile)
            val bootconfigHardware = getBootconfigString(profile, "hardware")
            val bootconfigProductDevice = getBootconfigString(profile, "product_device")
            Entry(
                release,
                recommendShizuku = fields["recommend_shizuku"] == 1L,
                fields = fields,
                bootconfigHardware = bootconfigHardware,
                bootconfigProductDevice = bootconfigProductDevice,
                document = profile,
            )
        }
    }.getOrDefault(emptyList())

    /**
     * Get the best matching profile for a given kernel release and device bootconfig.
     * If multiple profiles exist for the same release, disambiguate by bootconfig.hardware
     * and bootconfig.product_device. If no bootconfig match, return the first profile.
     * If bootconfig is provided but no profile matches, return null (unsupported).
     */
    fun getBestMatchingProfile(
        release: String,
        hardware: String?,
        productDevice: String?,
    ): ValueMap? {
        val candidates = entries.filter { it.release == release && !isTemplate(it.release) }
        if (candidates.isEmpty()) return null

        // If only one candidate, return it
        if (candidates.size == 1) return candidates.first().document

        // If device bootconfig is provided, try to match
        if (hardware != null || productDevice != null) {
            // Exact match on both hardware and product_device
            val exactMatch = candidates.firstOrNull { entry ->
                (hardware == null || entry.bootconfigHardware == hardware) &&
                (productDevice == null || entry.bootconfigProductDevice == productDevice)
            }
            if (exactMatch != null) return exactMatch.document

            // Partial match: hardware only
            val hardwareMatch = candidates.firstOrNull { entry ->
                entry.bootconfigHardware == hardware
            }
            if (hardwareMatch != null) return hardwareMatch.document

            // No match with provided bootconfig - device not supported by any profile for this release
            return null
        }

        // No bootconfig provided - return first profile (legacy behavior)
        return candidates.first().document
    }

    /**
     * Get all profiles for a given release (excluding templates).
     * Useful for debugging or listing available variants.
     */
    fun getAllProfilesForRelease(release: String): List<ValueMap> {
        return entries.filter { it.release == release && !isTemplate(it.release) }
            .map { it.document }
    }

    /** Extract a string value from the bootconfig block. */
    private fun getBootconfigString(profile: ValueMap, key: String): String? {
        val bootconfig = profile["bootconfig"] as? ValueMap ?: return null
        (bootconfig[key] as? String)?.takeIf { it.isNotBlank() }
    }

    private fun readAsset(path: String): String? =
        assetLoader.load(path).takeIf { it.isNotBlank() }

    /** Flat field map mirroring the former build-time generator. */
    private fun flatten(profile: ValueMap): Map<String, Long> {
        val fields = linkedMapOf<String, Long>()
        for ((key, raw) in profile) {
            when (key) {
                "schema_version", "release", "execution" -> Unit

                "route" -> raw.asValueMap()?.forEach { (name, branchRaw) ->
                    branchRaw.asValueMap()?.forEach { (field, value) ->
                        (value as? Number)?.toLong()?.let { fields[routeFieldKey(name, field)] = it }
                    }
                }

                "fallback" -> raw.asValueMap()?.let { fallback ->
                    val to = fallback["to"] as? String ?: ""
                    val branch = fallback["route"].asValueMap()?.get(to).asValueMap() ?: return@let
                    branch.forEach { (field, value) ->
                        (value as? Number)?.toLong()
                            ?.let { fields.putIfAbsent(routeFieldKey(to, field), it) }
                    }
                }

                "kernelsnitch", "task_struct", "cred", "offset" ->
                    raw.asValueMap()?.forEach { (field, value) ->
                        (value as? Number)?.toLong()?.let { fields["$key.$field"] = it }
                    }

                "symbols", "struct_fields" ->
                    raw.asValueMap()?.forEach { (field, value) ->
                        (value as? Number)?.toLong()?.let { fields[field] = it }
                    }

                else -> (raw as? Number)?.toLong()?.let { fields[key] = it }
            }
        }
        return fields
    }

    private fun routeFieldKey(route: String, field: String): String = when (route) {
        "tcp_zerocopy" if field == "compact_waiter" -> "compact_waiter"
        "select_stack" if field == "waiter_shift" -> "pselect_waiter_shift"
        "multicast_waiter" -> "mcast.$field"
        else -> "$route.$field"
    }

    private companion object {
        const val BuiltinDirectory = "kernel_profiles"
    }
}

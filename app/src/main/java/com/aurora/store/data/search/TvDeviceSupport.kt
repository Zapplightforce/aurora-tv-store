/*
 * SPDX-FileCopyrightText: 2026 Aurora OSS
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.aurora.store.data.search

import com.aurora.gplayapi.SearchSuggestEntry
import com.aurora.gplayapi.data.models.App
import com.aurora.gplayapi.data.models.AuthData
import com.aurora.gplayapi.data.models.StreamBundle
import com.aurora.gplayapi.data.models.StreamCluster
import com.aurora.gplayapi.helpers.contracts.SearchContract
import kotlinx.coroutines.CancellationException
import java.util.Properties

/**
 * Pure (JVM-testable) TV-context support used by [SearchBridge]: spoof-profile TV detection,
 * native->web routing and TV-twin list remediation. No Android or network dependencies.
 */
object TvDeviceSupport {

    /**
     * TV-only feature flags used to detect the TV context from a device profile.
     *
     * Grounded in the gplayapi 3.6.4 bundled profiles (res/raw/gplayapi_*.properties):
     * `gplayapi_bravia_vu2.properties` `Features=` contains both
     * `android.software.leanback_only` and `android.hardware.type.television`,
     * while all 22 other bundled profiles (px_9a, sm_a13_5g, google_kiwi_x86_64, ...)
     * contain neither flag — it is the only TV profile shipped.
     */
    val TV_FEATURES = setOf(
        "android.software.leanback_only",
        "android.hardware.type.television"
    )

    /** Mobile package -> its TV twin listing (Play serves TV apps as separate packages). */
    val TV_TWIN_PAIRS = mapOf(
        "com.netflix.mediaclient" to "com.netflix.ninja"
    )

    fun isTvDevice(properties: Properties?): Boolean {
        val features = properties?.getProperty("Features").orEmpty()
        return TV_FEATURES.any { feature -> feature in features }
    }

    /** Moves each present TV twin directly above its mobile twin; other apps keep their order. */
    fun hoistPairApps(apps: List<App>): List<App> {
        val result = apps.toMutableList()
        for ((mobile, tv) in TV_TWIN_PAIRS) {
            val mobileIndex = result.indexOfFirst { app -> app.packageName == mobile }
            val tvIndex = result.indexOfFirst { app -> app.packageName == tv }
            if (mobileIndex >= 0 && tvIndex >= 0) {
                val tvApp = result.removeAt(tvIndex)
                result.add(result.indexOfFirst { app -> app.packageName == mobile }, tvApp)
            }
        }
        return result
    }

    /** Inserts each fetched TV twin directly above its mobile twin if not already present. */
    fun insertPairApps(apps: List<App>, fetched: Map<String, App>): List<App> {
        val result = apps.toMutableList()
        for ((mobile, tv) in TV_TWIN_PAIRS) {
            val tvApp = fetched[tv] ?: continue
            if (result.none { app -> app.packageName == tv }) {
                val mobileIndex = result.indexOfFirst { app -> app.packageName == mobile }
                if (mobileIndex >= 0) result.add(mobileIndex, tvApp)
            }
        }
        return result
    }

    private fun missingTvPackages(apps: List<App>): List<String> = TV_TWIN_PAIRS.entries
        .filter { (mobile, tv) ->
            apps.any { app -> app.packageName == mobile } &&
                apps.none { app -> app.packageName == tv }
        }
        .map { it.value }
        .distinct()

    /**
     * Repairs one app list: hoists present TV twins, then fetches (one batched call) any
     * missing TV twins whose mobile twin is present and inserts them above the mobile twin.
     * A failed fetch leaves the hoisted list (web result) unchanged.
     */
    fun remediateApps(apps: List<App>, fetchTvApps: (List<String>) -> List<App>): List<App> {
        val hoisted = hoistPairApps(apps)
        val missing = missingTvPackages(hoisted)
        if (missing.isEmpty()) return hoisted
        val fetched = runCatching { fetchTvApps(missing) }
            .getOrDefault(emptyList())
            .associateBy { app -> app.packageName }
        return insertPairApps(hoisted, fetched)
    }

    fun remediateCluster(
        cluster: StreamCluster,
        fetchTvApps: (List<String>) -> List<App>
    ): StreamCluster = cluster.copy(clusterAppList = remediateApps(cluster.clusterAppList, fetchTvApps))

    fun remediateBundle(
        bundle: StreamBundle,
        fetchTvApps: (List<String>) -> List<App>
    ): StreamBundle {
        val hoisted = bundle.streamClusters
            .mapValues { (_, cluster) -> cluster.copy(clusterAppList = hoistPairApps(cluster.clusterAppList)) }
        val missing = missingTvPackages(hoisted.values.flatMap { cluster -> cluster.clusterAppList })
        if (missing.isEmpty()) return bundle.copy(streamClusters = hoisted)
        val fetched = runCatching { fetchTvApps(missing) }
            .getOrDefault(emptyList())
            .associateBy { app -> app.packageName }
        val placed = hoisted
            .mapValues { (_, cluster) -> cluster.copy(clusterAppList = insertPairApps(cluster.clusterAppList, fetched)) }
        return bundle.copy(streamClusters = placed)
    }

    fun searchResults(
        isTvContext: Boolean,
        usableAuth: AuthData?,
        web: SearchContract,
        native: (AuthData) -> SearchContract,
        fetchTvApps: (List<String>) -> List<App>,
        query: String,
        pageToken: String
    ): StreamBundle {
        if (!isTvContext) return web.searchResults(query, pageToken)
        val auth = usableAuth
        if (auth != null) {
            try {
                return native(auth).searchResults(query, pageToken)
            } catch (exception: Exception) {
                if (exception is CancellationException) throw exception
                // Fall back to the web path; its result is repaired for TV twins below.
            }
        }
        return remediateBundle(web.searchResults(query, pageToken), fetchTvApps)
    }

    fun nextStreamBundle(
        isTvContext: Boolean,
        usableAuth: AuthData?,
        web: SearchContract,
        native: (AuthData) -> SearchContract,
        fetchTvApps: (List<String>) -> List<App>,
        query: String,
        pageToken: String
    ): StreamBundle {
        if (!isTvContext) return web.nextStreamBundle(query, pageToken)
        val auth = usableAuth
        if (auth != null) {
            try {
                return native(auth).nextStreamBundle(query, pageToken)
            } catch (exception: Exception) {
                if (exception is CancellationException) throw exception
                // Fall back to the web path; its result is repaired for TV twins below.
            }
        }
        return remediateBundle(web.nextStreamBundle(query, pageToken), fetchTvApps)
    }

    fun nextStreamCluster(
        isTvContext: Boolean,
        usableAuth: AuthData?,
        web: SearchContract,
        native: (AuthData) -> SearchContract,
        fetchTvApps: (List<String>) -> List<App>,
        query: String,
        pageToken: String
    ): StreamCluster {
        if (!isTvContext) return web.nextStreamCluster(query, pageToken)
        val auth = usableAuth
        if (auth != null) {
            try {
                return native(auth).nextStreamCluster(query, pageToken)
            } catch (exception: Exception) {
                if (exception is CancellationException) throw exception
                // Fall back to the web path; its result is repaired for TV twins below.
            }
        }
        return remediateCluster(web.nextStreamCluster(query, pageToken), fetchTvApps)
    }

    fun searchSuggestions(
        isTvContext: Boolean,
        usableAuth: AuthData?,
        web: SearchContract,
        native: (AuthData) -> SearchContract,
        query: String
    ): List<SearchSuggestEntry> {
        val auth = if (isTvContext) usableAuth else null
        if (auth != null) {
            try {
                return native(auth).searchSuggestions(query)
            } catch (exception: Exception) {
                if (exception is CancellationException) throw exception
                // Fall back to the web path for suggestions.
            }
        }
        return web.searchSuggestions(query)
    }
}

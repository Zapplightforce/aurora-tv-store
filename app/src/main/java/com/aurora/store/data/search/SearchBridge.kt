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
import com.aurora.gplayapi.helpers.SearchHelper
import com.aurora.gplayapi.helpers.contracts.SearchContract
import com.aurora.gplayapi.helpers.web.WebAppDetailsHelper
import com.aurora.gplayapi.helpers.web.WebSearchHelper
import com.aurora.store.data.providers.AuthProvider
import com.aurora.store.data.providers.SpoofProvider
import com.aurora.gplayapi.network.IHttpClient
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Search [SearchContract] that prefers the native (gmsapi) path when the active spoof
 * profile is a TV device, falling back to the web (batchexecute) path on any native
 * failure. A web fallback under a TV context has its result list repaired: the known
 * TV twin of a mobile app is hoisted above it, or fetched via a single batched web
 * details call and inserted when the web search omitted it. Non-TV contexts return the
 * exact web result with zero extra calls.
 *
 * The native [SearchHelper] is built per call from the current [AuthProvider.authData]
 * instead of the HelperModule singleton: that singleton is created once with whatever
 * auth existed at construction time and can hold stale credentials after a session
 * rebuild (spoof change forces restart + re-login).
 */
@Singleton
class SearchBridge @Inject constructor(
    private val authProvider: AuthProvider,
    private val httpClient: IHttpClient,
    private val webSearchHelper: WebSearchHelper,
    private val webAppDetailsHelper: WebAppDetailsHelper,
    private val spoofProvider: SpoofProvider
) : SearchContract {

    private val isTvContext: Boolean
        get() = TvDeviceSupport.isTvDevice(spoofProvider.deviceProperties)

    /** Native session auth usable right now: not the no-session placeholder and matching the active spoof. */
    private val usableAuth: AuthData?
        get() = authProvider.authData
            ?.takeIf { it.email != "BOGUS" }
            ?.takeIf { authProvider.matchesActiveSpoof(it) }

    private fun nativeSearch(auth: AuthData): SearchContract = SearchHelper(auth).using(httpClient)

    private fun fetchTvApps(packages: List<String>): List<App> =
        webAppDetailsHelper.getAppByPackageName(packages)

    override fun searchResults(query: String, pageToken: String): StreamBundle =
        TvDeviceSupport.searchResults(
            isTvContext = isTvContext,
            usableAuth = usableAuth,
            web = webSearchHelper,
            native = ::nativeSearch,
            fetchTvApps = ::fetchTvApps,
            query = query,
            pageToken = pageToken
        )

    override fun nextStreamBundle(query: String, pageToken: String): StreamBundle =
        TvDeviceSupport.nextStreamBundle(
            isTvContext = isTvContext,
            usableAuth = usableAuth,
            web = webSearchHelper,
            native = ::nativeSearch,
            fetchTvApps = ::fetchTvApps,
            query = query,
            pageToken = pageToken
        )

    override fun nextStreamCluster(query: String, pageToken: String): StreamCluster =
        TvDeviceSupport.nextStreamCluster(
            isTvContext = isTvContext,
            usableAuth = usableAuth,
            web = webSearchHelper,
            native = ::nativeSearch,
            fetchTvApps = ::fetchTvApps,
            query = query,
            pageToken = pageToken
        )

    override fun searchSuggestions(query: String): List<SearchSuggestEntry> =
        TvDeviceSupport.searchSuggestions(
            isTvContext = isTvContext,
            usableAuth = usableAuth,
            web = webSearchHelper,
            native = ::nativeSearch,
            query = query
        )
}

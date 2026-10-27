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
import java.util.Properties
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure JVM tests (no network, no Android runtime) for the TV-context search routing,
 * detection and TV-twin remediation that [SearchBridge] delegates to [TvDeviceSupport].
 */
class TvDeviceSupportTest {

    private companion object {
        const val MOBILE = "com.netflix.mediaclient"
        const val TV = "com.netflix.ninja"

        // Features values copied verbatim from the gplayapi 3.6.4 bundled profiles
        // (res/raw/gplayapi_bravia_vu2.properties / res/raw/gplayapi_px_9a.properties).
        // Bravia line is the full TV feature list; the Pixel line is its decisive
        // (non-TV) subset.
        const val BRAVIA_FEATURES =
            "android.software.leanback_only,android.software.adoptable_storage,android.hardware.hdmi.cec," +
                "android.software.backup,com.amazon.amazonvideo.livingroom.feature.AV_TARGET,android.hardware.ethernet," +
                "android.software.activities_on_secondary_displays,android.software.voice_recognizers," +
                "android.hardware.audio.low_latency,android.software.vulkan.deqp.level," +
                "android.software.cant_save_state,com.sony.dtv.software.braviachat,android.hardware.opengles.aep," +
                "android.hardware.type.television,android.hardware.bluetooth,android.software.incremental_delivery," +
                "android.hardware.usb.host,android.hardware.audio.output,android.software.verified_boot," +
                "android.hardware.gamepad,android.software.live_tv,nrdp.modelgroup,android.software.autofill," +
                "android.software.securely_removes_users,android.software.leanback," +
                "android.hardware.bluetooth_le,android.software.app_widgets,com.google.android.tv.installed," +
                "android.software.input_methods,com.sony.dtv.software.m2.devicecontrolservice," +
                "android.hardware.vulkan.version,android.hardware.wifi.passpoint,android.hardware.screen.landscape," +
                "com.google.android.feature.GLOBAL_ASSIST_TRIGGERING,android.hardware.ram.normal," +
                "android.software.webview,com.sony.dtv.software.braviaapp,android.hardware.camera.any," +
                "android.hardware.vulkan.compute,android.hardware.location.network,android.software.cts," +
                "android.software.app_enumeration,com.sony.dtv.software.m2.seeds_auth,android.hardware.wifi.direct," +
                "android.software.live_wallpaper,android.hardware.ipsec_tunnels,com.sony.devices,android.hardware.wifi," +
                "android.hardware.location,android.hardware.vulkan.level,android.hardware.camera.external," +
                "nrdp.validation,android.software.file_based_encryption"

        const val PIXEL_FEATURES =
            "android.hardware.sensor.proximity,android.hardware.telephony.ims.singlereg," +
                "android.hardware.faketouch,android.hardware.touchscreen.multitouch," +
                "android.hardware.telephony,android.hardware.camera,android.hardware.nfc," +
                "android.hardware.sensor.accelerometer,android.software.telecom," +
                "android.hardware.touchscreen,android.hardware.telephony.subscription"
    }

    private val auth = AuthData("user@example.com")

    private class FakeSearchContract(
        private val bundle: StreamBundle = StreamBundle(0, "", "", emptyMap()),
        private val fail: Boolean = false
    ) : SearchContract {
        var searchResultsCalls = 0
        var nextStreamBundleCalls = 0
        var nextStreamClusterCalls = 0
        var searchSuggestionsCalls = 0

        private fun failIfNeeded() {
            if (fail) throw IllegalStateException("simulated native failure")
        }

        override fun searchResults(query: String, pageToken: String): StreamBundle {
            searchResultsCalls++
            failIfNeeded()
            return bundle
        }

        override fun nextStreamBundle(query: String, pageToken: String): StreamBundle {
            nextStreamBundleCalls++
            failIfNeeded()
            return bundle
        }

        override fun nextStreamCluster(query: String, pageToken: String): StreamCluster =
            bundle.streamClusters.values.first()

        override fun searchSuggestions(query: String): List<SearchSuggestEntry> {
            searchSuggestionsCalls++
            failIfNeeded()
            return emptyList()
        }
    }

    private fun failingFetch(): (List<String>) -> List<App> =
        { throw AssertionError("details fetch must not happen") }

    private fun app(packageName: String): App = App(packageName)

    private fun bundleOf(vararg apps: App): StreamBundle =
        StreamBundle(1, "Top apps", "", mapOf(1 to StreamCluster(1, "Top", "", "", "", apps.toList())))

    private fun packages(bundle: StreamBundle): List<String> =
        bundle.streamClusters.values.first().clusterAppList.map { it.packageName }

    // (a) Routing

    @Test
    fun tvContext_nativeTriedFirst_whenAuthUsable() {
        val native = FakeSearchContract(bundle = bundleOf(app(TV), app(MOBILE)))
        val web = FakeSearchContract()

        val outcome = TvDeviceSupport.searchResults(true, auth, web, { native }, failingFetch(), "netflix", "")

        assertEquals(listOf(TV, MOBILE), packages(outcome))
        assertEquals(1, native.searchResultsCalls)
        assertEquals(0, web.searchResultsCalls)
    }

    @Test
    fun tvContext_nativeFailure_fallsBackToWebUnchanged() {
        val webBundle = bundleOf(app("com.other.a"), app("com.other.b"))

        val outcome = TvDeviceSupport.searchResults(
            true, auth, FakeSearchContract(bundle = webBundle),
            { _ -> FakeSearchContract(fail = true) },
            failingFetch(), "netflix", ""
        )

        assertEquals(webBundle, outcome)
    }

    @Test
    fun tvContext_nativeFailure_hoistsPresentTvTwin() {
        val web = FakeSearchContract(bundle = bundleOf(app(MOBILE), app(TV), app("com.other.a")))

        val outcome = TvDeviceSupport.searchResults(
            true, auth, web,
            { FakeSearchContract(fail = true) },
            failingFetch(), "netflix", ""
        )

        assertEquals(listOf(TV, MOBILE, "com.other.a"), packages(outcome))
        assertEquals(1, web.searchResultsCalls)
    }

    @Test
    fun tvContext_nativeFailure_insertsMissingTvTwinWithSingleBatchedFetch() {
        val web = FakeSearchContract(bundle = bundleOf(app("com.other.a"), app(MOBILE), app("com.other.b")))
        val fetchCalls = mutableListOf<List<String>>()

        val outcome = TvDeviceSupport.searchResults(
            true, auth, web,
            { FakeSearchContract(fail = true) },
            { pkgs -> fetchCalls.add(pkgs); listOf(app(TV)) },
            "netflix", ""
        )

        assertEquals(listOf("com.other.a", TV, MOBILE, "com.other.b"), packages(outcome))
        assertEquals(1, fetchCalls.size)
        assertEquals(listOf(TV), fetchCalls.first())
    }

    @Test
    fun tvContext_nativeFailure_fetchFails_returnsWebUnchanged() {
        val webBundle = bundleOf(app("com.other.a"), app(MOBILE))

        val outcome = TvDeviceSupport.searchResults(
            true, auth, FakeSearchContract(bundle = webBundle),
            { FakeSearchContract(fail = true) },
            failingFetch(), "netflix", ""
        )

        assertEquals(webBundle, outcome)
    }

    @Test
    fun tvContext_noUsableAuth_nativeSkipped_webUsed() {
        val native = FakeSearchContract()
        val web = FakeSearchContract()

        val outcome = TvDeviceSupport.searchResults(true, null, web, { native }, failingFetch(), "netflix", "")

        assertEquals(0, native.searchResultsCalls)
        assertEquals(1, web.searchResultsCalls)
    }

    @Test
    fun nonTvContext_webOnly_zeroExtraCalls() {
        // Mobile twin present, TV twin absent: a web-only result stays untouched — no native, no fetch.
        val webBundle = bundleOf(app(MOBILE), app("com.other.a"))
        val native = FakeSearchContract()
        val web = FakeSearchContract(bundle = webBundle)

        val outcome = TvDeviceSupport.searchResults(false, auth, web, { native }, failingFetch(), "netflix", "")

        assertEquals(webBundle, outcome)
        assertEquals(0, native.searchResultsCalls)
        assertEquals(1, web.searchResultsCalls)
    }

    @Test
    fun tvContext_nextStreamBundle_fallbackHoistsTwin() {
        val web = FakeSearchContract(bundle = bundleOf(app(MOBILE), app(TV)))

        val outcome = TvDeviceSupport.nextStreamBundle(
            true, auth, web,
            { FakeSearchContract(fail = true) },
            failingFetch(), "netflix", "page-2"
        )

        assertEquals(listOf(TV, MOBILE), packages(outcome))
        assertEquals(1, web.nextStreamBundleCalls)
    }

    @Test
    fun tvContext_suggestions_nativeFirstThenWebFallback() {
        val native = FakeSearchContract(fail = true)
        val web = FakeSearchContract()

        val outcome = TvDeviceSupport.searchSuggestions(true, auth, web, { native }, "netflix")

        assertEquals(1, native.searchSuggestionsCalls)
        assertEquals(1, web.searchSuggestionsCalls)
        assertTrue(outcome.isEmpty())
    }

    // (b) TV detection on real profile data

    @Test
    fun braviaTvProfile_detectedAsTv() {
        val properties = Properties().apply {
            setProperty("Build.MODEL", "BRAVIA VU2")
            setProperty("Build.FINGERPRINT", "Sony/BRAVIA_VU2/BRAVIA_VU1_2K:11/RTT7.210923.001/146-60522:user/release-keys")
            setProperty("Features", BRAVIA_FEATURES)
        }

        assertTrue(TvDeviceSupport.isTvDevice(properties))
    }

    @Test
    fun pixelPhoneProfile_notTv() {
        val properties = Properties().apply {
            setProperty("Build.MODEL", "Pixel 9a")
            setProperty("Build.FINGERPRINT", "google/tegu/tegu:15/BD4A.250405.003/13238919:user/release-keys")
            setProperty("Features", PIXEL_FEATURES)
        }

        assertFalse(TvDeviceSupport.isTvDevice(properties))
    }

    @Test
    fun profileWithoutFeatures_notTv() {
        assertFalse(TvDeviceSupport.isTvDevice(Properties()))
        assertFalse(TvDeviceSupport.isTvDevice(null))
    }

    // (c) Hoist/insert pair logic

    @Test
    fun hoist_pairUnchanged_whenTvTwinAlreadyDirectlyAboveMobile() {
        val apps = listOf(app("com.other.a"), app(TV), app(MOBILE))

        assertEquals(apps, TvDeviceSupport.hoistPairApps(apps))
    }

    @Test
    fun hoist_movesTvTwinDirectlyAboveMobileTwin() {
        val apps = listOf(app(MOBILE), app("com.other.a"), app(TV))

        assertEquals(
            listOf(TV, MOBILE, "com.other.a"),
            TvDeviceSupport.hoistPairApps(apps).map { it.packageName }
        )
    }

    @Test
    fun hoist_nonPairedAppsUnchanged() {
        val apps = listOf(app("com.other.a"), app("com.other.b"))

        assertEquals(apps, TvDeviceSupport.hoistPairApps(apps))
    }

    @Test
    fun insert_fetchedTvTwinPlacedDirectlyAboveMobileTwin() {
        val apps = listOf(app("com.other.a"), app(MOBILE), app("com.other.b"))

        assertEquals(
            listOf("com.other.a", TV, MOBILE, "com.other.b"),
            TvDeviceSupport.insertPairApps(apps, mapOf(TV to app(TV))).map { it.packageName }
        )
    }

    @Test
    fun insert_noMobileTwinPresent_untouched() {
        val apps = listOf(app(TV), app("com.other.a"))

        assertEquals(apps, TvDeviceSupport.insertPairApps(apps, mapOf(TV to app(TV))))
    }

    @Test
    fun remediateApps_tvTwinPresent_noFetch_hoistedOrderKept() {
        val apps = listOf(app(MOBILE), app(TV))

        assertEquals(
            listOf(TV, MOBILE),
            TvDeviceSupport.remediateApps(apps, failingFetch()).map { it.packageName }
        )
    }
}

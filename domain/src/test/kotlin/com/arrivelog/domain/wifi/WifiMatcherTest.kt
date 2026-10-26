package com.arrivelog.domain.wifi

import com.arrivelog.domain.model.WifiNetwork
import com.arrivelog.domain.testing.OFFICE
import com.arrivelog.domain.testing.OFFICE_ID
import com.arrivelog.domain.testing.SITE2
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class WifiMatcherTest {

    @Test
    fun `quotes are stripped and redacted values become null`() {
        assertThat(WifiMatcher.normalizeSsid("\"Corp WiFi\"")).isEqualTo("Corp WiFi")
        assertThat(WifiMatcher.normalizeSsid("CorpWiFi")).isEqualTo("CorpWiFi")
        assertThat(WifiMatcher.normalizeSsid("<unknown ssid>")).isNull()
        assertThat(WifiMatcher.normalizeSsid("\"\"")).isNull()
        assertThat(WifiMatcher.normalizeSsid(null)).isNull()
        assertThat(WifiMatcher.normalizeBssid("02:00:00:00:00:00")).isNull()
        assertThat(WifiMatcher.normalizeBssid("AA:BB:CC:DD:EE:01")).isEqualTo("aa:bb:cc:dd:ee:01")
    }

    @Test
    fun `shared corporate SSID matches every site that lists it`() {
        val shared = SITE2.copy(ssids = OFFICE.ssids)
        val m = WifiMatcher.match(WifiNetwork("CorpWiFi", null), listOf(OFFICE, shared), matchBssid = false)
        assertThat(m).isEqualTo(WifiMatch.Matched(setOf(OFFICE_ID, shared.id), "CorpWiFi"))
    }

    @Test
    fun `strict matching needs a known BSSID`() {
        val m = WifiMatcher.match(WifiNetwork("CorpWiFi", null), listOf(OFFICE), matchBssid = true)
        assertThat(m).isEqualTo(WifiMatch.BssidMismatch)
    }

    @Test
    fun `scan results pick the first office network`() {
        val m = WifiMatcher.matchAny(
            listOf(WifiNetwork("Guest", null), WifiNetwork("CorpWiFi", null)),
            listOf(OFFICE),
            matchBssid = false,
        )
        assertThat(m).isInstanceOf(WifiMatch.Matched::class.java)
        assertThat(WifiMatcher.matchAny(listOf(WifiNetwork(null, null)), listOf(OFFICE), false)).isEqualTo(WifiMatch.UnknownSsid)
    }
}

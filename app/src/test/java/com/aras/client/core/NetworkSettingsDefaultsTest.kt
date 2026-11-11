package com.aras.client.core

import com.aras.client.AppConfig
import com.aras.client.handler.MmkvManager
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.`when`
import org.mockito.ArgumentMatchers.anyBoolean
import org.mockito.ArgumentMatchers.anyString

class NetworkSettingsDefaultsTest {
    private val savedValues = mutableMapOf<String, Boolean>()
    private val defaults = mapOf(
        AppConfig.PREF_IPV6_ENABLED to AppConfig.DEFAULT_IPV6_ENABLED,
        AppConfig.PREF_PREFER_IPV6 to AppConfig.DEFAULT_PREFER_IPV6,
        AppConfig.PREF_FAKE_DNS_ENABLED to AppConfig.DEFAULT_FAKE_DNS_ENABLED,
        AppConfig.PREF_LOCAL_DNS_ENABLED to AppConfig.DEFAULT_LOCAL_DNS_ENABLED,
    )

    @Before
    fun setUp() {
        val storage = TestSettings.install()
        `when`(storage.decodeBool(anyString(), anyBoolean())).thenAnswer { invocation ->
            savedValues[invocation.getArgument<String>(0)] ?: invocation.getArgument<Boolean>(1)
        }
    }

    @After
    fun tearDown() {
        TestSettings.install()
    }

    @Test
    fun `missing network preferences are enabled for runtime and settings UI`() {
        defaults.forEach { (key, defaultValue) ->
            assertTrue(key, defaultValue)
            assertTrue(key, MmkvManager.decodeSettingsBool(key))
            assertTrue(key, MmkvManager.decodeSettingsBool(key, defaultValue))
        }
    }

    @Test
    fun `saved disabled choices are preserved`() {
        defaults.forEach { (key, defaultValue) ->
            savedValues[key] = false
            assertFalse(key, MmkvManager.decodeSettingsBool(key))
            assertFalse(key, MmkvManager.decodeSettingsBool(key, defaultValue))
        }
    }

    @Test
    fun `cleared preferences return to enabled defaults`() {
        defaults.keys.forEach { savedValues[it] = false }
        savedValues.clear()
        defaults.keys.forEach { assertTrue(it, MmkvManager.decodeSettingsBool(it)) }
    }

    @Test
    fun `unrelated boolean preferences keep their disabled default`() {
        assertFalse(MmkvManager.decodeSettingsBool(AppConfig.PREF_MUX_ENABLED))
        assertFalse(MmkvManager.decodeSettingsBool(AppConfig.PREF_FRAGMENT_ENABLED))
    }
}

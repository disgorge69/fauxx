package com.fauxx.data.device

import com.fauxx.data.model.SyntheticPersona
import com.fauxx.data.querybank.CategoryPool
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Locks the Device Identity derivation contract (issue #242, spike C2). The device set must be
 * deterministic and coherent: a persona always resolves to the same one-mobile-plus-one-desktop pair,
 * the mobile UA is Android-Chromium (the phone's WebView TLS, #168), the desktop UA is not, and the
 * Chrome major is pinned to the persona's creation time. The catalog checksum guards the shared asset
 * so the desktop companion (fauxx-desktop#47), which vendors the same file, cannot drift out of sync.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class DeviceDeriverTest {

    private val deriver get() = DeviceDeriver(RuntimeEnvironment.getApplication())

    private fun persona(id: String, createdAt: Long = DeviceDeriver.BASELINE_EPOCH_MS) = SyntheticPersona(
        id = id,
        name = "n",
        ageRange = "AGE_35_44",
        profession = "ENGINEER",
        region = "US_MIDWEST",
        interests = setOf(CategoryPool.TECHNOLOGY),
        createdAt = createdAt,
        activeUntil = createdAt + 7L * 24 * 60 * 60 * 1000,
    )

    private fun isChromiumAndroid(ua: String) =
        ua.contains("Android") && ua.contains("Chrome/") && ua.contains("Mobile") &&
            !ua.contains("Firefox") && !ua.contains("CriOS") && !ua.contains("FxiOS") && !ua.contains("EdgA")

    // --- chromeMajor (pure) ---

    @Test
    fun `chromeMajor equals the baseline at the baseline epoch and floors below it`() {
        assertEquals(DeviceDeriver.BASELINE_MAJOR, DeviceDeriver.chromeMajor(DeviceDeriver.BASELINE_EPOCH_MS))
        assertEquals(DeviceDeriver.BASELINE_MAJOR, DeviceDeriver.chromeMajor(DeviceDeriver.BASELINE_EPOCH_MS - 5_000))
        assertEquals(DeviceDeriver.BASELINE_MAJOR, DeviceDeriver.chromeMajor(0L))
    }

    @Test
    fun `chromeMajor advances one major per release interval`() {
        val epoch = DeviceDeriver.BASELINE_EPOCH_MS
        val interval = DeviceDeriver.RELEASE_INTERVAL_MS
        assertEquals(DeviceDeriver.BASELINE_MAJOR + 1, DeviceDeriver.chromeMajor(epoch + interval))
        assertEquals(DeviceDeriver.BASELINE_MAJOR + 3, DeviceDeriver.chromeMajor(epoch + 3 * interval + 1))
    }

    @Test
    fun `chromeMajor is monotonic non-decreasing in createdAt`() {
        var prev = DeviceDeriver.chromeMajor(0L)
        var t = DeviceDeriver.BASELINE_EPOCH_MS
        repeat(40) {
            t += 9L * 24 * 60 * 60 * 1000 // 9-day steps
            val cur = DeviceDeriver.chromeMajor(t)
            assertTrue("major must never go backwards", cur >= prev)
            prev = cur
        }
    }

    // --- pick (pure) ---

    @Test
    fun `pick is deterministic and within range`() {
        repeat(20) { i ->
            val id = "persona-$i"
            val a = DeviceDeriver.pick(id, DeviceDeriver.DOMAIN_MOBILE, 0, 6)
            val b = DeviceDeriver.pick(id, DeviceDeriver.DOMAIN_MOBILE, 0, 6)
            assertEquals(a, b)
            assertTrue(a in 0 until 6)
        }
    }

    @Test
    fun `pick varies across persona ids`() {
        val distinct = (0 until 60).map { DeviceDeriver.pick("id-$it", DeviceDeriver.DOMAIN_MOBILE, 0, 6) }.toSet()
        assertTrue("selection must not be degenerate", distinct.size > 1)
    }

    // --- devicesFor (catalog + derivation) ---

    @Test
    fun `derives exactly one mobile and one desktop device`() {
        val devices = deriver.devicesFor(persona("p1"))
        assertEquals(2, devices.size)
        assertEquals(1, devices.count { it.formFactor == FormFactor.MOBILE })
        assertEquals(1, devices.count { it.formFactor == FormFactor.DESKTOP })
    }

    @Test
    fun `mobile device is android-chromium and desktop device is not mobile`() {
        val p = persona("p2")
        val mobile = deriver.mobileFor(p)
        val desktop = deriver.desktopFor(p)

        assertTrue("mobile UA must be Android-Chromium (#168): ${mobile.userAgent}", isChromiumAndroid(mobile.userAgent))
        assertTrue(mobile.isMobile)
        assertEquals("Android", mobile.platform)

        assertFalse("desktop must not be mobile", desktop.isMobile)
        assertFalse("desktop UA must not carry the Mobile token", desktop.userAgent.contains("Mobile"))
        assertFalse("desktop UA must not be Android", desktop.userAgent.contains("Android"))
        assertTrue(desktop.platform in setOf("Windows", "macOS", "Linux"))
    }

    @Test
    fun `derivation is stable across calls and across deriver instances`() {
        val p = persona("stable-id")
        val a = deriver.devicesFor(p)
        val b = deriver.devicesFor(p)
        val c = DeviceDeriver(RuntimeEnvironment.getApplication()).devicesFor(p) // fresh instance = "restart"
        assertEquals(a, b)
        assertEquals(a, c)
    }

    @Test
    fun `chrome major is substituted into the UA and brand versions with no token left`() {
        val p = persona("v-id", createdAt = DeviceDeriver.BASELINE_EPOCH_MS) // major == BASELINE_MAJOR
        val major = DeviceDeriver.BASELINE_MAJOR.toString()
        for (d in deriver.devicesFor(p)) {
            assertTrue("UA must carry the substituted major: ${d.userAgent}", d.userAgent.contains("Chrome/$major."))
            assertFalse("no unresolved token may remain", d.userAgent.contains(DeviceDeriver.MAJOR_TOKEN))
            val chromeBrand = d.brands.first { it.name == "Google Chrome" }
            assertEquals(major, chromeBrand.version)
        }
    }

    @Test
    fun `mobile model selection distributes across personas`() {
        val models = (0 until 60).map { deriver.mobileFor(persona("id-$it")).model }.toSet()
        assertTrue("expected more than one mobile model over many personas", models.size > 1)
    }

    // --- shared-asset checksum (vendor + checksum contract with fauxx-desktop#47) ---

    @Test
    fun `bundled device_templates_json matches the pinned checksum`() {
        val bytes = RuntimeEnvironment.getApplication().assets.open("device_templates.json").use { it.readBytes() }
        val hex = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        assertEquals(
            "device_templates.json changed — update this checksum AND the vendored copy + test in fauxx-desktop#47",
            DEVICE_TEMPLATES_SHA256,
            hex,
        )
    }

    private companion object {
        // Pinned SHA-256 of app/src/main/assets/device_templates.json. The desktop companion vendors
        // the same file and asserts the same value, so the two repos cannot silently diverge.
        const val DEVICE_TEMPLATES_SHA256 = "3059247b5e83ea09b3ec69d8ed68577c4ceff27d3ca09f0842dd6db0b1e7a3dd"
    }
}

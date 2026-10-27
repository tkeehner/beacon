package com.tyler.primelauncher

import androidx.test.core.app.ApplicationProvider
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class IconPackTest {
    private val ctx: android.content.Context = ApplicationProvider.getApplicationContext()
    private val phoneApps = listOf("com.android.chrome", "com.sec.android.app.camera", "com.sec.android.app.clockpackage", "com.sec.android.app.popupcalculator", "com.samsung.android.calendar", "com.google.android.calendar", "com.samsung.android.app.contacts", "com.samsung.android.dialer", "com.google.android.apps.messaging", "com.samsung.android.messaging", "com.sec.android.gallery3d", "com.sec.android.app.myfiles", "com.android.settings", "com.google.android.apps.maps", "com.android.vending", "com.google.android.gm", "com.google.android.youtube", "com.tyler.matrixsms", "ai.x.grok", "ai.x.grok.bot")

    @Test fun everyCommonAppHasAnAbstractIcon() {
        IconArt.load(ctx); assertEquals(20, phoneApps.size)
        for (p in phoneApps) { assertNotNull("abstract $p", IconArt.paths(p, IconStyle.ABSTRACT)); assertEquals("no brand pack $p", null, IconArt.paths(p, IconStyle.PACK)) }
        assertEquals(null, IconArt.paths("com.android.chrome", IconStyle.LETTERS))
    }
    @Test fun publicBuildShipsNoBrandPack() {
        val j = JSONObject(ctx.assets.open("icons.json").bufferedReader().readText()); assertEquals(0, j.getJSONObject("pack").length()); assertTrue(j.getString("note").contains("intentionally empty"))
    }
    @Test fun calendarDigitsParse() { for (d in 1..31) androidx.core.graphics.PathParser.createPathFromPathData(IconMath.dateDigits(d)) }
}

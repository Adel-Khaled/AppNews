package com.smartnews

import android.content.Intent
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/**
 * End-to-end walkthrough on a real emulator with real network: every tab, Egypt/World,
 * Arabic, search, story page, saving and settings. Screenshots go to /data/local/tmp/shots.
 */
@RunWith(AndroidJUnit4::class)
class WalkthroughTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val device = UiDevice.getInstance(inst)
    private val pkg = inst.targetContext.packageName
    private val failures = mutableListOf<String>()

    private fun log(msg: String) = Log.i("SmartNewsTest", msg)
    private fun shot(name: String) {
        device.waitForIdle(2000)
        device.executeShellCommand("screencap -p /data/local/tmp/shots/$name.png")
        log("shot $name")
    }
    private fun has(res: String, ms: Long) = device.wait(Until.hasObject(By.res(res)), ms) == true
    /** Tabs further along the row are off-screen; swipe the row until the tab appears. */
    private fun clickTab(id: String): Boolean {
        repeat(10) { i ->
            device.findObject(By.res("tab-$id"))?.let { tab ->
                Thread.sleep(1200) // let any swipe fling settle before tapping
                device.findObject(By.res("tab-$id"))?.click() ?: tab.click()
                Thread.sleep(1500)
                return runCatching { device.findObject(By.res("tab-$id"))?.isSelected == true }.getOrDefault(false)
                    .also { if (!it) failures += "tab '$id' did not become selected" }
            }
            val row = device.findObject(By.res("tab-row"))?.visibleBounds ?: return@repeat
            val y = row.centerY()
            // Real finger swipe; first one way (Arabic: later tabs are on the left), then the other.
            if (i < 5) device.swipe(row.left + 60, y, row.right - 60, y, 20) else device.swipe(row.right - 60, y, row.left + 60, y, 20)
            Thread.sleep(700)
        }
        failures += "tab '$id' not reachable"; return false
    }
    private fun click(res: String): Boolean {
        val o = device.wait(Until.findObject(By.res(res)), 10_000) ?: run { failures += "missing element '$res'"; return false }
        o.click(); return true
    }
    private fun check(step: String, ok: Boolean) { log("$step: ${if (ok) "OK" else "FAIL"}"); if (!ok) failures += step }
    private fun alive(step: String) {
        val ok = device.currentPackageName == pkg
        check("$step — app still running (no crash)", ok)
        if (!ok) relaunch()
    }
    private fun relaunch() {
        val i = inst.targetContext.packageManager.getLaunchIntentForPackage(pkg)!!.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        inst.targetContext.startActivity(i)
        device.wait(Until.hasObject(By.pkg(pkg).depth(0)), 15_000)
    }
    private fun keyboardShown() = device.executeShellCommand("dumpsys input_method").contains("mInputShown=true")
    // Lists update live while news arrives, so a node can go stale between finding and reading it.
    private fun titles(res: String = "story-title"): List<String> =
        runCatching { device.findObjects(By.res(res)).mapNotNull { o -> runCatching { o.text }.getOrNull() } }.getOrDefault(emptyList())
    private fun arabicShare(list: List<String>): Double {
        if (list.isEmpty()) return 0.0
        return list.count { t -> t.count { it in '؀'..'ۿ' } > t.count { it.isLetter() } / 2 }.toDouble() / list.size
    }
    private fun waitArabic(res: String, ms: Long, min: Double): Boolean {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) {
            if (arabicShare(titles(res)) >= min) return true
            Thread.sleep(3000)
        }
        return false
    }

    @Test
    fun walkthrough() {
        device.executeShellCommand("mkdir -p /data/local/tmp/shots")
        // Real breaking-news notifications pop up over the top of the screen; keep them out of the way.
        device.executeShellCommand("settings put global heads_up_notifications_enabled 0")
        relaunch()

        // 1. Home loads news on first launch (English device → English UI, World by default).
        check("home shows stories", has("story", 240_000))
        shot("01_home_first_launch")

        // 2. Category tabs.
        if (click("tab-politics")) { check("politics tab shows stories", has("story", 120_000)); shot("02_tab_politics") }
        alive("politics tab")
        if (click("tab-economy")) { check("economy tab shows stories", has("story", 120_000)); shot("03_tab_economy") }
        alive("economy tab")
        device.findObject(By.res("tab-row"))?.scroll(androidx.test.uiautomator.Direction.LEFT, 1f)
        Thread.sleep(800)
        click("tab-all")

        // 3. Egypt (local) news.
        if (click("scope-local")) {
            check("Egypt (local) shows stories", has("story", 120_000))
            Thread.sleep(20_000)
            shot("04_local_egypt")
        }
        alive("local")

        // 4. Arabic: UI and news in Arabic, including world news.
        if (click("lang-toggle")) {
            Thread.sleep(3000)
            check("Arabic: local headlines in Arabic", waitArabic("story-title", 120_000, 0.8))
            shot("05_arabic_local")
            click("scope-global")
            Thread.sleep(5000)
            check("Arabic: world headlines translated to Arabic", waitArabic("story-title", 300_000, 0.6))
            shot("06_arabic_global")
            if (clickTab("sports")) { check("Arabic sports tab shows stories", has("story", 120_000)); shot("07_arabic_sports") }
        }
        alive("arabic")

        // 5. Latest (was crashing).
        if (click("nav-latest")) {
            check("latest shows articles", has("article", 60_000) || has("empty", 5_000))
            shot("08_latest")
            device.findObject(By.res("date-filter"))?.let { shot("08b_latest_filters") }
        }
        alive("latest")

        // 6. Videos.
        if (click("nav-videos")) { check("videos screen opens", has("videos-screen", 15_000)); shot("09_videos") }
        alive("videos")

        // 7. Search (was crashing).
        if (click("nav-search")) {
            device.wait(Until.findObject(By.res("search-field")), 10_000)?.let { f ->
                f.click(); f.text = "مصر"
                click("search-go")
                check("search returns articles", has("search-results", 90_000))
                Thread.sleep(1500)
                check("keyboard hidden after searching", !keyboardShown())
                shot("10_search")
                if (keyboardShown()) device.pressBack()
            } ?: run { failures += "search field missing" }
        }
        alive("search")

        // 8. Open a story, save it.
        click("nav-home")
        device.wait(Until.findObject(By.res("story")), 60_000)?.click()
        check("story page opens", has("story-detail", 30_000))
        Thread.sleep(8000)
        shot("11_story_detail")
        device.findObject(By.res("story-detail"))?.let { it.scroll(androidx.test.uiautomator.Direction.DOWN, 0.8f); Thread.sleep(1500); shot("11b_story_detail_scrolled") }
        click("save-story")
        device.pressBack()
        alive("story")

        // 9. Saved.
        if (click("nav-saved")) { check("saved article listed", has("saved-list", 15_000)); shot("12_saved") }
        alive("saved")

        // 10. Settings.
        click("nav-home")
        if (click("settings")) { check("settings opens", has("settings-screen", 15_000)); shot("13_settings"); device.pressBack() }
        alive("settings")

        // 11. Restart: news is still there (stored locally).
        device.pressHome()
        Thread.sleep(2000)
        relaunch()
        check("after restart, stored news shows immediately", has("story", 15_000))
        shot("14_after_restart")

        if (failures.isNotEmpty()) fail("Failed checks:\n" + failures.joinToString("\n"))
    }
}

package com.voicedub.app

import android.view.View
import android.view.ViewGroup
import android.widget.Spinner
import android.widget.CheckBox
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.mlkit.nl.translate.TranslateLanguage
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LocalTranslationTest {
    @Test fun targetCatalogMatchesInstalledMlKitApiAndIdentityNeedsNoDownload() {
        assertTrue(TranslateLanguage.getAllLanguages().containsAll(TranslationLanguages.entries.map { it.code }))
        val engine = LocalTranslationEngine("en-US", "en")
        try {
            var prepared = false
            engine.prepare { prepared = it.isSuccess }
            assertTrue(prepared)
            var result: String? = null
            engine.translate("This stays in English.") { result = it.getOrThrow() }
            assertEquals("This stays in English.", result)
        } finally { engine.close() }
    }
    @Test fun targetSurvivesRecreationAndRussianOnlyMossIsDisabledForEnglish() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val preferences = context.getSharedPreferences("settings", 0)
        val previous = preferences.getString("targetLanguage", "ru")
        fun descendants(v: View): List<View> = listOf(v) + if (v is ViewGroup) (0 until v.childCount).flatMap { descendants(v.getChildAt(it)) } else emptyList()
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    val spinners = descendants(activity.window.decorView).filterIsInstance<Spinner>()
                    spinners[1].setSelection(TranslationLanguages.entries.indexOfFirst { it.code == "en" })
                }
                InstrumentationRegistry.getInstrumentation().waitForIdleSync()
                scenario.recreate()
                scenario.onActivity { activity ->
                    val views = descendants(activity.window.decorView)
                    assertEquals("Английский", views.filterIsInstance<Spinner>()[1].selectedItem.toString())
                    val moss = views.filterIsInstance<CheckBox>().first { it.text.startsWith("Сохранять") }
                    assertFalse(moss.isEnabled); assertFalse(moss.isChecked)
                }
            }
        } finally { preferences.edit().putString("targetLanguage", previous).commit() }
    }
}

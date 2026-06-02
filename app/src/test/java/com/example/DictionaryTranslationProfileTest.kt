package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.drduc.engine.DrDucGraphTranslationEngine
import com.drduc.engine.TranslationProfile
import com.drduc.engine.TranslationRequest
import com.drduc.engine.graph.LegadoDictionaryCandidateStore
import com.drduc.engine.graph.MobileGraphStore
import com.drduc.engine.graph.OverlayGraphStore
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DictionaryTranslationProfileTest {
    @Test
    fun vietPhraseAndHanVietAreIndependentReaderProfiles() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val directory = File(context.filesDir, "translate/vietphrase").apply { mkdirs() }
        File(directory, "Names.txt").writeText("张三=Trương Tam\n")
        File(directory, "VietPhrase.txt").writeText("修炼=tu luyện\n")
        File(directory, "ChinesePhienAmWords.txt").writeText("张=Trương\n三=Tam\n修=Tu\n炼=Luyện\n")
        val store = LegadoDictionaryCandidateStore(context)
        val overlay = OverlayGraphStore(context)
        val engine = DrDucGraphTranslationEngine(MobileGraphStore(null), overlay, store)
        try {
            val vp = engine.translate(TranslationRequest("张三修炼。", translationProfile = TranslationProfile.VIETPHRASE))
            val hv = engine.translate(TranslationRequest("张三修炼。", translationProfile = TranslationProfile.HAN_VIET))
            assertTrue(vp.displayText.contains("Trương Tam"))
            assertTrue(vp.displayText.contains("tu luyện"))
            assertTrue(hv.displayText.contains("Trương Tam Tu Luyện"))
            assertNotEquals(vp.cacheKey, hv.cacheKey)
        } finally {
            overlay.close()
        }
    }
}

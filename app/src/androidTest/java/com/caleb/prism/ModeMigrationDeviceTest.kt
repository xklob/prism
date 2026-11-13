package com.caleb.prism

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ModeMigrationDeviceTest {
    @Test fun removedScenesAndNamedLooksMigrateWithoutChangingRemainingIds() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val prefs=context.getSharedPreferences("prism",0)
        assertEquals(listOf(1,2,7,9),Scene.entries.map { it.id })
        for ((name,id) in listOf("AURORA" to 0,"JULIA" to 6,"EQUALIZER" to 8)) {
            prefs.edit().clear().putString("sceneName",name).putInt("scene",id)
                .putString("looks.ids","legacy").putString("preset.legacy.scene",name)
                .putString("preset.legacy.name","Saved favorite").putBoolean("preset.legacy.favorite",true).commit()
            val store=SettingsStore(context)
            assertEquals(Scene.KALEIDO,store.load().scene)
            val preset=store.presets().single()
            assertEquals(Scene.KALEIDO,preset.settings.scene)
            assertEquals("Saved favorite",preset.name);assertTrue(preset.favorite)
        }
        val store=SettingsStore(context)
        val retained=VisualSettings.defaults(Scene.KALEIDOSCOPE).copy(kaleidoscopeShape=2,kaleidoscopeSpace=1.3f)
        store.save(retained)
        assertEquals(retained,store.load())
        prefs.edit().clear().commit()
    }
}

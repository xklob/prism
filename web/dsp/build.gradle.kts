plugins {
    kotlin("multiplatform") version "2.3.21"
}

kotlin {
    js {
        outputModuleName = "prism-dsp"
        browser()
        useEsModules()
        binaries.executable()
    }
    sourceSets.jsMain {
        kotlin.srcDir("../../app/src/main/java/com/caleb/prism")
        kotlin.include(
            "WebDsp.kt", "TimingHistory.kt", "RhythmMath.kt", "BeatFeatures.kt", "RhythmInput.kt", "RhythmClock.kt",
            "PlaybackAnalysis.kt", "SpectrumAnalyzer.kt", "TempoHint.kt", "BeatPeriodicity.kt",
            "TempoMemory.kt", "RhythmTracker.kt", "RhythmSelector.kt", "RhythmRecovery.kt",
            "PhraseTracker.kt", "PhraseCue.kt", "LocalTiming.kt"
        )
    }
}

package com.syncdialect.app.benchmark

import com.syncdialect.app.BuildConfig

/**
 * Gated provider for the application's audio source.
 * In production release builds, injection is strictly disallowed.
 */
object AudioSourceProvider {

    @Volatile
    private var testAudioSourceFactory: (() -> AudioSource)? = null

    /**
     * Registers a custom test [AudioSource] provider.
     * Strictly gated to debug and androidTest builds.
     */
    fun setTestAudioSourceFactory(factory: (() -> AudioSource)?) {
        if (!BuildConfig.DEBUG) {
            throw SecurityException("Audio injection is strictly gated to debug and test builds.")
        }
        testAudioSourceFactory = factory
    }

    /**
     * Resolves the active audio source: custom test source if injected in debug mode,
     * otherwise the production hardware [MicAudioSource].
     */
    fun createAudioSource(sampleRate: Int = 16000): AudioSource {
        if (BuildConfig.DEBUG) {
            val custom = testAudioSourceFactory
            if (custom != null) {
                return custom.invoke()
            }
        }
        return MicAudioSource(sampleRate)
    }

    /**
     * Clears any registered test audio source factory.
     */
    fun reset() {
        testAudioSourceFactory = null
    }
}

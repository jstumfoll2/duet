package com.kinetica.keyboard.engine

/**
 * The decoder's switches as one immutable value (review M7, #184).
 *
 * They used to be process-wide statics read throughout a decode, so a switch flipped mid-decode
 * (the Settings switch, or the developer build's traces screen) could mix two decoders' scores
 * in one candidate list. Now [global] is what a new predictor captures ([WordPredictor.config]),
 * and a decode thread reads the config of the request it is serving ([using]); only code outside
 * a decode, on the main thread and in tools, reads [global].
 *
 * The old names stay as views of this: `ScoreModel.mode`, `ScoreModel.taps`, `ShapeModel.speed`,
 * `PreviousWordFix.enabled`, `LatticeDecoder.enabled` and `BoundaryModel.enabled` read [current]
 * and write [global], so tests and the replay tool set them as before.
 */
data class DecoderConfig(
    val mode: ScoreModel.Mode = ScoreModel.Mode.LEGACY,
    /** Continuous tap likelihood ([ScoreModel.taps]). */
    val taps: Boolean = false,
    /** Speed-weighted shape matching ([ShapeModel.speed]). */
    val speed: Boolean = false,
    /** Revising the previous word ([PreviousWordFix]). */
    val previousWordFix: Boolean = false,
    /** Lattice word breaks ([LatticeDecoder]). */
    val lattice: Boolean = false,
    /** The boundary model deciding peck splits ([BoundaryModel]). */
    val boundary: Boolean = false,
) {
    val logLinear: Boolean get() = mode == ScoreModel.Mode.LOGLINEAR

    companion object {
        /** The classic decoder: every switch off. */
        val CLASSIC = DecoderConfig()

        /** The decoder shipped from v3.0: every switch on (NeuralStack). */
        val NEW = DecoderConfig(ScoreModel.Mode.LOGLINEAR, taps = true, speed = true, previousWordFix = true, lattice = true, boundary = true)

        /** What a predictor built now captures, and what code outside a decode reads. */
        @Volatile
        @JvmStatic
        var global: DecoderConfig = CLASSIC
            private set

        private val local = ThreadLocal<DecoderConfig?>()

        /** The config of the decode this thread is running, or [global] outside one. */
        @JvmStatic
        fun current(): DecoderConfig = local.get() ?: global

        /** Replaces [global] (whole, so readers never see half a change). */
        @JvmStatic
        fun set(config: DecoderConfig) {
            synchronized(this) { global = config }
        }

        /** Changes one part of [global]. */
        @JvmStatic
        fun update(change: (DecoderConfig) -> DecoderConfig) {
            synchronized(this) { global = change(global) }
        }

        /** Runs [block] with [config] as this thread's [current], then restores the one before. */
        inline fun <T> using(config: DecoderConfig, block: () -> T): T {
            val before = enter(config)
            try {
                return block()
            } finally {
                exit(before)
            }
        }

        @PublishedApi
        internal fun enter(config: DecoderConfig): DecoderConfig? {
            val before = local.get()
            local.set(config)
            return before
        }

        @PublishedApi
        internal fun exit(before: DecoderConfig?) {
            if (before == null) local.remove() else local.set(before)
        }
    }
}

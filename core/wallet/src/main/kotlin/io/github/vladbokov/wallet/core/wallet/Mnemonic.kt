package io.github.vladbokov.wallet.core.wallet

import org.web3j.crypto.MnemonicUtils
import java.security.SecureRandom

/**
 * A BIP-39 recovery phrase: a human-readable encoding of wallet entropy as dictionary words.
 *
 * [toString] is masked so the secret cannot leak through logs or string templates;
 * the words are only available through an explicit [reveal]. Instances come from [generate] or [parse] only.
 */
@JvmInline
value class Mnemonic private constructor(
    private val phrase: String,
) {
    /** The normalized phrase: lower-case words separated by single spaces. */
    fun reveal(): String = phrase

    override fun toString(): String = "Mnemonic(***)"

    companion object {
        private val VALID_WORD_COUNTS = setOf(12, 15, 18, 21, 24)

        // \p{Z} also covers the non-breaking space that some keyboards and clipboards insert between words.
        private val SEPARATORS = Regex("[\\s\\p{Z}]+")

        /** 128 bits of entropy -> 12 words (the last word carries a 4-bit checksum). */
        fun generate(random: SecureRandom = SecureRandom()): Mnemonic {
            val entropy = ByteArray(16)
            random.nextBytes(entropy)
            return try {
                Mnemonic(MnemonicUtils.generateMnemonic(entropy))
            } finally {
                entropy.fill(0)
            }
        }

        /**
         * Normalizes [input] (case, whitespace), then checks word count, dictionary membership and the BIP-39
         * checksum. Returns null if the phrase is not valid, so pasted or typed text works as is.
         */
        fun parse(input: String): Mnemonic? {
            val phrase = normalize(input)
            if (phrase.split(" ").size !in VALID_WORD_COUNTS) return null
            val valid =
                try {
                    MnemonicUtils.validateMnemonic(phrase)
                } catch (e: RuntimeException) {
                    false
                }
            return if (valid) Mnemonic(phrase) else null
        }

        /** Whether [input] is a valid recovery phrase, e.g. to enable a button while the user types. */
        fun isValid(input: String): Boolean = parse(input) != null

        private fun normalize(input: String): String =
            input
                .lowercase()
                .split(SEPARATORS)
                .filter { it.isNotEmpty() }
                .joinToString(" ")
    }
}

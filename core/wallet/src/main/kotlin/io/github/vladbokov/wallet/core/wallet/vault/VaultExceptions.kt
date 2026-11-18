package io.github.vladbokov.wallet.core.wallet.vault

/**
 * Base type for vault failures. Messages are technical (for logs); the UI maps each subtype to a localized
 * string, so no user-facing text lives here.
 */
sealed class VaultException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

/** The Keystore key was destroyed (biometrics re-enrolled, lock screen removed). Restore from the phrase. */
class VaultInvalidatedException(
    cause: Throwable? = null,
) : VaultException("Seed key is missing or permanently invalidated", cause)

/**
 * A key that needs a biometric on every use cannot be created. Typically no strong (Class 3) biometric
 * is enrolled, or there is no secure lock screen.
 */
class VaultUnavailableException(
    cause: Throwable? = null,
) : VaultException("Cannot create a per-use biometric key: no strong biometric or secure lock screen", cause)

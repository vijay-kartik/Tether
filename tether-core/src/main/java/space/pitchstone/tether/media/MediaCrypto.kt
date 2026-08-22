package space.pitchstone.tether.media

import space.pitchstone.tether.crypto.Hkdf
import space.pitchstone.tether.crypto.Sha256
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * WhatsApp media encryption, ported from whatsmeow's `download.go`.
 *
 * Media does not travel over the Signal session — only its 32-byte `mediaKey` does. The bytes
 * themselves sit on WhatsApp's CDN, encrypted with keys expanded from that one key, so possession
 * of the message is what grants access to the file.
 */
internal object MediaCrypto {

    /**
     * The four subkeys expanded from a message's `mediaKey`. The info string is part of the
     * derivation, so keys for one media type never decrypt another — pass the wrong one and the
     * MAC check fails rather than yielding garbage.
     */
    class Keys(expanded: ByteArray) {
        val iv: ByteArray = expanded.copyOfRange(0, 16)
        val cipherKey: ByteArray = expanded.copyOfRange(16, 48)
        val macKey: ByteArray = expanded.copyOfRange(48, 80)
        // expanded[80..112] is the reference key, used only for streaming sidecars.
    }

    fun keys(mediaKey: ByteArray, kind: MediaKind): Keys {
        require(mediaKey.size == 32) { "mediaKey must be 32 bytes" }
        // Salt is 32 zero bytes (Go's hkdf.New with a nil salt), not the media key.
        return Keys(Hkdf.derive(ByteArray(32), mediaKey, kind.infoString.toByteArray(Charsets.UTF_8), 112))
    }

    /**
     * Verify and decrypt one downloaded blob.
     *
     * Layout is ciphertext followed by a 10-byte truncated HMAC over `iv || ciphertext`. The MAC is
     * checked before decrypting, and in constant time: a server that could learn whether our
     * comparison failed early would have a padding oracle.
     */
    fun decrypt(
        encrypted: ByteArray,
        keys: Keys,
        fileEncSha256: ByteArray?,
        fileSha256: ByteArray?,
    ): ByteArray {
        require(encrypted.size > MAC_LENGTH) { "media payload too short" }

        // Checked against the *encrypted* bytes, so a truncated or corrupted download is caught
        // before any crypto runs.
        if (fileEncSha256 != null && !MessageDigest.isEqual(Sha256.hash(encrypted), fileEncSha256)) {
            error("media fileEncSha256 mismatch — download corrupt")
        }

        val ciphertext = encrypted.copyOfRange(0, encrypted.size - MAC_LENGTH)
        val mac = encrypted.copyOfRange(encrypted.size - MAC_LENGTH, encrypted.size)
        val expected = Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(keys.macKey, "HmacSHA256"))
            update(keys.iv)
            doFinal(ciphertext)
        }.copyOfRange(0, MAC_LENGTH)
        if (!MessageDigest.isEqual(expected, mac)) error("media MAC mismatch — wrong key or tampered file")

        val plaintext = Cipher.getInstance("AES/CBC/PKCS5Padding").run {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(keys.cipherKey, "AES"), IvParameterSpec(keys.iv))
            doFinal(ciphertext)
        }

        if (fileSha256 != null && !MessageDigest.isEqual(Sha256.hash(plaintext), fileSha256)) {
            error("media fileSha256 mismatch — decrypted content is not what was sent")
        }
        return plaintext
    }

    private const val MAC_LENGTH = 10
}

package space.pitchstone.tether.media

/**
 * The kind of attachment on a message.
 *
 * [infoString] is WhatsApp's per-type HKDF label; it is part of the key derivation, so it must
 * match the type exactly or decryption fails the MAC check. Stickers deliberately share the image
 * label — that is what the protocol does, not an oversight.
 */
enum class MediaKind(internal val infoString: String) {
    IMAGE("WhatsApp Image Keys"),
    STICKER("WhatsApp Image Keys"),
    VIDEO("WhatsApp Video Keys"),
    AUDIO("WhatsApp Audio Keys"),
    DOCUMENT("WhatsApp Document Keys"),
}

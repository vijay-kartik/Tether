package space.pitchstone.tether.binary

/**
 * What kind of conversation a message belongs to.
 *
 * On the wire this is carried only by the server half of the chat's JID, so a caller would
 * otherwise have to string-match WhatsApp's conventions to tell a group message from a direct one.
 *
 * Deliberately an enum rather than an `isGroup` flag: a status update or a Channel post is not a
 * direct message, and a boolean would report it as one.
 */
enum class ChatType {
    /** One-to-one. [Jid.user] of the chat is the other person. */
    DIRECT,

    /** A group. The chat JID identifies the group; the *sender* is the participant who wrote. */
    GROUP,

    /** Status updates (`status@broadcast`) and broadcast lists — one-to-many, not a conversation. */
    BROADCAST,

    /** A Channel (newsletter): follow-only, nobody replies into it. */
    NEWSLETTER,

    /** Anything else — Messenger interop and whatever WhatsApp adds next. */
    OTHER;

    companion object {
        internal fun of(chat: Jid): ChatType = when (chat.server) {
            Jid.DEFAULT_USER_SERVER, Jid.HIDDEN_USER_SERVER, Jid.HOSTED_SERVER -> DIRECT
            Jid.GROUP_SERVER -> GROUP
            Jid.BROADCAST_SERVER -> BROADCAST
            Jid.NEWSLETTER_SERVER -> NEWSLETTER
            else -> OTHER
        }
    }
}

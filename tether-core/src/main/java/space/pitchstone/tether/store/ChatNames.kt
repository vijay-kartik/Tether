package space.pitchstone.tether.store

import space.pitchstone.tether.binary.Jid

/**
 * The human-readable names learned from the stream: a person's WhatsApp push name, and a group's
 * subject.
 *
 * Both are remembered rather than read per message, because neither is reliably present when you
 * need it. `notify` rides on most messages from other people but not all of them (and never on our
 * own), and a group subject only ever arrives in answer to a metadata query. Backed by
 * [KeyValueStore], so both are wiped on logout with the rest of the account state.
 */
internal class ChatNames(private val kv: KeyValueStore) {

    /** Remember [pushName] as the display name for [jid]; ignored when the server sent nothing. */
    fun recordPushName(jid: Jid, pushName: String?) {
        val name = pushName?.trim().orEmpty()
        if (name.isEmpty()) return
        put(KeyValueStore.NS_PUSH_NAME, jid, name)
    }

    /** The display name this person last announced, or null if we have never been told one. */
    fun pushName(jid: Jid): String? = get(KeyValueStore.NS_PUSH_NAME, jid)

    fun recordGroupSubject(jid: Jid, subject: String?) {
        val name = subject?.trim().orEmpty()
        if (name.isEmpty()) return
        put(KeyValueStore.NS_GROUP_SUBJECT, jid, name)
    }

    fun groupSubject(jid: Jid): String? = get(KeyValueStore.NS_GROUP_SUBJECT, jid)

    /**
     * Key on user+server and drop the device: the same person messaging from their phone and their
     * laptop is one name, and keying on the full JID would store it once per device.
     */
    private fun keyFor(jid: Jid) = "${jid.user}@${jid.server}"

    private fun put(namespace: String, jid: Jid, value: String) {
        if (jid.user.isEmpty()) return
        if (get(namespace, jid) == value) return
        kv.put(namespace, keyFor(jid), value.toByteArray(Charsets.UTF_8))
    }

    private fun get(namespace: String, jid: Jid): String? =
        kv.get(namespace, keyFor(jid))?.toString(Charsets.UTF_8)?.takeIf { it.isNotEmpty() }
}

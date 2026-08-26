package space.pitchstone.tether.session

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Upsert
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import space.pitchstone.tether.auth.CredentialStore
import space.pitchstone.tether.auth.DeviceCredentials
import space.pitchstone.tether.auth.SignedPreKey
import space.pitchstone.tether.binary.ChatType
import space.pitchstone.tether.crypto.KeyPair25519
import space.pitchstone.tether.media.MediaInfo
import space.pitchstone.tether.media.MediaKind
import space.pitchstone.tether.store.KeyValueStore

// --- Entities ---

@Entity(tableName = "wa_kv", primaryKeys = ["namespace", "name"])
data class WaKvEntity(val namespace: String, val name: String, val value: ByteArray)

@Entity(tableName = "wa_credentials")
data class WaCredentialsEntity(
    @PrimaryKey val id: Int = 0,
    val noisePriv: ByteArray, val noisePub: ByteArray,
    val identityPriv: ByteArray, val identityPub: ByteArray,
    val spkKeyId: Int, val spkPriv: ByteArray, val spkPub: ByteArray, val spkSig: ByteArray,
    val registrationId: Int, val advSecret: ByteArray,
    val deviceJid: String?, val accountSignedDeviceIdentity: ByteArray?, val pushName: String?,
)

@Entity(tableName = "wa_messages", primaryKeys = ["id"])
data class WaMessageEntity(
    val id: String,
    /** The [WaConversationEntity] this message was assigned to — see [WhatsAppManager.Received.conversationId]. */
    val conversationId: String,
    val phone: String?,
    val text: String?,
    val kind: String,
    val timestampMillis: Long,
    val fromMe: Boolean,
    val senderJid: String,
    val chatJid: String,
    val chatType: String, // Serialized ChatType
    val chatName: String?,
    val senderName: String?,
    val mediaJson: String?, // JSON-serialized MediaInfo
    val annotationLabel: String?,
    val annotationIsError: Boolean = false,
    val receivedAt: Long = System.currentTimeMillis(), // When we saved it
)

/**
 * A run of consecutive messages in one chat with less than 10 minutes between any two of them.
 * Mirrors [WhatsAppManager.Conversation]; see there for what a conversation means.
 */
@Entity(tableName = "wa_conversations")
data class WaConversationEntity(
    @PrimaryKey val id: String,
    /** The chatroom this happened in: a DM peer's JID or a group's JID. */
    val chatJid: String,
    val chatType: String, // Serialized ChatType
    val chatName: String?,
    val startTime: Long,
    val endTime: Long,
    /** Sender JIDs who took part, joined with [PARTICIPANT_DELIMITER] — Room has no list column. */
    val participantsCsv: String,
)

internal const val PARTICIPANT_DELIMITER = "|"

// --- DAOs ---

@Dao
interface WaKvDao {
    @Query("SELECT value FROM wa_kv WHERE namespace = :ns AND name = :name")
    fun get(ns: String, name: String): ByteArray?

    @Upsert
    fun put(entity: WaKvEntity)

    @Query("DELETE FROM wa_kv WHERE namespace = :ns AND name = :name")
    fun delete(ns: String, name: String)

    @Query("SELECT name FROM wa_kv WHERE namespace = :ns")
    fun keys(ns: String): List<String>

    @Query("SELECT value FROM wa_kv WHERE namespace = :ns")
    fun values(ns: String): List<ByteArray>

    @Query("DELETE FROM wa_kv")
    fun clearAll()
}

@Dao
interface WaCredentialsDao {
    @Query("SELECT * FROM wa_credentials WHERE id = 0")
    suspend fun get(): WaCredentialsEntity?

    @Upsert
    suspend fun upsert(entity: WaCredentialsEntity)

    @Query("DELETE FROM wa_credentials")
    suspend fun clear()
}

@Dao
interface WaMessageDao {
    @Upsert
    suspend fun upsert(entity: WaMessageEntity)

    @Query("SELECT * FROM wa_messages ORDER BY timestampMillis DESC")
    suspend fun getAllMessages(): List<WaMessageEntity>

    @Query("SELECT * FROM wa_messages WHERE chatJid = :chatJid ORDER BY timestampMillis DESC")
    suspend fun getMessagesByChat(chatJid: String): List<WaMessageEntity>

    @Query("SELECT * FROM wa_messages WHERE conversationId = :conversationId ORDER BY timestampMillis ASC")
    suspend fun getMessagesByConversation(conversationId: String): List<WaMessageEntity>

    @Query("DELETE FROM wa_messages")
    suspend fun clearAll()

    @Query("DELETE FROM wa_messages WHERE chatJid = :chatJid")
    suspend fun clearChat(chatJid: String)

    @Query("SELECT COUNT(*) FROM wa_messages")
    suspend fun getMessageCount(): Int
}

@Dao
interface WaConversationDao {
    @Upsert
    suspend fun upsert(entity: WaConversationEntity)

    /** The chat's still-open conversation, if it has one — what [ConversationTracker] extends or replaces. */
    @Query("SELECT * FROM wa_conversations WHERE chatJid = :chatJid ORDER BY endTime DESC LIMIT 1")
    suspend fun latestForChat(chatJid: String): WaConversationEntity?

    @Query("SELECT * FROM wa_conversations ORDER BY startTime ASC")
    suspend fun getAll(): List<WaConversationEntity>

    // A group's subject can arrive after messages from it are already stored, and can change
    // later — every conversation on the chat is corrected, not just the newest.
    @Query("UPDATE wa_conversations SET chatName = :chatName WHERE chatJid = :chatJid")
    suspend fun updateChatName(chatJid: String, chatName: String)

    @Query("DELETE FROM wa_conversations")
    suspend fun clearAll()

    @Query("DELETE FROM wa_conversations WHERE chatJid = :chatJid")
    suspend fun clearChat(chatJid: String)
}

@Database(
    entities = [WaKvEntity::class, WaCredentialsEntity::class, WaMessageEntity::class, WaConversationEntity::class],
    version = 2,
    exportSchema = false,
)
abstract class WaDatabase : RoomDatabase() {
    abstract fun kvDao(): WaKvDao
    abstract fun credentialsDao(): WaCredentialsDao
    abstract fun messageDao(): WaMessageDao
    abstract fun conversationDao(): WaConversationDao
}

/**
 * Messages and conversations are a local cache of what the connection has observed, not the
 * source of truth (WhatsApp's servers are), so upgrading to conversation-aware storage simply
 * drops and rebuilds the message table rather than backfilling a `conversationId` for old rows —
 * losing cached history costs nothing a reconnect won't eventually refill. Credentials and the
 * Signal key store live in separate tables and are untouched: this is not a fresh pairing.
 */
val WA_MIGRATION_1_2: Migration = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("DROP TABLE IF EXISTS wa_messages")
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS wa_messages (
                id TEXT NOT NULL PRIMARY KEY,
                conversationId TEXT NOT NULL,
                phone TEXT,
                text TEXT,
                kind TEXT NOT NULL,
                timestampMillis INTEGER NOT NULL,
                fromMe INTEGER NOT NULL,
                senderJid TEXT NOT NULL,
                chatJid TEXT NOT NULL,
                chatType TEXT NOT NULL,
                chatName TEXT,
                senderName TEXT,
                mediaJson TEXT,
                annotationLabel TEXT,
                annotationIsError INTEGER NOT NULL DEFAULT 0,
                receivedAt INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL("CREATE TABLE IF NOT EXISTS wa_conversations (id TEXT NOT NULL PRIMARY KEY, chatJid TEXT NOT NULL, chatType TEXT NOT NULL, chatName TEXT, startTime INTEGER NOT NULL, endTime INTEGER NOT NULL, participantsCsv TEXT NOT NULL)")
    }
}

// --- Store implementations (the SPIs that WAClient depends on) ---

class RoomKeyValueStore(private val dao: WaKvDao) : KeyValueStore {
    override fun get(namespace: String, key: String): ByteArray? = dao.get(namespace, key)
    override fun put(namespace: String, key: String, value: ByteArray) = dao.put(WaKvEntity(namespace, key, value))
    override fun delete(namespace: String, key: String) = dao.delete(namespace, key)
    override fun keys(namespace: String): List<String> = dao.keys(namespace)
    override fun values(namespace: String): List<ByteArray> = dao.values(namespace)
    override fun clearAll() = dao.clearAll()
}

class RoomCredentialStore(private val dao: WaCredentialsDao) : CredentialStore {
    override suspend fun load(): DeviceCredentials? = dao.get()?.toDomain()
    override suspend fun save(credentials: DeviceCredentials) = dao.upsert(credentials.toEntity())
    override suspend fun clear() = dao.clear()
}

private fun DeviceCredentials.toEntity() = WaCredentialsEntity(
    noisePriv = noiseKey.privateKey, noisePub = noiseKey.publicKey,
    identityPriv = identityKey.privateKey, identityPub = identityKey.publicKey,
    spkKeyId = signedPreKey.keyId, spkPriv = signedPreKey.keyPair.privateKey,
    spkPub = signedPreKey.keyPair.publicKey, spkSig = signedPreKey.signature,
    registrationId = registrationId, advSecret = advSecretKey,
    deviceJid = deviceJid, accountSignedDeviceIdentity = accountSignedDeviceIdentity, pushName = pushName,
)

private fun WaCredentialsEntity.toDomain() = DeviceCredentials(
    noiseKey = KeyPair25519(noisePriv, noisePub),
    identityKey = KeyPair25519(identityPriv, identityPub),
    signedPreKey = SignedPreKey(spkKeyId, KeyPair25519(spkPriv, spkPub), spkSig),
    registrationId = registrationId,
    advSecretKey = advSecret,
    deviceJid = deviceJid,
    accountSignedDeviceIdentity = accountSignedDeviceIdentity,
    pushName = pushName,
)

// --- Message serialization (simple JSON) ---

private fun WhatsAppManager.Received.toEntity() = WaMessageEntity(
    id = id,
    conversationId = conversationId,
    phone = phone,
    text = text,
    kind = kind,
    timestampMillis = timestampMillis,
    fromMe = fromMe,
    senderJid = senderJid,
    chatJid = chatJid,
    chatType = chatType.name,
    chatName = chatName,
    senderName = senderName,
    mediaJson = media?.let(::mediaToJson),
    annotationLabel = annotation?.label,
    annotationIsError = annotation?.isError ?: false,
)

private fun WaMessageEntity.toDomain() = WhatsAppManager.Received(
    id = id,
    conversationId = conversationId,
    phone = phone,
    text = text,
    kind = kind,
    timestampMillis = timestampMillis,
    fromMe = fromMe,
    senderJid = senderJid,
    chatJid = chatJid,
    chatType = ChatType.valueOf(chatType),
    chatName = chatName,
    senderName = senderName,
    media = mediaJson?.let(::mediaFromJson),
    annotation = if (annotationLabel != null) WhatsAppManager.Annotation(annotationLabel, annotationIsError) else null,
)

private fun mediaToJson(media: MediaInfo): String = buildString {
    append("{")
    append("\"kind\":\"${media.kind.name}\",")
    append("\"fileName\":\"${media.fileName?.replace("\"", "\\\"")}\",")
    append("\"fileLength\":${media.fileLength},")
    append("\"width\":${media.width},")
    append("\"height\":${media.height},")
    append("\"mimetype\":\"${media.mimetype?.replace("\"", "\\\"")}\",")
    append("\"downloadable\":${media.downloadable},")
    append("\"thumbnail\":${if (media.thumbnail != null) "\"base64\"" else "null"}")
    append("}")
}

private fun mediaFromJson(json: String): MediaInfo? = runCatching {
    val fields = mutableMapOf<String, String>()
    val current = json.substringAfter("{").substringBefore("}")
    current.split(",").forEach { pair ->
        val parts = pair.split(":")
        if (parts.size >= 2) {
            val key = parts[0].trim().trim('"')
            val value = parts.drop(1).joinToString(":").trim()
            fields[key] = value.trim('"')
        }
    }

    val kind = MediaKind.valueOf(fields["kind"] ?: return@runCatching null)
    val fileName = if (fields["fileName"] != "null") fields["fileName"] else null
    val fileLength = fields["fileLength"]?.toLongOrNull() ?: 0L
    val width = fields["width"]?.toIntOrNull() ?: 0
    val height = fields["height"]?.toIntOrNull() ?: 0
    val mimetype = if (fields["mimetype"] != "null") fields["mimetype"] else null
    val downloadable = fields["downloadable"]?.toBoolean() ?: false

    MediaInfo(
        kind = kind,
        fileName = fileName,
        fileLength = fileLength,
        width = width,
        height = height,
        mimetype = mimetype,
        downloadable = downloadable,
        thumbnail = if (fields["thumbnail"] == "\"base64\"") byteArrayOf() else null,
    )
}.getOrNull()

class RoomMessageStore(private val dao: WaMessageDao) {
    suspend fun saveBatch(messages: List<WhatsAppManager.Received>) {
        messages.forEach { dao.upsert(it.toEntity()) }
    }

    suspend fun loadAll(): List<WhatsAppManager.Received> =
        dao.getAllMessages().map { it.toDomain() }

    suspend fun loadByChat(chatJid: String): List<WhatsAppManager.Received> =
        dao.getMessagesByChat(chatJid).map { it.toDomain() }

    suspend fun loadByConversation(conversationId: String): List<WhatsAppManager.Received> =
        dao.getMessagesByConversation(conversationId).map { it.toDomain() }

    suspend fun clearAll() = dao.clearAll()

    suspend fun clearChat(chatJid: String) = dao.clearChat(chatJid)

    suspend fun getMessageCount(): Int = dao.getMessageCount()
}

/**
 * A chat's still-open conversation as loaded from storage — just enough for [ConversationTracker]
 * to decide whether the next message extends it or starts a new one.
 */
data class OpenConversation(val id: String, val startTime: Long, val endTime: Long, val participants: Set<String>)

class RoomConversationStore(private val dao: WaConversationDao) {
    suspend fun upsert(
        id: String,
        chatJid: String,
        chatType: String,
        chatName: String?,
        startTime: Long,
        endTime: Long,
        participants: Set<String>,
    ) = dao.upsert(
        WaConversationEntity(
            id = id,
            chatJid = chatJid,
            chatType = chatType,
            chatName = chatName,
            startTime = startTime,
            endTime = endTime,
            participantsCsv = participants.joinToString(PARTICIPANT_DELIMITER),
        )
    )

    suspend fun latestForChat(chatJid: String): OpenConversation? =
        dao.latestForChat(chatJid)?.let {
            OpenConversation(
                id = it.id,
                startTime = it.startTime,
                endTime = it.endTime,
                participants = it.participantsCsv.split(PARTICIPANT_DELIMITER).filterTo(mutableSetOf()) { p -> p.isNotBlank() },
            )
        }

    suspend fun updateChatName(chatJid: String, chatName: String) = dao.updateChatName(chatJid, chatName)

    suspend fun clearAll() = dao.clearAll()

    suspend fun clearChat(chatJid: String) = dao.clearChat(chatJid)
}

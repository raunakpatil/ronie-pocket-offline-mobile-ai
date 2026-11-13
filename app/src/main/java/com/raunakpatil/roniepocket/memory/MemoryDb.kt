package com.raunakpatil.roniepocket.memory

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import net.zetetic.database.sqlcipher.SQLiteDatabase
import org.json.JSONArray
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Ronie's memory database: SQLCipher (encrypted SQLite). The 256-bit database key is random, and it is only
 * ever stored encrypted by a key that never leaves the Android Keystore. Phase 6 grows this (episodes,
 * chats, tasks); it starts with the facts Ronie remembers about the user.
 */
object MemoryDb {
    private const val ALIAS = "ronie_memory_db"
    @Volatile private var db: SQLiteDatabase? = null

    @Synchronized
    fun open(ctx: Context): SQLiteDatabase {
        db?.let { return it }
        System.loadLibrary("sqlcipher")
        val file = File(ctx.filesDir, "memory/ronie.db").apply { parentFile?.mkdirs() }
        val d = SQLiteDatabase.openOrCreateDatabase(file, key(ctx), null, null, null)
        d.execSQL("""CREATE TABLE IF NOT EXISTS fact(
            id INTEGER PRIMARY KEY, text TEXT NOT NULL, source TEXT, confidence REAL NOT NULL DEFAULT 1,
            pinned INTEGER NOT NULL DEFAULT 0, created INTEGER NOT NULL, updated INTEGER NOT NULL, last_used INTEGER,
            deleted INTEGER NOT NULL DEFAULT 0, tomb TEXT, vec BLOB, model TEXT)""")
        db = d
        return d
    }

    /** The database key as hex: random once, kept encrypted by the Keystore. */
    private fun key(ctx: Context): String {
        val f = File(ctx.filesDir, "memory/db.key")
        if (f.exists()) runCatching {
            val (iv, enc) = f.readText().split(":").map { Base64.decode(it, Base64.NO_WRAP) }
            val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, keystoreKey(), GCMParameterSpec(128, iv)) }
            return c.doFinal(enc).joinToString("") { "%02x".format(it) }
        }.onFailure { Log.e("RonieMemory", "couldn't read the database key", it) }
        val raw = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, keystoreKey()) }
        f.parentFile?.mkdirs()
        f.writeText(Base64.encodeToString(c.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(c.doFinal(raw), Base64.NO_WRAP))
        return raw.joinToString("") { "%02x".format(it) }
    }

    private fun keystoreKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        return gen.generateKey()
    }

    fun toBlob(v: FloatArray): ByteArray = ByteBuffer.allocate(v.size * 4).order(ByteOrder.LITTLE_ENDIAN).apply { asFloatBuffer().put(v) }.array()
    fun fromBlob(b: ByteArray): FloatArray = FloatArray(b.size / 4).also { ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(it) }
}

/**
 * The facts Ronie remembers about the user, in the encrypted database, each with its bge-small vector.
 * For each message the most relevant ones (plus pinned ones) go into Ronie's prompt, instead of the newest
 * 30. Forgetting a fact wipes its text and keeps only a fingerprint, so it never comes back.
 */
class MemoryStore(private val ctx: Context) {
    data class Fact(val id: Long, val text: String, val pinned: Boolean = false)

    /** Set once the embedding model has loaded (a few hundred ms after Ronie wakes up). */
    @Volatile var embedder: Embedder? = null

    private val db get() = MemoryDb.open(ctx)

    init { migrateJson() }

    /** Facts saved by the first version (tools/memory.json) move into the database once. */
    private fun migrateJson() {
        val old = File(ctx.filesDir, "tools/memory.json")
        if (!old.exists()) return
        runCatching {
            val a = JSONArray(old.readText())
            for (i in 0 until a.length()) add(a.getJSONObject(i).getString("text"), source = "remember (v1)")
            old.delete()
            Log.i("RonieMemory", "moved ${a.length()} facts into the encrypted database")
        }.onFailure { Log.e("RonieMemory", "fact migration failed", it) }
    }

    private fun fingerprint(text: String) =
        MessageDigest.getInstance("SHA-256").digest(text.lowercase().replace(Regex("[^a-z0-9]+"), " ").trim().toByteArray())
            .joinToString("") { "%02x".format(it) }.take(32)

    @Synchronized fun all(): List<Fact> = db.rawQuery("SELECT id, text, pinned FROM fact WHERE deleted = 0 ORDER BY created", arrayOf()).use { c ->
        buildList { while (c.moveToNext()) add(Fact(c.getLong(0), c.getString(1), c.getInt(2) == 1)) }
    }

    /** Saves a fact (skips exact repeats and anything the user told Ronie to forget). Returns the fact. */
    @Synchronized fun add(text: String, source: String = "remember", confidence: Double = 1.0): Fact {
        val t = text.trim().removeSuffix(".")
        val fp = fingerprint(t)
        db.rawQuery("SELECT id FROM fact WHERE tomb = ? AND deleted = 1", arrayOf(fp)).use { c ->
            if (c.moveToFirst()) return Fact(-1, t)              // forgotten on purpose: stays forgotten
        }
        all().firstOrNull { fingerprint(it.text) == fp }?.let { return it }
        val now = System.currentTimeMillis()
        val vec = runCatching { embedder?.embedDoc(t) }.getOrNull()
        db.execSQL("INSERT INTO fact(text, source, confidence, created, updated, vec, model) VALUES(?,?,?,?,?,?,?)",
            arrayOf<Any?>(t, source, confidence, now, now, vec?.let { MemoryDb.toBlob(it) }, vec?.let { Embedder.MODEL_ID }))
        val id = db.rawQuery("SELECT last_insert_rowid()", arrayOf()).use { c -> c.moveToFirst(); c.getLong(0) }
        return Fact(id, t)
    }

    /** Forget: the text is wiped; only a fingerprint stays so the same fact is never saved again. */
    @Synchronized fun remove(id: Long) {
        val text = db.rawQuery("SELECT text FROM fact WHERE id = ?", arrayOf(id.toString())).use { c -> if (c.moveToFirst()) c.getString(0) else null } ?: return
        db.execSQL("UPDATE fact SET deleted = 1, text = '', vec = NULL, tomb = ?, updated = ? WHERE id = ?",
            arrayOf<Any?>(fingerprint(text), System.currentTimeMillis(), id))
    }

    /** Undo of a just-saved fact: removes it without a tombstone (it was a mistake, not a "forget"). */
    @Synchronized fun undoAdd(id: Long) { db.execSQL("DELETE FROM fact WHERE id = ?", arrayOf<Any?>(id)) }

    @Synchronized fun setPinned(id: Long, pinned: Boolean) =
        db.execSQL("UPDATE fact SET pinned = ? WHERE id = ?", arrayOf<Any?>(if (pinned) 1 else 0, id))

    /** Gives facts saved before the embedder was ready (or with an older model) their vectors. */
    fun backfill() {
        val e = embedder ?: return
        val todo = synchronized(this) {
            db.rawQuery("SELECT id, text FROM fact WHERE deleted = 0 AND (vec IS NULL OR model IS NOT ?)", arrayOf(Embedder.MODEL_ID)).use { c ->
                buildList { while (c.moveToNext()) add(c.getLong(0) to c.getString(1)) }
            }
        }
        for ((id, text) in todo) {
            val v = e.embedDoc(text)
            synchronized(this) { db.execSQL("UPDATE fact SET vec = ?, model = ? WHERE id = ?", arrayOf<Any?>(MemoryDb.toBlob(v), Embedder.MODEL_ID, id)) }
        }
        if (todo.isNotEmpty()) Log.i("RonieMemory", "embedded ${todo.size} facts")
    }

    /**
     * The facts for this message: pinned facts plus the [k] most relevant (cosine ≥ [minScore]), even when
     * only a few facts are saved, so nothing unrelated rides along with the message.
     */
    @Synchronized fun relevant(query: String?, k: Int = 6, minScore: Float = 0.55f): List<Fact> {
        data class Row(val f: Fact, val vec: FloatArray?)
        val rows = db.rawQuery("SELECT id, text, pinned, vec FROM fact WHERE deleted = 0 ORDER BY created", arrayOf()).use { c ->
            buildList { while (c.moveToNext()) add(Row(Fact(c.getLong(0), c.getString(1), c.getInt(2) == 1), c.getBlob(3)?.let { MemoryDb.fromBlob(it) })) }
        }
        val e = embedder
        // No query: everything (for the settings list). No embedder yet: only pinned facts, so nothing
        // irrelevant gets attached to the message.
        if (query.isNullOrBlank()) return rows.takeLast(30).map { it.f }
        if (e == null) return rows.filter { it.f.pinned }.map { it.f }
        val q = e.embedQuery(query)
        val scored = rows.filter { it.vec != null && !it.f.pinned }.map { it to dot(q, it.vec!!) }
            .filter { it.second >= minScore }.sortedByDescending { it.second }.take(k)
        val picked = rows.filter { it.f.pinned }.map { it.f } + scored.map { it.first.f }
        if (picked.isNotEmpty()) {
            val now = System.currentTimeMillis()
            picked.forEach { db.execSQL("UPDATE fact SET last_used = ? WHERE id = ?", arrayOf<Any?>(now, it.id)) }
        }
        Log.i("RonieMemory", "recall for \"${query.take(40)}\": " + scored.joinToString { "%.2f %s".format(it.second, it.first.f.text.take(30)) })
        return picked
    }

    /** The prompt lines for this message ("" when there's nothing relevant). */
    fun promptBlock(query: String? = null): String {
        val facts = runCatching { relevant(query) }.getOrElse { Log.e("RonieMemory", "recall failed", it); emptyList() }
        return if (facts.isEmpty()) "" else "What you know about the user:\n" + facts.joinToString("\n") { "- ${it.text}" }
    }
}

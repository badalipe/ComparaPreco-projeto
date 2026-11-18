package com.quickprice.app

import android.content.Context
import android.graphics.BitmapFactory
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.text.Normalizer
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

@Entity(tableName = "product_image_cache")
internal data class ProductImageCacheEntry(
    @PrimaryKey val cacheKey: String,
    val fileName: String,
    val sourceUrl: String,
    val cachedAt: Long,
    val selectionOrder: Long
)

@Dao
internal interface ProductImageCacheDao {
    @Query("SELECT * FROM product_image_cache WHERE cacheKey = :key LIMIT 1")
    fun find(key: String): ProductImageCacheEntry?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsert(entry: ProductImageCacheEntry)

    @Query("DELETE FROM product_image_cache WHERE cacheKey = :key")
    fun delete(key: String)

    @Query("SELECT fileName FROM product_image_cache GROUP BY fileName ORDER BY MAX(cachedAt) DESC")
    fun fileNamesByRecency(): List<String>

    @Query("DELETE FROM product_image_cache WHERE fileName = :fileName")
    fun deleteFile(fileName: String)
}

@Database(entities = [ProductImageCacheEntry::class], version = 1, exportSchema = false)
internal abstract class ProductImageCacheDatabase : RoomDatabase() {
    abstract fun imageCacheDao(): ProductImageCacheDao

    companion object {
        @Volatile private var instance: ProductImageCacheDatabase? = null

        fun get(context: Context): ProductImageCacheDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                ProductImageCacheDatabase::class.java,
                "product_image_cache.db"
            ).build().also { instance = it }
        }
    }
}

/**
 * Room guarda o índice (código/nome -> ficheiro); os bytes ficam em filesDir para não
 * transformar a base de dados num contentor de imagens grandes. Todas as chamadas que
 * acedem ao Room ou à rede devem ser feitas fora da thread principal.
 */
internal class ProductImageCache(context: Context, private val userAgent: String) {
    private val appContext = context.applicationContext
    private val dao by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        ProductImageCacheDatabase.get(appContext).imageCacheDao()
    }
    private val imageDir = File(appContext.filesDir, "product_images")

    fun findLocal(code: String, description: String): File? = synchronized(CACHE_METADATA_LOCK) {
        findLocalLocked(code, description)
    }

    private fun findLocalLocked(code: String, description: String): File? {
        if (!imageDir.isDirectory) return null
        limparTemporariosAntigos()
        val codeCacheKey = codeKey(code)
        // EAN é identidade exata; sem EAN, o nome completo é mais seguro que SKU interno.
        val primaryKey = if (isEan(code)) codeCacheKey else nameKey(description) ?: codeCacheKey
        val keys = if (isEan(code)) listOfNotNull(codeCacheKey)
        else listOfNotNull(nameKey(description), codeCacheKey).distinct()

        for (key in keys) {
            val entry = dao.find(key) ?: continue
            val image = File(imageDir, entry.fileName)
            if (!image.isFile || image.length() == 0L) {
                dao.delete(key)
                continue
            }
            val now = System.currentTimeMillis()
            dao.upsert(entry.copy(cachedAt = now))
            if (primaryKey != null && primaryKey != key) {
                dao.upsert(entry.copy(cacheKey = primaryKey, cachedAt = now))
            }
            return image
        }
        return null
    }

    fun nextSelectionOrder(): Long = selectionOrder.incrementAndGet()

    /** Baixa a foto em segundo plano e só publica o registo Room após validar o ficheiro. */
    fun cacheRemote(code: String, description: String, imageUrl: String, order: Long): File? {
        if (!imageUrl.startsWith("https://", ignoreCase = true) &&
            !imageUrl.startsWith("http://", ignoreCase = true)
        ) return null

        val codeCacheKey = codeKey(code)
        val descriptionCacheKey = nameKey(description)
        val primaryKey = if (isEan(code)) {
            codeCacheKey ?: return null
        } else {
            descriptionCacheKey ?: codeCacheKey ?: return null
        }
        val aliases = listOfNotNull(codeCacheKey, descriptionCacheKey).distinct()
        val fileName = "${sha256("$primaryKey|$imageUrl")}.img"
        val destination = File(imageDir, fileName)
        if (!imageDir.isDirectory && !imageDir.mkdirs()) return null

        val cachedFile = synchronized(CACHE_METADATA_LOCK) {
            val existingEntry = dao.find(primaryKey)
            if (destination.isFile && destination.length() > 0L && existingEntry?.sourceUrl == imageUrl) {
                if (existingEntry.selectionOrder > order) return@synchronized destination
                aliases.forEach {
                    dao.upsert(ProductImageCacheEntry(it, fileName, imageUrl, System.currentTimeMillis(), order))
                }
                prune()
                destination
            } else null
        }
        if (cachedFile != null) return cachedFile

        val temporary = File(imageDir, "$fileName.${Thread.currentThread().id}.${System.nanoTime()}.part")
        var connection: HttpURLConnection? = null
        try {
            connection = URL(imageUrl).openConnection() as? HttpURLConnection ?: return null
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("User-Agent", userAgent)
            connection.connect()
            if (connection.responseCode !in 200..299) return null
            val contentType = connection.contentType?.lowercase(Locale.ROOT)
            if (contentType != null && !contentType.startsWith("image/") &&
                !contentType.contains("octet-stream")
            ) return null
            if (connection.contentLength > MAX_IMAGE_BYTES) return null

            var total = 0
            connection.inputStream.use { input ->
                FileOutputStream(temporary).use { output ->
                    val buffer = ByteArray(16 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        if (total > MAX_IMAGE_BYTES) throw IllegalStateException("Image exceeds cache limit")
                        output.write(buffer, 0, count)
                    }
                }
            }
            if (total == 0) return null

            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(temporary.absolutePath, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

            return synchronized(CACHE_METADATA_LOCK) {
                val currentEntry = dao.find(primaryKey)
                if (currentEntry != null && currentEntry.selectionOrder > order) {
                    temporary.delete()
                    return@synchronized File(imageDir, currentEntry.fileName).takeIf { it.isFile }
                }
                if (destination.exists() && !destination.delete()) return@synchronized null
                if (!temporary.renameTo(destination)) {
                    temporary.copyTo(destination, overwrite = true)
                    temporary.delete()
                }
                aliases.forEach {
                    dao.upsert(ProductImageCacheEntry(it, fileName, imageUrl, System.currentTimeMillis(), order))
                }
                prune()
                destination.takeIf { it.isFile }
            }
        } catch (_: Exception) {
            temporary.delete()
            return null
        } finally {
            connection?.disconnect()
            temporary.delete()
        }
    }

    private fun prune() {
        var filesKept = 0
        var bytesKept = 0L
        for (name in dao.fileNamesByRecency()) {
            val file = File(imageDir, name)
            if (!file.isFile) {
                dao.deleteFile(name)
                continue
            }
            val size = file.length()
            if (filesKept >= MAX_FILES || bytesKept + size > MAX_TOTAL_BYTES) {
                file.delete()
                dao.deleteFile(name)
            } else {
                filesKept++
                bytesKept += size
            }
        }
        val referenced = dao.fileNamesByRecency().toSet()
        imageDir.listFiles()?.filter { it.isFile && !it.name.endsWith(".part") && it.name !in referenced }
            ?.forEach { it.delete() }
        limparTemporariosAntigos()
    }

    private fun limparTemporariosAntigos() {
        val limite = System.currentTimeMillis() - TEMP_FILE_MAX_AGE_MS
        imageDir.listFiles()?.filter { it.isFile && it.name.endsWith(".part") && it.lastModified() < limite }
            ?.forEach { it.delete() }
    }

    private fun codeKey(code: String): String? = code.trim().takeIf { it.isNotEmpty() }?.let {
        if (isEan(it)) "EAN:$it" else "CODE:$it"
    }

    private fun nameKey(description: String): String? = normalize(description)
        .takeIf { it.length >= 3 }
        ?.let { "NAME:$it" }

    private fun normalize(text: String): String = Normalizer.normalize(text.lowercase(Locale.ROOT), Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "")
        .split(Regex("[^a-z0-9]+"))
        .filter { it.isNotBlank() && it !in NAME_STOP_WORDS }
        .joinToString(" ")

    private fun isEan(code: String): Boolean = code.trim().let { it.length in 8..14 && it.all(Char::isDigit) }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

    private companion object {
        val CACHE_METADATA_LOCK = Any()
        val selectionOrder = AtomicLong(System.currentTimeMillis() * 1_000L)
        const val CONNECT_TIMEOUT_MS = 5_000
        const val READ_TIMEOUT_MS = 8_000
        const val MAX_IMAGE_BYTES = 8 * 1024 * 1024
        const val MAX_TOTAL_BYTES = 100L * 1024 * 1024
        const val MAX_FILES = 250
        const val TEMP_FILE_MAX_AGE_MS = 60 * 60 * 1_000L
        val NAME_STOP_WORDS = setOf("de", "do", "da", "dos", "das", "un", "unid", "familia")
    }
}

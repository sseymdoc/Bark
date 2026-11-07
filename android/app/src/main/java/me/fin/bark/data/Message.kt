package me.fin.bark.data

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Upsert
import coil3.SingletonImageLoader
import kotlinx.coroutines.flow.Flow
import me.fin.bark.widget.RecentWidget
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Mirrors the iOS Realm `Message`. Dates are epoch milliseconds. */
@Entity(
    tableName = "message",
    indices = [Index("group_name"), Index("createDate"), Index("expireDate"), Index("image")],
)
data class Message(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val title: String? = null,
    val subtitle: String? = null,
    val body: String? = null,
    /** "markdown" or null (plain text) */
    val bodyType: String? = null,
    val url: String? = null,
    val image: String? = null,
    @ColumnInfo(name = "group_name") val group: String? = null,
    val createDate: Long = System.currentTimeMillis(),
    val expireDate: Long? = null,
) {
    val isMarkdown get() = bodyType == "markdown"

    /** Text used by "copy": non-empty title, subtitle, body and url joined by newlines (iOS behavior). */
    fun copyText() = listOfNotNull(title, subtitle, body, url).filter { it.isNotBlank() }.joinToString("\n").trim()
}

data class GroupInfo(val name: String, val count: Int, val latest: Long)

@Dao
interface MessageDao {
    @Upsert
    suspend fun upsert(messages: List<Message>)

    /** [group] null = all groups; "" = messages without a group. */
    @Query(
        """SELECT * FROM message
        WHERE (:group IS NULL OR IFNULL(group_name, '') = :group)
        AND (:q = '' OR title LIKE '%' || :q || '%' OR subtitle LIKE '%' || :q || '%' OR body LIKE '%' || :q || '%')
        ORDER BY createDate DESC LIMIT :limit""",
    )
    fun list(group: String?, q: String, limit: Int): Flow<List<Message>>

    @Query(
        """SELECT IFNULL(group_name, '') AS name, COUNT(*) AS count, MAX(createDate) AS latest
        FROM message GROUP BY name ORDER BY latest DESC LIMIT :limit""",
    )
    fun groups(limit: Int): Flow<List<GroupInfo>>

    @Query("SELECT COUNT(*) FROM message")
    fun count(): Flow<Int>

    @Query("SELECT * FROM message ORDER BY createDate DESC")
    suspend fun all(): List<Message>

    @Query("SELECT * FROM message ORDER BY createDate DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<Message>

    @Query("SELECT DISTINCT image FROM message WHERE image IS NOT NULL")
    suspend fun images(): List<String>

    @Query("DELETE FROM message WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM message WHERE IFNULL(group_name, '') = :group")
    suspend fun deleteGroup(group: String)

    @Query(
        """DELETE FROM message WHERE createDate >= :start AND createDate <= :end
        AND (:group IS NULL OR IFNULL(group_name, '') = :group)""",
    )
    suspend fun deleteRange(start: Long, end: Long, group: String?)

    @Query("DELETE FROM message WHERE expireDate IS NOT NULL AND expireDate <= :now")
    suspend fun deleteExpired(now: Long)
}

@Database(entities = [Message::class], version = 1, exportSchema = false)
abstract class BarkDb : RoomDatabase() {
    abstract fun messages(): MessageDao
}

/** Single entry point for writes, so the widget and the image cache always follow the database. */
object Messages {
    @Volatile
    private var db: BarkDb? = null

    fun dao(context: Context): MessageDao = (db ?: synchronized(this) {
        db ?: Room.databaseBuilder(context.applicationContext, BarkDb::class.java, "bark.db").build().also { db = it }
    }).messages()

    suspend fun save(context: Context, messages: List<Message>) {
        dao(context).upsert(messages)
        RecentWidget.refresh(context)
    }

    /** Runs a delete, then drops cached images no message references any more (iOS MessageImageCleaner). */
    suspend fun delete(context: Context, block: suspend MessageDao.() -> Unit) {
        val dao = dao(context)
        val before = dao.images()
        dao.block()
        val gone = before - dao.images().toSet()
        val cache = SingletonImageLoader.get(context).diskCache
        gone.forEach { cache?.remove(it) }
        RecentWidget.refresh(context)
    }

    suspend fun deleteExpired(context: Context) = delete(context) { deleteExpired(System.currentTimeMillis()) }
}

/** Same JSON as the iOS export: an array of message objects, dates in Unix seconds. */
object MessageJson {
    fun encode(messages: List<Message>): String = JSONArray(messages.map { m ->
        JSONObject()
            .put("id", m.id)
            .put("title", m.title ?: JSONObject.NULL)
            .put("subtitle", m.subtitle ?: JSONObject.NULL)
            .put("body", m.body ?: JSONObject.NULL)
            .put("bodyType", m.bodyType ?: JSONObject.NULL)
            .put("url", m.url ?: JSONObject.NULL)
            .put("image", m.image ?: JSONObject.NULL)
            .put("group", m.group ?: JSONObject.NULL)
            .put("createDate", m.createDate / 1000)
            .put("expireDate", m.expireDate?.div(1000) ?: JSONObject.NULL)
    }).toString(2)

    /** Entries without `id` or `createDate` are skipped, as on iOS. */
    fun decode(text: String): List<Message> {
        val array = JSONArray(text)
        return (0 until array.length()).mapNotNull { i ->
            val o = array.optJSONObject(i) ?: return@mapNotNull null
            val id = o.str("id") ?: return@mapNotNull null
            val createDate = (o.opt("createDate") as? Number)?.toLong() ?: return@mapNotNull null
            Message(
                id = id,
                title = o.str("title"),
                subtitle = o.str("subtitle"),
                body = o.str("body"),
                bodyType = o.str("bodyType"),
                url = o.str("url"),
                image = o.str("image"),
                group = o.str("group"),
                createDate = createDate * 1000,
                expireDate = (o.opt("expireDate") as? Number)?.toLong()?.times(1000),
            )
        }
    }

    private fun JSONObject.str(key: String): String? = if (isNull(key)) null else optString(key)
}

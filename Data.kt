package com.roohux.app

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "favorites") data class Fav(@PrimaryKey val videoId: String, val title: String, val channel: String, val thumb: String, val ts: Long)
@Entity(tableName = "history") data class Hist(@PrimaryKey val videoId: String, val title: String, val channel: String, val thumb: String, val ts: Long)
@Entity(tableName = "searches") data class SearchQ(@PrimaryKey val q: String, val ts: Long)
@Entity(tableName = "playlists") data class Pl(@PrimaryKey(autoGenerate = true) val id: Long = 0, val name: String)
@Entity(tableName = "pl_items", primaryKeys = ["plId", "videoId"])
data class PlItem(val plId: Long, val videoId: String, val title: String, val channel: String, val thumb: String)
@Entity(tableName = "local") data class LocalTrack(@PrimaryKey val uri: String, val title: String)

fun Fav.toItem() = Item("video", videoId, title, channel, thumb)
fun Hist.toItem() = Item("video", videoId, title, channel, thumb)
fun PlItem.toItem() = Item("video", videoId, title, channel, thumb)

@Dao
interface AppDao {
    @Query("SELECT * FROM favorites ORDER BY ts DESC") fun favs(): Flow<List<Fav>>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun addFav(f: Fav)
    @Query("DELETE FROM favorites WHERE videoId = :id") suspend fun delFav(id: String)

    @Query("SELECT * FROM history ORDER BY ts DESC LIMIT 50") fun hist(): Flow<List<Hist>>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun addHist(h: Hist)

    @Query("SELECT * FROM searches ORDER BY ts DESC LIMIT 10") fun searches(): Flow<List<SearchQ>>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun addSearch(s: SearchQ)
    @Query("DELETE FROM searches") suspend fun clearSearches()

    @Query("SELECT * FROM playlists ORDER BY id DESC") fun playlists(): Flow<List<Pl>>
    @Insert suspend fun addPl(p: Pl): Long
    @Query("DELETE FROM playlists WHERE id = :id") suspend fun delPl(id: Long)
    @Query("DELETE FROM pl_items WHERE plId = :id") suspend fun delPlItems(id: Long)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun addPlItem(i: PlItem)
    @Query("DELETE FROM pl_items WHERE plId = :p AND videoId = :v") suspend fun delPlItem(p: Long, v: String)
    @Query("SELECT * FROM pl_items WHERE plId = :id") fun plItems(id: Long): Flow<List<PlItem>>

    @Query("SELECT * FROM local ORDER BY title") fun locals(): Flow<List<LocalTrack>>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun addLocal(t: LocalTrack)
    @Query("DELETE FROM local WHERE uri = :uri") suspend fun delLocal(uri: String)
}

@Database(entities = [Fav::class, Hist::class, SearchQ::class, Pl::class, PlItem::class, LocalTrack::class], version = 1, exportSchema = false)
abstract class Db : RoomDatabase() {
    abstract fun dao(): AppDao
    companion object {
        @Volatile private var inst: Db? = null
        fun get(c: Context): Db = inst ?: synchronized(this) {
            inst ?: Room.databaseBuilder(c.applicationContext, Db::class.java, "roohux.db").build().also { inst = it }
        }
    }
}

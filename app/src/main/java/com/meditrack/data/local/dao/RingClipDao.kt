package com.meditrack.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.meditrack.data.local.entity.RingClip

/**
 * Storage for the user's trimmed reminder sounds.
 *
 * Reads are one-shot: the set of clips changes only when the user edits it, and the picker is the
 * only screen that shows the list, so a stream would buy nothing and cost a re-composition of the
 * settings screen on every write.
 */
@Dao
interface RingClipDao {

    /** Every clip, newest first - the order the picker shows them in. */
    @Query("SELECT * FROM ring_clips ORDER BY createdAt DESC")
    suspend fun all(): List<RingClip>

    @Query("SELECT * FROM ring_clips WHERE id = :id")
    suspend fun byId(id: Long): RingClip?

    /** Clips that nothing references any more; their files are cache-clear candidates. */
    @Query("SELECT * FROM ring_clips WHERE inUse = 0 ORDER BY createdAt ASC")
    suspend fun unused(): List<RingClip>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(clip: RingClip): Long

    @Query("UPDATE ring_clips SET name = :name WHERE id = :id")
    suspend fun rename(id: Long, name: String)

    @Query("UPDATE ring_clips SET inUse = :inUse WHERE id = :id")
    suspend fun setInUse(id: Long, inUse: Boolean)

    @Query("DELETE FROM ring_clips WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM ring_clips")
    suspend fun deleteAll()

    @Query("SELECT COUNT(*) FROM ring_clips")
    suspend fun count(): Int
}

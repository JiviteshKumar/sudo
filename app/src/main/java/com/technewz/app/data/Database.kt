package com.technewz.app.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

object Section {
    const val TECH = "tech"
    const val AI = "ai"
}

object SummaryKind {
    const val PENDING = 0
    const val AI = 1
    const val EXCERPT = 2
    /** Key sentences chosen by the on-device model (verbatim publisher text). */
    const val ON_DEVICE = 3
}

@Entity(tableName = "articles", indices = [Index("section"), Index("clusterId"), Index("publishedAt")])
data class ArticleEntity(
    @PrimaryKey val id: String,
    val url: String,
    val title: String,
    val source: String,
    val sourceDomain: String,
    val section: String,
    val topic: String,
    val imageUrl: String?,
    val publishedAt: Long,
    val fetchedAt: Long,
    val excerpt: String,
    val summary: String?,
    val summaryKind: Int,
    val whyItMatters: String?,
    val clusterId: String,
    val bookmarked: Boolean = false,
    val read: Boolean = false,
    val alerted: Boolean = false,
)

@Entity(tableName = "jobs", indices = [Index("postedAt")])
data class JobEntity(
    @PrimaryKey val id: String,
    val title: String,
    val company: String,
    val location: String,
    val isRemote: Boolean,
    val employmentType: String,
    val isInternship: Boolean,
    val url: String,
    val source: String,
    val directFromEmployer: Boolean,
    val description: String,
    val postedAt: Long,
    val fetchedAt: Long,
    val salary: String?,
    val matchScore: Int?,
    val matchedSkills: String,
    val missingSkills: String,
    val scamFlags: String,
    val aiAnalysis: String? = null,
    val alerted: Boolean = false,
)

object AppStatus {
    const val SAVED = "Saved"
    const val APPLIED = "Applied"
    const val INTERVIEW = "Interview"
    const val OFFER = "Offer"
    const val REJECTED = "Rejected"
    val all = listOf(SAVED, APPLIED, INTERVIEW, OFFER, REJECTED)
}

@Entity(tableName = "applications")
data class ApplicationEntity(
    @PrimaryKey val jobId: String,
    val title: String,
    val company: String,
    val location: String,
    val url: String,
    val source: String,
    val status: String,
    val createdAt: Long,
    val updatedAt: Long,
    val appliedAt: Long? = null,
    val followUpAt: Long? = null,
    val notes: String = "",
    val coverLetter: String? = null,
)

@Entity(tableName = "trending", primaryKeys = ["kind", "id"])
data class TrendingEntity(
    val kind: String,
    val id: String,
    val rank: Int,
    val title: String,
    val subtitle: String,
    val url: String,
    val metric: String,
    val fetchedAt: Long,
)

object TermStatus {
    const val NEW = "New"
    const val RISING = "Rising"
    const val REJECTED = "Rejected"
}

/** A term on the Skills Radar. Every text field is either verbatim from a cited source or a computed count. */
@Entity(tableName = "terms")
data class TermEntity(
    @PrimaryKey val key: String,
    val term: String,
    val shortForm: String?,
    val kind: String,              // Concept (from research/news) | Tool (from job postings)
    val status: String,            // New | Rising | Rejected (hidden; kept so it isn't re-checked daily)
    val reason: String,            // why it was accepted/rejected, in numbers
    val what: String?,             // verbatim definition
    val whatSource: String?,
    val whatUrl: String?,
    val usage: String?,            // verbatim usage sentence or computed market fact
    val usageSource: String?,
    val usageUrl: String?,
    val simpleWhat: String?,       // optional AI simplification that passed the grounding check
    val simpleUsage: String?,
    val papers30: Int,
    val papersPrior: Int,          // papers in the 150 days before that
    val papersTotal: Int,
    val firstSeen: Long?,
    val newsMentions: Int,
    val jobMentions: Int,
    val jobCompanies: String,
    val repo: String?,
    val repoStars: Int?,
    val evidence: String,          // JSON list of sources
    val score: Double,
    val checkedAt: Long,
    val discoveredAt: Long,
)

@Dao
interface TermDao {
    /** Everything ever verified, newest first — the radar keeps its full record. */
    @Query("SELECT * FROM terms WHERE status != 'Rejected' ORDER BY discoveredAt DESC, score DESC")
    fun observeVisible(): Flow<List<TermEntity>>

    @Query("SELECT * FROM terms")
    suspend fun all(): List<TermEntity>

    @Upsert
    suspend fun upsert(t: TermEntity)

    @Query("DELETE FROM terms")
    suspend fun deleteAll()

    /** Rules changed: keep everything visible but queue verified terms for re-verification (checkedAt = 0). */
    @Query("UPDATE terms SET checkedAt = 0 WHERE status != 'Rejected'")
    suspend fun markAllForRecheck()

    @Query("DELETE FROM terms WHERE status = 'Rejected' AND checkedAt < :before")
    suspend fun pruneRejected(before: Long)

    @Query("DELETE FROM terms WHERE checkedAt < :before")
    suspend fun prune(before: Long)

    /** Verified terms are kept for 3 months from when they were first spotted; rejections for a month. */
    @Query("DELETE FROM terms WHERE (status != 'Rejected' AND discoveredAt < :keepVerifiedAfter) OR (status = 'Rejected' AND checkedAt < :keepRejectedAfter)")
    suspend fun pruneHistory(keepVerifiedAfter: Long, keepRejectedAfter: Long)
}

@Dao
interface ArticleDao {
    @Query("SELECT * FROM articles WHERE section = :section ORDER BY publishedAt DESC LIMIT 400")
    fun observeSection(section: String): Flow<List<ArticleEntity>>

    @Query("SELECT * FROM articles WHERE bookmarked = 1 ORDER BY publishedAt DESC")
    fun observeBookmarked(): Flow<List<ArticleEntity>>

    @Query("SELECT * FROM articles WHERE section = :section ORDER BY publishedAt DESC LIMIT :limit")
    suspend fun latest(section: String, limit: Int): List<ArticleEntity>

    @Query("SELECT * FROM articles WHERE publishedAt > :since")
    suspend fun since(since: Long): List<ArticleEntity>

    @Query("SELECT * FROM articles")
    suspend fun all(): List<ArticleEntity>

    @Query("SELECT id FROM articles")
    suspend fun allIds(): List<String>

    @Query("SELECT * FROM articles WHERE summaryKind != 1 AND publishedAt > :since ORDER BY publishedAt DESC LIMIT :limit")
    suspend fun needingAiSummary(since: Long, limit: Int): List<ArticleEntity>

    @Query("SELECT * FROM articles WHERE id IN (:ids)")
    suspend fun byIds(ids: List<String>): List<ArticleEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(items: List<ArticleEntity>)

    @Upsert
    suspend fun upsertAll(items: List<ArticleEntity>)

    @Query("UPDATE articles SET bookmarked = :value WHERE id = :id")
    suspend fun setBookmarked(id: String, value: Boolean)

    @Query("UPDATE articles SET read = 1 WHERE id = :id")
    suspend fun markRead(id: String)

    @Query("UPDATE articles SET alerted = 1 WHERE id IN (:ids)")
    suspend fun markAlerted(ids: List<String>)

    @Query("DELETE FROM articles WHERE bookmarked = 0 AND publishedAt < :before")
    suspend fun prune(before: Long)
}

/** What a job card needs — everything except the (large) description, so the list stays light. */
data class JobListItem(
    val id: String, val title: String, val company: String, val location: String, val isRemote: Boolean,
    val employmentType: String, val isInternship: Boolean, val source: String, val directFromEmployer: Boolean,
    val postedAt: Long, val salary: String?, val matchScore: Int?, val matchedSkills: String,
    val missingSkills: String, val scamFlags: String,
)

fun JobEntity.toListItem() = JobListItem(
    id, title, company, location, isRemote, employmentType, isInternship, source, directFromEmployer,
    postedAt, salary, matchScore, matchedSkills, missingSkills, scamFlags,
)

/** Job text for the Skills Radar (description trimmed in SQL, so full records never load into memory). */
data class JobText(
    val id: String, val title: String, val company: String, val url: String, val source: String,
    val postedAt: Long, val description: String,
)

@Dao
interface JobDao {
    @Query("SELECT id, title, company, url, source, postedAt, substr(description, 1, 3000) AS description FROM jobs")
    suspend fun texts(): List<JobText>

    @Query(
        "SELECT id, title, company, location, isRemote, employmentType, isInternship, source, directFromEmployer, " +
            "postedAt, salary, matchScore, matchedSkills, missingSkills, scamFlags FROM jobs ORDER BY postedAt DESC"
    )
    fun observeList(): Flow<List<JobListItem>>

    @Query("SELECT * FROM jobs WHERE id = :id")
    fun observe(id: String): Flow<JobEntity?>

    @Query("SELECT * FROM jobs WHERE id = :id")
    suspend fun get(id: String): JobEntity?

    @Query("SELECT * FROM jobs")
    suspend fun all(): List<JobEntity>

    @Upsert
    suspend fun upsertAll(items: List<JobEntity>)

    @Query("UPDATE jobs SET aiAnalysis = :json WHERE id = :id")
    suspend fun setAnalysis(id: String, json: String)

    @Query("UPDATE jobs SET alerted = 1 WHERE id IN (:ids)")
    suspend fun markAlerted(ids: List<String>)

    @Query("DELETE FROM jobs WHERE (postedAt < :before AND directFromEmployer = 0) OR fetchedAt < :staleBefore")
    suspend fun prune(before: Long, staleBefore: Long)
}

@Dao
interface ApplicationDao {
    @Query("SELECT * FROM applications ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<ApplicationEntity>>

    @Query("SELECT * FROM applications WHERE jobId = :jobId")
    fun observe(jobId: String): Flow<ApplicationEntity?>

    @Query("SELECT * FROM applications WHERE jobId = :jobId")
    suspend fun get(jobId: String): ApplicationEntity?

    @Upsert
    suspend fun upsert(item: ApplicationEntity)

    @Query("DELETE FROM applications WHERE jobId = :jobId")
    suspend fun delete(jobId: String)
}

@Dao
interface TrendingDao {
    @Query("SELECT * FROM trending ORDER BY kind, rank")
    fun observeAll(): Flow<List<TrendingEntity>>

    @Query("SELECT * FROM trending")
    suspend fun allOnce(): List<TrendingEntity>

    @Query("SELECT MAX(fetchedAt) FROM trending")
    suspend fun lastFetched(): Long?

    @Query("DELETE FROM trending WHERE kind = :kind")
    suspend fun clear(kind: String)

    @Upsert
    suspend fun upsertAll(items: List<TrendingEntity>)
}

@Database(
    entities = [ArticleEntity::class, JobEntity::class, ApplicationEntity::class, TrendingEntity::class, TermEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun articles(): ArticleDao
    abstract fun jobs(): JobDao
    abstract fun applications(): ApplicationDao
    abstract fun trending(): TrendingDao
    abstract fun terms(): TermDao

    companion object {
        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "technewz.db")
                .fallbackToDestructiveMigration()
                .build()
    }
}

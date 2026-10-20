package com.technewz.app

import android.app.Application
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import com.technewz.app.data.AppDatabase
import com.technewz.app.data.AppJson
import com.technewz.app.data.MIGRATION_1_2
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Updating the app must never wipe what people saved. Builds a database exactly as v1.0–1.1 created it (from
 * the exported schema `schemas/.../1.json`), then opens it with the current app, which runs the migration and
 * makes Room verify every table against the v2 schema, and checks bookmarks and tracker entries survived.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class MigrationTest {
    @Test fun v1ToV2KeepsEverythingSaved() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val name = "migration-test.db"
        app.deleteDatabase(name)
        val schema = AppJson.parseToJsonElement(File("schemas/com.technewz.app.data.AppDatabase/1.json").readText()).jsonObject["database"]!!.jsonObject
        val create = schema["entities"]!!.jsonArray.map { e ->
            val o = e.jsonObject
            o["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}", o["tableName"]!!.jsonPrimitive.content)
        } + schema["setupQueries"]!!.jsonArray.map { it.jsonPrimitive.content.replace("\${TABLE_NAME}", "room_master_table") } +
            "INSERT OR REPLACE INTO room_master_table (id,identity_hash) VALUES(42, '${schema["identityHash"]!!.jsonPrimitive.content}')"

        val v1 = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(app).name(name).callback(object : SupportSQLiteOpenHelper.Callback(1) {
                override fun onCreate(db: SupportSQLiteDatabase) = create.forEach { db.execSQL(it) }
                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
            }).build()
        )
        v1.writableDatabase.apply {
            execSQL(
                "INSERT INTO articles (id, url, title, source, sourceDomain, section, topic, imageUrl, publishedAt, fetchedAt, excerpt, summary, summaryKind, whyItMatters, clusterId, bookmarked, read, alerted) " +
                    "VALUES ('a1', 'https://example.com/a1', 'Saved story', 'Ars Technica', 'arstechnica.com', 'tech', 'Tech', NULL, 1, 1, 'x', 's', 3, NULL, 'a1', 1, 0, 0)"
            )
            execSQL(
                "INSERT INTO applications (jobId, title, company, location, url, source, status, createdAt, updatedAt, appliedAt, followUpAt, notes, coverLetter) " +
                    "VALUES ('j1', 'ML Intern', 'Databricks', 'Remote', 'https://x', 'Greenhouse', 'Applied', 1, 1, 1, 2, 'notes', NULL)"
            )
        }
        v1.close()

        // The app's real builder: without the migration, Room would refuse (or wipe) instead of upgrading.
        val db = Room.databaseBuilder(app, AppDatabase::class.java, name).addMigrations(MIGRATION_1_2).allowMainThreadQueries().build()
        runBlocking {
            assertEquals(2, db.openHelper.readableDatabase.version)
            assertTrue("bookmark survived", db.articles().all().single { it.id == "a1" }.bookmarked)
            assertEquals("tracker entry survived", "Applied", db.applications().get("j1")?.status)
            assertTrue(db.rising().all().isEmpty())
        }
        db.close()
    }
}

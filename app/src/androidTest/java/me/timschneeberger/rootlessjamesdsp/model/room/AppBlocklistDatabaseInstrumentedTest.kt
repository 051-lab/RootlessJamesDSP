package me.timschneeberger.rootlessjamesdsp.model.room

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppBlocklistDatabaseInstrumentedTest {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppBlocklistDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun versionOneSchemaMatchesCurrentDatabase() {
        helper.createDatabase(TEST_DATABASE, 1).close()

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = Room.databaseBuilder(
            context,
            AppBlocklistDatabase::class.java,
            TEST_DATABASE,
        ).build()
        try {
            database.openHelper.writableDatabase
        } finally {
            database.close()
            context.deleteDatabase(TEST_DATABASE)
        }
    }

    companion object {
        private const val TEST_DATABASE = "migration-test.db"
    }
}

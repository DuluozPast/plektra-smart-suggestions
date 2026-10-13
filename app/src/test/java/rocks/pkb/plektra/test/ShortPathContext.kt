package rocks.pkb.plektra.test

import android.content.Context
import android.content.ContextWrapper
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import java.io.File

/** sqlite4java on Windows cannot open deep Robolectric cache paths. Product code is unchanged. */
class ShortPathContext private constructor(base: Context) : ContextWrapper(base) {
    private val root = File(System.getProperty("java.io.tmpdir"), "fusion-${System.nanoTime()}").apply { mkdirs() }
    override fun getApplicationContext(): Context = this
    override fun getCacheDir(): File = File(root, "cache").apply { mkdirs() }
    override fun getDatabasePath(name: String): File = File(File(root, "db").apply { mkdirs() }, name)
    override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?): SQLiteDatabase =
        SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name), factory)
    override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?, errorHandler: DatabaseErrorHandler?): SQLiteDatabase =
        SQLiteDatabase.openDatabase(getDatabasePath(name).absolutePath, factory, SQLiteDatabase.CREATE_IF_NECESSARY, errorHandler)
    override fun deleteDatabase(name: String): Boolean = SQLiteDatabase.deleteDatabase(getDatabasePath(name))
    companion object { fun wrap(context: Context): Context = ShortPathContext(context) }
}

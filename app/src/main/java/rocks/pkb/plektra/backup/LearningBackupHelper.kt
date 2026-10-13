package rocks.pkb.plektra.backup

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import android.content.ContentValues
import android.util.AtomicFile

/** Read-only snapshot of the learning stores. Does not replay learn() or alter counts. */
internal object LearningBackupHelper {
    private data class Table(val database: String, val table: String, val file: String)

    private val tables = listOf(
        Table("phrase_usage.db", "phrase_usage", "phrase_usage.json"),
        Table("user_ngrams.db", "bigrams", "next_word_usage.json"),
        Table("word_usage.db", "word_usage", "word_usage.json"),
        Table("word_usage.db", "exact_word_usage", "exact_word_usage.json")
    )

    private val allowedColumnsByTable = mapOf(
        "phrase_usage" to setOf(
            "locale",
            "normalized_sequence",
            "sequence",
            "count",
            "last_used"
        ),
        "bigrams" to setOf(
            "locale",
            "prefix",
            "next_word",
            "count",
            "last_used"
        ),
        "word_usage" to setOf(
            "locale",
            "normalized_word",
            "word",
            "count",
            "last_used"
        ),
        "exact_word_usage" to setOf(
            "locale",
            "exact_word",
            "word",
            "count",
            "last_used"
        )
    )

    fun export(context: Context, destination: File): List<String> {
        destination.mkdirs()
        android.util.Log.i(
            "PastieraSQLite",
            "SQLite version: ${getSqliteVersion(context)}"
        )
        val components = mutableListOf<String>()
        val openDbs = mutableMapOf<String, SQLiteDatabase>()
        val snapshotDir = File(destination, "_sqlite_snapshots").apply {
            mkdirs()
        }
        try {
            for (databaseName in tables.map { it.database }.distinct()) {
                val dbFile = context.getDatabasePath(databaseName)

                if (dbFile.exists()) {
                    val snapshotFile = File(snapshotDir, databaseName)

                    val sourceDb = SQLiteDatabase.openDatabase(
                        dbFile.absolutePath,
                        null,
                        SQLiteDatabase.OPEN_READONLY
                    )

                    try {
                        createSqliteSnapshot(sourceDb, snapshotFile)
                    } finally {
                        sourceDb.close()
                    }
                }
            }
            for (spec in tables) {
                val dbFile = context.getDatabasePath(spec.database)
                val rows = JSONArray()
                if (dbFile.exists()) {
                    val db = openDbs.getOrPut(spec.database) {
                        SQLiteDatabase.openDatabase(
                            File(snapshotDir, spec.database).absolutePath,
                            null,
                            SQLiteDatabase.OPEN_READONLY
                        )
                    }
                    db.query(spec.table, null, null, null, null, null, null).use { cursor ->
                        while (cursor.moveToNext()) {
                            val row = JSONObject()
                            for (index in 0 until cursor.columnCount) {
                                val name = cursor.getColumnName(index)
                                when (cursor.getType(index)) {
                                    android.database.Cursor.FIELD_TYPE_NULL -> row.put(name, JSONObject.NULL)
                                    android.database.Cursor.FIELD_TYPE_INTEGER -> row.put(name, cursor.getLong(index))
                                    android.database.Cursor.FIELD_TYPE_FLOAT -> row.put(name, cursor.getDouble(index))
                                    android.database.Cursor.FIELD_TYPE_STRING -> row.put(name, cursor.getString(index))
                                    else -> error("Unsupported SQLite column type in ${spec.table}.$name")
                                }
                            }
                            rows.put(row)
                        }
                    }
                }
                val output = File(destination, spec.file)
                output.writeText(JSONObject().put("schemaVersion", 1).put("rows", rows).toString(2))
                components += "learning/${spec.file}"
            }
        } finally {
            try {
                openDbs.values.forEach { it.close() }
            } finally {
                snapshotDir.deleteRecursively()
            }
        }

        val multiWordFile = File(context.filesDir, "multi_word_sequences.json")
        val multiWordOutput = File(destination, "multi_word_sequences.json")
        if (multiWordFile.exists()) {
            val parsed = JSONObject(multiWordFile.readText())
            require(parsed.has("version") && parsed.optJSONArray("entries") != null) {
                "Invalid multi-word learning file"
            }
            multiWordOutput.writeText(parsed.toString(2))
        } else {
            multiWordOutput.writeText(JSONObject().put("version", 1).put("entries", JSONArray()).toString(2))
        }
        components += "learning/multi_word_sequences.json"
        return components
    }

    private fun validateSqliteBackupFile(file: File): JSONArray {
        require(file.isFile) {
            "Missing learning backup file: ${file.name}"
        }

        val root = JSONObject(file.readText(Charsets.UTF_8))

        require(root.getInt("schemaVersion") == 1) {
            "Unsupported learning backup version: ${file.name}"
        }
        val rows = root.getJSONArray("rows")
        val primaryKeyColumns = when (file.name) {
            "phrase_usage.json" -> listOf("locale", "normalized_sequence")
            "next_word_usage.json" -> listOf("locale", "prefix", "next_word")
            "word_usage.json" -> listOf("locale", "normalized_word")
            "exact_word_usage.json" -> listOf("locale", "exact_word")
            else -> error("Unknown learning backup file: ${file.name}")
        }

        val seenKeys = mutableSetOf<List<String>>()

        for (index in 0 until rows.length()) {
            val row = rows.getJSONObject(index)
            val requiredColumns = when (file.name) {
                "phrase_usage.json" -> listOf(
                    "normalized_sequence", "sequence"
                )
                "next_word_usage.json" -> listOf(
                    "prefix", "next_word"
                )
                "word_usage.json" -> listOf(
                    "normalized_word", "word"
                )
                "exact_word_usage.json" -> listOf(
                    "exact_word", "word"
                )
                else -> error("Unknown learning backup file: ${file.name}")
            }

            for (column in requiredColumns) {
                require(
                    row.has(column) &&
                            row.get(column) is String &&
                            row.getString(column).isNotBlank()
                ) {
                    "Invalid $column in ${file.name}, row $index"
                }
            }

            require(row.has("locale")) {
                "Missing locale in ${file.name}, row $index"
            }

            require(row.has("count")) {
                "Missing count in ${file.name}, row $index"
            }

            require(row.has("last_used")) {
                "Missing last_used in ${file.name}, row $index"
            }

            require(row.get("locale") is String &&
                    row.getString("locale").isNotBlank()) {
                "Invalid locale in ${file.name}, row $index"
            }

            val count = row.get("count")
            require(
                (count is Int || count is Long) &&
                        count.toLong() in 1L..Int.MAX_VALUE.toLong()
            ) {
                "Invalid count in ${file.name}, row $index"
            }

            val lastUsed = row.get("last_used")
            require(
                (lastUsed is Int || lastUsed is Long) &&
                        lastUsed.toLong() >= 0L
            ) {
                "Invalid last_used in ${file.name}, row $index"
            }
            val primaryKey = primaryKeyColumns.map { column ->
                row.getString(column)
            }

            require(seenKeys.add(primaryKey)) {
                "Duplicate learning entry in ${file.name}, row $index"
            }
        }


        return rows
    }

    private fun validateMultiWordBackupFile(file: File): JSONArray {
        require(file.isFile) {
            "Missing multi-word backup file: ${file.name}"
        }

        val root = JSONObject(file.readText(Charsets.UTF_8))

        require(root.getInt("version") == 1) {
            "Unsupported multi-word backup version"
        }

        val entries = root.getJSONArray("entries")

        val requiredFields = listOf(
            "locale",
            "normalizedWords",
            "words",
            "promotedAt",
            "updatedAt",
            "phraseCount"
        )

        val seenSequences = mutableSetOf<Pair<String, List<String>>>()

        for (index in 0 until entries.length()) {
            val entry = entries.getJSONObject(index)
            if (!entry.has("lastUsedAt")) {
                entry.put("lastUsedAt", 0L)
            }

            for (field in requiredFields) {
                require(entry.has(field) && !entry.isNull(field)) {
                    "Missing $field in multi-word backup, entry $index"
                }
            }

            require(
                entry.get("locale") is String &&
                        entry.getString("locale").isNotBlank()
            ) {
                "Invalid locale in multi-word backup, entry $index"
            }

            val words = entry.getJSONArray("words")
            val normalizedWords = entry.getJSONArray("normalizedWords")

            require(
                words.length() in 2..5 &&
                        normalizedWords.length() == words.length()
            ) {
                "Invalid word sequence length in multi-word backup, entry $index"
            }

            for (wordIndex in 0 until words.length()) {
                require(
                    words.get(wordIndex) is String &&
                            words.getString(wordIndex).isNotBlank() &&
                            normalizedWords.get(wordIndex) is String &&
                            normalizedWords.getString(wordIndex).isNotBlank()
                ) {
                    "Invalid word in multi-word backup, entry $index"
                }
            }

            val phraseCount = entry.get("phraseCount")

            require(
                (phraseCount is Int || phraseCount is Long) &&
                        phraseCount.toLong() in 1L..Int.MAX_VALUE.toLong()
            ) {
                "Invalid phraseCount in multi-word backup, entry $index"
            }

            for (field in listOf("promotedAt", "updatedAt", "lastUsedAt")) {
                if (field == "lastUsedAt" && !entry.has(field)) {
                    continue
                }

                val value = entry.get(field)

                require(
                    (value is Int || value is Long) &&
                            value.toLong() >= 0L
                ) {
                    "Invalid $field in multi-word backup, entry $index"
                }
            }

                val sequenceKey = entry.getString("locale") to
                        (0 until normalizedWords.length()).map { wordIndex ->
                            normalizedWords.getString(wordIndex)
                        }

                require(seenSequences.add(sequenceKey)) {
                    "Duplicate multi-word sequence in backup, entry $index"
                }
            }

        return entries
    }

    fun validateBackup(source: File) {
        require(source.isDirectory) {
            "Missing learning backup directory"
        }

        for (spec in tables) {
            validateSqliteBackupFile(File(source, spec.file))
        }

        validateMultiWordBackupFile(
            File(source, "multi_word_sequences.json")
        )
    }




    private fun ensureLearningDatabasesExist(context: Context) {
        val appContext = context.applicationContext

        val stores = listOf(
            rocks.pkb.plektra.core.suggestions.UserNGramStore(appContext),
            rocks.pkb.plektra.core.suggestions.PhraseUsageStore(appContext),
            rocks.pkb.plektra.core.suggestions.WordUsageStore(appContext)
        )

        try {
            stores.forEach { store ->
                store.writableDatabase
            }
        } finally {
            stores.forEach { store ->
                store.close()
            }
        }
    }





    // Test-only failure injection. Null in normal operation.
    @Volatile
    internal var restoreFailureInjector: (() -> Unit)? = null
    fun importBackup(context: Context, source: File) {
        validateBackup(source)

        // Initialiser les schÃ©mas officiels avant les snapshots.
        // Une base auparavant absente aura ainsi un snapshot vide,
        // permettant Ã©galement son retour arriÃ¨re.
        ensureLearningDatabasesExist(context)

        val safetyDir = File(
            context.cacheDir,
            "learning_restore_safety_${System.currentTimeMillis()}"
        )


        val snapshots = try {
            backupAllLearningData(context, safetyDir)
        } catch (error: Exception) {
            android.util.Log.e(
                "PastieraRestore",
                "Unable to create complete learning safety snapshots; " +
                        "restore aborted. Partial snapshots preserved at " +
                        safetyDir.absolutePath,
                error
            )
            throw error
        }

        val databaseNames = tables.map { it.database }.distinct()

        try {
            // Restaurer chaque base dans une transaction unique.
            for (databaseName in databaseNames) {
                val databaseFile = context.getDatabasePath(databaseName)

                val db = SQLiteDatabase.openDatabase(
                    databaseFile.absolutePath,
                    null,
                    SQLiteDatabase.OPEN_READWRITE
                )

                try {
                    restoreSqliteDatabase(
                        db,
                        tables.filter { it.database == databaseName },
                        source
                    )
                } finally {
                    db.close()
                }

                // Allows instrumented tests to simulate a failure
                // after a complete database restore.
                restoreFailureInjector?.invoke()
            }

            // Reconstruire l'index dÃ©rivÃ© des sÃ©quences apprises.
            val phraseStore =
                rocks.pkb.plektra.core.suggestions.PhraseUsageStore(
                    context.applicationContext
                )

            try {
                phraseStore.rebuildPredictionIndex()
            } finally {
                phraseStore.close()
            }

            // Restaurer le fichier des sÃ©quences multi-mots.
            val destination = File(
                context.filesDir,
                "multi_word_sequences.json"
            )

            val atomicFile = AtomicFile(destination)
            val output = atomicFile.startWrite()

            try {
                File(source, "multi_word_sequences.json")
                    .inputStream()
                    .use { input ->
                        input.copyTo(output)
                    }

                atomicFile.finishWrite(output)
            } catch (error: Exception) {
                atomicFile.failWrite(output)
                throw error
            }

            android.util.Log.i(
                "PastieraRestore",
                "Learning data restored successfully"
            )

            safetyDir.deleteRecursively()

        } catch (originalError: Exception) {
            val rollbackErrors = mutableListOf<Throwable>()

            for (databaseName in databaseNames.asReversed()) {
                try {
                    restoreSqliteSafetySnapshot(
                        context,
                        databaseName,
                        snapshots[databaseName]
                    )
                } catch (rollbackError: Exception) {
                    rollbackErrors.add(rollbackError)
                }
            }

            try {
                restoreMultiWordSafetySnapshot(
                    context,
                    snapshots["multi_word_sequences.json"]
                )
            } catch (rollbackError: Exception) {
                rollbackErrors.add(rollbackError)
            }

            try {
                val phraseStore =
                    rocks.pkb.plektra.core.suggestions.PhraseUsageStore(
                        context.applicationContext
                    )

                try {
                    phraseStore.rebuildPredictionIndex()
                } finally {
                    phraseStore.close()
                }
            } catch (rollbackError: Exception) {
                rollbackErrors.add(rollbackError)
            }


            if (rollbackErrors.isEmpty()) {
                safetyDir.deleteRecursively()
            } else {
                rollbackErrors.forEach {
                    originalError.addSuppressed(it)
                }

                android.util.Log.e(
                    "PastieraRestore",
                    "Rollback incomplete; safety snapshots preserved at " +
                            safetyDir.absolutePath,
                    originalError
                )
            }

            throw originalError
        }
    }


    private fun jsonRowToContentValues(
        row: JSONObject,
        allowedColumns: Set<String>
    ): ContentValues {
        val values = ContentValues()

        val keys = row.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            require(key in allowedColumns) {
                "Unexpected SQLite column in learning backup: $key"
            }

            when (val value = row.get(key)) {
                JSONObject.NULL -> values.putNull(key)
                is String -> values.put(key, value)
                is Int -> values.put(key, value)
                is Long -> values.put(key, value)
                is Double -> values.put(key, value)
                else -> error("Unsupported JSON value for column $key")
            }
        }

        return values
    }
    private fun insertBackupRows(
        db: SQLiteDatabase,
        table: String,
        rows: JSONArray
    ) {
        val allowedColumns = allowedColumnsByTable[table]
            ?: error("Unknown learning table: $table")

        for (index in 0 until rows.length()) {
            val row = rows.getJSONObject(index)

            val values = jsonRowToContentValues(
                row,
                allowedColumns
            )

            val result = db.insertOrThrow(
                table,
                null,
                values
            )

            check(result != -1L) {
                "Failed to restore row $index in $table"
            }
        }
    }

    private fun restoreSqliteTable(
        db: SQLiteDatabase,
        table: String,
        rows: JSONArray
    ) {
        db.beginTransaction()

        try {
            db.delete(table, null, null)

            insertBackupRows(db, table, rows)

            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private fun restoreSqliteDatabase(
        db: SQLiteDatabase,
        specs: List<Table>,
        source: File
    ) {
        val validatedRows = specs.map { spec ->
            spec to validateSqliteBackupFile(
                File(source, spec.file)
            )
        }

        db.beginTransaction()

        try {
            for ((spec, rows) in validatedRows) {
                db.delete(spec.table, null, null)
                insertBackupRows(db, spec.table, rows)
            }

            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private fun createSqliteSnapshot(
        sourceDb: SQLiteDatabase,
        destination: File
    ) {

        if (!supportsVacuumInto(sourceDb)) {
            createPortableSqliteSnapshot(sourceDb, destination)
            return
        }

        destination.parentFile?.mkdirs()

        require(!destination.exists()) {
            "SQLite snapshot destination already exists: ${destination.name}"
        }

        sourceDb.execSQL(
            "VACUUM INTO ?",
            arrayOf(destination.absolutePath)
        )

        check(destination.isFile) {
            "SQLite snapshot was not created: ${destination.name}"
        }
    }

    /** LearningOperationGate has drained writers before a snapshot is taken. */
    private fun createPortableSqliteSnapshot(sourceDb: SQLiteDatabase, destination: File) {
        destination.parentFile?.mkdirs()
        require(!destination.exists()) { "SQLite snapshot destination already exists: ${destination.name}" }
        val target = SQLiteDatabase.openOrCreateDatabase(destination, null)
        try {
            target.beginTransaction()
            try {
                sourceDb.rawQuery("SELECT name, sql FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'", null).use { schemas ->
                    while (schemas.moveToNext()) {
                        val name = schemas.getString(0)
                        val quotedName = "\"" + name.replace("\"", "\"\"") + "\""
                        target.execSQL("DROP TABLE IF EXISTS $quotedName")
                        target.execSQL(schemas.getString(1))
                        sourceDb.rawQuery("SELECT * FROM $quotedName", null).use { rows ->
                            while (rows.moveToNext()) {
                                val values = ContentValues()
                                for (column in 0 until rows.columnCount) {
                                    val key = rows.getColumnName(column)
                                    when (rows.getType(column)) {
                                        android.database.Cursor.FIELD_TYPE_NULL -> values.putNull(key)
                                        android.database.Cursor.FIELD_TYPE_INTEGER -> values.put(key, rows.getLong(column))
                                        android.database.Cursor.FIELD_TYPE_FLOAT -> values.put(key, rows.getDouble(column))
                                        android.database.Cursor.FIELD_TYPE_STRING -> values.put(key, rows.getString(column))
                                        android.database.Cursor.FIELD_TYPE_BLOB -> values.put(key, rows.getBlob(column))
                                    }
                                }
                                target.insertOrThrow(name, null, values)
                            }
                        }
                    }
                }
                sourceDb.rawQuery("SELECT sql FROM sqlite_master WHERE type IN ('index','trigger','view') AND sql IS NOT NULL", null).use { schema ->
                    while (schema.moveToNext()) target.execSQL(schema.getString(0))
                }
                target.version = sourceDb.version
                target.setTransactionSuccessful()
            } finally { target.endTransaction() }
        } finally { target.close() }
        check(destination.isFile) { "SQLite snapshot was not created: ${destination.name}" }
    }

    internal fun getSqliteVersion(context: Context): String {
        val db = context.openOrCreateDatabase(
            "sqlite_version_test.db",
            Context.MODE_PRIVATE,
            null
        )
        return try {
            db.rawQuery("SELECT sqlite_version()", null).use { cursor ->
                check(cursor.moveToFirst())
                cursor.getString(0)
            }
        } finally {
            db.close()
            context.deleteDatabase("sqlite_version_test.db")
        }
    }

    private fun supportsVacuumInto(db: SQLiteDatabase): Boolean {
        db.rawQuery("SELECT sqlite_version()", null).use { cursor ->
            check(cursor.moveToFirst()) {
                "Unable to determine SQLite version"
            }

            val parts = cursor.getString(0)
                .split(".")
                .map { it.toInt() }

            val major = parts.getOrElse(0) { 0 }
            val minor = parts.getOrElse(1) { 0 }
            val patch = parts.getOrElse(2) { 0 }

            return major > 3 ||
                    (major == 3 && (
                            minor > 27 ||
                                    (minor == 27 && patch >= 0)
                            ))
        }
    }

    private fun backupExistingDatabase(
        context: Context,
        databaseName: String,
        safetyDirectory: File
    ): File? {
        val databaseFile = context.getDatabasePath(databaseName)

        if (!databaseFile.exists()) {
            return null
        }

        safetyDirectory.mkdirs()

        val safetyFile = File(safetyDirectory, databaseName)

        val db = SQLiteDatabase.openDatabase(
            databaseFile.path,
            null,
            SQLiteDatabase.OPEN_READONLY
        )

        try {
            createSqliteSnapshot(db, safetyFile)
        } finally {
            db.close()
        }
        verifySqliteSnapshot(safetyFile)

        verifySqliteSnapshotTables(
            safetyFile,
            databaseName
        )

        verifySqliteSnapshotColumns(
            safetyFile,
            databaseName
        )

        return safetyFile
    }

    private fun backupAllLearningDatabases(
        context: Context,
        safetyDirectory: File
    ): Map<String, File?> {
        val snapshots = mutableMapOf<String, File?>()

        for (databaseName in tables.map { it.database }.distinct()) {
            snapshots[databaseName] = backupExistingDatabase(
                context,
                databaseName,
                safetyDirectory
            )
        }

        return snapshots
    }
    private fun backupMultiWordLearning(
        context: Context,
        safetyDirectory: File
    ): File? {
        val sourceFile = File(
            context.filesDir,
            "multi_word_sequences.json"
        )

        if (!sourceFile.exists()) {
            return null
        }

        safetyDirectory.mkdirs()

        val safetyFile = File(
            safetyDirectory,
            "multi_word_sequences.json"
        )

        require(!safetyFile.exists()) {
            "Multi-word safety snapshot already exists"
        }

        sourceFile.copyTo(
            target = safetyFile,
            overwrite = false
        )

        return safetyFile
    }

    private fun backupAllLearningData(
        context: Context,
        safetyDirectory: File
    ) : Map<String, File?> {
        require(
            !safetyDirectory.exists() ||
                    (safetyDirectory.isDirectory &&
                            safetyDirectory.listFiles()?.isEmpty() == true)
        ) {
            "Learning safety directory must be empty"
        }

        check(safetyDirectory.isDirectory || safetyDirectory.mkdirs()) {
            "Unable to create learning safety directory"
        }

        val snapshots = backupAllLearningDatabases(
            context,
            safetyDirectory
        ).toMutableMap()

        snapshots["multi_word_sequences.json"] =
            backupMultiWordLearning(
                context,
                safetyDirectory
            )

        return snapshots
    }
    private fun restoreMultiWordSafetySnapshot(
        context: Context,
        safetyFile: File?
    ) {
        val destination = File(
            context.filesDir,
            "multi_word_sequences.json"
        )

        if (safetyFile == null) {
            val atomicFile = AtomicFile(destination)

            atomicFile.delete()

            check(!destination.exists()) {
                "Unable to remove restored multi-word learning file"
            }

            return
        }

        require(safetyFile.isFile) {
            "Missing multi-word safety snapshot"
        }

        val atomicFile = AtomicFile(destination)
        val output = atomicFile.startWrite()

        try {
            safetyFile.inputStream().use { input ->
                input.copyTo(output)
            }

            atomicFile.finishWrite(output)
        } catch (error: Exception) {
            atomicFile.failWrite(output)
            throw error
        }
    }


    private fun restoreSqliteSafetySnapshot(
        context: Context,
        databaseName: String,
        safetyFile: File?
    ) {
        val databaseFile = context.getDatabasePath(databaseName)

        // Ne jamais effacer automatiquement une base nouvellement crÃ©Ã©e.
        // Ce cas devra Ãªtre gÃ©rÃ© explicitement avant d'activer l'import.
        require(safetyFile != null) {
            "Cannot safely roll back a previously absent database: $databaseName"
        }

        require(databaseFile.isFile) {
            "Missing target database for rollback: $databaseName"
        }

        require(safetyFile.isFile && safetyFile.name == databaseName) {
            "Invalid SQLite safety snapshot: $databaseName"
        }

        verifySqliteSnapshot(safetyFile)
        verifySqliteSnapshotTables(safetyFile, databaseName)
        verifySqliteSnapshotColumns(safetyFile, databaseName)

        val specs = tables.filter { it.database == databaseName }

        val snapshotDb = SQLiteDatabase.openDatabase(
            safetyFile.absolutePath,
            null,
            SQLiteDatabase.OPEN_READONLY
        )

        try {
            val targetDb = SQLiteDatabase.openDatabase(
                databaseFile.absolutePath,
                null,
                SQLiteDatabase.OPEN_READWRITE
            )

            try {
                targetDb.beginTransaction()
                try {
                    for (spec in specs) {
                        val columns = allowedColumnsByTable[spec.table]
                            ?: error("Unknown learning table: ${spec.table}")

                        targetDb.delete(spec.table, null, null)

                        snapshotDb.query(
                            spec.table,
                            null,
                            null,
                            null,
                            null,
                            null,
                            null
                        ).use { cursor ->
                            while (cursor.moveToNext()) {
                                val values = ContentValues()

                                for (index in 0 until cursor.columnCount) {
                                    val column = cursor.getColumnName(index)

                                    require(column in columns) {
                                        "Unexpected snapshot column: $column"
                                    }

                                    when (cursor.getType(index)) {
                                        android.database.Cursor.FIELD_TYPE_NULL ->
                                            values.putNull(column)

                                        android.database.Cursor.FIELD_TYPE_INTEGER ->
                                            values.put(column, cursor.getLong(index))

                                        android.database.Cursor.FIELD_TYPE_FLOAT ->
                                            values.put(column, cursor.getDouble(index))

                                        android.database.Cursor.FIELD_TYPE_STRING ->
                                            values.put(column, cursor.getString(index))

                                        else ->
                                            error("Unsupported snapshot column: $column")
                                    }
                                }

                                targetDb.insertOrThrow(
                                    spec.table,
                                    null,
                                    values
                                )
                            }
                        }
                    }

                    targetDb.setTransactionSuccessful()
                } finally {
                    targetDb.endTransaction()
                }
            } finally {
                targetDb.close()
            }
        } finally {
            snapshotDb.close()
        }
    }


    private fun verifySqliteSnapshot(snapshot: File) {
        require(snapshot.isFile) {
            "Missing SQLite snapshot: ${snapshot.name}"
        }

        val db = SQLiteDatabase.openDatabase(
            snapshot.absolutePath,
            null,
            SQLiteDatabase.OPEN_READONLY
        )

        try {
            db.rawQuery("PRAGMA integrity_check", null).use { cursor ->
                check(cursor.moveToFirst() && cursor.getString(0) == "ok") {
                    "SQLite snapshot integrity check failed: ${snapshot.name}"
                }
            }
        } finally {
            db.close()
        }
    }

    private fun verifySqliteSnapshotTables(
        snapshot: File,
        databaseName: String
    ) {
        val expectedTables = tables
            .filter { it.database == databaseName }
            .map { it.table }
            .toSet()

        require(expectedTables.isNotEmpty()) {
            "Unknown learning database: $databaseName"
        }

        val db = SQLiteDatabase.openDatabase(
            snapshot.absolutePath,
            null,
            SQLiteDatabase.OPEN_READONLY
        )

        try {
            db.rawQuery(
                "SELECT name FROM sqlite_master WHERE type = 'table'",
                null
            ).use { cursor ->
                val actualTables = mutableSetOf<String>()

                while (cursor.moveToNext()) {
                    actualTables.add(cursor.getString(0))
                }

                require(actualTables.containsAll(expectedTables)) {
                    "Missing learning tables in SQLite snapshot: $databaseName"
                }
            }
        } finally {
            db.close()
        }
    }

    private fun verifySqliteSnapshotColumns(
        snapshot: File,
        databaseName: String
    ) {
        val specs = tables.filter {
            it.database == databaseName
        }

        require(specs.isNotEmpty()) {
            "Unknown learning database: $databaseName"
        }

        val db = SQLiteDatabase.openDatabase(
            snapshot.absolutePath,
            null,
            SQLiteDatabase.OPEN_READONLY
        )

        try {
            for (spec in specs) {
                val expectedColumns =
                    allowedColumnsByTable[spec.table]
                        ?: error("Unknown learning table: ${spec.table}")

                val actualColumns = mutableSetOf<String>()

                db.rawQuery(
                    "PRAGMA table_info(${spec.table})",
                    null
                ).use { cursor ->
                    val nameIndex = cursor.getColumnIndexOrThrow("name")

                    while (cursor.moveToNext()) {
                        actualColumns.add(
                            cursor.getString(nameIndex)
                        )
                    }
                }

                require(actualColumns.containsAll(expectedColumns)) {
                    "Missing columns in SQLite snapshot: ${spec.table}"
                }
            }
        } finally {
            db.close()
        }
    }

    private fun copySqliteSafetySnapshot(
        safetyFile: File,
        destination: File,
        databaseName: String
    ) {

        require(safetyFile.canonicalFile != destination.canonicalFile) {
            "SQLite snapshot source and destination must be different"
        }

        require(safetyFile.name == databaseName) {
            "SQLite safety snapshot does not match database: $databaseName"
        }

        require(destination.name == "$databaseName.restore.tmp") {
            "Invalid SQLite restore temporary filename: $databaseName"
        }

        require(safetyFile.isFile) {
            "Missing SQLite safety snapshot: ${safetyFile.name}"
        }

        require(!destination.exists()) {
            "SQLite restore destination already exists: ${destination.name}"
        }

        destination.parentFile?.let { parent ->
            require(parent.isDirectory || parent.mkdirs()) {
                "Cannot create SQLite restore directory"
            }
        }

        try {
            safetyFile.copyTo(
                destination,
                overwrite = false
            )
        } catch (error: Exception) {
            destination.delete()
            throw error
        }

        try {
            verifySqliteSnapshot(destination)

            verifySqliteSnapshotTables(
                destination,
                databaseName
            )

            verifySqliteSnapshotColumns(
                destination,
                databaseName
            )
        } catch (error: Exception) {
            destination.delete()
            throw error
        }
    }

    private fun prepareSqliteSafetySnapshot(
        context: Context,
        databaseName: String,
        safetyFile: File
    ): File {

        require(tables.any { it.database == databaseName }) {
            "Unknown learning database: $databaseName"
        }
        val databaseFile = context.getDatabasePath(databaseName)

        val temporaryFile = File(
            databaseFile.parentFile,
            "$databaseName.restore.tmp"
        )

        require(!temporaryFile.exists()) {
            "SQLite restore temporary file already exists: $databaseName"
        }

        copySqliteSafetySnapshot(
            safetyFile,
            temporaryFile,
            databaseName
        )

        return temporaryFile
    }

    private fun cleanupPreparedSqliteSnapshot(
        context: Context,
        databaseName: String
    ) {
        require(tables.any { it.database == databaseName }) {
            "Unknown learning database: $databaseName"
        }

        val databaseFile = context.getDatabasePath(databaseName)

        val temporaryFile = File(
            databaseFile.parentFile,
            "$databaseName.restore.tmp"
        )

        if (!temporaryFile.exists()) {
            return
        }

        require(temporaryFile.isFile) {
            "SQLite restore temporary path is not a file: $databaseName"
        }

        check(temporaryFile.delete()) {
            "Unable to remove SQLite restore temporary file: $databaseName"
        }
    }

}


package org.totschnig.myexpenses.provider

import android.content.ContentValues
import android.content.Context
import android.content.res.Resources
import android.database.Cursor
import android.database.sqlite.SQLiteConstraintException
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteStatement
import org.totschnig.myexpenses.R
import org.totschnig.myexpenses.model.PaymentMethod
import org.totschnig.myexpenses.provider.DatabaseConstants.*
import org.totschnig.myexpenses.sync.json.TransactionChange
import org.totschnig.myexpenses.util.Utils
import org.totschnig.myexpenses.util.crashreporting.CrashHandler
import timber.log.Timber

fun safeUpdateWithSealedAccounts(db: SQLiteDatabase, runnable: Runnable) {
    db.beginTransaction()
    try {
        ContentValues(1).apply {
            put(KEY_SEALED, -1)
            db.update(TABLE_ACCOUNTS, this, "$KEY_SEALED= ?", arrayOf("1"))
        }
        runnable.run()
        ContentValues(1).apply {
            put(KEY_SEALED, 1)
            db.update(TABLE_ACCOUNTS, this, "$KEY_SEALED= ?", arrayOf("-1"))
        }
        db.setTransactionSuccessful()
    } finally {
        db.endTransaction()
    }
}

fun linkTransfers(db: SQLiteDatabase, uuid1: String, uuid2: String, writeChange: Boolean): Int {
    db.beginTransaction()
    var count = 0
    try {
        //both transactions get uuid from first transaction
        val sql =
            "UPDATE $TABLE_TRANSACTIONS SET $KEY_CATID = null, $KEY_PAYEEID = null, $KEY_UUID = ?," +
                    "$KEY_TRANSFER_PEER = (SELECT $KEY_ROWID FROM $TABLE_TRANSACTIONS WHERE $KEY_UUID = ?)," +
                    "$KEY_TRANSFER_ACCOUNT = (SELECT $KEY_ACCOUNTID FROM $TABLE_TRANSACTIONS WHERE $KEY_UUID = ?) WHERE $KEY_UUID = ? AND EXISTS (SELECT 1 FROM $TABLE_TRANSACTIONS where $KEY_UUID = ?)"
        val statement: SQLiteStatement = db.compileStatement(sql)
        statement.bindAllArgsAsStrings(arrayOf(uuid1, uuid2, uuid2, uuid1, uuid2))
        count += statement.executeUpdateDelete()
        statement.bindAllArgsAsStrings(arrayOf(uuid1, uuid1, uuid1, uuid2, uuid1))
        count += statement.executeUpdateDelete()
        if (writeChange) {
            // This is a hack, we abuse the number field of the changes table for storing uuid of transfer_peer
            // We do not want to extend the table since the whole trigger based concept of recording changes
            // should be abandoned in a future new architecture of the synchronization mechanism
            val updateSql =
                "INSERT INTO $TABLE_CHANGES ($KEY_TYPE, $KEY_ACCOUNTID, $KEY_SYNC_SEQUENCE_LOCAL, $KEY_UUID, $KEY_REFERENCE_NUMBER) " +
                        "SELECT '${TransactionChange.Type.link.name}', $KEY_ROWID, $KEY_SYNC_SEQUENCE_LOCAL, ?, ? FROM " +
                        "$TABLE_ACCOUNTS WHERE $KEY_ROWID IN ((SELECT $KEY_ACCOUNTID FROM $TABLE_TRANSACTIONS WHERE $KEY_UUID = ?), (SELECT $KEY_TRANSFER_ACCOUNT FROM $TABLE_TRANSACTIONS WHERE $KEY_UUID = ?)) AND $KEY_SYNC_ACCOUNT_NAME IS NOT NULL"
            val updateStatement: SQLiteStatement = db.compileStatement(updateSql)
            //we write identical changes for the two accounts, so that on the other end of the synchronization we know which part of the transfer keeps its uuid
            updateStatement.bindAllArgsAsStrings(arrayOf(uuid1, uuid2, uuid1, uuid1))
            updateStatement.execute()
        }
        db.setTransactionSuccessful()
    } finally {
        db.endTransaction()
    }
    return count
}

fun groupByForPaymentMethodQuery(projection: Array<String>?) =
    if (projection?.contains(KEY_ACCOUNT_TPYE_LIST) == true) KEY_ROWID else null

fun havingForPaymentMethodQuery(projection: Array<String>?) =
    if (projection?.contains(KEY_ACCOUNT_TPYE_LIST) == true) "$KEY_ACCOUNT_TPYE_LIST is not null" else null

fun tableForPaymentMethodQuery(projection: Array<String>?) =
    if (projection?.contains(KEY_ACCOUNT_TPYE_LIST) == true)
        "$TABLE_METHODS left join $TABLE_ACCOUNTTYES_METHODS on $KEY_METHODID = $KEY_ROWID"
    else
        TABLE_METHODS

fun mapPaymentMethodProjection(projection: Array<String>, ctx: Context): Array<String> {
    return projection.map { column ->
        when (column) {
            KEY_LABEL -> "${PaymentMethod.localizedLabelSqlColumn(ctx, column)} AS $column"
            KEY_TYPE -> "$TABLE_METHODS.$column"
            KEY_ACCOUNT_TPYE_LIST -> "group_concat($TABLE_ACCOUNTTYES_METHODS.$KEY_TYPE) AS $column"
            else -> column
        }
    }.toTypedArray()
}

fun setupDefaultCategories(database: SQLiteDatabase, resources: Resources): Int {
    var total = 0
    var catIdMain: Long
    database.beginTransaction()
    val sql = "INSERT INTO " + TABLE_CATEGORIES + " " +
            "(" + KEY_LABEL + ", " + KEY_LABEL_NORMALIZED + ", " + KEY_PARENTID + ", " + KEY_COLOR + ", " + KEY_ICON +
            ") VALUES (?, ?, ?, ?, ?)"
    val stmt = database.compileStatement(sql)
    val categoryDefinitions = arrayOf(
        R.array.Cat_1 to R.array.Cat_1_Icons,
        R.array.Cat_2 to R.array.Cat_2_Icons,
        R.array.Cat_3 to R.array.Cat_3_Icons,
        R.array.Cat_4 to R.array.Cat_4_Icons,
        R.array.Cat_5 to R.array.Cat_5_Icons,
        R.array.Cat_6 to R.array.Cat_6_Icons,
        R.array.Cat_7 to R.array.Cat_7_Icons,
        R.array.Cat_8 to R.array.Cat_8_Icons,
        R.array.Cat_9 to R.array.Cat_9_Icons,
        R.array.Cat_10 to R.array.Cat_10_Icons,
        R.array.Cat_11 to R.array.Cat_11_Icons,
        R.array.Cat_12 to R.array.Cat_12_Icons,
        R.array.Cat_13 to R.array.Cat_13_Icons,
        R.array.Cat_14 to R.array.Cat_14_Icons,
        R.array.Cat_15 to R.array.Cat_15_Icons,
        R.array.Cat_16 to R.array.Cat_16_Icons,
        R.array.Cat_17 to R.array.Cat_17_Icons,
        R.array.Cat_18 to R.array.Cat_18_Icons,
        R.array.Cat_19 to R.array.Cat_19_Icons,
        R.array.Cat_20 to R.array.Cat_20_Icons,
        R.array.Cat_21 to R.array.Cat_21_Icons,
        R.array.Cat_22 to R.array.Cat_22_Icons
    )
    for ((categoriesResId, iconsResId) in categoryDefinitions) {
        val categories = resources.getStringArray(categoriesResId)
        val icons = resources.getStringArray(iconsResId)
        if(categories.size != icons.size) {
            CrashHandler.report("Inconsistent category definitions")
            return 0
        }
        val mainLabel = categories[0]
        val mainIcon = icons[0]
        catIdMain = findMainCategory(database, mainLabel)
        if (catIdMain != -1L) {
            Timber.i("category with label %s already defined", mainLabel)
        } else {
            stmt.bindString(1, mainLabel)
            stmt.bindString(2, Utils.normalize(mainLabel))
            stmt.bindNull(3)
            stmt.bindLong(4, DbUtils.suggestNewCategoryColor(database).toLong())
            stmt.bindString(5, mainIcon)
            catIdMain = stmt.executeInsert()
            if (catIdMain != -1L) {
                total++
            } else {
                // this should not happen
                Timber.w("could neither retrieve nor store main category %s", mainLabel)
                continue
            }
        }
        val subLabels = categories.drop(1)
        val subIconNames = icons.drop(1)
        for (i in subLabels.indices) {
            val subLabel = subLabels[i]
            val subIcon = subIconNames[i]
            stmt.bindString(1, subLabel)
            stmt.bindString(2, Utils.normalize(subLabel))
            stmt.bindLong(3, catIdMain)
            stmt.bindNull(4)
            stmt.bindString(5, subIcon)
            try {
                if (stmt.executeInsert() != -1L) {
                    total++
                } else {
                    Timber.i("could not store sub category %s", subLabel)
                }
            } catch (e: SQLiteConstraintException) {
                Timber.i("could not store sub category %s", subLabel)
            }
        }
    }
    stmt.close()
    database.setTransactionSuccessful()
    database.endTransaction()
    return total
}

private fun findMainCategory(database: SQLiteDatabase, label: String): Long {
    val selection = "$KEY_PARENTID is null and $KEY_LABEL = ?"
    val selectionArgs = arrayOf(label)
    return database.query(
        TABLE_CATEGORIES,
        arrayOf(KEY_ROWID),
        selection,
        selectionArgs,
        null,
        null,
        null
    ).use {
        if (it.moveToFirst()) {
            it.getLong(0)
        } else {
            -1
        }
    }
}

val Cursor.asSequence: Sequence<Cursor>
    get() = generateSequence { takeIf { it.moveToNext() } }
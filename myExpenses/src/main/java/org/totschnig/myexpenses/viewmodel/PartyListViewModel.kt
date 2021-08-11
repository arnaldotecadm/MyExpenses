package org.totschnig.myexpenses.viewmodel

import android.app.Application
import android.content.ContentProviderOperation
import android.content.ContentProviderOperation.newDelete
import android.content.ContentProviderOperation.newUpdate
import android.content.ContentValues
import android.database.Cursor
import android.text.TextUtils
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.liveData
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import org.jetbrains.annotations.Nullable
import org.totschnig.myexpenses.dialog.select.SelectFromMappedTableDialogFragment
import org.totschnig.myexpenses.provider.DatabaseConstants.KEY_PAYEEID
import org.totschnig.myexpenses.provider.DatabaseConstants.KEY_PAYEE_NAME
import org.totschnig.myexpenses.provider.DatabaseConstants.KEY_PAYEE_NAME_NORMALIZED
import org.totschnig.myexpenses.provider.DatabaseConstants.KEY_ROWID
import org.totschnig.myexpenses.provider.DatabaseConstants.TABLE_BUDGETS
import org.totschnig.myexpenses.provider.DatabaseConstants.TABLE_PAYEES
import org.totschnig.myexpenses.provider.DatabaseConstants.TABLE_TRANSACTIONS
import org.totschnig.myexpenses.provider.TransactionProvider
import org.totschnig.myexpenses.provider.filter.PayeeCriteria
import org.totschnig.myexpenses.util.Utils
import org.totschnig.myexpenses.util.crashreporting.CrashHandler
import org.totschnig.myexpenses.util.replace
import org.totschnig.myexpenses.viewmodel.data.Party
import timber.log.Timber
import java.util.*

class PartyListViewModel(application: Application) : ContentResolvingAndroidViewModel(application) {
    private val parties = MutableLiveData<List<Party>>()

    fun getParties(): LiveData<List<Party>> = parties

    fun loadParties(filter: @Nullable String?, accountId: Long) {
        val filterSelection = if (TextUtils.isEmpty(filter)) null else "$KEY_PAYEE_NAME_NORMALIZED LIKE ?"
        val filterSelectionArgs = if (TextUtils.isEmpty(filter)) null else
            arrayOf("%${Utils.escapeSqlLikeExpression(Utils.normalize(filter))}%")
        val accountSelection = if (accountId == 0L) null else
            StringBuilder("exists (SELECT 1 from $TABLE_TRANSACTIONS WHERE $KEY_PAYEEID = $TABLE_PAYEES.$KEY_ROWID").apply {
                SelectFromMappedTableDialogFragment.accountSelection(accountId)?.let {
                    append(" AND ")
                    append(it)
                }
                append(")")
            }
        val accountSelectionArgs = if (accountId == 0L) null else SelectFromMappedTableDialogFragment.accountSelectionArgs(accountId)
        val selection = StringBuilder().apply {
            filterSelection?.let { append(it) }
            accountSelection?.let {
                if (length > 0) append(" AND ")
                append(it)
            }
        }.takeIf { it.isNotEmpty() }?.toString()
        disposable = briteContentResolver.createQuery(TransactionProvider.PAYEES_URI, null,
                selection, Utils.joinArrays(filterSelectionArgs, accountSelectionArgs), null, true)
                .mapToList { Party.fromCursor(it) }
                .subscribe {
                    parties.postValue(it)
                }
    }

    fun deleteParties(idList: List<Long>): LiveData<Result<Int>> = liveData(context = coroutineContext()) {
        try {
            emit(Result.success(contentResolver.delete(TransactionProvider.PAYEES_URI,
                "$KEY_ROWID IN (${idList.joinToString()})",null)))
        } catch (e: Exception) {
            emit(Result.failure<Int>(e))
        }
    }

    private fun updatePartyFilters(old: Set<Long>, new: Long) {
        contentResolver.query(TransactionProvider.ACCOUNTS_MINIMAL_URI, null, null, null, null)?.use { cursor ->
            updateFilterHelper(old, new, cursor, TransactionListViewModel::prefNameForCriteria)
        }
    }


    private fun updatePartyBudgets(old: Set<Long>, new: Long) {
        contentResolver.query(TransactionProvider.BUDGETS_URI, arrayOf("$TABLE_BUDGETS.$KEY_ROWID"), null, null, null)?.use { cursor ->
            updateFilterHelper(old, new, cursor, BudgetViewModel::prefNameForCriteria)
        }
    }

    private fun updateFilterHelper(old: Set<Long>, new: Long, cursor: Cursor, prefNameCreator: (Long) -> String) {
        cursor.moveToFirst()
        while (!cursor.isAfterLast) {
            val payeeFilterKey = prefNameCreator(cursor.getLong(cursor.getColumnIndex(KEY_ROWID))).format(Locale.ROOT, KEY_PAYEEID)
            val oldPayeeFilterValue = prefHandler.getString(payeeFilterKey, null)
            val oldCriteria = oldPayeeFilterValue?.let {
                PayeeCriteria.fromStringExtra(it)
            }
            if (oldCriteria != null) {
                val oldSet = oldCriteria.values.map { it.toLong() }.toSet()
                val newSet: Set<Long> = oldSet.replace(old, new)
                if (oldSet != newSet) {
                    val labelList = mutableListOf<String>()
                    contentResolver.query(TransactionProvider.PAYEES_URI, arrayOf(KEY_PAYEE_NAME),
                        "$KEY_ROWID IN (${newSet.joinToString()})", null, null)?.use {
                        it.moveToFirst()
                        while (!it.isAfterLast) {
                            labelList.add(it.getString(0))
                            it.moveToNext()
                        }
                    }
                    val newPayeeFilterValue = PayeeCriteria(labelList.joinToString(","), *newSet.toLongArray()).toStringExtra()
                    Timber.d("Updating %s (%s -> %s", payeeFilterKey, oldPayeeFilterValue, newPayeeFilterValue)
                    prefHandler.putString(payeeFilterKey, newPayeeFilterValue)
                }
            }
            cursor.moveToNext()
        }
    }

    fun mergeParties(itemIds: LongArray, keepId: Long) {
        viewModelScope.launch(context = coroutineContext()) {
            check(itemIds.contains(keepId))

            val contentValues = ContentValues(1).apply {
                put(KEY_PAYEEID, keepId)
            }
            itemIds.subtract(listOf(keepId)).let {
                val inOp = "IN (${it.joinToString()})"
                val where = "$KEY_PAYEEID $inOp"
                val operations = ArrayList<ContentProviderOperation>().apply {
                    add(newUpdate(TransactionProvider.TRANSACTIONS_URI).withValues(contentValues).withSelection(where, null).build())
                    add(newUpdate(TransactionProvider.TEMPLATES_URI).withValues(contentValues).withSelection(where, null).build())
                    add(newUpdate(TransactionProvider.CHANGES_URI).withValues(contentValues).withSelection(where, null).build())
                    add(newDelete(TransactionProvider.PAYEES_URI).withSelection("$KEY_ROWID $inOp", null).build())
                }
                val size = contentResolver.applyBatch(TransactionProvider.AUTHORITY, operations).size
                if (size == operations.size) {
                    updatePartyFilters(it, keepId)
                    updatePartyBudgets(it, keepId)
                } else {
                    CrashHandler.report("Unexpected result while merging Parties, result size is : $size")
                }
            }
        }
    }
}
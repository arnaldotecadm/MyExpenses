package org.totschnig.myexpenses.util

import android.content.ContentResolver
import org.totschnig.myexpenses.model.CurrencyUnit
import org.totschnig.myexpenses.model.Money
import org.totschnig.myexpenses.preference.PrefHandler
import org.totschnig.myexpenses.preference.PrefKey
import org.totschnig.myexpenses.provider.TransactionProvider
import org.totschnig.myexpenses.util.locale.UserLocaleProvider
import java.math.BigDecimal
import java.text.DecimalFormat
import java.text.NumberFormat
import java.util.*
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CurrencyFormatter @Inject constructor(
    private val prefHandler: PrefHandler,
    private val userLocaleProvider: UserLocaleProvider
) {
    private val numberFormats: MutableMap<String, NumberFormat?> = HashMap()
    fun invalidate(currency: String, contentResolver: ContentResolver) {
        numberFormats.remove(currency)
        notifyUris(contentResolver)
    }

    fun invalidateAll(contentResolver: ContentResolver) {
        numberFormats.clear()
        notifyUris(contentResolver)
    }

    private fun notifyUris(contentResolver: ContentResolver) {
        contentResolver.notifyChange(TransactionProvider.TEMPLATES_URI, null, false)
        contentResolver.notifyChange(TransactionProvider.TRANSACTIONS_URI, null, false)
        contentResolver.notifyChange(TransactionProvider.ACCOUNTS_URI, null, false)
        contentResolver.notifyChange(TransactionProvider.UNCOMMITTED_URI, null, false)
    }

    private fun initNumberFormat(): NumberFormat {
        val prefFormat = prefHandler.getString(PrefKey.CUSTOM_DECIMAL_FORMAT, "")
        if ("" != prefFormat) {
            val nf = DecimalFormat()
            try {
                nf.applyLocalizedPattern(prefFormat)
                return nf
            } catch (ignored: IllegalArgumentException) {
                //fallback to default currency instance
            }
        }
        return NumberFormat.getCurrencyInstance(userLocaleProvider.getUserPreferredLocale())
    }

    private fun getNumberFormat(currencyUnit: CurrencyUnit): NumberFormat {
        var numberFormat = numberFormats[currencyUnit.code]
        if (numberFormat == null) {
            numberFormat = initNumberFormat()
            val fractionDigits = currencyUnit.fractionDigits
            try {
                numberFormat.currency = Currency.getInstance(currencyUnit.code)
            } catch (ignored: Exception) { /*Custom locale}*/
            }
            if (fractionDigits <= 3) {
                numberFormat.minimumFractionDigits = fractionDigits
            }
            numberFormat.maximumFractionDigits = fractionDigits
            val currencySymbol = currencyUnit.symbol
            val decimalFormatSymbols = (numberFormat as DecimalFormat?)!!.decimalFormatSymbols
            decimalFormatSymbols.currencySymbol = currencySymbol
            (numberFormat as DecimalFormat?)!!.decimalFormatSymbols = decimalFormatSymbols
            numberFormats[currencyUnit.code] = numberFormat
        }
        return numberFormat
    }

    /**
     * formats an amount with a currency
     * @return formatted string
     */
    fun formatCurrency(money: Money): String {
        val amount = money.amountMajor
        return formatCurrency(amount, money.currencyUnit)
    }

    fun formatCurrency(amount: BigDecimal?, currency: CurrencyUnit): String {
        return getNumberFormat(currency).format(amount)
    }

    /**
     * utility method that calls formatters for amount this method can be called
     * directly with Long values retrieved from db
     *
     * @return formatted string
     */
    fun convAmount(amount: Long?, currency: CurrencyUnit?): String {
        return formatCurrency(Money(currency!!, amount!!))
    }
}
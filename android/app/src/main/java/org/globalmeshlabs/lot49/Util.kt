package org.globalmeshlabs.lot49

import android.annotation.SuppressLint
import android.content.res.Resources
import android.icu.util.Calendar
import android.os.Build
import android.text.Html
import android.text.Spanned
import android.widget.TextView
import androidx.core.text.HtmlCompat
import androidx.recyclerview.widget.RecyclerView
import org.globalmeshlabs.lot49.R
import org.globalmeshlabs.lot49.database.MeshContact
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit

/**
 * These functions create a formatted string that can be set in a TextView.
 */
private val ONE_MINUTE_MILLIS = TimeUnit.MILLISECONDS.convert(1, TimeUnit.MINUTES)
private val ONE_HOUR_MILLIS = TimeUnit.MILLISECONDS.convert(1, TimeUnit.HOURS)
private val ONE_DAY_MILLIS = TimeUnit.MILLISECONDS.convert(1, TimeUnit.DAYS)

/**
 * Convert a balance to a formatted string for display.
 *
 * Examples:
 *
 * 600 sats
 * 600,000 sats
 * 6,000,000 sats
 * 99,000,000 sats
 * 1 BTC
 * 10 BTC
 *
 * @param balanceMSats the balance in milli satoshis
 * @param res resources used to load formatted strings
 */
fun convertBalanceToFormatted(balanceMSats: Int, res: Resources): String {
    return when {
        balanceMSats > 100000000000 -> {
            (balanceMSats / 100000000000).toString()
        }
        balanceMSats < 1000 -> {
            0.toString()
        }
        else -> {
            (balanceMSats / 1000).toString()
        }
    }
}

/**
 * Returns a string representing the last time node was seen.
 */
fun convertNumericLastSeenToString(last_seen_time_milli: Long, resources: Resources): String {
    val currentTime = java.util.Calendar.getInstance()
    currentTime.timeInMillis = System.currentTimeMillis()
    val lastSeenTime = java.util.Calendar.getInstance()
    lastSeenTime.timeInMillis = last_seen_time_milli
    val elapsedMilli = System.currentTimeMillis() - last_seen_time_milli
    val locale = Locale.US

    if (last_seen_time_milli == Long.MAX_VALUE) {
        return resources.getString(R.string.never)
    }
    else {
        return when {
           currentTime.get(Calendar.YEAR) != lastSeenTime.get(Calendar.YEAR) ->
               SimpleDateFormat("MMM dd, yyyy", locale).format(last_seen_time_milli).toString()
            elapsedMilli < ONE_HOUR_MILLIS -> "${elapsedMilli/ ONE_MINUTE_MILLIS}m"
            elapsedMilli < ONE_DAY_MILLIS -> "${elapsedMilli/ ONE_HOUR_MILLIS}h"
            else -> SimpleDateFormat("MMM dd", locale).format(last_seen_time_milli).toString()
        }
    }
}

/**
 * Take the Long milliseconds returned by the system and stored in Room,
 * and convert it to a nicely formatted string for display.
 *
 * EEEE - Display the long letter version of the weekday
 * MMM - Display the letter abbreviation of the nmotny
 * dd-yyyy - day in month and full year numerically
 * HH:mm - Hours and minutes in 24hr format
 */
@SuppressLint("SimpleDateFormat")
fun convertLongToDateString(systemTime: Long): String {
    return SimpleDateFormat("MMM-dd-yyyy HH:mm").format(systemTime).toString()
}

/**
 * Takes a list of MeshContacts and converts and formats it into one string for display.
 *
 * For display in a TextView, we have to supply one string, and styles are per TextView, not
 * applicable per word. So, we build a formatted string using HTML. This is handy, but we will
 * learn a better way of displaying this data in a future lesson.
 *
 * @param   contacts - List of all SleepNights in the database.
 * @param   resources - Resources object for all the resources defined for our app.
 *
 * @return  Spanned - An interface for text that has formatting attached to it.
 *           See: https://developer.android.com/reference/android/text/Spanned
 */

fun formatContacts(nights: List<MeshContact>, resources: Resources): Spanned {
    val sb = StringBuilder()
    sb.apply {
        append(resources.getString(R.string.title))
        nights.forEach {
            append("<br>")
            append(resources.getString(R.string.name))
            append("\t${it.name}<br>")
        }
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
        return Html.fromHtml(sb.toString(), Html.FROM_HTML_MODE_LEGACY)
    } else {
        return HtmlCompat.fromHtml(sb.toString(), HtmlCompat.FROM_HTML_MODE_LEGACY)
    }
}

/**
 * ViewHolder that holds a single [TextView].
 *
 * A ViewHolder holds a view for the [RecyclerView] as well as providing additional information
 * to the RecyclerView such as where on the screen it was last drawn during scrolling.
 */
class TextItemViewHolder(val textView: TextView): RecyclerView.ViewHolder(textView)
package org.globalmeshlabs.lot49.contacts

import android.os.Build
import android.widget.ImageView
import android.widget.TextView
import androidx.annotation.RequiresApi
import androidx.databinding.BindingAdapter
import org.globalmeshlabs.lot49.R
import org.globalmeshlabs.lot49.convertBalanceToFormatted
import org.globalmeshlabs.lot49.convertNumericLastSeenToString
import org.globalmeshlabs.lot49.database.MeshContact
import java.util.concurrent.TimeUnit

private val ONE_MINUTE_MILLIS = TimeUnit.MILLISECONDS.convert(1, TimeUnit.MINUTES)
private val ONE_HOUR_MILLIS = TimeUnit.MILLISECONDS.convert(1, TimeUnit.HOURS)
private val ONE_DAY_MILLIS = TimeUnit.MILLISECONDS.convert(1, TimeUnit.DAYS)

@BindingAdapter("channelImage")
fun ImageView.setChannelImage(item: MeshContact?) {
    item?.let { setImageResource(
        if (item.channelId <= 0) R.drawable.ic_baseline_link_off_24
        else R.drawable.ic_baseline_link_24) }
}

@BindingAdapter("nameString")
fun TextView.setNameString(item: MeshContact?) {
    item?.let {
        text = item.name
    }
}

@BindingAdapter( "lastChatTimeFormatted")
fun TextView.setLastChatFormatted(item: MeshContact?) {
    item?.let {
        text = convertNumericLastSeenToString(
            item.lastChatTimeMilli,
            context.resources
        )
    }
}

@BindingAdapter("avatarImage")
fun ImageView.setAvatarImage(item: MeshContact?) {
    item?.let { setImageResource(R.drawable.ic_launcher_foreground)
    }
}

@BindingAdapter("channelBalanceFormatted")
fun TextView.setchannelBalanceFormatted(item: MeshContact?) {
    item?.let {
        text = convertBalanceToFormatted(
            item.ourBalanceMSats,
            context.resources
        )
    }
}

@RequiresApi(Build.VERSION_CODES.Q)
@BindingAdapter("lastSeenColor")
fun ImageView.setLastSeenColor(item: MeshContact?) {
    item?.let {
        val elapsedTime = System.currentTimeMillis() - item.lastSeenTimeMilli
        val color = when {
            item.lastSeenTimeMilli == Long.MAX_VALUE -> R.color.colorNeverSeen
            elapsedTime < ONE_MINUTE_MILLIS *5 -> R.color.colorSeenRecent
            elapsedTime < ONE_HOUR_MILLIS -> R.color.colorSeen
            else -> R.color.colorNotSeen
        }
        drawable.setTint(resources.getColor(color, null) )
    }
}

/*
@RequiresApi(Build.VERSION_CODES.Q)
@BindingAdapter("channelBalanceColor")
fun ImageView.setChannelBalanceColor(item: MeshContact?) {
    item?.let {
        val balancePercent = item.ourBalanceMSats / 50000
        val color = when {
            balancePercent >= 0.5 -> R.color.colorSeenRecent
            balancePercent >= 0.25 -> R.color.colorSeen
            balancePercent >= 0.1 -> R.color.colorNotSeen
            else -> R.color.colorNeverSeen
        }
        drawable.setTint(resources.getColor(color, null) )
    }
}*/

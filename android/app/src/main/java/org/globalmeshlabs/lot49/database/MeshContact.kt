package org.globalmeshlabs.lot49.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "mesh_contact_table")
data class MeshContact (
    @PrimaryKey(autoGenerate = true)
    var contactId: Long = 0L,

    @ColumnInfo(name = "avatar")
    var avatar: String = "",

    @ColumnInfo(name = "name")
    var name: String = "",

    @ColumnInfo(name = "routing_address")
    var routingAddress: Long = 0,

    @ColumnInfo(name = "last_seen_time_milli")
    var lastSeenTimeMilli: Long = Long.MAX_VALUE,

    @ColumnInfo(name = "last_chat_time_milli")
    var lastChatTimeMilli: Long = Long.MAX_VALUE,

    @ColumnInfo(name = "public_key")
    var publicKey: String = "",

    @ColumnInfo(name = "channel_id")
    var channelId: Long = 0L,

    @ColumnInfo(name = "our_balance_msats")
    var ourBalanceMSats: Int = 0
)
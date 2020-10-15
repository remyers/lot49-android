package org.globalmeshlabs.lot49.database

import androidx.lifecycle.LiveData
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update

/**
 * Defines methods for using the Contact class with Room.
 */
@Dao
interface ContactDatabaseDao {

    @Insert
    fun insert(meshContact: MeshContact)

    /**
     * When updating a row with a value already set in a column,
     * replaces the old value with the new one.
     *
     * @param meshContact new value to write
     */
    @Update
    fun update(meshContact: MeshContact)

    /**
     * Selects and returns the row that matches the supplied routing address, which is our key.
     *
     * @param key routingAddress to match
     */
    @Query("SELECT * from mesh_contact_table WHERE contactId = :key")
    fun get(key: Long): MeshContact?

    /**
     * Deletes all values from the table.
     *
     * This does not delete the table, only its contents.
     */
    @Query("DELETE FROM mesh_contact_table")
    fun clear()

    /**
     * Selects and returns all rows in the table,
     *
     * sorted by contact id in descending order.
     */
    @Query("SELECT * FROM mesh_contact_table ORDER BY ContactId DESC")
    fun getAllContacts(): LiveData<List<MeshContact>>

    /**
     * Selects and returns the latest pending contact.
     */
    @Query("SELECT * FROM mesh_contact_table ORDER BY contactId DESC LIMIT 1")
    fun getPending(): MeshContact?

    /**
     * Selects and returns the contact with given contactId.
     */
    @Query("SELECT * from mesh_contact_table WHERE contactId = :key")
    fun getContactWithId(key: Long): LiveData<MeshContact>
}

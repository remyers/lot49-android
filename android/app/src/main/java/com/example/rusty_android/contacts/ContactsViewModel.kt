package com.example.rusty_android.contacts

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.Transformations
import com.example.rusty_android.database.ContactDatabaseDao
import com.example.rusty_android.database.MeshContact
import com.example.rusty_android.formatContacts
import kotlinx.coroutines.*
import java.util.concurrent.TimeUnit
import kotlin.random.Random

private val ONE_MINUTE_MILLIS = TimeUnit.MILLISECONDS.convert(1, TimeUnit.MINUTES)
private val ONE_HOUR_MILLIS = TimeUnit.MILLISECONDS.convert(1, TimeUnit.HOURS)

/**
 * ViewModel for ContactsFragment.
 */
class ContactsViewModel(
    val database: ContactDatabaseDao,
    application: Application
) : AndroidViewModel(application) {

    /**
     * viewModelJob allows us to cancel all coroutines started by this ViewModel.
     */
    private var viewModelJob = Job()

    /**
     * A [CoroutineScope] keeps track of all coroutines started by this ViewModel.
     *
     * Because we pass it [viewModelJob], any coroutine started in this uiScope can be cancelled
     * by calling `viewModelJob.cancel()`
     *
     * By default, all coroutines started in uiScope will launch in [Dispatchers.Main] which is
     * the main thread on Android. This is a sensible default because most coroutines started by
     * a [ViewModel] update the UI after performing some processing.
     */
    private val uiScope = CoroutineScope(Dispatchers.Main + viewModelJob)

    private var pendingContact = MutableLiveData<MeshContact?>()

    val contacts = database.getAllContacts()

    /**
     * Converted contacts to Spanned for displaying.
     */
    val contactsString = Transformations.map(contacts) { contacts ->
        formatContacts(contacts, application.resources)
    }

    /**
     * If pendingContact has not been set, then the START button should be visible.
     */
    val startButtonVisible = Transformations.map(pendingContact) {
        null == it
    }

    /**
     * If tonight has been set, then the STOP button should be visible.
     */
    val stopButtonVisible = Transformations.map(pendingContact) {
        null != it
    }

    /**
     * If there are any contacts in the database, show the CLEAR button.
     */
    val clearButtonVisible = Transformations.map(contacts) {
        it?.isNotEmpty()
    }

    /**
     * Request a toast by setting this value to true.
     *
     * This is private because we don't want to expose setting this value to the Fragment.
     */
    private var _showSnackbarEvent = MutableLiveData<Boolean>()

    /**
     * If this is true, immediately `show()` a toast and call `doneShowingSnackbar()`.
     */
    val showSnackBarEvent: LiveData<Boolean>
        get() = _showSnackbarEvent

    /**
     * Variable that tells the Fragment to navigate to a specific [ChatFragment]
     *
     * This is private because we don't want to expose setting this value to the Fragment.
     */

    private val _navigateToChat = MutableLiveData<MeshContact>()

    /**
     * Call this immediately after calling `show()` on a toast.
     *
     * It will clear the toast request, so if the user rotates their phone it won't show a duplicate
     * toast.
     */

    fun doneShowingSnackbar() {
        _showSnackbarEvent.value = false
    }

    /**
     * Call this immediately after navigating to [ChatFragment]
     *
     * It will clear the navigation request, so if the user rotates their phone it won't navigate
     * twice.
     */
    fun doneNavigating() {
        _navigateToChat.value = null
    }

    private val _navigateToChatData = MutableLiveData<Long>()
    val navigateToChat
        get() = _navigateToChatData

    fun onMeshContactClicked(id: Long) {
        _navigateToChatData.value = id
    }

    fun onChatNavigated() {
        _navigateToChatData.value = null
    }

    init {
        removePending()
    }

    private fun removePending() {
        uiScope.launch {
            pendingContact.value = getPendingFromDatabase()
        }
    }

    /**
     *  Handling the case of the stopped app before new contact added,
     *  the public key will not be set.
     *
     *  If the public key is set, then we do not have an unfinished new contact.
     */
    private suspend fun getPendingFromDatabase(): MeshContact? {
        return withContext(Dispatchers.IO) {
            var contact = database.getPending()
            if (!contact?.publicKey.isNullOrBlank()) {
                contact = null
            }
            contact
        }
    }

    private suspend fun clear() {
        withContext(Dispatchers.IO) {
            database.clear()
        }
    }

    private suspend fun update(night: MeshContact) {
        withContext(Dispatchers.IO) {
            database.update(night)
        }
    }

    private suspend fun insert(night: MeshContact) {
        withContext(Dispatchers.IO) {
            database.insert(night)
        }
    }

    /**
     * Executes when the START button is clicked.
     */
    fun onStartTracking() {
        uiScope.launch {
            // Create a new night, which captures the current time,
            // and insert it into the database.
            val newContact = MeshContact()

            insert(newContact)

            pendingContact.value = getPendingFromDatabase()
        }
    }

    /**
     * Executes when the STOP button is clicked.
     */
    fun onStopTracking() {
        uiScope.launch {
            // In Kotlin, the return@label syntax is used for specifying which function among
            // several nested ones this statement returns from.
            // In this case, we are specifying to return from launch(),
            // not the lambda.
            val newContact = pendingContact.value ?: return@launch

            // Update the new contact in the database
            // TODO: should fill these values in a different thread
            newContact.name = when (newContact.contactId.toInt() % 16) {
                0 -> "Oedipa Maas"
                1 -> "Wendell \"Mucho\" Maas"
                2 -> "Metzger"
                3 -> "Miles"
                4 -> "Dean"
                5 -> "Serge"
                6 -> "Leonard"
                7 -> "Dr. Hilarius"
                9 -> "Stanley Koteks"
                10 -> "John Nefastis"
                11 -> "Yoyodyne"
                12 -> "Randolph \"Randy\" Driblette"
                13 -> "Mike Fallopian"
                14 -> "Genghis Cohen"
                15 -> "Professor Emory Bortz"
                else -> "Maxwell's demon"
            }
            newContact.lastChatTimeMilli = System.currentTimeMillis()
            newContact.channelId = Random.nextLong(0, 4)
            newContact.ourBalanceMSats = Random.nextInt(1000, 50001)
            newContact.publicKey = Random.nextBytes(32).toString()
            newContact.routingAddress = Random.nextLong(0, Long.MAX_VALUE)
            newContact.lastSeenTimeMilli = System.currentTimeMillis() -
                    when (Random.nextInt(0, 3)) {
                        1 -> ONE_MINUTE_MILLIS * 5
                        2 -> ONE_HOUR_MILLIS
                        else -> 0
                    }

            update(newContact)

            // Set state to navigate to the ChatFragment.
            // TODO: newContact vs. newContact.contactId ??
            //_navigateToChat.value = newContact.contactId

            // no pending contact to add
            pendingContact.value = null
        }
    }

    /**
     * Executes when the CLEAR button is clicked.
     */
    fun onClear() {
        uiScope.launch {
            // Clear the database table.
            clear()

            // And clear pending contact since it's not yet in the database
            pendingContact.value = null
        }

        // Show a snackbar message, because it's friendly.
        _showSnackbarEvent.value = true
    }

    /**
     * Called when the ViewModel is dismantled.
     * At this point, we want to cancel all coroutines;
     * otherwise we end up with processes that have nowhere to return to
     * using memory and resources.
     */
    override fun onCleared() {
        super.onCleared()
        viewModelJob.cancel()
    }
}
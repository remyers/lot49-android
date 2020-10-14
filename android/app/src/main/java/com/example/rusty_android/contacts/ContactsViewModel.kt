package com.example.rusty_android.contacts

import android.app.Application
import android.util.Base64
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.Transformations
import com.blixtwallet.LndMobile
import com.example.rusty_android.database.ContactDatabaseDao
import com.example.rusty_android.database.MeshContact
import com.example.rusty_android.formatContacts
import com.google.protobuf.ByteString
import com.hypertrack.hyperlog.HyperLog
import kotlinx.coroutines.*
import lnrpc.Rpc
import lnrpc.Walletunlocker.GenSeedResponse
import routerrpc.RouterOuterClass
import java.security.MessageDigest
import java.util.concurrent.CompletableFuture
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

    private val TAG = "ContactsViewModel"

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

    private var lndStatus = MutableLiveData<Int>()
    private var lndSyncedToChain = MutableLiveData<Boolean>()
    private var lndNumPeers = MutableLiveData<Int>()
    private var lndNumPendingChannels = MutableLiveData<Int>()
    private var lndNumActiveChannels = MutableLiveData<Int>()
    private var lndNumInactiveChannels = MutableLiveData<Int>()

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
     * Show status of LND as button text
     */

    val isReady : Boolean
        get() = lndNumActiveChannels.value.let { it != null && it > 0 } && lndNumPendingChannels.value.let {it != null && it == 0}

    val isConnected : Boolean
        get() = lndNumPeers.value.let { it != null && it > 0 }

    val isSynced : Boolean
        get() = lndSyncedToChain.value.let { it != null && it }

    val isUnlocked : Boolean
        get() = lndStatus.value.let {it != null && it and LndMobile.LndStatus.WALLET_UNLOCKED.flag == LndMobile.LndStatus.WALLET_UNLOCKED.flag }

    val isStarted : Boolean
        get() = lndStatus.value.let {it != null && it and LndMobile.LndStatus.PROCESS_STARTED.flag == LndMobile.LndStatus.PROCESS_STARTED.flag }

    val isBound : Boolean
        get() = lndStatus.value.let {it != null && it and LndMobile.LndStatus.SERVICE_BOUND.flag == LndMobile.LndStatus.SERVICE_BOUND.flag }

    val lndButtonStatus = Transformations.map(lndStatus) {
        when {
            lndStatus.value == null -> "UNBOUND"
            isReady -> "READY"
            isConnected -> "CONNECTED"
            isSynced -> "SYNCED"
            isUnlocked -> "UNLOCKED"
            isStarted -> "STARTED"
            isBound -> "BOUND"
            else -> "UNBOUND"
        }
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
        LndInit()
    }

    private fun removePending() {
        uiScope.launch {
            pendingContact.value = getPendingFromDatabase()
        }
    }

    private fun LndInit() {
        uiScope.launch {
            if (!getPendingLndInit()) {
                lndStatus.value = 0
            } else {
                lndStatus.value = LndMobile.LndStatus.SERVICE_BOUND.flag
            }
            lndSyncedToChain.value = false
            lndNumPeers.value = 0
            lndNumPendingChannels.value = 0
            lndNumActiveChannels.value = 0
            lndNumInactiveChannels.value = 0
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

    private suspend fun getPendingLndInit(): Boolean {
        return withContext(Dispatchers.IO) {
            val result: CompletableFuture<Boolean> = CompletableFuture()
            LndMobile.checkLndMobileServiceConnected(result)
            result.get()
        }
    }

    private suspend fun lnd() {
        withContext(Dispatchers.IO) {
            checkStatus()
            when {
                isUnlocked -> {
                    getInfo()
                    if (isSynced) {
                        newAddress()
                        walletBalance()
                        if (!isConnected) {
                            connectPeer(
                                "192.168.86.56:9735",
                                "0388ff9d435160d03ebb5faca4f0a64eba7346e615d74e01052610328ca57b1445",
                                false
                            )
                        }
                        else if (!isReady) {
                            openChannelSync(
                                "0388ff9d435160d03ebb5faca4f0a64eba7346e615d74e01052610328ca57b1445",
                                100000,
                                50000,
                                2,
                                false
                            )
                        }
                        else {
                            // addInvoice(10)
                            // sendPayment("lnbcrt100u1p0c2axwpp53733g3rfllvmnzrncd6mefa0q6eg8q736s44ynwzy4ynlungzxgqdqqcqzpgsp507etncy04yhmy4zfe382l2gzxt3waksnj6tpta4f7gsnamzq3vfs9qy9qsq8a5d0kghmnrdarjlsef0sj6wz49z60n08m3pfn3t9hm4k8z4uz94j7guwxk932v57pj29r2f7h7nl557hdw0lwp6k37x9aw2a25n89cqjevukw")
                            // sendPayment(13, "0388ff9d435160d03ebb5faca4f0a64eba7346e615d74e01052610328ca57b1445", 40)
                            subscribeInvoices(0,0)
                        }
                        getInfo()
                    }
                }
                isStarted -> {
                    unlockWallet()
                    // TODO: should prompt before creating a new wallet
                    //createWallet()
                }
                isBound -> {
                    startLnd()
                }
                else -> {
                    init()
                }
            }
            checkStatus()
        }
    }

    private suspend fun init() {

        withContext(Dispatchers.IO) {
            // bind to LndMobileService
            var initResult: CompletableFuture<HashMap<String, *>> = CompletableFuture()
            LndMobile.init(getApplication(), initResult)
            val result = initResult.get()["data"]
            HyperLog.i(TAG, "init, result: $result");
        }
    }

    private suspend fun createWallet() {
        withContext(Dispatchers.IO) {
            try {
                // DEBUG DEBUG DEBUG
                val isWalletDeleted: CompletableFuture<Boolean> = CompletableFuture()
                LndMobile.DEBUG_deleteWallet(getApplication(), isWalletDeleted)
                if (isWalletDeleted.get()) {
                    HyperLog.i(TAG, "DEBUG_deleteWallet, result: true");
                }

                val seedPromise: CompletableFuture<HashMap<String, *>> = CompletableFuture()
                LndMobile.genSeed(seedPromise)
                val byteString: String = seedPromise.get()["data"].toString()
                HyperLog.i(TAG, "genSeed, result: $byteString");
                val byteArray = Base64.decode(byteString, Base64.NO_WRAP)
                val seedList =
                    GenSeedResponse.parseFrom(byteArray).cipherSeedMnemonicList.toCollection(
                        ArrayList()
                    )
                HyperLog.i(TAG, "genSeed, seed: $seedList");

                val initWalletPromise: CompletableFuture<HashMap<String, *>> = CompletableFuture()
                LndMobile.initWallet(
                    seedList,
                    "test1234",
                    0,
                    null,
                    initWalletPromise
                )
                val unlocked = initWalletPromise.get()["unlocked"] as Boolean
                HyperLog.i(TAG, "createWallet, result: $unlocked");
            }
            catch (e: CancellationException) {
                HyperLog.i(TAG, "createWallet, exception: $e");
            }
        }
    }

    private suspend fun unlockWallet() {
        withContext(Dispatchers.IO) {
            try {
                val unlockWalletPromise: CompletableFuture<HashMap<String, *>> = CompletableFuture()
                LndMobile.unlockWallet(
                    "test1234", unlockWalletPromise
                )
                val result = unlockWalletPromise.get()
                HyperLog.i(TAG, "unlockWallet, result: unlocked");
            } catch (e: CancellationException) {
                HyperLog.i(TAG, "unlockWallet, exception: $e");
            }
        }
    }

    private suspend fun newAddress() {
        withContext(Dispatchers.IO) {
            try {
                val request = Rpc.NewAddressRequest.newBuilder()
                    .setType(
                        Rpc.AddressType.NESTED_PUBKEY_HASH
                    )
                    .build()
                val response = LndMobile.sendCommand("NewAddress", request, {bytes -> Rpc.NewAddressResponse.parseFrom(bytes)})
                HyperLog.i(TAG, "newAddress, address: ${response?.address}")
            } catch (e: Exception) {
                HyperLog.i(TAG, "newAddress, exception: $e");
            }
        }
    }

    private suspend fun getInfo() {
        withContext(Dispatchers.IO) {
            try {
                val request = Rpc.GetInfoRequest.getDefaultInstance()
                val response = LndMobile.sendCommand("GetInfo", request, {bytes -> Rpc.GetInfoResponse.parseFrom(bytes)})

                lndNumPendingChannels.postValue(response?.numPendingChannels)
                lndNumActiveChannels.postValue(response?.numActiveChannels)
                lndNumInactiveChannels.postValue(response?.numInactiveChannels)
                lndNumPeers.postValue(response?.numPeers)
                lndSyncedToChain.postValue(response?.syncedToChain)

                // The version of the LND software that the node is running.
                HyperLog.i(TAG, "getInfo, version: ${response?.version}")

                // The SHA1 commit hash that the daemon is compiled with.
                HyperLog.i(TAG, "getInfo, commitHash: ${response?.commitHash}")

                // The identity pubkey of the current node.
                HyperLog.i(TAG, "getInfo, identityPubkey: ${response?.identityPubkey}")

                // If applicable, the alias of the current node, e.g. "bob"
                HyperLog.i(TAG, "getInfo, alias: ${response?.alias}")

                // The color of the current node in hex code format
                HyperLog.i(TAG, "getInfo, color: ${response?.color}")

                // Number of pending channels
                HyperLog.i(TAG, "getInfo, numPendingChannels: ${response?.numPendingChannels}")

                // Number of active channels
                HyperLog.i(TAG, "getInfo, numActiveChannels: ${response?.numActiveChannels}")

                // Number of inactive channels
                HyperLog.i(TAG, "getInfo, numInactiveChannels: ${response?.numInactiveChannels}")

                // Number of peers
                HyperLog.i(TAG, "getInfo, numPeers: ${response?.numPeers}")

                // The node's current view of the height of the best block
                HyperLog.i(TAG, "getInfo, blockHeight: ${response?.blockHeight}")

                // The node's current view of the hash of the best block
                HyperLog.i(TAG, "getInfo, blockHash: ${response?.blockHash}")

                // Timestamp of the block best known to the wallet
                HyperLog.i(TAG, "getInfo, bestHeaderTimestamp: ${response?.bestHeaderTimestamp}")

                // Whether the wallet's view is synced to the main chain
                HyperLog.i(TAG, "getInfo, syncedToChain: ${response?.syncedToChain}");

                // Whether we consider ourselves synced with the public channel graph.
                HyperLog.i(TAG, "getInfo, synchedtoGraph: ${response?.syncedToGraph}");

                HyperLog.i(TAG, "getInfo, featuresMap: ${response?.featuresMap}");

            } catch (e: Exception) {
                HyperLog.i(TAG, "getInfo, exception: $e");
            }
        }
    }

    private suspend fun connectPeer(host: String, pubkey: String, perm: Boolean) {
        withContext(Dispatchers.IO) {
            try {
                val request = Rpc.ConnectPeerRequest.newBuilder()
                    .setAddr(
                        Rpc.LightningAddress.newBuilder()
                            .setHost(host)
                            .setPubkey(pubkey)
                    )
                    .setPerm(perm)
                    .build()
                val response = LndMobile.sendCommand("ConnectPeer", request, {bytes -> Rpc.ConnectPeerResponse.parseFrom(bytes)})
                HyperLog.i(TAG, "connectPeer, response: $response")
            } catch (e: Exception) {
                HyperLog.i(TAG, "connectPeer, exception: $e");
            }
        }
    }

    private suspend fun walletBalance() {
        withContext(Dispatchers.IO) {
            try {
                val request = Rpc.WalletBalanceRequest.getDefaultInstance()
                val response = LndMobile.sendCommand("WalletBalance", request, {bytes -> Rpc.WalletBalanceResponse.parseFrom(bytes)})

                HyperLog.i(TAG, "LndMobile::walletBalance, totalBalance: ${response?.totalBalance}")
                HyperLog.i(TAG, "LndMobile::walletBalance, confirmedBalance: ${response?.confirmedBalance}")
                HyperLog.i(TAG, "LndMobile::walletBalance, unconfirmedBalance: ${response?.unconfirmedBalance}")
            } catch (e: Exception) {
                HyperLog.i(TAG, "LndMobile::walletBalance, exception: $e");
            }
        }
    }

    private suspend fun addInvoice(valueSat: Long) {
        withContext(Dispatchers.IO) {
            try {
                val invoice = Rpc.Invoice.newBuilder()
                    .setValue(valueSat)
                    .setMemo("test")
                    .build()
                val response = LndMobile.sendCommand("AddInvoice", invoice, {bytes -> Rpc.AddInvoiceResponse.parseFrom(bytes)})

                HyperLog.i(TAG, "LndMobile::addInvoice, addInvoice: ${response?.paymentRequest}")
            } catch (e: Exception) {
                HyperLog.i(TAG, "LndMobile::addInvoice, exception: $e");
            }
        }
    }

    private suspend fun sendPayment(payReq: String) {
        withContext(Dispatchers.IO) {
            try {
                val request = Rpc.SendRequest.newBuilder()
                    .setPaymentRequest(payReq)
                    .build()
                val response = LndMobile.sendCommand("SendPaymentSync", request, {bytes -> Rpc.SendResponse.parseFrom(bytes)})

                HyperLog.i(TAG, "LndMobile::sendPayment, paymentPreimage: ${response?.paymentPreimage}")
                HyperLog.i(TAG, "LndMobile::sendPayment, paymentRoute: ${response?.paymentRoute}")
                HyperLog.i(TAG, "LndMobile::sendPayment, paymentHash: ${response?.paymentHash}")
                HyperLog.i(TAG, "LndMobile::sendPayment, paymentError: ${response?.paymentError}")
            } catch (e: Exception) {
                HyperLog.i(TAG, "LndMobile::sendPayment, exception: $e");
            }
        }
    }

    private suspend fun sendPayment(amt: Long, dest: String, finalCltvDelta: Int) {
        withContext(Dispatchers.IO) {
            val bytes = dest.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            val destByteString = ByteString.copyFrom(bytes)
            val preImageBytes = Random.nextBytes(32)
            val preImage = ByteString.copyFrom(preImageBytes)
            val md = MessageDigest.getInstance("SHA-256")
            val hash = ByteString.copyFrom(md.digest(preImageBytes))
            val keySendType = 5482373484

            try {

                val request = RouterOuterClass.SendPaymentRequest.newBuilder()
                    .setDest(destByteString)
                    .setAmt(amt)
                    .putDestCustomRecords(keySendType, preImage)
                    .setPaymentHash(hash)
                    .setFinalCltvDelta(finalCltvDelta)
                    .setNoInflightUpdates(true)
                    .setTimeoutSeconds(3600)
                    .setMaxParts(1)
                    .addDestFeatures(Rpc.FeatureBit.TLV_ONION_REQ)
                    .setFeeLimitMsat(50000)
                    .build()

                val response = LndMobile.sendStreamCommand(
                    "RouterSendPaymentV2",
                    request,
                    true)

                HyperLog.i(TAG, "LndMobile::sendPayment (keysend), response: ${response}")
            } catch (e: Exception) {
                HyperLog.i(TAG, "LndMobile::sendPayment, exception: $e");
            }
        }
    }

    private suspend fun subscribeInvoices(addIndex: Long, settleIndex: Long) {
        withContext(Dispatchers.IO) {
            try {
                val request = Rpc.InvoiceSubscription.newBuilder()
                    .setAddIndex(addIndex)
                    .setSettleIndex(settleIndex)
                    .build()
                val response = LndMobile.sendStreamCommand(
                    "SubscribeInvoices",
                    request,
                    true)
                HyperLog.i(TAG, "LndMobile::subscribeInvoices, response: ${response}")
            } catch (e: Exception) {
                HyperLog.i(TAG, "LndMobile::subscribeInvoices, exception: $e");
            }
        }
    }


    private suspend fun openChannelSync(pubkey: String, localFundingAmount: Long, pushSat: Long, targetConf: Int, private: Boolean) {
        withContext(Dispatchers.IO) {
            try {
                val bytes = pubkey.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
                val pubkeyByteString = ByteString.copyFrom(bytes)
                val request = Rpc.OpenChannelRequest.newBuilder()
                    .setNodePubkey(pubkeyByteString)
                    .setLocalFundingAmount(localFundingAmount)
                    .setPushSat(pushSat)
                    .setTargetConf(targetConf)
                    .setPrivate(private)
                    .build()

                val response = LndMobile.sendCommand(
                    "OpenChannelSync",
                    request,
                    {bytes -> Rpc.ChannelPoint.parseFrom(bytes)})

                HyperLog.i(TAG, "openChannel, fundingTxidStr: ${response?.fundingTxidStr}")
                HyperLog.i(TAG, "openChannel, outputIndex: ${response?.outputIndex}")
            } catch (e: Exception) {
                HyperLog.i(TAG, "openChannel, exception: $e");
            }
        }
    }

    private suspend fun checkStatus() {
        withContext(Dispatchers.IO) {
            var statusResult: CompletableFuture<HashMap<String, *>> = CompletableFuture()
            LndMobile.checkStatus(statusResult);
            var status: Int = statusResult.get()["flags"] as Int
            lndStatus.postValue(status)
            HyperLog.i(TAG, "LndMobile::checkStatus, result: $status");
        }
    }

    private suspend fun startLnd() {
        withContext(Dispatchers.IO) {
            val startLndResult: CompletableFuture<HashMap<String, *>> = CompletableFuture()
            LndMobile.startLnd(getApplication(), false, startLndResult)
            val result = startLndResult.get()["data"]
            HyperLog.i(TAG, "LndMobile::startLnd, result: $result")
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
     * Executes when the LND button is clicked.
     */
    fun onLND() {
        uiScope.launch {
            // start the LND process
            lnd()
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

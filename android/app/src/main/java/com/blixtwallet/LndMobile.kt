package com.blixtwallet

// TODO enable NFC support
/*
import android.database.sqlite.SQLiteDatabase
import android.nfc.NdefRecord
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.Ndef
 */

import android.Manifest
import android.app.ActivityManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.*
import android.util.Base64
import android.util.Log
import androidx.core.content.ContextCompat
import com.example.rusty_android.BuildConfig
import com.google.protobuf.GeneratedMessageLite
import com.google.protobuf.InvalidProtocolBufferException
import com.google.protobuf.MessageLite
import com.hypertrack.hyperlog.HyperLog
import com.jakewharton.processphoenix.ProcessPhoenix
import kotlinx.coroutines.Runnable
import lnrpc.Rpc
import lnrpc.Rpc.GetInfoResponse
import lnrpc.Walletunlocker.GenSeedRequest
import routerrpc.RouterOuterClass
import java.io.*
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.CompletableFuture
import kotlin.collections.HashMap


// TODO break this class up
object LndMobile  {
    private val TAG = "LndMobile"
    var messenger: Messenger? = null
    var lndMobileServiceBound = false
    private var lndMobileServiceMessenger // The service
            : Messenger? = null
    private val requests =
        HashMap<Int, CompletableFuture<HashMap<String, *>>?>()

    enum class LndStatus {
        SERVICE_BOUND, PROCESS_STARTED, WALLET_UNLOCKED;

        val flag: Int

        companion object {
            val ALL_OPTS =
                EnumSet.allOf(LndStatus::class.java)
        }

        init {
            flag = 1 shl ordinal
        }
    }

    val constants: Map<String, Any>
        get() {
            val constants: MutableMap<String, Any> =
                HashMap()
            constants["STATUS_SERVICE_BOUND"] = LndStatus.SERVICE_BOUND.flag
            constants["STATUS_PROCESS_STARTED"] = LndStatus.PROCESS_STARTED.flag
            constants["STATUS_WALLET_UNLOCKED"] = LndStatus.WALLET_UNLOCKED.flag
            return constants
        }

    internal class IncomingHandler : Handler() {

        override fun handleMessage(msg: Message) {
            HyperLog.d(TAG, "New incoming message from LndMobileService, msg id: " + msg.what)
            val bundle = msg.data
            when (msg.what) {
                LndMobileService.MSG_GRPC_COMMAND_RESULT, LndMobileService.MSG_START_LND_RESULT, LndMobileService.MSG_REGISTER_CLIENT_ACK, LndMobileService.MSG_STOP_LND_RESULT, LndMobileService.MSG_PONG -> {
                    val request = msg.arg1
                    if (!requests.containsKey(request)) {
                        // If request is -1,
                        // we intentionally don't want to
                        // Resolve the promise.
                        if (request != -1) {
                            HyperLog.e(TAG, "Unknown request: " + request + " for " + msg.what)
                        }
                        return  // !
                    }
                    val promise = requests.remove(request)
                    if (bundle.containsKey("error_code") && bundle.containsKey("error_desk")) {
                        HyperLog.e(TAG, "ERROR$msg")
                        val params: HashMap<String, *> = hashMapOf(
                            "error_code" to bundle.getString("error_code"),
                            "error_desc" to bundle.getString("error_desc"))
                        promise?.complete(params)
                        //promise!!.cancel(true)
                        return
                    }
                    val bytes = bundle["response"] as ByteArray?
                    var b64: String? = null
                    if (bytes != null && bytes.isNotEmpty()) {
                        b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
                    }
                    val params: HashMap<String, *> = hashMapOf("data" to b64)
                    promise?.complete(params)
                }
                LndMobileService.MSG_GRPC_STREAM_RESULT -> {

                    // TODO handle when error is returned
                    val bytes = bundle["response"] as ByteArray?
                    val method = bundle["method"] as String?
                    var b64: String? = ""
                    if (bytes != null && bytes.isNotEmpty()) {
                        b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
                    }
                    val params: HashMap<String, *> = hashMapOf("data" to b64)

                    HyperLog.i(TAG, "MSG_GRPC_STREAM_RESULT received. response: $b64, method: $method")

                    // TODO translate callback to Kotlin
                    //getReactApplicationContext()
                    //    .getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter::class.java)
                    //    .emit(method, params)

                    if (bytes != null) {
                        if (method == "SubscribeInvoices") {
                            val response = Rpc.Invoice.parseFrom(bytes)
                            HyperLog.i(TAG, "SubscribeInvoices Invoice, amtPaidSat: ${response.amtPaidSat}")
                            HyperLog.i(TAG, "SubscribeInvoices Invoice, rHash: ${response.rHash}")
                            HyperLog.i(TAG, "SubscribeInvoices Invoice, rPreimage: ${response.rPreimage}")
                            HyperLog.i(TAG, "SubscribeInvoices Invoice, state: ${response.state}")
                            HyperLog.i(TAG, "SubscribeInvoices Invoice, cltvExpiry: ${response.cltvExpiry}")
                            HyperLog.i(TAG, "SubscribeInvoices Invoice, settleDate: ${response.settleDate}")
                        }
                        else if (method == "RouterSendPaymentV2") {
                            val response = Rpc.Payment.parseFrom(bytes)
                            HyperLog.i(TAG, "RouterSendPaymentV2 Payment, status: ${response.status}")
                            HyperLog.i(TAG, "RouterSendPaymentV2 Payment, preimage: ${response.paymentPreimage}")
                            HyperLog.i(TAG, "RouterSendPaymentV2 Payment, valueSat: ${response.valueSat}")
                            HyperLog.i(TAG, "RouterSendPaymentV2 Payment, feeSat: ${response.feeSat}")
                            HyperLog.i(TAG, "RouterSendPaymentV2 Payment, failureReason: ${response.failureReason}")
                        }
                    }
                }
                LndMobileService.MSG_CHECKSTATUS_RESPONSE -> {
                    val request = msg.arg1
                    if (!requests.containsKey(request)) {
                        HyperLog.e(TAG, "Unknown request: " + request + " for " + msg.what)
                        return
                    }
                    val promise = requests.remove(request)
                    val flags = msg.arg2
                    val params: HashMap<String, *> = hashMapOf("flags" to flags)

                    promise?.complete(params)
                }
                LndMobileService.MSG_WALLETUNLOCKED -> {
                    val request = msg.arg1
                    val promise = requests.remove(request)
                    if (promise != null) {
                        val params: HashMap<String, *> = hashMapOf("unlocked" to true)
                        promise.complete(params)
                    }
                    // TODO translate callback to Kotlin
                    //getReactApplicationContext()
                    //    .getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter::class.java)
                    //    .emit("WalletUnlocked", null)
                }
                LndMobileService.MSG_GRPC_STREAM_STARTED -> {
                    val request = msg.arg1
                    val promise = requests.remove(request)
                    if (promise != null) {
                        val params: HashMap<String, *> = hashMapOf("started" to true)
                        promise.complete(params)
                    }
                }
            }
        }
    }

    internal class LndMobileServiceConnection(private val request: Int) : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            HyperLog.i(TAG, "Service attached")
            HyperLog.i(TAG, "Request = $request")
            lndMobileServiceBound = true
            lndMobileServiceMessenger = Messenger(service)
            try {
                val msg = Message.obtain(
                    null,
                    LndMobileService.MSG_REGISTER_CLIENT,
                    request,
                    0
                )
                msg.replyTo = messenger
                lndMobileServiceMessenger!!.send(msg)
            } catch (e: RemoteException) {
                // In this case the service has crashed before we could even
                // do anything with it; we can count on soon being
                // disconnected (and then reconnected if it can be restarted)
                // so there is no need to do anything here.
                Log.e(TAG, "LndMobileServiceConnection:onServiceConnected exception")
                Log.e(TAG, e.message!!)
            }
        }

        override fun onServiceDisconnected(className: ComponentName) {
            // This is called when the connection with the service has been
            // unexpectedly disconnected -- that is, its process crashed.
            lndMobileServiceMessenger = null
            lndMobileServiceBound = false
            HyperLog.e(TAG, "Service disconnected")
        }
    }

    internal var lndMobileServiceConnection: LndMobileServiceConnection? = null
    val name: String
        get() = "LndMobile"

    fun checkLndMobileServiceConnected(promise: CompletableFuture<Boolean>?) {
        promise?.complete(lndMobileServiceBound)
    }

    fun sendPongToLndMobileservice(promise: CompletableFuture<HashMap<String, *>>?) {
        val req = Random().nextInt()
        requests[req] = promise
        val message =
            Message.obtain(null, LndMobileService.MSG_PING, req, 0)
        message.replyTo = messenger
        try {
            lndMobileServiceMessenger!!.send(message)
        } catch (e: RemoteException) {
            promise?.completeExceptionally(Exception(TAG + "Could not Send MSG_PONG to LndMobileService" + e))
        }
    }

    fun checkLndProcessExist(context: Context, promise: CompletableFuture<Boolean>?) {
        val packageName: String = context.packageName
        val am =
            context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        for (p in am.runningAppProcesses) {
            if (p.processName == "$packageName:blixtLndMobile") {
                HyperLog.d(TAG, packageName + ":blixtLndMobile pid: " + p.pid.toString())
                promise?.complete(true)
                return
            }
        }
        promise?.complete(false)
    }

    fun deadPromise(promise: CompletableFuture<Boolean?>) {
    }

    fun init(context: Context, promise: CompletableFuture<HashMap<String, *>>) {
        if (!lndMobileServiceBound) {
            val req = Random().nextInt()
            requests[req] = promise
            lndMobileServiceConnection = LndMobileServiceConnection(req)
            messenger = Messenger(IncomingHandler()) // me
        } else {
            val params: HashMap<String, *> = hashMapOf("bound" to true)
            params
        }

        val ret = context.bindService(
            Intent(context, LndMobileService::class.java),
            lndMobileServiceConnection!!,
            Context.BIND_AUTO_CREATE
        )

        if (ret) {
            lndMobileServiceBound = true
            HyperLog.i(TAG, "LndMobile initialized")

            // Note: Promise is returned from MSG_REGISTER_CLIENT_ACK message from LndMobileService
        } else {
            HyperLog.i(TAG, "LndMobile bind failed")
        }
    }

    fun unbindLndMobileService(context: Context, promise: CompletableFuture<HashMap<String,*>>?) {
        if (lndMobileServiceBound) {
            val req = Random().nextInt()
            requests[req] = promise
            if (lndMobileServiceMessenger != null) {
                try {
                    val message =
                        Message.obtain(null, LndMobileService.MSG_UNREGISTER_CLIENT, req)
                    message.replyTo = messenger
                    lndMobileServiceMessenger!!.send(message)
                } catch (e: RemoteException) {
                    HyperLog.e(TAG, "Unable to send unbind request to LndMobileService", e)
                }
            }
            context.unbindService(lndMobileServiceConnection!!)
            lndMobileServiceBound = false
            HyperLog.i(TAG, "Unbinding LndMobileService")
        }
    }

    // TODO unbind LndMobileService?

    fun checkStatus(promise: CompletableFuture<HashMap<String,*>>?) {
        val req = Random().nextInt()
        requests[req] = promise
        val message =
            Message.obtain(null, LndMobileService.MSG_CHECKSTATUS, req, 0)
        message.replyTo = messenger
        try {
            lndMobileServiceMessenger!!.send(message)
        } catch (e: RemoteException) {
            promise?.completeExceptionally(Exception(TAG + "Could not Send MSG_CHECKSTATUS to LndMobileService" + e))
        }
    }

    fun startLnd(context: Context, torEnabled: Boolean, promise: CompletableFuture<HashMap<String,*>>?) {
        val req = Random().nextInt()
        requests[req] = promise
        val message =
            Message.obtain(null, LndMobileService.MSG_START_LND, req, 0)
        message.replyTo = messenger
        val bundle = Bundle()
        var params =
            "--lnddir=" + context.filesDir.path
        // TODO: enable Tor
        /*
        if (torEnabled) {
            val listenPort: Int = BlixtTorUtils.getListenPort()
            val socksPort: Int = BlixtTorUtils.getSocksPort()
            val controlPort: Int = BlixtTorUtils.getControlPort()
            params += " --tor.active --tor.socks=127.0.0.1:$socksPort --tor.control=127.0.0.1:$controlPort"
            params += " --tor.v3 --listen=localhost:$listenPort"
        } else {
            // If Tor isn't active, make sure we aren't
            // listening at all
            params += " --nolisten"
        }
        */
        bundle.putString(
            "args",
            params
        )
        message.data = bundle
        try {
            lndMobileServiceMessenger!!.send(message)
        } catch (e: RemoteException) {
            promise?.completeExceptionally(Exception(TAG + "Could not Send MSG_START_LND to LndMobileService" + e))
        }
    }

    fun stopLnd(promise: CompletableFuture<HashMap<String,*>>?) {
        val req = Random().nextInt()
        requests[req] = promise
        val message =
            Message.obtain(null, LndMobileService.MSG_STOP_LND, req, 0)
        message.replyTo = messenger
        try {
            lndMobileServiceMessenger!!.send(message)
        } catch (e: RemoteException) {
            promise?.completeExceptionally(Exception(TAG + "Could not Send MSG_STOP_LND to LndMobileService" + e))
        }
    }

    private var logObserver: FileObserver? = null

    fun observeLndLogFile(context: Context, promise: CompletableFuture<Boolean>?) {
        val appDir: File? = context.filesDir
        val logDir = "$appDir/logs/bitcoin/mainnet"
        val logFile = "$logDir/lnd.log"
        var stream: FileInputStream?
        while (true) {
            stream = try {
                FileInputStream(logFile)
            } catch (e: FileNotFoundException) {
                val dir = File(logDir)
                dir.mkdirs()
                val f = File(logFile)
                try {
                    f.createNewFile()
                    continue
                } catch (e1: IOException) {
                    e1.printStackTrace()
                    return
                }
            }

            break
        }
        val iStream = InputStreamReader(stream)
        val buf = BufferedReader(iStream)
        try {
            readToEnd(buf, false)
        } catch (e: IOException) {
            e.printStackTrace()
            return
        }
        logObserver = object : FileObserver(logFile) {
            override fun onEvent(event: Int, file: String?) {
                if (event != MODIFY) {
                    return
                }
                try {
                    readToEnd(buf, true)
                } catch (e: IOException) {
                    e.printStackTrace()
                }
            }
        }
        (logObserver as FileObserver).startWatching()
        Log.i("LndNativeModule", "Started watching $logFile")
        promise?.complete(true)
    }

    @Throws(IOException::class)
    private fun readToEnd(buf: BufferedReader, emit: Boolean) {
        var s: String? = ""
        while (buf.readLine().also { s = it } != null) {
            if (!emit) {
                continue
            }
            // TODO translate to Kotlin
            /*
            getReactApplicationContext()
                .getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter::class.java)
                .emit("lndlog", s)
            */
        }
    }

    fun killLnd(context: Context, promise: CompletableFuture<Boolean>?) {
        val result = killLndProcess(context)
        promise?.complete(result)
    }

    private fun killLndProcess(context: Context): Boolean {
        val packageName: String = context.packageName
        val am =
            context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        for (p in am.runningAppProcesses) {
            if (p.processName == "$packageName:blixtLndMobile") {
                HyperLog.i(
                    TAG,
                    "Killing " + packageName + ":blixtLndMobile with pid: " + p.pid.toString()
                )
                Process.killProcess(p.pid)
                return true
            }
        }
        return false
    }

    fun restartApp(context: Context) {
        ProcessPhoenix.triggerRebirth(context)
    }

    fun writeConfigFile(context: Context, promise: CompletableFuture<String>?) {
        val filename: String =
            context.filesDir.toString() + "/lnd.conf"
        try {
            File(filename).parentFile.mkdirs()
            val out = PrintWriter(filename)
            if (BuildConfig.CHAIN.equals("mainnet")) {
                out.println(
                    """
                        [Application Options]
                        debuglevel=info
                        maxbackoff=2s
                        norest=1
                        sync-freelist=1
                        accept-keysend=1

                        [Routing]
                        routing.assumechanvalid=1

                        [Bitcoin]
                        bitcoin.active=1
                        bitcoin.mainnet=1
                        bitcoin.node=neutrino

                        [Neutrino]
                        neutrino.connect=btcd-mainnet.lightning.computer
                        neutrino.feeurl=https://nodes.lightning.computer/fees/v1/btc-fee-estimates.json

                        [autopilot]
                        autopilot.active=0
                        autopilot.private=1
                        autopilot.minconfs=1
                        autopilot.conftarget=3
                        autopilot.allocation=1.0
                        autopilot.heuristic=externalscore:0.95
                        autopilot.heuristic=preferential:0.05

                        """.trimIndent()
                )
            } else if (BuildConfig.CHAIN.equals("testnet")) {
                out.println(
                    """
                        [Application Options]
                        debuglevel=info
                        maxbackoff=2s
                        norest=true
                        sync-freelist=1
                        accept-keysend=1

                        [Routing]
                        routing.assumechanvalid=1

                        [Bitcoin]
                        bitcoin.active=1
                        bitcoin.testnet=1
                        bitcoin.node=neutrino

                        [Neutrino]
                        neutrino.connect=btcd-testnet.lightning.computer
                        neutrino.feeurl=https://nodes.lightning.computer/fees/v1/btc-fee-estimates.json

                        [autopilot]
                        autopilot.active=0
                        autopilot.private=1
                        autopilot.minconfs=1
                        autopilot.conftarget=3
                        autopilot.allocation=1.0
                        autopilot.heuristic=externalscore:0.95
                        autopilot.heuristic=preferential:0.05

                        """.trimIndent()
                )
            } else if (BuildConfig.CHAIN.equals("regtest")) {
                    out.println(
                    """
                        [Application Options]
                        debuglevel=info
                        maxbackoff=2s
                        nolisten=1
                        norest=1
                        sync-freelist=1
                        accept-keysend=1
                        nobootstrap=1
                        ignore-historical-gossip-filters=true
                        alias=Lot49 Phone
                        

                        [Routing]
                        routing.assumechanvalid=1

                        [Bitcoin]
                        bitcoin.active=1
                        bitcoin.regtest=1
                        bitcoin.node=bitcoind

                        [Bitcoind]
                        bitcoind.rpchost=192.168.86.56:18443
                        bitcoind.rpcuser=lnd
                        bitcoind.rpcpass=lightning
                        bitcoind.zmqpubrawblock=192.168.86.56:28332
                        bitcoind.zmqpubrawtx=192.168.86.56:28333

                        [autopilot]
                        autopilot.active=0
                        autopilot.private=1
                        autopilot.minconfs=1
                        autopilot.conftarget=3
                        autopilot.allocation=1.0
                        autopilot.heuristic=externalscore:0.95
                        autopilot.heuristic=preferential:0.05

                        """.trimIndent()
                )
            }
            out.close()
            HyperLog.d(TAG, "Saved lnd config: $filename")
        } catch (e: Exception) {
            HyperLog.e(TAG, "Couldn't write $filename", e)
            promise?.completeExceptionally(Exception("Couldn't write: $filename" + e))
            return
        }
        promise?.complete("File written: $filename")
    }

    private fun deleteRecursive(fileOrDirectory: File) {
        if (fileOrDirectory.isDirectory) {
            for (child in fileOrDirectory.listFiles()) {
                deleteRecursive(child)
            }
        }
        HyperLog.d(
            TAG,
            "Delete file " + fileOrDirectory.name + " : " + fileOrDirectory.delete()
        )
    }

    // TODO translate DEBUG_getWalletPasswordFromKeychain to Kotlin
    /*
    fun DEBUG_getWalletPasswordFromKeychain(promise: CompletableFuture<String>?) {

        val keychain = KeychainModule(context)
        val keychainOptions: WritableMap = Arguments.createMap()
        val keychainOptionsAuthenticationPrompt: WritableMap = Arguments.createMap()
        keychainOptionsAuthenticationPrompt.putString("title", "Authenticate to retrieve secret")
        keychainOptionsAuthenticationPrompt.putString("cancel", "Cancel")
        keychainOptions.putMap("authenticationPrompt", keychainOptionsAuthenticationPrompt)
        keychain.getInternetCredentialsForServer(
            "password",
            keychainOptions,
            object : PromiseWrapper() {

                fun onSuccess(value: Any?) {
                    if (value != null) {
                        promise?.complete((value as ReadableMap).getString("password"))
                        return
                    }
                    promise?.completeExceptionally(Exception("fail2"))
                }


                fun onFail(throwable: Throwable) {
                    Log.d(TAG, "error", throwable)
                    promise?.completeExceptionally(throwable)
                }
            })
    }
    */

    fun DEBUG_deleteWallet(context: Context, promise: CompletableFuture<Boolean>?) {
        HyperLog.i(TAG, "DEBUG deleting wallet")
        val filename: String = context.filesDir.toString()
            .toString() + "/data/chain/bitcoin/" + BuildConfig.CHAIN + "/wallet.db"
        val file = File(filename)
        promise?.complete(file.delete())
    }

    fun DEBUG_deleteDatafolder(context: Context, promise: CompletableFuture<String>?) {
        HyperLog.i(TAG, "DEBUG deleting data folder")
        val filename: String =
            context.filesDir.toString() + "/data/"
        val file = File(filename)
        deleteRecursive(file)
        promise?.complete(null)
    }

    fun DEBUG_listProcesses(context: Context, promise: CompletableFuture<String>?) {
        var processes = ""
        val packageName: String = context.packageName
        val am =
            context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        for (p in am.runningAppProcesses) {
            processes += """
                ${p.processName}

                """.trimIndent()
        }
        promise?.complete(processes)
    }

    fun deleteTLSCerts(context: Context, promise: CompletableFuture<Boolean>?) {
        HyperLog.i(TAG, "Deleting lnd TLS certificates")
        val tlsKeyFilename: String =
            context.filesDir.toString().toString() + "/tls.key"
        val tlsKeyFile = File(tlsKeyFilename)
        val tlsKeyFileDeletion = tlsKeyFile.delete()
        HyperLog.i(TAG, "Delete: $tlsKeyFilename: $tlsKeyFileDeletion")
        val tlsCertFilename: String =
            context.filesDir.toString().toString() + "/tls.cert"
        val tlsCertFile = File(tlsCertFilename)
        val tlsCertFileDeletion = tlsCertFile.delete()
        HyperLog.i(TAG, "Delete: $tlsCertFilename: $tlsCertFileDeletion")
        promise?.complete(tlsKeyFileDeletion && tlsCertFileDeletion)
    }

    /* TODO: replace my version of sendCommand and sendStreamCommand with original versions
    fun sendCommand(
        method: String,
        payloadStr: String?,
        promise: CompletableFuture<HashMap<String,*>>?
    ) {
        HyperLog.d(TAG, "sendCommand() $method")
        val req = Random().nextInt()
        requests[req] = promise
        val message =
            Message.obtain(null, LndMobileService.MSG_GRPC_COMMAND, req, 0)
        message.replyTo = messenger
        val bundle = Bundle()
        bundle.putString("method", method)
        bundle.putByteArray(
            "payload",
            Base64.decode(payloadStr, Base64.NO_WRAP)
        )
        message.data = bundle
        try {
            lndMobileServiceMessenger!!.send(message)
        } catch (e: RemoteException) {
            promise?.completeExceptionally(Exception(TAG + "Could not Send MSG_GRPC_COMMAND to LndMobileService" + e))
        }
    }

    fun sendStreamCommand(
        method: String,
        payloadStr: String?,
        streamOnlyOnce: Boolean,
        promise: CompletableFuture<HashMap<String,*>>?
    ) {
        HyperLog.d(TAG, "sendStreamCommand() $method")
        val req = Random().nextInt()
        requests[req] = promise
        val message =
            Message.obtain(null, LndMobileService.MSG_GRPC_STREAM_COMMAND, req, 0)
        message.replyTo = messenger
        val bundle = Bundle()
        bundle.putString("method", method)
        bundle.putByteArray(
            "payload",
            Base64.decode(payloadStr, Base64.NO_WRAP)
        )
        bundle.putBoolean("stream_only_once", streamOnlyOnce)
        message.data = bundle
        try {
            lndMobileServiceMessenger!!.send(message)
        } catch (e: RemoteException) {
            promise?.completeExceptionally(Exception( "Could not Send MSG_GRPC_STREAM_COMMAND to LndMobileService" + e))
        }
        val params: HashMap<String, *> = hashMapOf("done" to true)
        promise?.complete(params)
    }
    */

    fun unlockWallet(password: String?, promise: CompletableFuture<HashMap<String,*>>?) {
        val req = Random().nextInt()
        requests[req] = promise
        HyperLog.d(TAG, "unlockWallet()")
        val message =
            Message.obtain(null, LndMobileService.MSG_UNLOCKWALLET, req, 0)
        message.replyTo = messenger
        val bundle = Bundle()
        bundle.putString("password", password)
        message.data = bundle
        try {
            lndMobileServiceMessenger!!.send(message)
        } catch (e: RemoteException) {
            promise?.completeExceptionally(Exception(TAG + "Could not Send MSG_UNLOCKWALLET to LndMobileService" + e))
        }
    }

    fun genSeed(promise: CompletableFuture<HashMap<String,*>>?) {
        val req = Random().nextInt()
        requests[req] = promise
        val message = Message.obtain(null, LndMobileService.MSG_GRPC_COMMAND, req, 0)
        message.replyTo = messenger
        val bundle = Bundle()
        bundle.putString("method", "GenSeed")
        bundle.putByteArray("payload", GenSeedRequest.getDefaultInstance().toByteArray())
        message.data = bundle
        try {
            lndMobileServiceMessenger!!.send(message)
        } catch (e: RemoteException) {
            promise?.completeExceptionally(Exception(TAG + "Could not Send MSG_GRPC_COMMAND to LndMobileService" + e))
        }
    }

    fun sendCommand(promise: CompletableFuture<HashMap<String,*>>?, method: String, request: MessageLite) {
        val req = Random().nextInt()
        requests[req] = promise
        val message = Message.obtain(null, LndMobileService.MSG_GRPC_COMMAND, req, 0)
        message.replyTo = messenger
        val bundle = Bundle()
        bundle.putString("method", method)
        bundle.putByteArray("payload", request.toByteArray())
        message.data = bundle
        try {
            lndMobileServiceMessenger!!.send(message)
        } catch (e: RemoteException) {
            promise?.completeExceptionally(Exception(TAG + "Could not Send MSG_GRPC_COMMAND to LndMobileService" + e))
        }
    }

    fun sendStreamCommand(promise: CompletableFuture<HashMap<String,*>>?, method: String, request: MessageLite, streamOnlyOnce: Boolean) {
        val req = Random().nextInt()
        requests[req] = promise
        val message = Message.obtain(null, LndMobileService.MSG_GRPC_STREAM_COMMAND, req, 0)
        message.replyTo = messenger
        val bundle = Bundle()
        bundle.putString("method", method)
        bundle.putByteArray("payload", request.toByteArray())
        bundle.putBoolean("stream_only_once", streamOnlyOnce)
        message.data = bundle
        try {
            lndMobileServiceMessenger!!.send(message)
        } catch (e: RemoteException) {
            promise?.completeExceptionally(Exception(TAG + "Could not Send MSG_GRPC_STREAM_COMMAND to LndMobileService" + e))
        }
    }

    fun <RequestType: MessageLite, ResponseType: Any> sendCommand(method: String, request: RequestType, builder: (data: ByteArray) -> ResponseType): ResponseType? {
        try {
            val promise: CompletableFuture<HashMap<String,*>> = CompletableFuture()
            sendCommand(promise, method, request)
            val responseMessage = promise.get()
            when {
                responseMessage.containsKey("data") -> {
                    val byteString: String = promise.get()["data"].toString()
                    if (byteString != "null") {
                        val bytes = Base64.decode(byteString, Base64.NO_WRAP)
                        return builder(bytes)
                    }
                    else {
                        HyperLog.d(TAG, "LndMobile::$method, result: null")
                    }
                }
                responseMessage.containsKey("error_code") && responseMessage.containsKey("error_desc") -> {
                    HyperLog.e(TAG, "LndMobile::$method, error: code = ${responseMessage["error_code"]}, desc = ${responseMessage["error_desc"]}")
                }

                responseMessage.containsKey("error") -> {
                    HyperLog.e(TAG, "LndMobile::$method, error: ${responseMessage["error"]}")
                }

                else -> {
                    HyperLog.i(TAG, "LndMobile::$method, error: missing error_code and/or error_desc")
                }
            }

        } catch (e: Exception) {
            HyperLog.i(TAG, "LndMobile::$method, exception: $e");
        }
        return null
    }

    fun <RequestType: MessageLite> sendStreamCommand(method: String, request: RequestType, streamOnlyOnce:Boolean): Boolean? {
        try {
            val promise: CompletableFuture<HashMap<String,*>> = CompletableFuture()
            sendStreamCommand(promise, method, request, streamOnlyOnce)
            val response = promise.get()
            when {
                response.containsKey("started") -> {
                    var started: Boolean = response["started"] as Boolean
                    HyperLog.i(TAG, "LndMobile::lndStreamCommand, started: $started")
                    return started
                }
                response.containsKey("error_code") && response.containsKey("error_desc") -> {
                    HyperLog.e(TAG, "LndMobile::$method, error: code = ${response["error_code"]}, desc = ${response["error_desc"]}")
                }

                response.containsKey("error") -> {
                    HyperLog.e(TAG, "LndMobile::$method, error: ${response["error"]}")
                }

                else -> {
                    HyperLog.i(TAG, "LndMobile::$method, error: missing error_code and/or error_desc")
                }
            }

        } catch (e: Exception) {
            HyperLog.i(TAG, "LndMobile::$method, exception: $e");
        }
        return false
    }

    fun initWallet(
        seedList: ArrayList<String?>,
        password: String?,
        recoveryWindow: Int,
        channelBackupsBase64: String?,
        promise: CompletableFuture<HashMap<String,*>>?
    ) {
        val req = Random().nextInt()
        requests[req] = promise
        HyperLog.d(TAG, "initWallet()")
        val message =
            Message.obtain(null, LndMobileService.MSG_INITWALLET, req, 0)
        message.replyTo = messenger
        val bundle = Bundle()
        // TODO(hsjoberg): this could possibly be faster if we
        // just encode it to a bytearray using the grpc lib here,
        // instead of letting LndMobileService do that part
        bundle.putStringArrayList("seed", seedList)
        bundle.putString("password", password)
        bundle.putInt("recoveryWindow", recoveryWindow)
        bundle.putString("channelBackupsBase64", channelBackupsBase64)
        message.data = bundle
        try {
            lndMobileServiceMessenger!!.send(message)
        } catch (e: RemoteException) {
            promise?.completeExceptionally(Exception(TAG + "Could not Send MSG_INITWALLET to LndMobileService" + e))
        }
    }

    fun saveLogs(context: Context, promise: CompletableFuture<String>?) {
        val file = HyperLog.getDeviceLogsInFile(context, false)
        if (file != null && file.exists()) {
            promise?.complete(file.absolutePath)
        } else {
            promise?.complete("Fail saving log")
        }
    }

    fun copyLndLog(context: Context, promise: CompletableFuture<String>?) {
        checkWriteExternalStoragePermission(
            context,
            object : RequestWriteExternalStoragePermissionCallback {
                override fun success(value: Any?) {
                    if (value == "granted") {
                        val lndLogFile = copyLndLogFile(context)
                        if (lndLogFile != null) {
                            promise?.complete(lndLogFile)
                        } else {
                            promise?.complete("Error copying")
                        }
                    }
                }
            },
            Runnable { promise?.complete("Request Error") },
            Runnable { promise?.complete("Permission Check Error") }
        )
    }

    fun copyLndLogFile(context: Context): String? {
        val sourceLocation = File(
            context.filesDir.toString().toString() +
                    "/logs/bitcoin/" +
                    BuildConfig.CHAIN +
                    "/lnd.log"
        )
        val targetDir = File(
            ContextCompat.getExternalFilesDirs(
                context,
                null
            )[0].toString()
        )
        val targetLocation =
            File(targetDir.toString() + "/lnd-" + BuildConfig.CHAIN + (if (BuildConfig.DEBUG) "-debug" else "") + ".log")
        return try {
            Log.i(TAG, targetLocation.toString())
            if (!targetDir.exists()) {
                if (!targetDir.mkdirs()) {
                    throw Error("Error creating dir")
                }
            }
            val `in`: InputStream = FileInputStream(sourceLocation)
            val out: OutputStream = FileOutputStream(targetLocation)
            val buf = ByteArray(1024)
            var len: Int
            while (`in`.read(buf).also { len = it } > 0) {
                out.write(buf, 0, len)
            }
            `in`.close()
            out.close()
            targetLocation.toString()
        } catch (e: Throwable) {
            Log.e(
                TAG, """copyLndLogFile() failed: ${e.message} source: ${sourceLocation.toString()}
                dest: $targetDir"""
            )
            null
        }
    }

    fun saveChannelsBackup(context: Context, base64Backups: String?, promise: CompletableFuture<String>?) {
        val backups =
            Base64.decode(base64Backups, Base64.NO_WRAP)
        checkWriteExternalStoragePermission(
            context,
            object : RequestWriteExternalStoragePermissionCallback {
                override fun success(value: Any?) {
                    if (value == "granted") {
                        saveChannelBackupToFile(context, backups, promise)
                    } else {
                        promise?.complete("You must grant access")
                    }
                }
            },
            Runnable { promise?.complete("Request Error") },
            Runnable { promise?.complete("Permission Check Error") }
        )
    }

    private fun saveChannelBackupToFile(context: Context, backups: ByteArray, promise: CompletableFuture<String>?) {
        val dateFormat =
            SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US)
        val path = ContextCompat.getExternalFilesDirs(
            context,
            null
        )[0].toString()
        val file = path +
                "/channels-backup-" +
                dateFormat.format(Date()) + ".bin"
        try {
            val dir = File(path)
            if (!dir.exists()) {
                dir.mkdirs()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Couldn't create folder $path", e)
            promise?.completeExceptionally(Exception("Couldn't create folder: $path".format(e.message)))
        }
        try {
            FileOutputStream(file).use { stream ->
                stream.write(backups)
                Log.i(TAG, "Success $file")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Couldn't write $file", e)
            promise?.completeExceptionally(Exception("Couldn't write: $file".format(e.message)))
        }
        promise?.complete(file)
    }

    private interface RequestWriteExternalStoragePermissionCallback {
        fun success(value: Any?)
    }

    private fun checkWriteExternalStoragePermission(
        context: Context,
        successCallback: RequestWriteExternalStoragePermissionCallback,
        failCallback: Runnable,
        failPermissionCheckcallback: Runnable
    ) {
        when {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED -> {
                // You can use the API that requires the permission.
                successCallback.success(true)
            }
            else -> {
                // You can directly ask for the permission.
                // The registered ActivityResultCallback gets the result of this request.
                failCallback.run()
            }
        }
    }

    // TODO convert to Kotlin
    /*
    fun getIntentStringData(promise: CompletableFuture<String>?) {
        val sharedText: String? = getReactApplicationContext()
            .getCurrentActivity().getIntent().getStringExtra(Intent.EXTRA_TEXT)
        if (sharedText != null) {
            Log.d(TAG, sharedText)
            promise?.complete(sharedText)
        } else {
            Log.d(TAG, "sharedText null")
            promise?.complete(null)
        }
    }
    */

    // TODO update NFC code
    /*fun getIntentNfcData(promise: CompletableFuture<HashMap<String,*>>?) {
        // https://code.tutsplus.com/tutorials/reading-nfc-tags-with-android--mobile-17278
        val tag: Tag = getReactApplicationContext()
            .getCurrentActivity().getIntent().getParcelableExtra(NfcAdapter.EXTRA_TAG)
        if (tag == null) {
            promise.resolve(null)
            return
        }
        val ndef = Ndef.get(tag)
        if (ndef == null) {
            HyperLog.d(TAG, "NFC tag is not NDEF")
            promise.resolve(null)
        }
        val ndefMessage = ndef!!.cachedNdefMessage
        val records = ndefMessage.records
        if (records.size > 0) {
            // Get first record and ignore the rest
            val record = records[0]
            if (record.tnf == NdefRecord.TNF_WELL_KNOWN && Arrays.equals(
                    record.type,
                    NdefRecord.RTD_TEXT
                )
            ) {
                *//*
         * See NFC forum specification for "Text Record Type Definition" at 3.2.1
         *
         * http://www.nfc-forum.org/specs/
         *
         * bit_7 defines encoding
         * bit_6 reserved for future use, must be 0
         * bit_5..0 length of IANA language code
        *//*
                val payload = record.payload

                // Get the Text Encoding
                val textEncoding =
                    if (payload[0] and 128 == 0) "UTF-8" else "UTF-16"

                // Get the Language Code
                val languageCodeLength: Int = payload[0] and 51

                // String languageCode = new String(payload, 1, languageCodeLength, "US-ASCII");
                // e.g. "en"
                try {
                    val s = String(
                        payload,
                        languageCodeLength + 1,
                        payload.size - languageCodeLength - 1,
                        textEncoding
                    )
                    promise.resolve(s)
                    return
                } catch (e: UnsupportedEncodingException) {
                    HyperLog.e(TAG, "Error returning ndef data", e)
                }
            } else {
                HyperLog.d(TAG, "Cannot read NFC Tag Record")
            }
        }
        promise.resolve(null)
    }*/


    fun tailLog(context: Context, numberOfLines: Int, promise: CompletableFuture<String>?) {
        val file = File(
            context.filesDir.toString() +
                    "/logs/bitcoin/" +
                    BuildConfig.CHAIN +
                    "/lnd.log"
        )
        var fileHandler: RandomAccessFile? = null
        try {
            fileHandler = RandomAccessFile(file, "r")
            val fileLength = fileHandler.length() - 1
            val sb = StringBuilder()
            var line = 0
            for (filePointer in fileLength downTo -1 + 1) {
                fileHandler.seek(filePointer)
                val readByte = fileHandler.readByte().toInt()
                if (readByte == 0xA) {
                    if (filePointer < fileLength) {
                        line = line + 1
                    }
                } else if (readByte == 0xD) {
                    if (filePointer < fileLength - 1) {
                        line = line + 1
                    }
                }
                if (line >= numberOfLines) {
                    break
                }
                sb.append(readByte.toChar())
            }
            val lastLine = sb.reverse().toString()
            promise?.complete(lastLine)
        } catch (e: FileNotFoundException) {
            e.printStackTrace()
            promise?.completeExceptionally(e)
        } catch (e: IOException) {
            e.printStackTrace()
            promise?.completeExceptionally(e)
        } finally {
            if (fileHandler != null) {
                try {
                    fileHandler.close()
                } catch (e: IOException) {
                }
            }
        }
    }

    fun log(type: String?, tag: String, message: String) {
        val mainTag = "BlixtWallet"
        when (type) {
            "v" -> HyperLog.v(mainTag, "[$tag] $message")
            "d" -> HyperLog.d(mainTag, "[$tag] $message")
            "i" -> HyperLog.i(mainTag, "[$tag] $message")
            "w" -> HyperLog.w(mainTag, "[$tag] $message")
            "e" -> HyperLog.e(mainTag, "[$tag] $message")
            else -> HyperLog.v(mainTag, "[unknown msg type][$tag] $message")
        }
    }

    // TODO enable Tor
    /*
    fun getTorEnabled(promise: CompletableFuture<Boolean>?) {
        val db: SQLiteDatabase =
            com.facebook.react.modules.storage.ReactDatabaseSupplier.getInstance(
                getReactApplicationContext()
            ).get()
        val torEnabled: String = AsyncLocalStorageUtil.getItemImpl(db, "torEnabled")
        if (torEnabled != null) {
            promise?.complete(torEnabled == "true")
        }
        promise?.completeExceptionally(Error(""))
    }
    */
}
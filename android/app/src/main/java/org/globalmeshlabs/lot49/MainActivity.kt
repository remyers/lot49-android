package org.globalmeshlabs.lot49

import android.os.*
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import org.globalmeshlabs.lot49.R
import org.blixtwallet.LndMobile
import org.globalmeshlabs.rust_android_lib.loadRustyLib
import com.hypertrack.hyperlog.HyperLog
import java.util.concurrent.CompletableFuture
import kotlin.collections.HashMap

class MainActivity : AppCompatActivity() {

    private val TAG = "MainActivity"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // initialize logging
        HyperLog.initialize(this)
        HyperLog.setLogLevel(
            if (BuildConfig.DEBUG) Log.VERBOSE else Log.DEBUG
        )

        // load the Rust library
        loadRustyLib()

        val result: CompletableFuture<String> = CompletableFuture()
        LndMobile.writeConfigFile(this, result)
        HyperLog.i(TAG, "LndMobile::writeConfigFile, result:" + result.get());
    }

    override fun onStart() {
        super.onStart()

        // bind to LndMobileService
        var initResult: CompletableFuture<HashMap<String, *>> = CompletableFuture()
        LndMobile.init(this, initResult)
    }

    override fun onStop() {
        super.onStop()

        val stopLndResult: CompletableFuture<HashMap<String, *>> = CompletableFuture()
        LndMobile.stopLnd(stopLndResult)
        HyperLog.i(TAG, "LndMobile::stopLnd, result:" + stopLndResult.get());
        val unbindLndMobileServiceResult: CompletableFuture<HashMap<String, *>> = CompletableFuture()
        LndMobile.unbindLndMobileService(this, unbindLndMobileServiceResult)
        HyperLog.i(TAG, "LndMobileService stopped, result:" + unbindLndMobileServiceResult.get());
    }

}

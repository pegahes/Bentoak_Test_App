package com.getbouncer.cardscan.demo

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.graphics.PointF
import android.os.Bundle
import android.os.Handler
import android.util.Log
import android.util.Size
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.NumberPicker
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.getbouncer.cardscan.ui.CardScanFlow
import com.getbouncer.cardscan.ui.SavedFrame
import com.getbouncer.cardscan.ui.analyzer.CompletionLoopAnalyzer
import com.getbouncer.cardscan.ui.result.CompletionLoopListener
import com.getbouncer.cardscan.ui.result.CompletionLoopResult
import com.getbouncer.cardscan.ui.result.MainLoopAggregator.FinalResult
import com.getbouncer.cardscan.ui.result.MainLoopAggregator.InterimResult
import com.getbouncer.cardscan.ui.result.MainLoopState.*
import com.getbouncer.scan.camera.CameraAdapter
import com.getbouncer.scan.camera.CameraErrorListener
import com.getbouncer.scan.camera.camera1.Camera1Adapter
import com.getbouncer.scan.framework.AggregateResultListener
import com.getbouncer.scan.framework.AnalyzerLoopErrorListener
import com.getbouncer.scan.framework.Config.logTag
import com.getbouncer.scan.framework.Config.slowDeviceFrameRate
import com.getbouncer.scan.framework.Stats
import com.getbouncer.scan.framework.Stats.instanceId
import com.getbouncer.scan.framework.Stats.scanId
import com.getbouncer.scan.framework.TrackedImage
import com.getbouncer.scan.framework.api.dto.ScanStatistics.Companion.fromStats
import com.getbouncer.scan.framework.api.uploadScanStats
import com.getbouncer.scan.framework.interop.BlockingAggregateResultListener
import com.getbouncer.scan.framework.util.AppDetails
import com.getbouncer.scan.framework.util.Device
import com.getbouncer.scan.payment.card.formatPan
import com.getbouncer.scan.payment.card.getCardIssuer
import com.getbouncer.scan.payment.card.isValidExpiry
import com.getbouncer.scan.ui.util.asRect
import com.getbouncer.scan.ui.util.fadeIn
import com.getbouncer.scan.ui.util.hide
import com.getbouncer.scan.ui.util.startAnimation
import com.google.android.material.bottomsheet.BottomSheetDialog
import kotlinx.android.synthetic.main.activity_launcher.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers.Default
import java.util.*
import kotlin.coroutines.CoroutineContext

class LauncherActivity : AppCompatActivity(), CameraErrorListener,
    AnalyzerLoopErrorListener, CoroutineScope {

    private enum class State {
        NOT_FOUND, FOUND, CORRECT
    }

    private val PERMISSION_REQUEST_CODE = 1200
    private val MINIMUM_RESOLUTION = Size(1280, 720)


    private var cameraAdapter: CameraAdapter<TrackedImage>? = null

    private var cardScanFlow: CardScanFlow? = null

    private var scanState = State.NOT_FOUND

    private var pan: String? = null

    /**
     * CardScan uses kotlin coroutines to run multiple analyzers in parallel for maximum image
     * throughput. This coroutine context binds the coroutines to this activity, so that if this
     * activity is terminated, all coroutines are terminated and there is no work leak.
     *
     * Additionally, this specifies which threads the coroutines will run on. Normally, the default
     * dispatchers should be used so that coroutines run on threads bound by the number of CPU
     * cores.
     */

    override val coroutineContext: CoroutineContext
        get() = Default

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_launcher)

        button_scan_card!!.setOnClickListener {
            button_scan_card!!.visibility = View.GONE
            scanView!!.visibility = View.VISIBLE
            if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.CAMERA) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                requestCameraPermission()
            } else {
                startScan()
            }
        }

        card_expiry_date_txt!!.setOnClickListener {
            showBottomSheetDatePicker()
        }

        closeButtonView.setOnClickListener { userCancelScan() }
        flashButtonView.setOnClickListener {
            setFlashlightState(
                !cameraAdapter!!.isTorchOn()
            )
        }

        // Allow the user to set the focus of the camera by tapping on the view finder.
        viewFinderWindow.setOnTouchListener { v: View?, event: MotionEvent ->
            cameraAdapter!!.setFocus(
                PointF(
                    event.x + viewFinderWindow.left,
                    event.y + viewFinderWindow.top
                )
            )
            true
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cardScanFlow?.cancelFlow()
    }

    override fun onPause() {
        super.onPause()
        setFlashlightState(false)
    }

    override fun onResume() {
        super.onResume()
        setStateNotFound()
    }

    /**
     * Request permission to use the camera.
     */
    private fun requestCameraPermission() {
        ActivityCompat.requestPermissions(
            this, arrayOf(Manifest.permission.CAMERA),
            PERMISSION_REQUEST_CODE
        )
    }

    /**
     * Handle permission status changes. If the camera permission has been granted, start it. If
     * not, show a dialog.
     */
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String?>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode ==  PERMISSION_REQUEST_CODE && grantResults.size > 0) {
            if (grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startScan()
            } else {
                showPermissionDeniedDialog()
            }
        }
    }

    /**
     * Show an explanation dialog for why we are requesting camera permissions.
     */
    private fun showPermissionDeniedDialog() {
        val builder = AlertDialog.Builder(this)
        builder.setMessage(R.string.bouncer_camera_permission_denied_message)
            .setPositiveButton(
                R.string.bouncer_camera_permission_denied_ok
            ) { dialog, which -> requestCameraPermission() }
            .setNegativeButton(
                R.string.bouncer_camera_permission_denied_cancel
            ) { dialog, which -> startScan() }
            .show()
    }

    /**
     * Start the scanning flow.
     */
    private fun startScan() {
        // ensure the cameraPreview view has rendered.
        cameraPreviewHolder!!.post {

            // Track scan statistics for health check
            Stats.startScan()

            // Tell the background where to draw a hole for the viewfinder window
            viewFinderBackground.setViewFinderRect(viewFinderWindow.asRect())

            // Create a camera adapter and bind it to this activity.
            cameraAdapter = Camera1Adapter(
                this,
                cameraPreviewHolder, MINIMUM_RESOLUTION, this, this
            )
            (cameraAdapter as Camera1Adapter).bindToLifecycle(this)
            (cameraAdapter as Camera1Adapter).withFlashSupport { supported: Boolean ->
                flashButtonView.visibility = if (supported) View.VISIBLE else View.INVISIBLE
                Unit
            }

            // Create and start a CardScanFlow which will handle the business logic of the scan
            cardScanFlow = CardScanFlow(
                true,
                true,
                aggregateResultListener,
                this
            )
            cardScanFlow!!.startFlow(
                this,
                (cameraAdapter as Camera1Adapter).getImageStream(),
                Size(cameraPreviewHolder.width, cameraPreviewHolder.height),
                viewFinderWindow.asRect(),
                this,
                this
            )
        }
    }

    /**
     * Turn the flashlight on or off.
     */
    private fun setFlashlightState(on: Boolean) {
        if (cameraAdapter != null) {
            cameraAdapter!!.setTorchState(on)
            if (cameraAdapter!!.isTorchOn()) {
                flashButtonView.setImageResource(R.drawable.bouncer_flash_on_dark)
            } else {
                flashButtonView.setImageResource(R.drawable.bouncer_flash_off_dark)
            }
        }
    }

    /**
     * Cancel scanning due to analyzer failure
     */
    private fun analyzerFailureCancelScan(cause: Throwable?) {
        Log.e(logTag, "Canceling scan due to analyzer error", cause)
        AlertDialog.Builder(this)
            .setMessage("Analyzer failure")
            .show()
        closeScanner()
    }

    /**
     * Cancel scanning due to a camera error.
     */
    private fun cameraErrorCancelScan(cause: Throwable?) {
        Log.e(logTag, "Canceling scan due to camera error", cause)
        AlertDialog.Builder(this)
            .setMessage("Camera error")
            .show()
        closeScanner()
    }

    /**
     * The scan has been cancelled by the user.
     */
    private fun userCancelScan() {
        AlertDialog.Builder(this)
            .setMessage("Scan Canceled by user")
            .show()
        closeScanner()
    }

    /**
     * Show the completed scan results
     */
    private fun completeScan(
        expiryMonth: String?,
        expiryYear: String?,
        cardNumber: String?,
        issuer: String?,
        name: String?,
        error: String?
    ) {
        card_number_txt.text = cardNumber.toString()
        if (expiryMonth != null && expiryYear != null) {
            card_expiry_date_txt.text = "$expiryMonth/$expiryYear"
        } else {
            card_expiry_date_txt.text = "00/00"
        }
        if (issuer != null) {
            setImageForCard(issuer.toLowerCase())
        }
        closeScanner()
    }

    private fun setImageForCard(issuer: String?) {
        when(issuer){
            "mastercard"-> card_image.setImageResource(R.drawable.master_card_logo)
            "visa"-> card_image.setImageResource(R.drawable.visa_card_logo)
            "paypal"-> card_image.setImageResource(R.drawable.paypal_card_logo)
        }
    }

    /**
     * Close the scanner.
     */
    private fun closeScanner() {
        setFlashlightState(false)
        button_scan_card!!.visibility = View.VISIBLE
        scanView!!.visibility = View.GONE
        setStateNotFound()
        cameraAdapter!!.unbindFromLifecycle(this)
        if (cardScanFlow != null) {
            cardScanFlow!!.cancelFlow()
        }
        uploadScanStats(
            this,
            instanceId,
            scanId,
            Device.fromContext(this),
            AppDetails.fromContext(this),
            fromStats()
        )
    }

    override fun onCameraOpenError(cause: Throwable?) {
        cameraErrorCancelScan(cause)
    }

    override fun onCameraAccessError(cause: Throwable?) {
        cameraErrorCancelScan(cause)
    }

    override fun onCameraUnsupportedError(cause: Throwable?) {
        cameraErrorCancelScan(cause)
    }

    override fun onAnalyzerFailure(t: Throwable): Boolean {
        analyzerFailureCancelScan(t)
        return true
    }

    override fun onResultFailure(t: Throwable): Boolean {
        analyzerFailureCancelScan(t)
        return true
    }

    private val completionLoopListener: CompletionLoopListener = object : CompletionLoopListener {
        override fun onCompletionLoopFrameProcessed(
            result: CompletionLoopAnalyzer.Prediction,
            frame: SavedFrame
        ) {
            // display debug information if so desired
        }

        override fun onCompletionLoopDone(result: CompletionLoopResult) {
            val expiryMonth: String?
            val expiryYear: String?
            if (result.expiryMonth != null && result.expiryYear != null &&
                isValidExpiry(
                    null,
                    result.expiryMonth!!,
                    result.expiryYear!!
                )
            ) {
                expiryMonth = result.expiryMonth
                expiryYear = result.expiryYear
            } else {
                expiryMonth = null
                expiryYear = null
            }
            Handler(mainLooper).post {
                // Only show the expiry dates that are not expired
                completeScan(
                    expiryMonth,
                    expiryYear,
                    this@LauncherActivity.pan,
                    getCardIssuer(this@LauncherActivity.pan).displayName,
                    result.name,
                    result.errorString
                )
            }
        }
    }

    private val aggregateResultListener: AggregateResultListener<InterimResult, FinalResult> =
        object : BlockingAggregateResultListener<InterimResult, FinalResult>() {
            /**
             * An interim result has been received from the scan, the scan is still running. Update your
             * UI as necessary here to display the progress of the scan.
             */
            override fun onInterimResultBlocking(interimResult: InterimResult) {
                Handler(mainLooper).post {
                    val mainLoopState = interimResult.state
                    if (mainLoopState is Initial) {
                        // In initial state, show no card found
                        setStateNotFound()
                    } else if (mainLoopState is PanFound) {
                        // If OCR is running and a valid card number is visible, display it
                        val pan = mainLoopState.getMostLikelyPan()
                        if (pan != null) {
                            cardPanTextView.text = formatPan(pan)
                            cardPanTextView.fadeIn(null)
                        }
                        setStateFound()
                    } else if (mainLoopState is CardSatisfied) {
                        // If OCR is running and a valid card number is visible, display it
                        val pan = mainLoopState.getMostLikelyPan()
                        if (pan != null) {
                            cardPanTextView.text = formatPan(pan)
                            cardPanTextView.fadeIn(null)
                        }
                        setStateFound()
                    } else if (mainLoopState is PanSatisfied) {
                        // If OCR is running and a valid card number is visible, display it
                        val pan = mainLoopState.pan
                        if (pan != null) {
                            cardPanTextView.text = formatPan(pan)
                            cardPanTextView.fadeIn(null)
                        }
                        setStateFound()
                    } else if (mainLoopState is Finished) {
                        // Once the main loop has finished, the camera can stop
                        cameraAdapter!!.unbindFromLifecycle(this@LauncherActivity)
                        setStateCorrect()
                    }
                }
            }

            /**
             * The scan has completed and the final result is available. Close the scanner and make use
             * of the final result.
             */
            override fun onResultBlocking(result: FinalResult) {
                this@LauncherActivity.pan = result.pan
                cardScanFlow!!.launchCompletionLoop(
                    this@LauncherActivity,
                    completionLoopListener,
                    cardScanFlow!!.selectCompletionLoopFrames(
                        result.averageFrameRate,
                        result.savedFrames
                    ),
                    result.averageFrameRate.compareTo(slowDeviceFrameRate) > 0,
                    this@LauncherActivity
                )
            }

            /**
             * The scan was reset (usually because the activity was backgrounded). Reset the UI.
             */
            override fun onResetBlocking() {
                Handler(mainLooper).post { setStateNotFound() }
            }
        }

    /**
     * Display a blue border tracing the outline of the card to indicate that the card is identified
     * and scanning is running.
     */
    private fun setStateFound() {
        if (scanState == State.FOUND) return
        viewFinderBorder.startAnimation(R.drawable.bouncer_card_border_found_long)
        processing_overlay.hide()
        scanState = State.FOUND
    }

    /**
     * Return the view to its initial state, where no card has been detected.
     */
    private fun setStateNotFound() {
        if (scanState == State.NOT_FOUND) return
        viewFinderBorder.startAnimation(R.drawable.bouncer_card_border_not_found)
        cardPanTextView.hide()
        processing_overlay.hide()
        scanState = State.NOT_FOUND
    }

    /**
     * Flash the border around the card green to indicate that scanning was successful.
     */
    private fun setStateCorrect() {
        if (scanState == State.CORRECT) return
        viewFinderBorder.startAnimation(R.drawable.bouncer_card_border_correct)
        processing_overlay.fadeIn(null)
        scanState = State.CORRECT
    }

    private fun showBottomSheetDatePicker() {
        val dialog = BottomSheetDialog(this, R.style.bottomSheetStyleDailyGem)
        dialog.setContentView(R.layout.bottoms_sheet_date_picker)
        dialog.setCanceledOnTouchOutside(true)
        val yearNumberPicker = dialog.findViewById<NumberPicker>(R.id.year_numberPicker)
        val monthNumberPicker = dialog.findViewById<NumberPicker>(R.id.month_numberPicker)
        val selectDateBtn = dialog.findViewById<Button>(R.id.date_picker_button)
        yearNumberPicker!!.maxValue = 99
        yearNumberPicker.minValue = 0
        yearNumberPicker.setFormatter { i -> String.format("%02d", i) }
        monthNumberPicker!!.maxValue = 12
        monthNumberPicker.minValue = 1
        monthNumberPicker.setFormatter { i -> String.format("%02d", i) }
        dialog.setOnDismissListener {
            setDateText(
                monthNumberPicker.value,
                yearNumberPicker.value
            )
        }
        dialog.show()
        selectDateBtn!!.setOnClickListener { v: View? -> dialog.dismiss() }
    }

    private fun setDateText(month: Int, year: Int) {
        card_expiry_date_txt.setText(String.format("%02d", year) + "/" + String.format("%02d", month))
    }

}



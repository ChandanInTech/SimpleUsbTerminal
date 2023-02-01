package de.kai_morich.simple_usb_terminal

import android.app.Activity
import android.app.PendingIntent
import android.content.*
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.method.ScrollingMovementMethod
import android.text.style.ForegroundColorSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.hoho.android.usbserial.driver.SerialTimeoutException
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import de.kai_morich.simple_usb_terminal.SerialService.SerialBinder
import java.util.*

class TerminalFragment : Fragment(), ServiceConnection, SerialListener {
    private enum class Connected {
        False, Pending, True
    }

    private val broadcastReceiver: BroadcastReceiver
    private var deviceId = 0
    private var portNum = 0
    private var baudRate = 0
    private var usbSerialPort: UsbSerialPort? = null
    private var service: SerialService? = null
    private var receiveText: TextView? = null
    private var sendText: TextView? = null
    private var connected = Connected.False
    private var initialStart = true
    private val newline = TextUtil.newline_crlf
    private var pendingNewline = false

    init {
        broadcastReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (Constants.INTENT_ACTION_GRANT_USB == intent.action) {
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    connect(granted)
                }
            }
        }
    }

    /*
     * Lifecycle
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setHasOptionsMenu(true)
        retainInstance = true
        deviceId = requireArguments().getInt("device")
        portNum = requireArguments().getInt("port")
        baudRate = requireArguments().getInt("baud")
    }

    override fun onDestroy() {
        if (connected != Connected.False) disconnect()
        requireActivity().stopService(Intent(activity, SerialService::class.java))
        super.onDestroy()
    }

    override fun onStart() {
        super.onStart()
        if (service != null) service!!.attach(this) else requireActivity().startService(
            Intent(
                activity,
                SerialService::class.java
            )
        ) // prevents service destroy on unbind from recreated activity caused by orientation change
    }

    override fun onStop() {
        if (service != null && !requireActivity().isChangingConfigurations) service!!.detach()
        super.onStop()
    }

    override fun onAttach(activity: Activity) {
        super.onAttach(activity)
        requireActivity().bindService(
            Intent(getActivity(), SerialService::class.java),
            this,
            Context.BIND_AUTO_CREATE
        )
    }

    override fun onDetach() {
        try {
            requireActivity().unbindService(this)
        } catch (ignored: Exception) {
        }
        super.onDetach()
    }

    override fun onResume() {
        super.onResume()
        requireActivity().registerReceiver(
            broadcastReceiver,
            IntentFilter(Constants.INTENT_ACTION_GRANT_USB)
        )
        if (initialStart && service != null) {
            initialStart = false
            requireActivity().runOnUiThread { connect() }
        }
    }

    override fun onPause() {
        requireActivity().unregisterReceiver(broadcastReceiver)
        super.onPause()
    }

    override fun onServiceConnected(name: ComponentName, binder: IBinder) {
        service = (binder as SerialBinder).service
        service?.attach(this)
        if (initialStart && isResumed) {
            initialStart = false
            requireActivity().runOnUiThread { connect() }
        }
    }

    override fun onServiceDisconnected(name: ComponentName) {
        service = null
    }

    /*
     * UI
     */
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_terminal, container, false)
        receiveText =
            view.findViewById(R.id.receive_text) // TextView performance decreases with number of spans
        receiveText?.setTextColor(resources.getColor(R.color.colorRecieveText)) // set as default color to reduce number of spans
        receiveText?.movementMethod = ScrollingMovementMethod.getInstance()
        sendText = view.findViewById(R.id.send_text)
        val sendBtn = view.findViewById<View>(R.id.send_btn)
        sendBtn.setOnClickListener { v: View? -> send(sendText?.text.toString()) }
        return view
    }

    /*
     * Serial + UI
     */
    private fun connect(permissionGranted: Boolean? = null) {
        var device: UsbDevice? = null
        val usbManager = requireActivity().getSystemService(Context.USB_SERVICE) as UsbManager
        for (v in usbManager.deviceList.values) if (v.deviceId == deviceId) device = v
        if (device == null) {
            status("connection failed: device not found")
            return
        }
        var driver = UsbSerialProber.getDefaultProber().probeDevice(device)
        if (driver == null) {
            driver = CustomProber.getCustomProber().probeDevice(device)
        }
        if (driver == null) {
            status("connection failed: no driver for device")
            return
        }
        if (driver.ports.size < portNum) {
            status("connection failed: not enough ports at device")
            return
        }
        usbSerialPort = driver.ports[portNum]
        val usbConnection = usbManager.openDevice(driver.device)
        if (usbConnection == null && permissionGranted == null && !usbManager.hasPermission(driver.device)) {
            val flags =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
            val usbPermissionIntent = PendingIntent.getBroadcast(
                activity,
                0,
                Intent(Constants.INTENT_ACTION_GRANT_USB),
                flags
            )
            usbManager.requestPermission(driver.device, usbPermissionIntent)
            return
        }
        if (usbConnection == null) {
            if (!usbManager.hasPermission(driver.device)) status("connection failed: permission denied") else status(
                "connection failed: open failed"
            )
            return
        }
        connected = Connected.Pending
        try {
            usbSerialPort?.open(usbConnection)
            usbSerialPort?.setParameters(
                baudRate,
                UsbSerialPort.DATABITS_8,
                UsbSerialPort.STOPBITS_1,
                UsbSerialPort.PARITY_NONE
            )
            val socket = SerialSocket(requireActivity().applicationContext, usbConnection, usbSerialPort)
            service!!.connect(socket)
            // usb connect is not asynchronous. connect-success and connect-error are returned immediately from socket.connect
            // for consistency to bluetooth/bluetooth-LE app use same SerialListener and SerialService classes
            onSerialConnect()
        } catch (e: Exception) {
            onSerialConnectError(e)
        }
    }

    private fun disconnect() {
        connected = Connected.False
        service!!.disconnect()
        usbSerialPort = null
    }

    private fun send(str: String) {
        if (connected != Connected.True) {
            Toast.makeText(activity, "not connected", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val msg: String
            val data: ByteArray
            msg = str
            data = (str + newline).toByteArray()
            val spn = SpannableStringBuilder(
                """
    $msg
    
    """.trimIndent()
            )
            spn.setSpan(
                ForegroundColorSpan(resources.getColor(R.color.colorSendText)),
                0,
                spn.length,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            receiveText!!.append(spn)
            service!!.write(data)
        } catch (e: SerialTimeoutException) {
            status("write timeout: " + e.message)
        } catch (e: Exception) {
            onSerialIoError(e)
        }
    }

    private fun receive(datas: ArrayDeque<ByteArray>) {
        val spn = SpannableStringBuilder()
        for (data in datas) {
            var msg = String(data)
            if (newline == TextUtil.newline_crlf && msg.length > 0) {
                // don't show CR as ^M if directly before LF
                msg = msg.replace(TextUtil.newline_crlf, TextUtil.newline_lf)
                // special handling if CR and LF come in separate fragments
                if (pendingNewline && msg[0] == '\n') {
                    if (spn.length >= 2) {
                        spn.delete(spn.length - 2, spn.length)
                    } else {
                        val edt = receiveText!!.editableText
                        if (edt != null && edt.length >= 2) edt.delete(edt.length - 2, edt.length)
                    }
                }
                pendingNewline = msg[msg.length - 1] == '\r'
            }
            spn.append(TextUtil.toCaretString(msg, newline.length != 0))
        }
        receiveText!!.append(spn)
    }

    fun status(str: String) {
        val spn = SpannableStringBuilder(
            """
    $str
    
    """.trimIndent()
        )
        spn.setSpan(
            ForegroundColorSpan(resources.getColor(R.color.colorStatusText)),
            0,
            spn.length,
            Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        receiveText!!.append(spn)
    }

    /*
     * SerialListener
     */
    override fun onSerialConnect() {
        status("connected")
        connected = Connected.True
    }

    override fun onSerialConnectError(e: Exception) {
        status("connection failed: " + e.message)
        disconnect()
    }

    override fun onSerialRead(data: ByteArray) {
        val datas = ArrayDeque<ByteArray>()
        datas.add(data)
        receive(datas)
    }

    override fun onSerialRead(datas: ArrayDeque<ByteArray>) {
        receive(datas)
    }

    override fun onSerialIoError(e: Exception) {
        status("connection lost: " + e.message)
        disconnect()
    }
}
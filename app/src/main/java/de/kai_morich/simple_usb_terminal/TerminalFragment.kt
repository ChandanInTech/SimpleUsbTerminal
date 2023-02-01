package de.kai_morich.simple_usb_terminal

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
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import com.hoho.android.usbserial.driver.SerialTimeoutException
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import de.kai_morich.simple_usb_terminal.SerialService.SerialBinder
import de.kai_morich.simple_usb_terminal.databinding.FragmentTerminalBinding
import java.util.ArrayDeque

class TerminalFragment : Fragment(), ServiceConnection, SerialListener {
    private val broadcastReceiver: BroadcastReceiver
    private var usbSerialPort: UsbSerialPort? = null
    private var service: SerialService? = null
    private lateinit var binding: FragmentTerminalBinding
    private val viewModel: TerminalViewModel by viewModels()

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
        requireActivity().bindService(
            Intent(activity, SerialService::class.java),
            this,
            Context.BIND_AUTO_CREATE
        )
        viewModel.deviceId = requireArguments().getInt("device")
        viewModel.portNum = requireArguments().getInt("port")
        viewModel.baudRate = requireArguments().getInt("baud")
    }

    override fun onDestroy() {
        if (viewModel.connected != TerminalViewModel.Connected.FALSE) disconnect()
        requireActivity().stopService(Intent(activity, SerialService::class.java))
        super.onDestroy()
    }

    override fun onStart() {
        super.onStart()
        if (service != null) {
            service?.attach(this)
        } else {
            requireActivity().startService(
                Intent(
                    activity,
                    SerialService::class.java
                )
            ) // prevents service destroy on unbind from recreated activity caused by orientation change
        }
    }

    override fun onStop() {
        if (service != null && !requireActivity().isChangingConfigurations) service?.detach()
        super.onStop()
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
        if (viewModel.initialStart && service != null) {
            viewModel.initialStart = false
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
        if (viewModel.initialStart && isResumed) {
            viewModel.initialStart = false
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
    ): View {

        binding = FragmentTerminalBinding.inflate(inflater, container, false)
        binding.receiveText.setTextColor(
            ContextCompat.getColor(
                requireContext(),
                R.color.colorRecieveText
            )
        ) // set as default color to reduce number of spans
        binding.receiveText.movementMethod = ScrollingMovementMethod.getInstance()
        binding.sendBtn.setOnClickListener { send(binding.sendText.text.toString()) }
        return binding.root
    }

    /*
     * Serial + UI
     */
    private fun connect(permissionGranted: Boolean? = null) {
//        var device: UsbDevice? = null
        val usbManager = requireActivity().getSystemService(Context.USB_SERVICE) as UsbManager
//        for (v in usbManager.deviceList.values) if (v.deviceId == viewModel.deviceId) device = v

        val device = usbManager.deviceList.values.firstOrNull { it.deviceId == viewModel.deviceId }

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
        if (driver.ports.size < viewModel.portNum) {
            status("connection failed: not enough ports at device")
            return
        }
        usbSerialPort = driver.ports[viewModel.portNum]
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
        viewModel.connected = TerminalViewModel.Connected.PENDING
        try {
            usbSerialPort?.open(usbConnection)
            usbSerialPort?.setParameters(
                viewModel.baudRate,
                UsbSerialPort.DATABITS_8,
                UsbSerialPort.STOPBITS_1,
                UsbSerialPort.PARITY_NONE
            )
            val socket =
                SerialSocket(requireActivity().applicationContext, usbConnection, usbSerialPort)
            service?.connect(socket)
            // usb connect is not asynchronous. connect-success and connect-error are returned immediately from socket.connect
            // for consistency to bluetooth/bluetooth-LE app use same SerialListener and SerialService classes
            onSerialConnect()
        } catch (e: Exception) {
            onSerialConnectError(e)
        }
    }

    private fun disconnect() {
        viewModel.connected = TerminalViewModel.Connected.FALSE
        service?.disconnect()
        usbSerialPort = null
    }

    private fun send(str: String) {
        if (viewModel.connected != TerminalViewModel.Connected.TRUE) {
            Toast.makeText(activity, "not connected", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val msg: String = str
            val data: ByteArray = (str + viewModel.newline).toByteArray()
            val spn = SpannableStringBuilder(msg.trimIndent())
            spn.setSpan(
                ForegroundColorSpan(
                    ContextCompat.getColor(
                        requireContext(),
                        R.color.colorSendText
                    )
                ),
                0,
                spn.length,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            binding.receiveText.append(spn)
            service?.write(data)
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
            if (viewModel.newline == TextUtil.newline_crlf && msg.isNotEmpty()) {
                // don't show CR as ^M if directly before LF
                msg = msg.replace(TextUtil.newline_crlf, TextUtil.newline_lf)
                // special handling if CR and LF come in separate fragments
                if (viewModel.pendingNewline && msg[0] == '\n') {
                    if (spn.length >= 2) {
                        spn.delete(spn.length - 2, spn.length)
                    } else {
                        val edt = binding.receiveText.editableText
                        if (edt != null && edt.length >= 2) edt.delete(edt.length - 2, edt.length)
                    }
                }
                viewModel.pendingNewline = msg[msg.length - 1] == '\r'
            }
            spn.append(TextUtil.toCaretString(msg, viewModel.newline.isNotEmpty()))
        }
        binding.receiveText.append(spn)
    }

    fun status(str: String) {
        val spn = SpannableStringBuilder(str.trimIndent())
        spn.setSpan(
            ForegroundColorSpan(ContextCompat.getColor(requireContext(), R.color.colorStatusText)),
            0,
            spn.length,
            Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        binding.receiveText.append(spn)
    }

    /*
     * SerialListener
     */
    override fun onSerialConnect() {
        status("connected")
        viewModel.connected = TerminalViewModel.Connected.TRUE
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
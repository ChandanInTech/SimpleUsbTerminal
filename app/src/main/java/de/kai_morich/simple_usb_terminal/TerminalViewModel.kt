package de.kai_morich.simple_usb_terminal

import androidx.lifecycle.ViewModel

class TerminalViewModel() : ViewModel() {
    var deviceId = 0
    var portNum = 0
    var baudRate = 0
    var connected = Connected.FALSE
    val newline = TextUtil.newline_crlf
    var initialStart = true
    var pendingNewline = false

    enum class Connected {
        FALSE, PENDING, TRUE
    }
}
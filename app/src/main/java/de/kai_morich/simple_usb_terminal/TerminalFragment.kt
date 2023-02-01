package de.kai_morich.simple_usb_terminal

import android.os.Bundle
import android.text.method.ScrollingMovementMethod
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import de.kai_morich.simple_usb_terminal.databinding.FragmentTerminalBinding

class TerminalFragment : SuperTerminalFragment() {

    private lateinit var binding: FragmentTerminalBinding

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
        binding.ledButton.setOnClickListener {
            if (count == 0) {
                send("from machine import Pin as pin")
                send("import time")
            }
            count++
            send("pin('LED', pin.OUT, value=${count % 2})")
        }
        return binding.root
    }
}

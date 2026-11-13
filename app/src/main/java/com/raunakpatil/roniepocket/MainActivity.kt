package com.raunakpatil.roniepocket

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.lifecycleScope
import com.raunakpatil.roniepocket.vision.ImageImport
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    /** Bumped each time Ronie is summoned as the assistant, so the chat focuses the message box. */
    private val assistRequests = mutableIntStateOf(0)
    /** Text or a link shared into Ronie from another app ("Ask Ronie" card). */
    private val shared = mutableStateOf<SharedItem?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Light status/navigation icons on the dark canvas, whatever the system theme.
        enableEdgeToEdge(androidx.activity.SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            androidx.activity.SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))
        handle(intent)
        setContent {
            // Always dark: Ronie's look (ui/Theme.kt). The Surface sets the default text colour for everything inside.
            com.raunakpatil.roniepocket.ui.RonieTheme {
                androidx.compose.material3.Surface(color = com.raunakpatil.roniepocket.ui.R.bg,
                    contentColor = com.raunakpatil.roniepocket.ui.R.text) {
                    ChatScreen(assistRequest = assistRequests.intValue, shared = shared.value, onSharedUsed = { shared.value = null })
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(intent: Intent?) {
        when (intent?.action) {
            Intent.ACTION_ASSIST -> assistRequests.intValue++
            Intent.ACTION_SEND -> {
                val type = intent.type ?: ""
                if (type.startsWith("image/")) {
                    val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri
                    }
                    if (uri != null) {
                        lifecycleScope.launch {
                            val file = ImageImport.importFromUri(this@MainActivity, uri)
                            if (file != null) {
                                shared.value = SharedItem(text = "", images = listOf(file.absolutePath))
                            }
                        }
                    }
                } else {
                    val text = intent.getStringExtra(Intent.EXTRA_TEXT)?.trim()
                    if (!text.isNullOrEmpty()) {
                        shared.value = SharedItem(text, intent.getStringExtra(Intent.EXTRA_SUBJECT)?.takeIf { it.isNotBlank() && it != text })
                    }
                }
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                val type = intent.type ?: ""
                if (type.startsWith("image/")) {
                    val uris = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM)
                    }
                    if (!uris.isNullOrEmpty()) {
                        if (uris.size > MAX_PHOTOS) {
                            Toast.makeText(this, "Ronie can look at $MAX_PHOTOS photos at a time, so only the first $MAX_PHOTOS were attached", Toast.LENGTH_LONG).show()
                        }
                        val toImport = uris.take(MAX_PHOTOS)
                        lifecycleScope.launch {
                            val files = toImport.mapNotNull { ImageImport.importFromUri(this@MainActivity, it) }
                            if (files.isNotEmpty()) {
                                shared.value = SharedItem(text = "", images = files.map { it.absolutePath })
                            }
                        }
                    }
                }
            }
        }
    }
}

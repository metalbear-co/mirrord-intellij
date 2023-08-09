package com.metalbear.mirrord

import com.intellij.notification.NotificationType
import com.intellij.openapi.components.service
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogBuilder
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.util.ui.JBUI
import java.awt.Dimension
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.*
import javax.swing.*
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

private const val SIGNUP_ENDPOINT = "https://waitlist.metalbear.co/v1/waitlist"

/**
 * `java.net.http.HttpRequest.BodyPublishers` does not have a dedicated publisher for the multipart forms.
 */
private fun HttpRequest.Builder.postMultipartFormData(data: Iterable<Pair<String, String>>): HttpRequest.Builder {
    val boundary = UUID.randomUUID().toString()
    val byteArrays = mutableListOf<ByteArray>()
    val separator = "--$boundary\r\nContent-Disposition: form-data; name=".toByteArray(StandardCharsets.UTF_8)

    data.forEach {
        byteArrays.add(separator)
        byteArrays.add("\"${it.first}\"\r\n\r\n${it.second}\r\n".toByteArray(StandardCharsets.UTF_8))
    }

    byteArrays.add("--$boundary--".toByteArray(StandardCharsets.UTF_8))

    this
        .header("Content-Type", "multipart/form-data;boundary=$boundary")
        .POST(HttpRequest.BodyPublishers.ofByteArrays(byteArrays))

    return this
}

class MirrordWaitlistDialog(private val project: Project) {
    /**
     * Background task for making the signup request.
     */
    private class SignupTask(project: Project, private val email: String) : Task.Backgroundable(project, "mirrord", true) {
        private val service: MirrordProjectService = project.service()

        override fun run(indicator: ProgressIndicator) {
            indicator.text = "making the signup request..."

            val client = HttpClient.newHttpClient()
            val request = HttpRequest
                .newBuilder(URI(SIGNUP_ENDPOINT))
                .timeout(Duration.ofSeconds(5))
                .postMultipartFormData(listOf(Pair("email", email)))
                .build()

            val response = client.send(request, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() != 200) {
                throw RuntimeException("invalid response status ${response.statusCode()}")
            }
        }

        override fun onThrowable(error: Throwable) {
            MirrordLogger.logger.debug("waitlist signup failed", error)
            service.notifier.notifyRichError("Failed to join the waitlist: ${error.message}")
        }

        override fun onSuccess() {
            service.notifier.notifySimple(
                "Thank you for joining the waitlist for mirrord for Teams! We'll be in touch soon.",
                NotificationType.INFORMATION
            )
        }
    }

    /**
     * Display a simple dialog with email input.
     * Waitlist signup is requested when the user presses the "Ok" button.
     */
    fun show() {
        val dialog = DialogBuilder()

        val textField = JTextField().apply {
            val field = this
            document.addDocumentListener(object : DocumentListener {
                override fun insertUpdate(e: DocumentEvent) = doUpdate()
                override fun removeUpdate(e: DocumentEvent) = doUpdate()
                override fun changedUpdate(e: DocumentEvent) = doUpdate()

                private fun doUpdate() {
                    dialog.okActionEnabled(field.text.isNotEmpty())
                }
            })
        }

        dialog.apply {
            val panel = JPanel().apply {
                layout = BoxLayout(this, BoxLayout.X_AXIS)
                border = JBUI.Borders.empty(10, 5)
                add(JLabel("Email Address:"))
                add(Box.createRigidArea(Dimension(10, 0)))
                add(
                    textField.apply {
                        minimumSize = Dimension(250, 50)
                    }
                )
            }
            setCenterPanel(panel)
            setTitle("mirrord for Teams Waitlist Signup")
            okActionEnabled(false)
        }

        if (dialog.show() != DialogWrapper.OK_EXIT_CODE) {
            return
        }

        val email = textField.text
        SignupTask(project, email).queue()
    }
}

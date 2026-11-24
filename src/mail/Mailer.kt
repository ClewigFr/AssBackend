package com.ass.mail

import jakarta.mail.Message
import jakarta.mail.Session
import jakarta.mail.Transport
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.util.Properties
import java.util.concurrent.CopyOnWriteArrayList

/** Sends the account emails (password reset). */
interface Mailer {
    suspend fun send(to: String, subject: String, body: String)
}

data class SmtpSettings(
    val host: String,
    val port: Int = 465,
    /** ssl (implicit TLS, port 465), starttls (port 587) or off */
    val tls: String = "ssl",
    val user: String? = null,
    val password: String? = null,
    val from: String
)

class SmtpMailer(private val settings: SmtpSettings) : Mailer {
    private val session: Session = Session.getInstance(Properties().apply {
        put("mail.smtp.host", settings.host)
        put("mail.smtp.port", settings.port.toString())
        put("mail.smtp.auth", (settings.user != null).toString())
        put("mail.smtp.connectiontimeout", "10000")
        put("mail.smtp.timeout", "10000")
        put("mail.smtp.writetimeout", "10000")
        when (settings.tls) {
            "ssl" -> { put("mail.smtp.ssl.enable", "true"); put("mail.smtp.ssl.checkserveridentity", "true") }
            "starttls" -> { put("mail.smtp.starttls.enable", "true"); put("mail.smtp.starttls.required", "true"); put("mail.smtp.ssl.checkserveridentity", "true") }
        }
    })

    override suspend fun send(to: String, subject: String, body: String) = withContext(Dispatchers.IO) {
        val message = MimeMessage(session).apply {
            setFrom(InternetAddress(settings.from))
            setRecipient(Message.RecipientType.TO, InternetAddress(to))
            setSubject(subject, "UTF-8")
            setText(body, "UTF-8")
        }
        if (settings.user != null) Transport.send(message, settings.user, settings.password)
        else Transport.send(message)
    }
}

/**
 * Without SMTP settings: in dev, logs the emails (and keeps them, for the tests); outside dev,
 * only warns, because the email holds a secret link.
 */
class LogMailer(private val logBody: Boolean) : Mailer {
    data class Sent(val to: String, val subject: String, val body: String)
    private val log = LoggerFactory.getLogger("Mailer")
    val sent = CopyOnWriteArrayList<Sent>()

    override suspend fun send(to: String, subject: String, body: String) {
        if (logBody) {
            sent += Sent(to, subject, body)
            log.info("Email to {} (SMTP_HOST not set, not sent): {}\n{}", to, subject, body)
        } else {
            log.warn("Email to {} not sent: set SMTP_HOST to send emails ({})", to, subject)
        }
    }
}

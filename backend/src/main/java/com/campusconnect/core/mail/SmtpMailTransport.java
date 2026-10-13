package com.campusconnect.core.mail;

import jakarta.mail.internet.MimeMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;

/** SMTP through Spring's JavaMailSender (spring.mail.*). */
public class SmtpMailTransport implements MailTransport {

    private final JavaMailSender sender;
    private final String from;

    public SmtpMailTransport(JavaMailSender sender, String from) {
        this.sender = sender;
        this.from = from;
    }

    @Override
    public String mode() {
        return "smtp";
    }

    @Override
    public void send(MailMessage message) throws Exception {
        MimeMessage mime = sender.createMimeMessage();
        MimeMessageHelper helper = new MimeMessageHelper(mime, MimeMessageHelper.MULTIPART_MODE_MIXED_RELATED, "UTF-8");
        helper.setFrom(from);
        helper.setTo(message.to());
        helper.setSubject(message.subject());
        helper.setText(message.text(), message.html());
        if (message.headers() != null) {
            for (var header : message.headers().entrySet()) {
                mime.setHeader(header.getKey(), header.getValue());
            }
        }
        sender.send(mime);
    }
}

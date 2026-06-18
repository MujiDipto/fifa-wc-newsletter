package com.muji.worldcup.delivery;

import com.muji.worldcup.model.ComposedEmail;
import jakarta.mail.*;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Properties;

public class EmailSender {

    private static final Logger log = LoggerFactory.getLogger(EmailSender.class);

    private final Session session;
    private final String from;

    public EmailSender(String host, int port, String username, String password) {
        this.from = username;

        Properties props = new Properties();
        props.put("mail.smtp.host", host);
        props.put("mail.smtp.port", String.valueOf(port));
        props.put("mail.smtp.auth", "true");
        props.put("mail.smtp.starttls.enable", "true");

        this.session = Session.getInstance(props, new Authenticator() {
            @Override
            protected PasswordAuthentication getPasswordAuthentication() {
                return new PasswordAuthentication(username, password);
            }
        });
    }

    public void send(String to, ComposedEmail email) throws MessagingException {
        MimeMessage message = new MimeMessage(session);
        message.setFrom(new InternetAddress(from));
        message.setRecipients(Message.RecipientType.TO, InternetAddress.parse(to));
        message.setSubject(email.subject());
        message.setContent(email.body(), "text/html; charset=utf-8");

        Transport.send(message);
        log.info("Sent email to {} — subject: {}", to, email.subject());
    }
}

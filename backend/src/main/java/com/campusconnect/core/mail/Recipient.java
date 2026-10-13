package com.campusconnect.core.mail;

/** Who an e-mail is for (the Node code's lean {@code { _id, name, email }} user). */
public record Recipient(String id, String name, String email) {
}

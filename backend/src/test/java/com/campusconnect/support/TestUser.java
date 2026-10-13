package com.campusconnect.support;

/** A user created directly in the database, with a ready-to-use access token. */
public record TestUser(String id, String name, String email, String globalRole, String departmentCode, String batchCode, String token) {
}

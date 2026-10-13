package com.campusconnect.core.events;

/**
 * Someone joined or left a club (approved, removed, left). Published synchronously by the membership service;
 * the chat feature keeps the club's group chat in sync (ChatService.clubMembershipChanged).
 */
public record ClubMembershipChanged(String clubId, String userId) {
}

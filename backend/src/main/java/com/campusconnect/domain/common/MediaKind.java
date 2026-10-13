package com.campusconnect.domain.common;

/**
 * Kinds of stored media (services/MediaStore.js KINDS). Each document accepts a subset: gallery items and stories
 * IMAGE or VIDEO, chat attachments all four, recruitment answer files IMAGE or DOCUMENT.
 */
public enum MediaKind {
    IMAGE,
    VIDEO,
    AUDIO,
    DOCUMENT
}

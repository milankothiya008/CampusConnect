package com.campusconnect.feature.chat;

import com.campusconnect.core.config.AppProperties;
import com.campusconnect.core.error.AppException;
import com.campusconnect.core.error.ErrorCode;
import com.campusconnect.core.media.MediaStore;
import com.campusconnect.core.media.MediaStores;
import com.campusconnect.core.security.AuthUser;
import com.campusconnect.domain.common.MediaKind;
import com.campusconnect.feature.chat.ChatAccess.Loaded;
import java.io.IOException;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

/**
 * Chat photos, videos, voice notes and documents (ChatService uploadTicket / uploadLocal, ChatMediaService): a signed
 * ticket lets the browser upload straight to Cloudinary (or to {@code /api/chat/media} in development), issued for one
 * chat and one person, so a file can only be attached in the chat it was uploaded for, by whoever uploaded it.
 */
@Service
public class ChatUploads {

    /** {@code { provider: "local", token, kind, bytes, format }}: what the browser attaches when sending. */
    public record LocalUpload(String provider, String token, MediaKind kind, long bytes, String format) {
    }

    private final ChatAccess access;
    private final MediaStore store;
    private final AppProperties properties;

    public ChatUploads(ChatAccess access, MediaStores stores, AppProperties properties) {
        this.access = access;
        this.store = stores.chat();
        this.properties = properties;
    }

    /** Someone who can't send here may still upload a group photo if they are an admin. */
    public MediaStore.UploadTicket uploadTicket(AuthUser actor, String conversationId, String kind, String ext) {
        Loaded loaded = access.loadAccessible(actor.id(), conversationId);
        String blocked = access.sendBlockedReason(actor.id(), loaded.conversation(), loaded.access());
        if (blocked != null && !(loaded.access().isAdmin() && "IMAGE".equals(kind))) {
            throw AppException.forbidden(blocked);
        }
        return store.createUploadTicket(loaded.conversation().getId(), actor.id(), MediaKind.valueOf(kind), ext);
    }

    /** The largest file the chat accepts at all (each kind then has its own limit). */
    private long maxUploadBytes() {
        AppProperties.Chat chat = properties.getChat();
        return Math.max(Math.max(chat.getMaxImageBytes(), chat.getMaxVideoBytes()), Math.max(chat.getMaxDocumentBytes(), chat.getMaxAudioBytes()));
    }

    /** Development storage: the file itself is posted here, then attached on send with the returned token. */
    public LocalUpload uploadLocal(AuthUser actor, String conversationId, MultipartFile file, String kind, String ext) {
        if (file != null && file.getSize() > maxUploadBytes()) {
            throw AppException.of(413, "This file is too large to send", ErrorCode.UPLOAD_ERROR);
        }
        Loaded loaded = access.loadAccessible(actor.id(), conversationId);
        byte[] bytes;
        try {
            bytes = file == null ? null : file.getBytes();
        } catch (IOException e) {
            throw AppException.badRequest("Invalid upload", ErrorCode.UPLOAD_ERROR);
        }
        MediaKind hint = kind == null ? null : MediaKind.valueOf(kind);
        MediaStore.LocalUpload saved = store.saveLocalFile(bytes, file == null ? null : file.getOriginalFilename(), loaded.conversation().getId(), actor.id(), hint, ext);
        return new LocalUpload("local", saved.token(), saved.kind(), saved.bytes(), saved.format());
    }
}

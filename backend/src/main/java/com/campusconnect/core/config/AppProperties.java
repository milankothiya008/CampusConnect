package com.campusconnect.core.config;

import java.time.Duration;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Application settings ({@code app.*}), the Java counterpart of config/env.js. Every value can be set from the
 * environment (e.g. {@code APP_CLIENT_URL}); defaults suit local development.
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "app")
public class AppProperties {

    /** Origin of the React app (CORS, links in e-mails). */
    private String clientUrl = "http://localhost:3000";
    /** Public origin of this API when it differs from the client (absolute links to /api resources). */
    private String publicApiUrl = "";

    private final Jwt jwt = new Jwt();
    private final University university = new University();
    private final BootstrapAdmin bootstrapAdmin = new BootstrapAdmin();
    private final Mail mail = new Mail();
    private final Cloudinary cloudinary = new Cloudinary();
    private final Push push = new Push();
    private final Uploads uploads = new Uploads();
    private final MediaLimits stories = new MediaLimits(10 * MB, 40 * MB, 30, 30);
    private final MediaLimits gallery = new MediaLimits(15 * MB, 80 * MB, 90, 30);
    private final Chat chat = new Chat();
    private final MediaLimits recruitment = new MediaLimits(5 * MB, 0, 0, 0);
    private final RateLimit rateLimit = new RateLimit();
    private final Realtime realtime = new Realtime();
    private final Jobs jobs = new Jobs();
    private final Seed seed = new Seed();

    static final long MB = 1024L * 1024L;

    @Getter
    @Setter
    public static class Jwt {
        /** HS256 secret for access tokens (at least 32 bytes). */
        private String accessSecret;
        /** Secret for refresh tokens, OTP hashes' sibling keys and signed links. */
        private String refreshSecret;
        private Duration accessTokenTtl = Duration.ofMinutes(15);
        private Duration refreshTokenTtl = Duration.ofDays(7);
        /** Secure flag of the refresh cookie (true in production; false only for plain-http local dev). */
        private boolean cookieSecure = true;
        /** SameSite of the refresh cookie: Strict when frontend and API share one origin; None for split hosting. */
        private String cookieSameSite = "Strict";
    }

    @Getter
    @Setter
    public static class University {
        private String domain = "ddu.ac.in";
        private String name = "Dharmsinh Desai University";
        /** Event dates and times are entered as university wall-clock time. */
        private String timezoneOffset = "+05:30";
    }

    @Getter
    @Setter
    public static class BootstrapAdmin {
        private String email = "";
        private String password = "";
        private String name = "University Admin";
    }

    @Getter
    @Setter
    public static class Mail {
        /** auto (brevo when the key is set, else smtp when spring.mail.host is set, else preview — disabled in prod), smtp, brevo, preview, disabled. */
        private String mode = "auto";
        private String from = "CampusConnect <no-reply@campusconnect.local>";
        /** Brevo's HTTPS e-mail API key, for hosts that block outgoing SMTP. */
        private String brevoApiKey = "";
        /** Cluster-wide sending rate for queued e-mails. */
        private int ratePerMinute = 30;
    }

    @Getter
    @Setter
    public static class Cloudinary {
        private String cloudName = "";
        private String apiKey = "";
        private String apiSecret = "";
        private String folder = "campusconnect";

        public boolean isConfigured() {
            return !cloudName.isBlank() && !apiKey.isBlank() && !apiSecret.isBlank();
        }
    }

    @Getter
    @Setter
    public static class Push {
        /** VAPID keys (base64url). Generate with: npx web-push generate-vapid-keys */
        private String publicKey = "";
        private String privateKey = "";
        private String subject = "mailto:admin@campusconnect.local";
        /** Browser push services subscriptions may point to (prevents server-side request forgery). */
        private java.util.List<String> allowedHosts = new java.util.ArrayList<>(java.util.List.of(
                "fcm.googleapis.com", "android.googleapis.com", "push.services.mozilla.com", "notify.windows.com", "push.apple.com"));

        public boolean isConfigured() {
            return !publicKey.isBlank() && !privateKey.isBlank();
        }
    }

    @Getter
    @Setter
    public static class Uploads {
        /** Local storage folder used when Cloudinary is not configured (served at /uploads). */
        private String dir = "uploads";
        private long maxBytes = 5 * MB;
    }

    @Getter
    @Setter
    public static class MediaLimits {
        private long maxImageBytes;
        private long maxVideoBytes;
        private int maxVideoSeconds;
        /** Stories: active stories per club. Gallery: pending uploads per person per event. */
        private int maxPerOwner;
        /** Stories only: how long a story stays up. */
        private int lifetimeHours = 24;

        public MediaLimits() {
        }

        MediaLimits(long maxImageBytes, long maxVideoBytes, int maxVideoSeconds, int maxPerOwner) {
            this.maxImageBytes = maxImageBytes;
            this.maxVideoBytes = maxVideoBytes;
            this.maxVideoSeconds = maxVideoSeconds;
            this.maxPerOwner = maxPerOwner;
        }
    }

    @Getter
    @Setter
    public static class Chat {
        private long maxImageBytes = 15 * MB;
        private long maxVideoBytes = 50 * MB;
        private int maxVideoSeconds = 180;
        private long maxAudioBytes = 10 * MB;
        private int maxAudioSeconds = 600;
        private long maxDocumentBytes = 25 * MB;
        /** How long the sender can still edit a message. */
        private int editMinutes = 15;
        private int maxGroupMembers = 256;
    }

    @Getter
    @Setter
    public static class RateLimit {
        private boolean enabled = true;
        private Duration window = Duration.ofMinutes(15);
        /** Every /api call, per signed-in user (or per IP when anonymous). */
        private int api = 2000;
        /** Sign-in, sign-up, codes, refresh — per IP. */
        private int auth = 100;
        /** Uploads — per user. */
        private int upload = 120;
        /** Wrong passwords for one account, from any address (forged or rotating addresses don't reset it). */
        private int signInFailures = 10;
    }

    @Getter
    @Setter
    public static class Realtime {
        /** relay (RabbitMQ STOMP — required with several replicas) or simple (in-memory, single node / tests). */
        private String broker = "relay";
        private String relayHost = "localhost";
        private int relayPort = 61613;
        private String relayLogin = "guest";
        private String relayPasscode = "guest";
        private String relayVirtualHost = "/";
    }

    @Getter
    @Setter
    public static class Jobs {
        /** rabbit (queued, retried, rate-limited) or direct (run in-process right away — tests and tiny setups). */
        private String mode = "rabbit";
        /** Waits before re-trying a failed e-mail (after the 1st, 2nd, ... failure); the last one repeats. */
        private java.util.List<Duration> mailRetryDelays = new java.util.ArrayList<>(java.util.List.of(
                Duration.ofMinutes(1), Duration.ofMinutes(5), Duration.ofMinutes(15), Duration.ofMinutes(60)));
        /** Deliveries before an e-mail is given up on. */
        private int mailMaxAttempts = 5;
    }

    @Getter
    @Setter
    public static class Seed {
        /** Load departments, batches and venues when the database is empty. */
        private boolean referenceData = true;
        /** Load the demo clubs, people and events (never in production). */
        private boolean demo = false;
        /** Create this many load-test students plus clubs and events (load-tests/; staging only). */
        private int loadTestUsers = 0;
        /** The load-test seeder refuses the prod profile unless this is set (staging that runs the prod profile). */
        private boolean loadTestAllowProduction = false;
    }
}

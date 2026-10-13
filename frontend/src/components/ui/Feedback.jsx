import { AlertCircle, AlertTriangle, CheckCircle2, Info, Inbox, RefreshCw, ShieldAlert } from "lucide-react";
import { ContentSkeleton } from "./Skeletons";

export const Spinner = ({ size }) => <span className={`spinner ${size === "sm" ? "spinner-sm" : ""}`} role="status" aria-label="Loading" />;

export const PageLoader = ({ label = "Loading…" }) => (
    <div className="state">
        <Spinner />
        <span className="small">{label}</span>
    </div>
);

export const EmptyState = ({ icon: Icon = Inbox, title, description, action }) => (
    <div className="state">
        <div className="state-icon">
            <Icon size={24} />
        </div>
        {title && <h3>{title}</h3>}
        {description && <p>{description}</p>}
        {action}
    </div>
);

export const ErrorState = ({ error, onRetry, title }) => {
    const forbidden = error?.status === 403;
    const notFound = error?.status === 404;
    const Icon = forbidden ? ShieldAlert : AlertCircle;

    return (
        <div className="state state-error" role="alert">
            <div className="state-icon">
                <Icon size={24} />
            </div>
            <h3>{title || (forbidden ? "You don't have access" : notFound ? "Not found" : "Something went wrong")}</h3>
            <p>{error?.message || "We couldn't load this. Please try again."}</p>
            {onRetry && !forbidden && !notFound && (
                <button type="button" className="btn btn-secondary btn-sm" onClick={() => onRetry()}>
                    <RefreshCw size={14} /> Try again
                </button>
            )}
        </div>
    );
};

const ALERT_ICONS = { error: AlertCircle, success: CheckCircle2, info: Info, warning: AlertTriangle };

export const Alert = ({ type = "info", title, children }) => {
    const Icon = ALERT_ICONS[type];
    return (
        <div className={`alert alert-${type}`} role={type === "error" ? "alert" : undefined}>
            <Icon size={18} />
            <div>
                {title && <strong style={{ display: "block", marginBottom: children ? 2 : 0 }}>{title}</strong>}
                {children}
            </div>
        </div>
    );
};

// Renders an API error, including venue-conflict details when present.
export const ApiErrorAlert = ({ error }) => {
    if (!error) {
        return null;
    }
    return (
        <Alert type="error" title={error.code === "VENUE_CONFLICT" ? "Venue conflict" : undefined}>
            {error.message}
        </Alert>
    );
};

// Standard wrapper for the loading → error → empty → content sequence. While loading it shows `skeleton` —
// a placeholder shaped like the content (see Skeletons.jsx) — or a few placeholder lines by default.
export const AsyncContent = ({ loading, error, onRetry, isEmpty, empty, skeleton, children }) => {
    if (loading) {
        return skeleton || <ContentSkeleton />;
    }
    if (error) {
        return <ErrorState error={error} onRetry={onRetry} />;
    }
    if (isEmpty) {
        return empty || <EmptyState title="Nothing here yet" />;
    }
    return children;
};

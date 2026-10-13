// Content-shaped placeholders shown while data loads, instead of a spinner. Each one is built from the same
// layout classes as the content it stands in for (cards, list rows, the detail layout, chat rows…), so the
// page keeps its shape when the real content arrives. Colours follow the theme (styles/skeleton.css) and the
// shimmer stops when the system asks for reduced motion.

const TITLES = ["62%", "48%", "71%", "55%", "66%", "44%"];
const LINES = ["84%", "68%", "76%", "58%", "80%", "64%"];
const pick = (list, index) => list[index % list.length];
const times = (count, render) => Array.from({ length: count }, (_, index) => render(index));

/** One placeholder shape. `width`/`height` take any CSS length; null leaves the size to CSS. */
export const Skeleton = ({ width = "100%", height = 16, radius, circle = false, className = "", style }) => (
    <span
        className={`skeleton${circle ? " sk-circle" : ""}${className ? ` ${className}` : ""}`}
        style={{
            ...(width === null ? null : { width }),
            ...(height === null ? null : { height }),
            ...(radius === undefined ? null : { borderRadius: radius }),
            ...style
        }}
        aria-hidden="true"
    />
);

/** A few lines of text; the last one is shorter. */
export const SkeletonLines = ({ lines = 3, height = 12, gap = 8, widths = LINES, last = "55%" }) => (
    <span className="sk-lines" style={{ gap }}>
        {times(lines, (index) => (
            <Skeleton key={index} height={height} radius={6} width={lines > 1 && index === lines - 1 ? last : pick(widths, index)} />
        ))}
    </span>
);

/** The loading region: screen readers hear "Loading" once; the shapes themselves are hidden from them. */
export const SkeletonRegion = ({ label = "Loading", className = "", children }) => (
    <div className={`skeleton-region${className ? ` ${className}` : ""}`} role="status" aria-busy="true" aria-label={label}>
        {children}
    </div>
);

// ---------------------------------------------------------------- Building blocks (no region of their own)

const PageHeaderShape = ({ back = false, actions = 0, meta = false }) => (
    <div className="page-header sk-page-header" aria-hidden="true">
        {back && <Skeleton circle width={32} height={32} />}
        <span className="page-header-title">
            <Skeleton width="min(340px, 70%)" height={22} radius={6} />
            {meta && <Skeleton width="min(220px, 45%)" height={12} radius={6} />}
        </span>
        {actions > 0 && (
            <span className="row page-header-actions">
                {times(actions, (index) => (
                    <Skeleton key={index} width={index ? 88 : 116} height={36} radius={8} />
                ))}
            </span>
        )}
    </div>
);

const TabsShape = ({ count = 4 }) => (
    <div className="tabs sk-tabs" aria-hidden="true">
        {times(count, (index) => (
            <span key={index} className="tab">
                <Skeleton width={pick([64, 86, 72, 94, 58], index)} height={12} radius={6} />
            </span>
        ))}
    </div>
);

const SectionShape = ({ lines = 3, title = true }) => (
    <div className="card" aria-hidden="true">
        {title && (
            <div className="card-header">
                <Skeleton width="34%" height={15} radius={6} />
            </div>
        )}
        <div className="card-body">
            <SkeletonLines lines={lines} />
        </div>
    </div>
);

const ListShape = ({ rows = 5, avatar = "circle", size = 36, trailing = true, className = "list-rows", rowClassName = "list-row" }) => (
    <div className={className} aria-hidden="true">
        {times(rows, (index) => (
            <div key={index} className={`${rowClassName} sk-list-row`}>
                {avatar && <Skeleton width={size} height={size} radius={avatar === "square" ? 10 : "50%"} className="sk-fixed" />}
                <span className="sk-lines sk-grow" style={{ gap: 7 }}>
                    <Skeleton width={pick(TITLES, index)} height={13} radius={6} />
                    <Skeleton width={pick(LINES, index + 2)} height={11} radius={6} />
                </span>
                {trailing && <Skeleton width={64} height={22} radius={999} className="sk-fixed" />}
            </div>
        ))}
    </div>
);

const MediaCardShape = ({ index, variant }) => (
    <div className={`card sk-card is-${variant}`} aria-hidden="true">
        <Skeleton className="sk-card-media" height={null} radius={0} />
        <div className="sk-card-body">
            {variant === "result" ? (
                <span className="sk-row">
                    <Skeleton width={28} height={28} radius={8} />
                    <Skeleton width="42%" height={11} radius={6} />
                </span>
            ) : (
                <Skeleton width="38%" height={11} radius={6} />
            )}
            <Skeleton width={pick(TITLES, index)} height={17} radius={6} />
            <SkeletonLines lines={variant === "result" ? 2 : 3} height={11} gap={7} />
            <span className="sk-card-foot">
                <Skeleton width={96} height={22} radius={999} />
                {variant === "result" && <Skeleton width={84} height={12} radius={6} />}
            </span>
        </div>
    </div>
);

const ClubCardShape = ({ index }) => (
    <div className="card sk-card is-club" aria-hidden="true">
        <Skeleton height={86} radius={0} />
        <div className="sk-card-body">
            <span className="sk-club-head">
                <Skeleton className="sk-club-logo" width={56} height={56} radius={12} />
                <span className="sk-lines sk-grow" style={{ gap: 8 }}>
                    <Skeleton width={pick(TITLES, index)} height={16} radius={6} />
                    <span className="sk-row" style={{ gap: 6 }}>
                        <Skeleton width={72} height={20} radius={999} />
                        <Skeleton width={52} height={20} radius={999} />
                    </span>
                </span>
            </span>
            <SkeletonLines lines={2} height={11} gap={7} />
            <span className="sk-card-foot">
                <Skeleton width={92} height={12} radius={6} />
                <Skeleton width={64} height={22} radius={999} />
            </span>
        </div>
    </div>
);

const PlainCardShape = ({ index }) => (
    <div className="card card-body sk-plain-card" aria-hidden="true">
        <span className="sk-row">
            <Skeleton width={40} height={40} radius="50%" className="sk-fixed" />
            <span className="sk-lines sk-grow" style={{ gap: 7 }}>
                <Skeleton width={pick(TITLES, index)} height={14} radius={6} />
                <Skeleton width={pick(LINES, index + 1)} height={11} radius={6} />
            </span>
        </span>
        <SkeletonLines lines={2} height={11} gap={7} />
    </div>
);

const StatShape = () => (
    <div className="card stat" aria-hidden="true">
        <div className="stat-top">
            <Skeleton width="45%" height={11} radius={6} />
            <Skeleton width={30} height={30} radius={10} />
        </div>
        <Skeleton width="35%" height={26} radius={6} />
        <Skeleton width="55%" height={10} radius={6} />
    </div>
);

const PostShape = () => (
    <div className="card post-skeleton" aria-hidden="true">
        <div className="sk-row sk-post-head">
            <Skeleton circle width={40} height={40} />
            <span className="sk-lines sk-grow" style={{ gap: 6 }}>
                <Skeleton width="40%" height={12} radius={6} />
                <Skeleton width="25%" height={10} radius={6} />
            </span>
        </div>
        <Skeleton className="sk-post-media" height={null} radius={0} />
        <div className="sk-post-body">
            <Skeleton width="60%" height={16} radius={6} />
            <SkeletonLines lines={2} last="75%" widths={["90%"]} />
        </div>
    </div>
);

// ---------------------------------------------------------------- Ready-made skeletons

/** The default for AsyncContent: a heading and a few lines. */
export const ContentSkeleton = ({ lines = 3 }) => (
    <SkeletonRegion className="sk-content">
        <Skeleton width="38%" height={18} radius={6} />
        <SkeletonLines lines={lines} />
    </SkeletonRegion>
);

/** Any page while its code loads: a title row and two sections. */
export const PageSkeleton = () => (
    <SkeletonRegion className="sk-page">
        <PageHeaderShape />
        <SectionShape lines={4} />
        <SectionShape lines={3} />
    </SkeletonRegion>
);

/** One card: an optional title and a few lines. */
export const SectionSkeleton = ({ lines = 3, title = true }) => (
    <SkeletonRegion>
        <SectionShape lines={lines} title={title} />
    </SkeletonRegion>
);

/** A grid of cards: event cards (default), club cards, result/gallery cards or plain cards. */
export const CardGridSkeleton = ({ count = 6, variant = "event", className }) => (
    <SkeletonRegion className={className || (variant === "result" ? "result-grid" : "grid-cards")}>
        {times(count, (index) =>
            variant === "club" ? (
                <ClubCardShape key={index} index={index} />
            ) : variant === "plain" ? (
                <PlainCardShape key={index} index={index} />
            ) : (
                <MediaCardShape key={index} index={index} variant={variant} />
            )
        )}
    </SkeletonRegion>
);

/** Cards one under another (reports, announcements, applications). */
export const CardListSkeleton = ({ count = 3 }) => (
    <SkeletonRegion className="stack">
        {times(count, (index) => (
            <PlainCardShape key={index} index={index} />
        ))}
    </SkeletonRegion>
);

/** Rows with a picture, a title and a line of detail. `className`/`rowClassName` pick the list's own styles. */
export const ListSkeleton = ({ rows = 5, avatar = "circle", size = 36, trailing = true, className = "list-rows", rowClassName = "list-row", card = false }) => {
    const list = <ListShape rows={rows} avatar={avatar} size={size} trailing={trailing} className={className} rowClassName={rowClassName} />;
    return <SkeletonRegion className={card ? "card" : ""}>{list}</SkeletonRegion>;
};

/** A table: a header row and `rows` rows; the first column has a picture and two lines. */
export const TableSkeleton = ({ rows = 6, columns = 5 }) => (
    <SkeletonRegion className="table-wrap">
        <table className="table sk-table" aria-hidden="true">
            <thead>
                <tr>
                    {times(columns, (column) => (
                        <th key={column}>
                            <Skeleton width={column ? 56 : 80} height={10} radius={5} />
                        </th>
                    ))}
                </tr>
            </thead>
            <tbody>
                {times(rows, (row) => (
                    <tr key={row}>
                        {times(columns, (column) => (
                            <td key={column}>
                                {column === 0 ? (
                                    <span className="sk-row sk-table-lead">
                                        <Skeleton circle width={28} height={28} />
                                        <span className="sk-lines sk-grow" style={{ gap: 6 }}>
                                            <Skeleton width={pick([150, 120, 170, 135], row)} height={12} radius={6} />
                                            <Skeleton width={pick([110, 140, 90, 120], row)} height={10} radius={6} />
                                        </span>
                                    </span>
                                ) : (
                                    <Skeleton width={pick([84, 60, 96, 52, 72], row + column)} height={12} radius={6} />
                                )}
                            </td>
                        ))}
                    </tr>
                ))}
            </tbody>
        </table>
    </SkeletonRegion>
);

/** A detail page (event, drive, request, election): title row, the picture and sections, and the side column. */
export const DetailSkeleton = ({ header = true, media = "16 / 7", aside = true }) => (
    <SkeletonRegion className="sk-page">
        {header && <PageHeaderShape back actions={2} />}
        <div className={aside ? "detail-layout aside-first-mobile" : "stack-lg"}>
            <div className="stack-lg">
                {media && <Skeleton className="sk-poster" height={null} style={{ aspectRatio: media }} />}
                <SectionShape lines={4} />
                <SectionShape lines={3} />
            </div>
            {aside && (
                <aside className="stack" aria-hidden="true">
                    <div className="card card-body sk-meta">
                        {times(5, (index) => (
                            <span key={index} className="sk-row">
                                <Skeleton circle width={18} height={18} />
                                <span className="sk-lines sk-grow" style={{ gap: 6 }}>
                                    <Skeleton width="36%" height={10} radius={5} />
                                    <Skeleton width={pick(["72%", "58%", "80%", "64%"], index)} height={12} radius={6} />
                                </span>
                            </span>
                        ))}
                    </div>
                    <Skeleton height={46} radius={10} />
                    <SectionShape lines={2} />
                </aside>
            )}
        </div>
    </SkeletonRegion>
);

/** A form page: title row, optional step dots, a card of fields and the buttons. */
export const FormSkeleton = ({ steps = 0, fields = 4, header = true }) => (
    <SkeletonRegion className="sk-page">
        {header && <PageHeaderShape back />}
        {steps > 0 && (
            <div className="sk-stepper" aria-hidden="true">
                {times(steps, (index) => (
                    <span key={index} className="sk-step">
                        <Skeleton circle width={28} height={28} />
                        <Skeleton width={64} height={10} radius={5} />
                    </span>
                ))}
            </div>
        )}
        <div className="card" aria-hidden="true">
            <div className="card-header">
                <Skeleton width="30%" height={16} radius={6} />
            </div>
            <div className="card-body sk-fields">
                {times(fields, (index) => (
                    <span key={index} className="sk-field">
                        <Skeleton width={pick(["22%", "30%", "18%", "26%"], index)} height={11} radius={5} />
                        <Skeleton height={index === 1 ? 104 : 42} radius={8} />
                    </span>
                ))}
            </div>
        </div>
        <div className="sk-actions" aria-hidden="true">
            <Skeleton width={96} height={40} radius={8} />
            <Skeleton width={128} height={40} radius={8} />
        </div>
    </SkeletonRegion>
);

/** Instagram-style feed posts. */
export const FeedPostSkeleton = ({ count = 2 }) => (
    <SkeletonRegion className="sk-feed">
        {times(count, (index) => (
            <PostShape key={index} />
        ))}
    </SkeletonRegion>
);

/** The square poster grid (Explore). */
export const TileGridSkeleton = ({ count = 9 }) => (
    <SkeletonRegion className="explore-grid">
        {times(count, (index) => (
            <Skeleton key={index} height={null} radius={0} className="sk-tile-square" />
        ))}
    </SkeletonRegion>
);

/** Number tiles (dashboards). */
export const StatTilesSkeleton = ({ count = 3 }) => (
    <SkeletonRegion className="grid-3">
        {times(count, (index) => (
            <StatShape key={index} />
        ))}
    </SkeletonRegion>
);

/** Faculty and admin dashboards: number tiles, then panels of rows. */
export const DashboardSkeleton = ({ header = true, tiles = 3, panels = 2 }) => (
    <SkeletonRegion className="stack-lg">
        {header && <PageHeaderShape />}
        <div className="grid-3">
            {times(tiles, (index) => (
                <StatShape key={index} />
            ))}
        </div>
        <div className="grid-2 sk-panels">
            {times(panels, (index) => (
                <div key={index} className="card">
                    <div className="card-header" aria-hidden="true">
                        <Skeleton width="44%" height={15} radius={6} />
                    </div>
                    <ListShape rows={4} avatar="square" size={28} />
                </div>
            ))}
        </div>
    </SkeletonRegion>
);

/** Someone's profile: the header, tabs and their clubs. */
export const ProfileSkeleton = ({ tabs = 4, tiles = 6 }) => (
    <SkeletonRegion className="profile">
        <div className="profile-head" aria-hidden="true">
            <Skeleton circle className="sk-avatar-xl" width={null} height={null} />
            <div className="profile-head-main">
                <Skeleton width={180} height={22} radius={6} />
                <span className="sk-row" style={{ gap: 24 }}>
                    {times(3, (index) => (
                        <Skeleton key={index} width={72} height={14} radius={6} />
                    ))}
                </span>
                <span className="sk-lines" style={{ gap: 6 }}>
                    <Skeleton width={220} height={13} radius={6} />
                    <Skeleton width={160} height={12} radius={6} />
                </span>
            </div>
        </div>
        {tabs > 0 && <TabsShape count={tabs} />}
        <div className="person-clubs" aria-hidden="true">
            {times(tiles, (index) => (
                <span key={index} className="sk-tile">
                    <Skeleton width={44} height={44} radius={12} className="sk-fixed" />
                    <span className="sk-lines sk-grow" style={{ gap: 6 }}>
                        <Skeleton width={pick(TITLES, index)} height={13} radius={6} />
                        <Skeleton width="40%" height={11} radius={6} />
                    </span>
                    <Skeleton width={56} height={20} radius={999} className="sk-fixed" />
                </span>
            ))}
        </div>
    </SkeletonRegion>
);

/** A page of tabs over cards (the student's profile: Club HQ, schedule, applications, clubs). */
export const TabbedSkeleton = ({ tabs = 4 }) => (
    <SkeletonRegion className="stack-lg">
        <TabsShape count={tabs} />
        <Skeleton height={118} radius="var(--radius-lg)" />
        <div className="card">
            <ListShape rows={3} avatar="square" size={44} />
        </div>
    </SkeletonRegion>
);

/** A club's page: the profile-style header, its tabs and the events grid. */
export const ClubPageSkeleton = () => (
    <SkeletonRegion className="stack-lg">
        <div className="club-head" aria-hidden="true">
            <div className="club-head-avatar">
                <Skeleton circle className="sk-avatar-club" width={null} height={null} />
            </div>
            <div className="club-head-main">
                <Skeleton width="min(260px, 70%)" height={22} radius={6} />
                <span className="sk-row" style={{ gap: 24 }}>
                    {times(3, (index) => (
                        <Skeleton key={index} width={78} height={14} radius={6} />
                    ))}
                </span>
                <SkeletonLines lines={3} height={12} gap={7} widths={["44%", "70%"]} last="36%" />
                <span className="club-head-actions">
                    <Skeleton width={120} height={34} radius={8} />
                    <Skeleton width={34} height={34} radius={8} />
                </span>
            </div>
        </div>
        <TabsShape count={4} />
        <div className="grid-cards">
            {times(3, (index) => (
                <MediaCardShape key={index} index={index} variant="event" />
            ))}
        </div>
    </SkeletonRegion>
);

/** The chat list (Messages). */
export const ChatListSkeleton = ({ rows = 7 }) => (
    <SkeletonRegion className="sk-chat-rows">
        {times(rows, (index) => (
            <div key={index} className="chat-row is-skeleton" aria-hidden="true">
                <Skeleton circle width={56} height={56} />
                <span className="chat-row-body">
                    <Skeleton width={pick(["46%", "58%", "38%", "52%"], index)} height={12} radius={6} />
                    <Skeleton width={pick(["78%", "64%", "84%", "70%"], index)} height={10} radius={6} />
                </span>
            </div>
        ))}
    </SkeletonRegion>
);

const BUBBLES = [
    ["theirs", "46%", 38],
    ["theirs", "30%", 38],
    ["mine", "54%", 58],
    ["theirs", "62%", 38],
    ["mine", "28%", 38],
    ["mine", "42%", 38],
    ["theirs", "36%", 58]
];

/** An open chat: its header, a conversation and the message box. */
export const ChatThreadSkeleton = () => (
    <SkeletonRegion className="chat-thread">
        <div className="chat-main" aria-hidden="true">
            <div className="chat-head">
                <Skeleton circle width={44} height={44} />
                <span className="sk-lines sk-grow" style={{ gap: 6 }}>
                    <Skeleton width={140} height={13} radius={6} />
                    <Skeleton width={90} height={10} radius={6} />
                </span>
            </div>
            <div className="chat-scroll sk-bubbles">
                {BUBBLES.map(([side, width, height], index) => (
                    <Skeleton key={index} className={`sk-bubble is-${side}`} width={width} height={height} radius={20} />
                ))}
            </div>
            <div className="composer">
                <Skeleton height={44} radius={22} />
            </div>
        </div>
    </SkeletonRegion>
);

/** One day of the event planner: time labels beside event blocks. */
export const ScheduleSkeleton = ({ rows = 4 }) => (
    <SkeletonRegion className="card">
        <div className="card-header" aria-hidden="true">
            <Skeleton width="32%" height={15} radius={6} />
            <Skeleton width={90} height={11} radius={6} />
        </div>
        <div className="card-body sk-timeline" aria-hidden="true">
            {times(rows, (index) => (
                <span key={index} className="sk-timeline-row">
                    <Skeleton width={44} height={11} radius={5} className="sk-fixed" />
                    <span className="sk-grow">
                        <Skeleton width={pick(["92%", "78%", "86%", "70%"], index)} height={pick([52, 64, 52, 76], index)} radius={12} />
                    </span>
                </span>
            ))}
        </div>
    </SkeletonRegion>
);

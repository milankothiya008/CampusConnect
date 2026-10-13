import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { render, screen, within } from "@testing-library/react";
import { AsyncContent } from "./Feedback";
import {
    CardGridSkeleton,
    ChatListSkeleton,
    ChatThreadSkeleton,
    ClubPageSkeleton,
    DashboardSkeleton,
    DetailSkeleton,
    FeedPostSkeleton,
    FormSkeleton,
    ListSkeleton,
    ProfileSkeleton,
    ScheduleSkeleton,
    TableSkeleton
} from "./Skeletons";

const loadingRegion = () => screen.getByRole("status", { name: "Loading" });

describe("AsyncContent", () => {
    test("while loading it shows a skeleton by default, not a spinner", () => {
        const { container } = render(
            <AsyncContent loading>
                <p>Loaded</p>
            </AsyncContent>
        );
        const region = loadingRegion();
        expect(region).toHaveAttribute("aria-busy", "true");
        expect(region.querySelectorAll(".skeleton").length).toBeGreaterThan(1);
        expect(container.querySelector(".spinner")).toBeNull();
        expect(screen.queryByText("Loaded")).not.toBeInTheDocument();
    });

    test("a page passes the skeleton shaped like its content", () => {
        render(
            <AsyncContent loading skeleton={<ListSkeleton rows={4} />}>
                <p>Loaded</p>
            </AsyncContent>
        );
        expect(loadingRegion().querySelectorAll(".list-row")).toHaveLength(4);
    });

    test("after loading: the error, the empty state, or the content — and no skeleton", () => {
        const { rerender } = render(
            <AsyncContent loading={false} error={new Error("Couldn't load the clubs")} onRetry={() => {}}>
                <p>Loaded</p>
            </AsyncContent>
        );
        expect(screen.getByRole("alert")).toHaveTextContent("Couldn't load the clubs");

        rerender(
            <AsyncContent loading={false} isEmpty empty={<p>No clubs yet</p>}>
                <p>Loaded</p>
            </AsyncContent>
        );
        expect(screen.getByText("No clubs yet")).toBeInTheDocument();

        rerender(
            <AsyncContent loading={false}>
                <p>Loaded</p>
            </AsyncContent>
        );
        expect(screen.getByText("Loaded")).toBeInTheDocument();
        expect(screen.queryByRole("status")).not.toBeInTheDocument();
    });
});

describe("skeletons", () => {
    const all = [
        ["card grid", <CardGridSkeleton key="a" />],
        ["club cards", <CardGridSkeleton key="b" variant="club" />],
        ["list", <ListSkeleton key="c" />],
        ["table", <TableSkeleton key="d" />],
        ["detail page", <DetailSkeleton key="e" />],
        ["form", <FormSkeleton key="f" steps={3} />],
        ["feed posts", <FeedPostSkeleton key="g" />],
        ["profile", <ProfileSkeleton key="h" />],
        ["club page", <ClubPageSkeleton key="i" />],
        ["dashboard", <DashboardSkeleton key="j" />],
        ["chat list", <ChatListSkeleton key="k" />],
        ["chat thread", <ChatThreadSkeleton key="l" />],
        ["planner day", <ScheduleSkeleton key="m" />]
    ];

    test.each(all)("%s: one loading region; every shape is hidden from screen readers and coloured by the theme", (_, skeleton) => {
        render(skeleton);
        const region = loadingRegion();
        const shapes = [...region.querySelectorAll(".skeleton")];
        expect(shapes.length).toBeGreaterThan(2);
        shapes.forEach((shape) => {
            expect(shape.closest('[aria-hidden="true"]')).not.toBeNull();
            // No inline colours: light and dark mode come from the stylesheet's tokens.
            expect(shape.style.background).toBe("");
            expect(shape.style.backgroundColor).toBe("");
        });
        expect(region.querySelector('[role="status"]')).toBeNull();
    });

    test("they reuse the content's own layout, so nothing jumps when it arrives", () => {
        const { unmount } = render(<CardGridSkeleton count={4} variant="result" />);
        expect(loadingRegion()).toHaveClass("result-grid");
        expect(loadingRegion().querySelectorAll(".card")).toHaveLength(4);
        unmount();

        render(<TableSkeleton rows={3} columns={4} />);
        const table = within(loadingRegion()).getByRole("table", { hidden: true });
        expect(table).toHaveClass("table");
        expect(table.querySelectorAll("tbody tr")).toHaveLength(3);
        expect(table.querySelectorAll("thead th")).toHaveLength(4);
    });

    test("a detail page has its side column; the chat thread has its header, both sides of a conversation and the box", () => {
        const { container, unmount } = render(<DetailSkeleton />);
        expect(container.querySelector(".detail-layout > aside")).not.toBeNull();
        unmount();

        const chat = render(<ChatThreadSkeleton />);
        expect(chat.container.querySelector(".chat-head")).not.toBeNull();
        expect(chat.container.querySelector(".composer")).not.toBeNull();
        expect(chat.container.querySelectorAll(".sk-bubble.is-mine").length).toBeGreaterThan(0);
        expect(chat.container.querySelectorAll(".sk-bubble.is-theirs").length).toBeGreaterThan(0);
    });

    test("the stylesheet themes them for dark mode and stops the shimmer for reduced motion", () => {
        // Tests run without CSS processing, so the stylesheet is read as text.
        const css = readFileSync(resolve(process.cwd(), "src/styles/skeleton.css"), "utf8").replace(/\s+/g, " ");
        expect(css).toMatch(/:root\[data-theme="dark"\] \{[^}]*--skeleton-base:/);
        expect(css).toMatch(/\.skeleton \{[^}]*background: var\(--skeleton-base\)/);
        expect(css).toMatch(/@media \(prefers-reduced-motion: reduce\) \{ \.skeleton::after \{[^}]*animation: none/);
    });
});

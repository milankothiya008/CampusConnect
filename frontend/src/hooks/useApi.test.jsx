import { act, renderHook, waitFor } from "@testing-library/react";
import { QueryClientProvider } from "@tanstack/react-query";
import { callerOf, useApi } from "./useApi";
import { api, ApiError, setAccessToken } from "../api/client";
import { createQueryClient, queryClient } from "../lib/queryClient";

const ok = (data, meta) => ({ success: true, data, ...(meta ? { meta } : {}) });

const deferred = () => {
    let resolve;
    let reject;
    const promise = new Promise((done, fail) => {
        resolve = done;
        reject = fail;
    });
    return { promise, resolve, reject };
};

// Lets queued cache notifications reach the hooks.
const settle = () => act(() => new Promise((resolve) => setTimeout(resolve, 20)));

// Each of these is one call site, as a screen would have.
const fetchItem = vi.fn();
const useItem = (id, options) => useApi(() => fetchItem(id), [id], options);
const useList = (load) => useApi(load, []);

const json = (body) => ({ ok: true, status: 200, text: () => Promise.resolve(JSON.stringify(body)) });

describe("useApi", () => {
    beforeEach(() => {
        vi.clearAllMocks();
        fetchItem.mockImplementation((id) => Promise.resolve(ok({ id })));
    });

    afterEach(() => {
        vi.restoreAllMocks();
    });

    test("loads data and meta, and is loading until then", async () => {
        const loader = vi.fn(() => Promise.resolve(ok([1, 2], { page: 1, totalPages: 3 })));
        const { result } = renderHook(() => useApi(loader, []));

        expect(result.current).toMatchObject({ data: null, meta: null, loading: true, error: null });
        await waitFor(() => expect(result.current.loading).toBe(false));
        expect(result.current).toMatchObject({ data: [1, 2], meta: { page: 1, totalPages: 3 }, error: null });
        expect(loader).toHaveBeenCalledTimes(1);
    });

    test("different deps never share data; the same screen opened again shows its data at once", async () => {
        const a = renderHook(() => useItem("a"));
        const b = renderHook(() => useItem("b"));
        await waitFor(() => expect(a.result.current.data).toEqual({ id: "a" }));
        await waitFor(() => expect(b.result.current.data).toEqual({ id: "b" }));

        a.unmount();
        const again = renderHook(() => useItem("a"));
        // Straight from the cache: no loading state, and no new request while it's fresh.
        expect(again.result.current).toMatchObject({ data: { id: "a" }, loading: false });
        await settle();
        expect(fetchItem).toHaveBeenCalledTimes(2);
    });

    test("loaders with the same deps never collide: other code, or another loader passed to the same call site", async () => {
        const { result } = renderHook(() => ({
            events: useApi(() => Promise.resolve(ok("events")), ["c1"]),
            members: useApi(() => Promise.resolve(ok("members")), ["c1"])
        }));
        await waitFor(() => expect(result.current.events.data).toBe("events"));
        await waitFor(() => expect(result.current.members.data).toBe("members"));

        const departments = () => Promise.resolve(ok(["CE", "IT"]));
        const batches = () => Promise.resolve(ok(["24", "25"]));
        const first = renderHook(() => useList(departments));
        const second = renderHook(() => useList(batches));
        await waitFor(() => expect(first.result.current.data).toEqual(["CE", "IT"]));
        await waitFor(() => expect(second.result.current.data).toEqual(["24", "25"]));
    });

    test("new deps load again and keep the previous data on screen meanwhile", async () => {
        const next = deferred();
        fetchItem.mockImplementation((id) => (id === "b" ? next.promise : Promise.resolve(ok({ id }))));
        const { result, rerender } = renderHook(({ id }) => useItem(id), { initialProps: { id: "a" } });
        await waitFor(() => expect(result.current.data).toEqual({ id: "a" }));

        rerender({ id: "b" });
        expect(result.current).toMatchObject({ data: { id: "a" }, loading: true });
        await act(async () => next.resolve(ok({ id: "b" })));
        await waitFor(() => expect(result.current).toMatchObject({ data: { id: "b" }, loading: false }));
    });

    test("enabled: false loads nothing until it is enabled", async () => {
        const { result, rerender } = renderHook(({ on }) => useItem("x", { enabled: on }), { initialProps: { on: false } });
        expect(result.current).toMatchObject({ data: null, meta: null, loading: false, error: null });
        await settle();
        expect(fetchItem).not.toHaveBeenCalled();

        rerender({ on: true });
        expect(result.current.loading).toBe(true);
        await waitFor(() => expect(result.current.data).toEqual({ id: "x" }));

        rerender({ on: false });
        expect(result.current).toMatchObject({ data: null, loading: false });
    });

    test("reload() shows the loading state, reload({ silent: true }) keeps the data on screen; both resolve to the response", async () => {
        const loader = vi.fn(() => Promise.resolve(ok("first")));
        const { result } = renderHook(() => useApi(loader, []));
        await waitFor(() => expect(result.current.data).toBe("first"));

        let call = deferred();
        loader.mockImplementation(() => call.promise);
        let pending;
        act(() => {
            pending = result.current.reload();
        });
        expect(result.current).toMatchObject({ loading: true, data: "first" });
        await act(async () => {
            call.resolve(ok("second"));
            expect(await pending).toEqual(ok("second"));
        });
        await waitFor(() => expect(result.current).toMatchObject({ loading: false, data: "second" }));

        call = deferred();
        act(() => {
            pending = result.current.reload({ silent: true });
        });
        expect(result.current).toMatchObject({ loading: false, data: "second" });
        await act(async () => {
            call.resolve(ok("third"));
            await pending;
        });
        await waitFor(() => expect(result.current.data).toBe("third"));
        expect(result.current.loading).toBe(false);

        // Stable functions: screens put them in effect dependencies.
        const { reload, setData } = result.current;
        await settle();
        expect(result.current.reload).toBe(reload);
        expect(result.current.setData).toBe(setData);
    });

    test("setData(updater) changes the cached data, for every screen showing it", async () => {
        const useCounter = () => useApi(() => Promise.resolve(ok({ count: 1 })), []);
        const first = renderHook(() => useCounter());
        const second = renderHook(() => useCounter());
        await waitFor(() => expect(first.result.current.data).toEqual({ count: 1 }));
        await waitFor(() => expect(second.result.current.data).toEqual({ count: 1 }));

        act(() => first.result.current.setData((current) => ({ count: current.count + 1 })));
        await waitFor(() => expect(first.result.current.data).toEqual({ count: 2 }));
        await waitFor(() => expect(second.result.current.data).toEqual({ count: 2 }));

        act(() => second.result.current.setData({ count: 7 }));
        await waitFor(() => expect(first.result.current.data).toEqual({ count: 7 }));
    });

    test("a 4xx answer is not retried; any other failure is tried once more", async () => {
        const client = createQueryClient({ retryDelay: 0 });
        const wrapper = ({ children }) => <QueryClientProvider client={client}>{children}</QueryClientProvider>;

        const missing = vi.fn(() => Promise.reject(new ApiError("Event not found", { status: 404 })));
        const notFound = renderHook(() => useApi(missing, []), { wrapper });
        await waitFor(() => expect(notFound.result.current.error?.message).toBe("Event not found"));
        expect(notFound.result.current.loading).toBe(false);
        expect(missing).toHaveBeenCalledTimes(1);

        const flaky = vi.fn().mockRejectedValueOnce(new ApiError("Cannot reach CampusConnect.")).mockResolvedValueOnce(ok("back"));
        const recovered = renderHook(() => useApi(flaky, []), { wrapper });
        await waitFor(() => expect(recovered.result.current.data).toBe("back"));
        expect(recovered.result.current.error).toBeNull();
        expect(flaky).toHaveBeenCalledTimes(2);

        const down = vi.fn(() => Promise.reject(new ApiError("Service unavailable", { status: 503 })));
        const failed = renderHook(() => useApi(down, []), { wrapper });
        await waitFor(() => expect(failed.result.current.error?.status).toBe(503));
        expect(down).toHaveBeenCalledTimes(2);
        client.clear();
    });

    test("a failed background refresh keeps the data on screen; a failed reload() shows the error and resolves to null", async () => {
        const loader = vi.fn(() => Promise.resolve(ok("saved")));
        const { result } = renderHook(() => useApi(loader, []));
        await waitFor(() => expect(result.current.data).toBe("saved"));

        loader.mockImplementation(() => Promise.reject(new ApiError("Server error", { status: 500 })));
        await act(() => queryClient.refetchQueries());
        await settle();
        expect(loader).toHaveBeenCalledTimes(2);
        expect(result.current).toMatchObject({ data: "saved", error: null, loading: false });

        let value;
        await act(async () => {
            value = await result.current.reload();
        });
        expect(value).toBeNull();
        await waitFor(() => expect(result.current.error?.message).toBe("Server error"));
        expect(result.current.data).toBe("saved");

        // "Try again" clears the error while it loads.
        loader.mockImplementation(() => Promise.resolve(ok("saved again")));
        await act(async () => {
            await result.current.reload();
        });
        await waitFor(() => expect(result.current).toMatchObject({ data: "saved again", error: null }));
    });

    describe("with the API client", () => {
        beforeEach(() => setAccessToken("token-1"));
        afterEach(() => setAccessToken(null));

        test("a change sent to the API marks data out of date without refetching; the next visit loads it again", async () => {
            const fetchMock = vi
                .spyOn(globalThis, "fetch")
                .mockImplementation((url, init) => Promise.resolve(json({ data: init?.method === "POST" ? "done" : "events" })));
            const useEvents = () => useApi(() => api.get("/events"), []);

            const first = renderHook(() => useEvents());
            await waitFor(() => expect(first.result.current.data).toBe("events"));
            expect(fetchMock).toHaveBeenCalledTimes(1);

            await act(() => api.post("/events/e1/register"));
            await settle();
            // No burst of refetches for the screen that is open.
            expect(fetchMock).toHaveBeenCalledTimes(2);

            first.unmount();
            const again = renderHook(() => useEvents());
            // Shown at once from the cache, then quietly loaded again because it is out of date.
            expect(again.result.current).toMatchObject({ data: "events", loading: false });
            await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(3));
            expect(fetchMock.mock.calls[2][0]).toBe("/api/events");
        });

        test("live data (api.fresh) is never served from the cache", async () => {
            const fetchMock = vi.spyOn(globalThis, "fetch").mockImplementation(() => Promise.resolve(json({ data: ["n1"] })));
            const useActivity = () => useApi(() => api.fresh("/notifications"), []);

            const first = renderHook(() => useActivity());
            await waitFor(() => expect(first.result.current.data).toEqual(["n1"]));
            first.unmount();

            fetchMock.mockImplementation(() => Promise.resolve(json({ data: ["n2", "n1"] })));
            const again = renderHook(() => useActivity());
            expect(again.result.current).toMatchObject({ data: null, loading: true });
            await waitFor(() => expect(again.result.current.data).toEqual(["n2", "n1"]));
            expect(fetchMock).toHaveBeenCalledTimes(2);

            // Its entry goes as soon as the screen closes.
            const entries = queryClient.getQueryCache().getAll().length;
            again.unmount();
            await waitFor(() => expect(queryClient.getQueryCache().getAll()).toHaveLength(entries - 1));
        });

        test("signing in or out empties the cache", async () => {
            vi.spyOn(globalThis, "fetch").mockImplementation(() => Promise.resolve(json({ data: "mine" })));
            const { result } = renderHook(() => useApi(() => api.get("/dashboard"), []));
            await waitFor(() => expect(result.current.data).toBe("mine"));
            expect(queryClient.getQueryCache().getAll()).toHaveLength(1);

            setAccessToken("someone-else");
            expect(queryClient.getQueryCache().getAll()).toHaveLength(0);
        });
    });

    test("cache: false loads on every visit and is never refreshed behind the screen's back", async () => {
        const first = renderHook(() => useItem("draft", { cache: false }));
        await waitFor(() => expect(first.result.current.data).toEqual({ id: "draft" }));

        // Window focus, reconnects and changes elsewhere refresh other screens, never this one.
        await act(() => queryClient.invalidateQueries());
        await settle();
        expect(fetchItem).toHaveBeenCalledTimes(1);

        first.unmount();
        const again = renderHook(() => useItem("draft", { cache: false }));
        expect(again.result.current.loading).toBe(true);
        await waitFor(() => expect(again.result.current.data).toEqual({ id: "draft" }));
        expect(fetchItem).toHaveBeenCalledTimes(2);

        // reload() still works.
        await act(async () => {
            await again.result.current.reload();
        });
        expect(fetchItem).toHaveBeenCalledTimes(3);
    });

    test("the call site is read from Chrome, Firefox/Safari, dev-server and Windows stacks", () => {
        const stack = (...lines) => lines.join("\n");

        const chrome = stack(
            "Error",
            "    at Yt (https://campus.example.edu/assets/useApi-Bly2H6Mj.js:1:1234)",
            "    at Ze (https://campus.example.edu/assets/EventDetailPage-DJAjq0ix.js:1:5678)",
            "    at Wo (https://campus.example.edu/assets/vendor-CRqlOdaT.js:9:41234)"
        );
        expect(callerOf(chrome)).toBe("https://campus.example.edu/assets/EventDetailPage-DJAjq0ix.js:1:5678");

        const firefox = stack(
            "Yt@https://campus.example.edu/assets/useApi-Bly2H6Mj.js:1:1234",
            "Ze@https://campus.example.edu/assets/ClubLayout-BS_EvVsG.js:1:900",
            "Wo@https://campus.example.edu/assets/vendor.js:9:4"
        );
        expect(callerOf(firefox)).toBe("https://campus.example.edu/assets/ClubLayout-BS_EvVsG.js:1:900");

        // The dev server's cache-busting query is not part of the place.
        const dev = stack(
            "Error",
            "    at useApi (http://localhost:3000/src/hooks/useApi.js?t=1760000000000:52:22)",
            "    at EventDetailPage (http://localhost:3000/src/pages/events/EventDetailPage.jsx?t=1760000000001:187:48)"
        );
        expect(callerOf(dev)).toBe("http://localhost:3000/src/pages/events/EventDetailPage.jsx:187:48");

        const windows = stack("Error", "    at useApi (D:\\app\\src\\hooks\\useApi.js:52:22)", "    at Page (D:\\app\\src\\pages\\Page.jsx:3:7)");
        expect(callerOf(windows)).toBe("D:\\app\\src\\pages\\Page.jsx:3:7");

        // Unreadable: that hook instance just doesn't share its cache entry.
        expect(callerOf("")).toBeNull();
        expect(callerOf(undefined)).toBeNull();
        expect(callerOf(stack("Error", "    at useApi (https://x.example/a.js:1:2)"))).toBeNull();
    });
});

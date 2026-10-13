import { useCallback, useContext, useId, useRef, useState } from "react";
import { hashKey, keepPreviousData, QueryClientContext, useQuery } from "@tanstack/react-query";
import { trackRequests } from "../api/client";
import { queryClient as appQueryClient } from "../lib/queryClient";

// Where useApi was called from ("file:line:column"), read from the stack of an Error created inside useApi:
// the first frame is useApi itself, the next one its caller. The same screen mounted again finds the same
// cache entry; null when the stack can't be read (that hook instance then simply doesn't share its cache).
export const callerOf = (stack) => {
    const frames = String(stack || "")
        .split("\n")
        .filter((line) => /:\d+:\d+\)?\s*$/.test(line));
    const match = frames[1] && /([^\s(@]+):(\d+):(\d+)\)?\s*$/.exec(frames[1]);
    return match ? `${match[1].replace(/[?#].*$/, "")}:${match[2]}:${match[3]}` : null;
};

// Loaders found to read live data (api.fresh), by call site and code. Like the API client, the cache never
// serves those: once known, each mount gets its own entry and loads them again (they still refresh while on
// screen when the tab regains focus).
const liveLoaders = new Set();
const LIVE = { staleTime: 0, gcTime: 0 };
// `cache: false`: loaded on every mount and never replaced behind the screen's back.
const UNCACHED = { staleTime: "static", gcTime: 0 };

/**
 * Loads data from an API call and exposes `{ data, meta, loading, error, reload, setData }`.
 *
 * `deps` re-run the loader (they are part of the cache key); responses from superseded calls are ignored.
 * Data lives in the shared TanStack Query cache, keyed by the call site, the loader's code and `deps`, so a
 * screen opened again shows its data at once and refreshes it quietly when it is out of date.
 *
 * - `enabled: false` loads nothing and returns `data: null`.
 * - `reload()` loads again and shows the loading state; `reload({ silent: true })` keeps the current data on
 *   screen while it loads. Both resolve to the response, or null on error.
 * - `setData(value | updater)` replaces the data in the cache (every screen showing it updates).
 * - `cache: false` is for data that seeds a form or is extended locally (load more): it is loaded fresh on
 *   every mount and never refreshed in the background, so nothing the person typed is reset.
 *
 * Errors of background refreshes are not shown while there is data; errors of `reload()` are.
 */
export const useApi = (loader, deps = [], { enabled = true, cache = true } = {}) => {
    const client = useContext(QueryClientContext) || appQueryClient;
    const instance = useId();
    const site = useRef(undefined);
    if (site.current === undefined) {
        site.current = callerOf(new Error().stack);
    }
    const loaderRef = useRef(loader);
    loaderRef.current = loader;

    // The loader's code is part of the key: a component that takes its loader as a prop (one call site,
    // different loaders) never mixes up their data.
    const code = String(loader);
    const siteKey = site.current === null ? null : `${site.current}\n${code}`;
    // Decided once per mount, so a loader found to be live on its first load keeps its entry until unmount.
    const liveness = useRef(null);
    if (liveness.current?.siteKey !== siteKey) {
        liveness.current = { siteKey, live: siteKey !== null && liveLoaders.has(siteKey) };
    }
    const live = liveness.current.live;
    const shared = cache && !live && siteKey !== null;
    const queryKey = ["api", shared ? siteKey : `${instance}\n${code}`, ...deps];
    const hash = hashKey(queryKey);

    const query = useQuery(
        {
            queryKey,
            queryFn: () => {
                const { value, live: readsLive } = trackRequests(() => loaderRef.current());
                if (readsLive && siteKey !== null) {
                    liveLoaders.add(siteKey);
                }
                // A query never holds `undefined`.
                return Promise.resolve(value).then((response) => response ?? null);
            },
            enabled,
            // While new deps load, the previous data stays (with `loading` true), as it always did.
            placeholderData: keepPreviousData,
            ...(!cache ? UNCACHED : live ? LIVE : null)
        },
        client
    );

    const [reloading, setReloading] = useState(false);
    // The error of the last reload() that failed: which key, and when (so a later error isn't taken for it).
    const [failed, setFailed] = useState(null);
    const runs = useRef(0);
    const latest = useRef({ query, queryKey, hash });
    latest.current = { query, queryKey, hash };

    const reload = useCallback(async ({ silent = false } = {}) => {
        const run = ++runs.current;
        const forHash = latest.current.hash;
        if (!silent) {
            setReloading(true);
        }
        const result = await latest.current.query.refetch();
        if (run === runs.current) {
            setReloading(false);
            setFailed(result.isError ? { hash: forHash, at: result.errorUpdatedAt } : null);
        }
        return result.isError ? null : (result.data ?? null);
    }, []);

    const setData = useCallback(
        (updater) =>
            client.setQueryData(latest.current.queryKey, (previous) => ({
                ...previous,
                data: typeof updater === "function" ? updater(previous?.data ?? null) : updater
            })),
        [client]
    );

    // A failed load with nothing to show keeps the last data this screen had (as before).
    const last = useRef(null);
    let response = null;
    if (enabled) {
        response = query.data !== undefined ? query.data : query.isError ? last.current : null;
        last.current = response;
    } else {
        last.current = null;
    }
    const hasData = query.data !== undefined && !query.isPlaceholderData;
    const fromReload = failed !== null && failed.hash === hash && failed.at === query.errorUpdatedAt;
    const error = !enabled || reloading || !query.isError ? null : !hasData || fromReload ? query.error : null;

    return {
        data: response?.data ?? null,
        meta: response?.meta ?? null,
        loading: enabled && (reloading || query.isPending || query.isPlaceholderData),
        error,
        reload,
        setData
    };
};

// Wraps a mutation with pending/error state; resolves to the response or null on error.
export const useAction = () => {
    const [pending, setPending] = useState(false);
    const [error, setError] = useState(null);

    const run = useCallback(async (fn) => {
        setPending(true);
        setError(null);
        try {
            return (await fn()) ?? true;
        } catch (err) {
            setError(err);
            return null;
        } finally {
            setPending(false);
        }
    }, []);

    return { pending, error, setError, run };
};

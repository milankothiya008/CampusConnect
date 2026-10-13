import { QueryClient } from "@tanstack/react-query";

// The one cache of server data for the whole app (TanStack Query). Screens read it through hooks/useApi.
//
// - Data stays fresh for 10 s: going back to a screen within that time shows it at once, without a request.
// - It is kept for 5 minutes after the last screen using it closes: a later visit shows it at once and
//   refreshes it quietly in the background.
// - A failed load is tried once more, except for 4xx answers (asking again gives the same answer).
// - Coming back to the tab (or back online) refreshes what is on screen and out of date.
// - Any change sent to the API marks everything out of date without refetching (see api/client.js).

const isClientError = (error) => error?.status >= 400 && error?.status < 500;

// A dialog may hold a form seeded from the data on screen; refreshing that data underneath it could reset
// what the person typed, so focus/reconnect refreshes wait until no dialog is open.
const unlessDialogOpen = () => typeof document === "undefined" || !document.querySelector('[aria-modal="true"]');

export const QUERY_DEFAULTS = {
    staleTime: 10_000,
    gcTime: 5 * 60_000,
    retry: (failureCount, error) => failureCount < 1 && !isClientError(error),
    refetchOnWindowFocus: unlessDialogOpen,
    refetchOnReconnect: unlessDialogOpen,
    // Always attempt the request, so an offline device shows "Cannot reach CampusConnect" with a retry
    // button (as before) instead of waiting silently for the network.
    networkMode: "always"
};

export const createQueryClient = (overrides = {}) => new QueryClient({ defaultOptions: { queries: { ...QUERY_DEFAULTS, ...overrides } } });

export const queryClient = createQueryClient();

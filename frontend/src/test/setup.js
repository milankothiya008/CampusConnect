import "@testing-library/jest-dom/vitest";
import { cleanup, configure } from "@testing-library/react";
import { afterEach } from "vitest";
import { queryClient } from "../lib/queryClient";

// The whole suite runs files in parallel; on a busy machine screens can take longer than the default
// 1 second to appear, so wait up to 5 before calling it a failure.
configure({ asyncUtilTimeout: 5000 });

// Screens share one data cache (hooks/useApi). Tests mock the API per test, so each test starts with an
// empty cache, and a mocked failure shows at once instead of after a retry.
queryClient.setDefaultOptions({ queries: { ...queryClient.getDefaultOptions().queries, retry: false } });
afterEach(() => {
    cleanup();
    queryClient.clear();
});

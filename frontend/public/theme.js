// Applies the saved theme before the first paint, so dark mode never flashes white. A plain (blocking) script loaded
// from index.html, kept out of the page itself so the Content-Security-Policy needs no inline-script exception.
try {
    var t = localStorage.getItem("cc.theme") || "system";
    var dark = t === "dark" || (t === "system" && window.matchMedia("(prefers-color-scheme: dark)").matches);
    document.documentElement.dataset.theme = dark ? "dark" : "light";
} catch {
    // Storage blocked (private mode): the stylesheet's default theme applies.
}

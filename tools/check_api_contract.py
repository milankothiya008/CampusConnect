"""Static API contract check: every HTTP call the React app makes must have a matching Spring route.

usage: python tools/check_api_contract.py            (from the repository root; exit code 1 on mismatches)

Frontend calls are read from frontend/src (api.get/post/put/patch/delete("/path"...), downloadFile, request);
backend routes from @RequestMapping/@GetMapping/... annotations in backend/src/main/java. Path parameters on both
sides are normalised to "{}" before comparing.
"""
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
FRONTEND = os.path.join(ROOT, "frontend", "src")
BACKEND = os.path.join(ROOT, "backend", "src", "main", "java")

CALL = re.compile(r"""\bapi\.(get|post|put|patch|delete|fresh)\(\s*(`[^`]*`|"[^"]*"|'[^']*')""")
DOWNLOAD = re.compile(r"""\bdownloadFile\(\s*(`[^`]*`|"[^"]*"|'[^']*')""")
REQUEST = re.compile(r"""\brequest\(\s*["'](GET|POST|PUT|PATCH|DELETE)["']\s*,\s*(`[^`]*`|"[^"]*"|'[^']*')""")


def normalise(path):
    path = path.strip("`\"'")
    path = path.split("?")[0]
    path = re.sub(r"\$\{[^}]*\}", "{}", path)
    path = re.sub(r"\{[^}/]*\}", "{}", path)
    path = re.sub(r"//+", "/", path)
    return path.rstrip("/") or "/"


def frontend_calls():
    calls = {}
    for base, _, files in os.walk(FRONTEND):
        if "node_modules" in base:
            continue
        for file in files:
            if not file.endswith((".js", ".jsx")) or ".test." in file:
                continue
            path = os.path.join(base, file)
            text = open(path, encoding="utf-8").read()
            for method, raw in CALL.findall(text):
                method = "GET" if method == "fresh" else method.upper()
                calls.setdefault((method, "/api" + normalise(raw)), set()).add(os.path.relpath(path, ROOT))
            for raw in DOWNLOAD.findall(text):
                calls.setdefault(("GET", "/api" + normalise(raw)), set()).add(os.path.relpath(path, ROOT))
            for method, raw in REQUEST.findall(text):
                calls.setdefault((method, "/api" + normalise(raw)), set()).add(os.path.relpath(path, ROOT))
    return calls


MAPPING = re.compile(r"@(Get|Post|Put|Patch|Delete|Request)Mapping\s*(\(([^)]*)\))?")


def mapping_paths(args):
    if not args:
        return [""]
    values = re.findall(r'"([^"]*)"', args.split("method")[0] if "path" not in args and "value" not in args else args)
    return values or [""]


def backend_routes():
    routes = set()
    for base, _, files in os.walk(BACKEND):
        for file in files:
            if not file.endswith(".java"):
                continue
            text = open(os.path.join(base, file), encoding="utf-8").read()
            if "@RestController" not in text and "@Controller" not in text:
                continue
            class_part = text.split(" class ", 1)
            prefixes = [""]
            header = class_part[0]
            for kind, _, args in MAPPING.findall(header):
                if kind == "Request":
                    prefixes = mapping_paths(args)
            body = class_part[1] if len(class_part) > 1 else ""
            for kind, _, args in MAPPING.findall(body):
                if kind == "Request":
                    method_match = re.search(r"RequestMethod\.(\w+)", args or "")
                    methods = [method_match.group(1)] if method_match else ["GET", "POST", "PUT", "PATCH", "DELETE"]
                else:
                    methods = [kind.upper()]
                for prefix in prefixes:
                    for sub in mapping_paths(args):
                        for method in methods:
                            routes.add((method, normalise(prefix + sub)))
    return routes


def main():
    calls = frontend_calls()
    routes = backend_routes()
    missing = {key: where for key, where in calls.items() if key not in routes}
    print(f"Frontend calls: {len(calls)}, backend routes: {len(routes)}")
    if not missing:
        print("Every frontend call has a backend route.")
        return 0
    print(f"{len(missing)} frontend calls without a backend route:")
    for (method, path), where in sorted(missing.items(), key=lambda item: (item[0][1], item[0][0])):
        print(f"  {method:6} {path}    <- {', '.join(sorted(where))}")
    return 1


if __name__ == "__main__":
    sys.exit(main())

#!/usr/bin/env python3
"""Serves the bare repositories under a directory over git's smart HTTP protocol, read only, by handing each request to
git http-backend. Flux's GitRepository takes http, https or ssh URLs only, so git daemon will not do for k3d.
Usage: git-http.py <directory> <address> <port>"""

import subprocess
import sys
from wsgiref.simple_server import WSGIRequestHandler, make_server

HTTP_BACKEND = ["git", "http-backend"]
PASSED_HEADERS = ("CONTENT_TYPE", "CONTENT_LENGTH", "HTTP_CONTENT_ENCODING", "HTTP_GIT_PROTOCOL")


def application(root):
    def serve(environ, start_response):
        length = int(environ.get("CONTENT_LENGTH") or 0)
        body = environ["wsgi.input"].read(length) if length else b""
        cgi = {
            "GIT_PROJECT_ROOT": root,
            "GIT_HTTP_EXPORT_ALL": "1",
            "PATH_INFO": environ.get("PATH_INFO", ""),
            "QUERY_STRING": environ.get("QUERY_STRING", ""),
            "REQUEST_METHOD": environ["REQUEST_METHOD"],
            "REMOTE_ADDR": environ.get("REMOTE_ADDR", ""),
            "PATH": "/usr/bin:/bin",
        }
        cgi.update({key: environ[key] for key in PASSED_HEADERS if environ.get(key)})
        # The request body was read whole above, so git sees a plain body, not a chunked one.
        cgi["CONTENT_LENGTH"] = str(len(body))
        answer = subprocess.run(HTTP_BACKEND, input=body, env=cgi, capture_output=True, check=False).stdout
        head, _, content = answer.partition(b"\r\n\r\n")
        status = "200 OK"
        headers = []
        for line in head.decode("latin-1").split("\r\n"):
            name, _, value = line.partition(":")
            if name.lower() == "status":
                status = value.strip()
            elif name:
                headers.append((name, value.strip()))
        start_response(status, headers)
        return [content]

    return serve


class QuietHandler(WSGIRequestHandler):
    def log_request(self, code="-", size="-"):
        if not str(code).startswith("2"):
            super().log_request(code, size)


if __name__ == "__main__":
    directory, address, port = sys.argv[1], sys.argv[2], int(sys.argv[3])
    make_server(address, port, application(directory), handler_class=QuietHandler).serve_forever()

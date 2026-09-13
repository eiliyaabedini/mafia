#!/usr/bin/env python3
"""Serve a built web distribution on loopback, with compressed static assets."""
import argparse
import functools
import gzip
import io
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import unquote, urlsplit

REPO = Path(__file__).resolve().parent.parent
COMPRESSIBLE = {'.html', '.js', '.mjs', '.wasm', '.css', '.json', '.webmanifest', '.svg', '.ttf'}


@functools.lru_cache(maxsize=32)
def compressed(path, modified_ns, size):
    return gzip.compress(Path(path).read_bytes(), compresslevel=6, mtime=0)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--target', choices=['wasmJs', 'js'], default='wasmJs')
    parser.add_argument('--port', type=int, default=8080)
    args = parser.parse_args()
    root = (REPO / 'webApp/build/dist' / args.target / 'productionExecutable').resolve()
    if not (root / 'index.html').is_file() or not (root / 'webApp.js').is_file():
        parser.error(f'Production files are missing. Run ./gradlew :webApp:{args.target}BrowserDistribution first.')

    class Handler(SimpleHTTPRequestHandler):
        extensions_map = {
            **SimpleHTTPRequestHandler.extensions_map,
            '.wasm': 'application/wasm',
            '.mjs': 'text/javascript',
            '.js': 'text/javascript',
            '.webmanifest': 'application/manifest+json',
            '.woff2': 'font/woff2',
            '.ttf': 'font/ttf',
        }

        def __init__(self, *handler_args, **kwargs):
            super().__init__(*handler_args, directory=str(root), **kwargs)

        def log_request(self, code='-', size='-'):
            # OAuth callbacks can contain credentials; never log query parameters.
            print(f'{self.log_date_time_string()} {self.command} {urlsplit(self.path).path!r} {code}', flush=True)

        def end_headers(self):
            path = urlsplit(self.path).path
            if path == '/' or path.endswith(('.html', '/aipass-config.json', '/sw.js')):
                self.send_header('Cache-Control', 'no-store')
            else:
                self.send_header('Cache-Control', 'no-cache')
            self.send_header('X-Content-Type-Options', 'nosniff')
            self.send_header('Referrer-Policy', 'strict-origin-when-cross-origin')
            self.send_header('Vary', 'Accept-Encoding')
            super().end_headers()

        def send_head(self):
            public_path = unquote(urlsplit(self.path).path)
            if any(part.startswith('.') for part in public_path.split('/') if part):
                self.send_error(404)
                return None
            path = Path(self.translate_path(self.path)).resolve()
            if path != root and root not in path.parents:
                self.send_error(404)
                return None
            if path.is_dir():
                if path != root:
                    self.send_error(404)
                    return None
                path = root / 'index.html'
            if not path.is_file():
                self.send_error(404)
                return None
            encoding = self.headers.get('Accept-Encoding', '')
            allows_gzip = any(part.split(';')[0].strip() == 'gzip' and 'q=0' not in part.replace(' ', '') for part in encoding.split(','))
            stat = path.stat()
            if allows_gzip and path.suffix in COMPRESSIBLE and stat.st_size > 1024:
                data = compressed(str(path), stat.st_mtime_ns, stat.st_size)
                self.send_response(200)
                self.send_header('Content-Type', self.guess_type(str(path)))
                self.send_header('Content-Encoding', 'gzip')
                self.send_header('Content-Length', str(len(data)))
                self.send_header('Last-Modified', self.date_time_string(stat.st_mtime))
                self.end_headers()
                return io.BytesIO(data)
            return super().send_head()

        def list_directory(self, path):
            self.send_error(404)
            return None

        def send_error(self, code, message=None, explain=None):
            body = ('<!doctype html><html lang="fa" dir="rtl"><meta charset="utf-8">'
                    '<meta name="viewport" content="width=device-width,initial-scale=1">'
                    '<title>صفحه پیدا نشد</title><body style="background:#10100f;color:#f1ebdf;'
                    'font:18px Tahoma;padding:40px"><p>این صفحه پیدا نشد.</p>'
                    '<a href="/" style="color:#e0b77a">بازگشت به بازی</a></body></html>').encode('utf-8')
            self.send_response(code)
            self.send_header('Content-Type', 'text/html; charset=utf-8')
            self.send_header('Content-Length', str(len(body)))
            self.end_headers()
            if self.command != 'HEAD':
                self.wfile.write(body)

    server = ThreadingHTTPServer(('127.0.0.1', args.port), Handler)
    print(f'Mafia production: http://localhost:{args.port}/', flush=True)
    print(f'Distribution: {root}', flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()


if __name__ == '__main__':
    main()

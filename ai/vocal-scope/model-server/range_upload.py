"""Bounded form metadata with disk-spooled audio, including v5 candidate evidence."""
from fastapi.routing import APIRoute


class RangeUploadRoute(APIRoute):
    def get_route_handler(self):
        handle = super().get_route_handler()

        async def parse(request):
            if request.method == 'POST' and request.headers.get('content-type', '').startswith('multipart/form-data'):
                # FastAPI reuses this Request's parsed form; it never reads the whole WAV.
                await request.form(max_files=1, max_fields=3, max_part_size=8_000_000)
            return await handle(request)

        return parse

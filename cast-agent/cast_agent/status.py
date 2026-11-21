"""Reading what a Cast device is doing right now (read-only: nothing is launched or changed).

Every Cast device reports its running app. If the app uses Cast's media channel we can also ask what is
playing: its state, title, artist and (for apps that share it) the address of the media.
"""

from __future__ import annotations

import logging
import time
import uuid

log = logging.getLogger(__name__)

CONNECT_TIMEOUT = 10.0
MEDIA_WAIT = 1.0
TEXT_MAX = 255
URL_MAX = 1024


def _clean(value, limit: int = TEXT_MAX) -> str | None:
    text = str(value).strip() if value is not None else ""
    return text[:limit] if text else None


def describe(app_name, status_text, media) -> dict:
    """The shape reported to the service. [media] is pychromecast's MediaStatus, or None."""
    shared = None
    if media is not None:
        url = _clean(getattr(media, "content_id", None), URL_MAX)
        state = _clean(getattr(media, "player_state", None))
        if state == "UNKNOWN":
            state = None
        shared = {
            "state": state,
            "title": _clean(getattr(media, "title", None)),
            "artist": _clean(getattr(media, "artist", None)),
            "url": url if url and url.lower().startswith(("http://", "https://")) else None,
            "contentType": _clean(getattr(media, "content_type", None), 80),
        }
        if not any(shared.values()):
            shared = None
    return {"appName": _clean(app_name), "statusText": _clean(status_text), "media": shared}


def read_status(host: str, port: int = 8009) -> dict | None:
    """What the device at [host] is running and playing, or None if it will not talk to us."""
    import pychromecast  # imported here so tests and tools that never connect do not need a network stack

    cast = pychromecast.get_chromecast_from_host((host, port, uuid.uuid4(), None, "inventory"))
    try:
        cast.start()
        cast.wait(timeout=CONNECT_TIMEOUT)
        media = None
        if cast.app_id and not cast.is_idle:
            try:
                cast.media_controller.update_status()
                time.sleep(MEDIA_WAIT)
                media = cast.media_controller.status
            except Exception:
                log.debug("No media status from %s", host, exc_info=True)
        status = cast.status
        return describe(cast.app_display_name, getattr(status, "status_text", None), media)
    except Exception:
        log.debug("Could not read the status of %s", host, exc_info=True)
        return None
    finally:
        try:
            cast.disconnect(timeout=3)
        except Exception:
            pass

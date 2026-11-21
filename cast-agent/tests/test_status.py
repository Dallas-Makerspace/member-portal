from types import SimpleNamespace

from cast_agent.status import describe


def media(**values):
    base = dict(player_state="PLAYING", title="Shop safety", artist=None, content_id="https://media.example.test/a.mp4", content_type="video/mp4")
    base.update(values)
    return SimpleNamespace(**base)


def test_an_app_with_no_media_reports_just_its_name():
    assert describe("Spotify", "Spotify", None) == {"appName": "Spotify", "statusText": "Spotify", "media": None}


def test_playing_media_carries_its_title_and_address():
    result = describe("YouTube", "", media())

    assert result["appName"] == "YouTube"
    assert result["statusText"] is None
    assert result["media"] == {
        "state": "PLAYING",
        "title": "Shop safety",
        "artist": None,
        "url": "https://media.example.test/a.mp4",
        "contentType": "video/mp4",
    }


def test_only_web_addresses_are_kept_as_a_link():
    assert describe("App", None, media(content_id="abc123"))["media"]["url"] is None
    assert describe("App", None, media(content_id="javascript:alert(1)"))["media"]["url"] is None
    assert describe("App", None, media(content_id="HTTP://media.example.test/x"))["media"]["url"] == "HTTP://media.example.test/x"


def test_media_with_nothing_in_it_is_dropped():
    empty = media(player_state="UNKNOWN", title=None, content_id=None, content_type=None)

    assert describe("App", None, empty)["media"] is None


def test_overlong_text_is_cut():
    result = describe("A" * 400, None, media(title="T" * 400))

    assert len(result["appName"]) == 255
    assert len(result["media"]["title"]) == 255

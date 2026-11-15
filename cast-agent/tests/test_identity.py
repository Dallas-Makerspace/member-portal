from cast_agent.identity import Identity, find_devices, hosts_in, is_same_device, match_device, normalize_id


def test_ids_compare_regardless_of_case_and_prefix():
    assert normalize_id("uuid:ABC-123") == "abc-123"
    assert normalize_id("abc-123") == "abc-123"
    assert normalize_id("") is None
    assert normalize_id(None) is None


def test_a_tv_with_no_recorded_id_matches_anything_and_a_recorded_one_only_itself():
    actual = Identity("abc-123", "Shop TV")

    assert is_same_device(None, actual)
    assert is_same_device("ABC-123", actual)
    assert not is_same_device("def-456", actual)


def test_hosts_in_skips_bad_ranges():
    assert hosts_in(["192.0.2.0/30", "not-a-range"]) == ["192.0.2.1", "192.0.2.2"]


def test_find_devices_maps_ids_to_addresses_and_skips_silent_hosts():
    identities = {"192.0.2.5": Identity("aaa", "One"), "192.0.2.9": Identity("bbb", "Two"), "192.0.2.7": None}

    found = find_devices(
        ["192.0.2.0/24"], sweeper=lambda ranges: ["192.0.2.5", "192.0.2.7", "192.0.2.9"], fetch=identities.get
    )

    assert set(found) == {"aaa", "bbb"}
    assert found["bbb"][0] == "192.0.2.9"


def test_a_tv_known_only_by_name_matches_that_name_case_insensitively():
    actual = Identity("abc-123", "South_work_area")

    assert is_same_device(None, actual, "south_work_AREA")
    assert not is_same_device(None, actual, "Jewelry")
    assert is_same_device("abc-123", actual, "ignored when an id is known")


def test_match_device_prefers_the_id_and_falls_back_to_the_name():
    found = {
        "aaa": ("192.0.2.5", Identity("aaa", "Jewelry")),
        "bbb": ("192.0.2.9", Identity("bbb", "South_work_area")),
    }

    assert match_device(found, "AAA", "South_work_area")[0] == "192.0.2.5"  # the id wins
    assert match_device(found, None, "south_work_area")[0] == "192.0.2.9"
    assert match_device(found, None, "No such TV") is None
    assert match_device(found, None, None) is None

from __future__ import annotations

from dataclasses import dataclass


@dataclass(frozen=True)
class Target:
    """One TV, as the service describes it."""

    id: int
    committee_id: int
    name: str
    host: str
    port: int
    display_start: str | None
    display_end: str | None
    reclaim_after_minutes: int | None

    @staticmethod
    def from_json(data: dict) -> "Target":
        return Target(
            id=int(data["id"]),
            committee_id=int(data["committeeId"]),
            name=str(data.get("name") or f"TV {data['id']}"),
            host=str(data["host"]),
            port=int(data.get("port") or 8009),
            display_start=data.get("displayStart"),
            display_end=data.get("displayEnd"),
            reclaim_after_minutes=data.get("reclaimAfterMinutes"),
        )

    @property
    def address(self) -> tuple[str, int]:
        """What identifies the connection; if it changes the TV is reconnected."""
        return (self.host, self.port)

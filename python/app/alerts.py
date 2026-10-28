"""Overspeed rule: speed above the limit for N consecutive pings -> one alert per run."""
import datetime as dt
from typing import Optional, Sequence, Tuple

Row = Tuple[dt.datetime, float]   # (ts_utc, speed_kmph)


def overspeed_run_start(rows_newest_first: Sequence[Row], limit_kmph: float, min_pings: int) -> Optional[dt.datetime]:
    """Return the start time of the current overspeed run if it is at least `min_pings` long, else None.

    rows_newest_first: the vehicle's latest pings ordered by ts DESC.
    The run start is stable while the run continues, so (vin, type, started_at) de-duplicates the alert.
    If the run is longer than the rows passed in, the oldest row passed in is used.
    """
    run = 0
    start = None
    for ts, speed in rows_newest_first:
        if speed > limit_kmph:
            run += 1
            start = ts
        else:
            break
    return start if run >= min_pings else None

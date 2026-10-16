"""Wall-clock recurrence shared by persistent reminders and their occurrence revisions."""
from datetime import datetime, time, timedelta, timezone
from zoneinfo import ZoneInfo, ZoneInfoNotFoundError
import json
from uuid import uuid4


def rule(value):
    if value is None:
        return None
    if not isinstance(value, dict):
        raise ValueError('重复规则无效')
    days = value.get('days')
    if not isinstance(days, list) or not days or any(type(d) is not int or d not in range(1, 8) for d in days):
        raise ValueError('请选择每周重复日期')
    hour, minute = value.get('hour'), value.get('minute')
    if type(hour) is not int or hour not in range(24) or type(minute) is not int or minute not in range(60):
        raise ValueError('重复时间无效')
    try:
        zone = ZoneInfo(value.get('timezone', 'Asia/Shanghai'))
    except (ZoneInfoNotFoundError, TypeError, ValueError):
        raise ValueError('重复时区无效') from None
    return dict(days=sorted(set(days)), hour=hour, minute=minute, timezone=str(zone), seed=uuid4().hex)


def next_time(value, after):
    zone = ZoneInfo(value['timezone'])
    local = after.astimezone(zone)
    for offset in range(9):
        day = local.date() + timedelta(days=offset)
        if day.isoweekday() not in value['days']:
            continue
        candidate = datetime.combine(day, time(value['hour'], value['minute']), zone)
        # Skip nonexistent spring-forward clock times; use the first fall-back occurrence.
        utc = candidate.astimezone(timezone.utc)
        if utc.astimezone(zone).replace(tzinfo=None) != candidate.replace(tzinfo=None):
            continue
        if utc > after:
            return utc
    raise ValueError('无法计算下一次提醒时间')


def revision(value, when):
    return value['seed'] + '_' + str(int(when.timestamp()))


def decode(row):
    item = dict(row)
    item['repeat'] = json.loads(item['repeat']) if isinstance(item.get('repeat'), str) else item.get('repeat')
    return item

"""Append to existing workflows; does not replace any Feishu notification step."""
import json
import os
import sys
import time
from datetime import datetime, timezone
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen
from urllib.parse import urlsplit


def main():
    url = os.environ.get('MYDESK_WEBHOOK_URL', '')
    if urlsplit(url).scheme != 'https':
        print('MyDesk: MYDESK_WEBHOOK_URL must use HTTPS', file=sys.stderr)
        return 1
    payload = {
        'task_id': os.environ['MYDESK_TASK_ID'], 'task_name': os.environ['MYDESK_TASK_NAME'],
        'status': os.environ['MYDESK_TASK_STATUS'], 'message': os.environ.get('MYDESK_TASK_MESSAGE', '执行完成'),
        'timestamp': os.environ.get('MYDESK_TASK_TIMESTAMP') or datetime.now(timezone.utc).isoformat(),
        'source': 'github_actions',
        'event_id': ':'.join([os.environ.get('GITHUB_REPOSITORY','manual'),os.environ.get('GITHUB_RUN_ID',str(time.time_ns())),
                              os.environ.get('GITHUB_RUN_ATTEMPT','1'),os.environ['MYDESK_TASK_ID'],os.environ['MYDESK_TASK_STATUS']]),
    }
    body = json.dumps(payload, ensure_ascii=False).encode()
    # Same event_id and timestamp on every retry: retries cannot duplicate history.
    for attempt in range(3):
        try:
            request = Request(url, data=body, headers={'Content-Type':'application/json'}, method='POST')
            with urlopen(request, timeout=15) as response:
                result = json.load(response)
                if response.status == 200 and result.get('accepted') is True:
                    print('MyDesk: report accepted')
                    return 0
        except HTTPError as exc:
            print(f'MyDesk: HTTP {exc.code}', file=sys.stderr)
            if 400 <= exc.code < 500 and exc.code != 429:
                return 1
        except (URLError, TimeoutError, ValueError):
            print('MyDesk: report failed (connection or invalid response)', file=sys.stderr)
        if attempt < 2:
            time.sleep(2 ** attempt)
    return 1


if __name__ == '__main__':
    sys.exit(main())

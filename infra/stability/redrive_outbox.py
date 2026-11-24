"""Preview or explicitly redrive one previously sent event with its original ID."""

import argparse
import json
import os
import re
import subprocess
import sys
from uuid import UUID


def query(args, sql):
    result = subprocess.run(
        ["mysql", "--no-defaults", "--protocol=TCP", "--connect-timeout=3", "--batch", "--skip-column-names",
         "--host=" + args.host, "--port=" + str(args.port), "--user=" + args.user,
         "--database=flour_" + args.service, "--execute=" + sql],
        capture_output=True, check=True, text=True, timeout=15,
    )
    return result.stdout.strip()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("service", choices=("order", "inventory", "pay"))
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, required=True)
    parser.add_argument("--user", required=True)
    parser.add_argument("--event-id", type=UUID, required=True)
    parser.add_argument("--expected-broker-id")
    parser.add_argument("--execute", action="store_true")
    args = parser.parse_args()
    if not 1 <= args.port <= 65535 or not os.environ.get("MYSQL_PWD"):
        parser.error("Set a valid MySQL port and provide its password through MYSQL_PWD")
    if args.execute and not re.fullmatch(r"[A-Za-z0-9_-]{1,128}", args.expected_broker_id or ""):
        parser.error("--execute needs the expected broker ID from the approved preview")
    event_id = str(args.event_id)
    try:
        row = query(args, f"""SELECT tag,attempts,COALESCE(CAST(sent_at AS CHAR),'-'),COALESCE(broker_message_id,'-')
                               FROM rocketmq_outbox WHERE event_id='{event_id}'""")
        if not row:
            print("No such outbox event", file=sys.stderr)
            return 1
        tag, attempts, sent, broker_id = row.split("\t")
        print(json.dumps({"eventId": event_id, "tag": tag, "attempts": int(attempts),
                          "sentAt": sent, "brokerMessageId": broker_id}))
        if not args.execute:
            return 0
        changed = query(args, f"""SET time_zone='+00:00';
                    UPDATE rocketmq_outbox
                    SET sent_at=NULL,available_at=CURRENT_TIMESTAMP(3),lease_token=NULL,last_error=NULL
                    WHERE event_id='{event_id}' AND sent_at IS NOT NULL AND lease_token IS NULL
                    AND BINARY broker_message_id=BINARY '{args.expected_broker_id}';
                    SELECT ROW_COUNT()""")
    except (OSError, subprocess.SubprocessError, ValueError):
        print("Unable to access the selected outbox", file=sys.stderr)
        return 2
    if changed != "1":
        print("Outbox row changed since preview or is already pending", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())

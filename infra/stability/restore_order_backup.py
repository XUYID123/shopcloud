"""Create an order dump, restore to an empty exercise schema, and compare business data."""

import argparse
import hashlib
import json
import os
import re
import subprocess
import sys
from pathlib import Path


TABLES = ("order_main", "order_shipment", "order_shipment_request", "order_cancellation",
          "checkout_quote", "rocketmq_inbox", "rocketmq_outbox")
ORDER_ROWS = """SELECT order_no,order_status,inventory_status,payment_status,
                       payable_amount_fen,received_at FROM order_main ORDER BY order_no"""
SHIPMENTS = """SELECT order_no,carrier_code,tracking_no,shipped_at,SHA2(CAST(allocation_snapshot AS CHAR),256)
               FROM order_shipment ORDER BY order_no"""


def mysql(args, schema, query):
    return subprocess.run(["mysql", "--no-defaults", *args, "--connect-timeout=3", "--batch", "--skip-column-names",
                           "--database=" + schema, "--execute=" + query],
                          check=True, capture_output=True, timeout=60).stdout


def snapshot(args, schema):
    counts = mysql(args, schema, " UNION ALL ".join(f"SELECT COUNT(*) FROM {table}" for table in TABLES))
    digest = hashlib.sha256(mysql(args, schema, ORDER_ROWS) + b"\0" + mysql(args, schema, SHIPMENTS)).hexdigest()
    return dict(zip(TABLES, map(int, counts.splitlines()), strict=True)), digest


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--port", type=int, required=True, help="MySQL listening on 127.0.0.1")
    parser.add_argument("--user", required=True)
    parser.add_argument("--restore-schema", required=True)
    parser.add_argument("--dump", type=Path, required=True)
    args = parser.parse_args()
    if not 1 <= args.port <= 65535 or not re.fullmatch(r"flour_order_restore_[a-z0-9]{1,24}", args.restore_schema):
        parser.error("Use an isolated MySQL port and a precreated flour_order_restore_<suffix> schema")
    if not os.environ.get("MYSQL_PWD"):
        parser.error("Supply the exercise MySQL password through MYSQL_PWD")
    auth = ["--protocol=TCP", "--host=127.0.0.1", f"--port={args.port}", f"--user={args.user}"]
    try:
        if mysql(auth, args.restore_schema, "SHOW FULL TABLES").strip():
            parser.error("The restore schema must be empty")
        expected = snapshot(auth, "flour_order")
        # Exclusive create prevents replacing an existing backup.
        with args.dump.open("xb") as output:
            subprocess.run(["mysqldump", "--no-defaults", *auth, "--single-transaction", "--no-tablespaces",
                            "--set-gtid-purged=OFF", "--skip-add-drop-table", "flour_order"],
                           stdout=output, stderr=subprocess.PIPE, check=True, timeout=120)
        with args.dump.open("rb") as dump:
            subprocess.run(["mysql", "--no-defaults", *auth, "--connect-timeout=3", "--database=" + args.restore_schema],
                           stdin=dump, stdout=subprocess.PIPE, stderr=subprocess.PIPE, check=True, timeout=120)
        restored = snapshot(auth, args.restore_schema)
    except (OSError, subprocess.SubprocessError, ValueError):
        print("Order backup or restore failed", file=sys.stderr)
        return 2
    if expected != restored:
        print("Restored order rows differ from the source snapshot", file=sys.stderr)
        return 1
    print(json.dumps({"restored": restored[0], "order_and_shipment_sha256": restored[1]}))
    return 0


if __name__ == "__main__":
    sys.exit(main())

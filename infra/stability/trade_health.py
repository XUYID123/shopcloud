"""Read-only MySQL reconciliation for one trade schema at a time."""

import argparse
import json
import os
import subprocess
import sys
import time


OUTBOX = {
    "outbox_pending": "COUNT(*) FROM rocketmq_outbox WHERE sent_at IS NULL",
    "outbox_oldest_seconds": """COALESCE(GREATEST(0,TIMESTAMPDIFF(SECOND,MIN(created_at),NOW(3))),0)
                                 FROM rocketmq_outbox WHERE sent_at IS NULL""",
    "outbox_retried": "COUNT(*) FROM rocketmq_outbox WHERE sent_at IS NULL AND last_error IS NOT NULL",
}

CHECKS = {
    "order": {
        "orders_stale_preparing": "COUNT(*) FROM order_main WHERE order_status='PREPARING' AND created_at<@cutoff",
        "orders_overdue_unpaid": "COUNT(*) FROM order_main WHERE order_status='UNPAID' AND expires_at<@cutoff",
        "orders_stale_closing": """COUNT(*) FROM order_main o LEFT JOIN order_cancellation c USING (order_no)
                                   WHERE o.order_status='CLOSING'
                                     AND COALESCE(c.requested_at,o.expires_at,o.created_at)<@cutoff""",
        "orders_stale_confirmation": """COUNT(*) FROM order_main WHERE order_status='PAID' AND payment_status='SUCCEEDED'
                                        AND inventory_status='RESERVED' AND paid_at<@cutoff""",
        "orders_stale_release": """COUNT(*) FROM order_main o LEFT JOIN order_cancellation c USING (order_no)
                                  WHERE o.inventory_status='RELEASE_PENDING'
                                    AND COALESCE(c.requested_at,o.expires_at,o.created_at)<@cutoff""",
        "orders_refund_pending": "COUNT(*) FROM order_main WHERE payment_status='REFUNDING'",
        "orders_stale_refunding": """COUNT(*) FROM order_main WHERE payment_status='REFUNDING'
                                      AND COALESCE(paid_at,expires_at,created_at)<@cutoff""",
        "orders_manual_review": """COUNT(*) FROM order_main
                                   WHERE payment_status='EXCEPTION'
                                      OR (inventory_status='EXCEPTION' AND payment_status NOT IN ('REFUNDED','REFUNDING'))""",
        "orders_fulfillment_mismatch": """COUNT(*) FROM order_main o LEFT JOIN order_shipment s USING (order_no)
                 WHERE (o.order_status IN ('SHIPPED','COMPLETED') AND s.order_no IS NULL)
                    OR (o.order_status NOT IN ('SHIPPED','COMPLETED') AND s.order_no IS NOT NULL)
                    OR (o.order_status='COMPLETED') <> (o.received_at IS NOT NULL)""",
    },
    "inventory": {
        "inventory_stale_reservations": """COUNT(*) FROM inventory_reservation
                                WHERE (state='PENDING' AND updated_at<@cutoff)
                                   OR (state='RESERVED' AND updated_at<TIMESTAMPADD(MINUTE,-15,@cutoff))""",
        "inventory_balance_mismatch": """COUNT(*) FROM sku_stock s
              LEFT JOIN (SELECT sku_id,warehouse_id,SUM(available) available,SUM(reserved) reserved,
                                SUM(committed) committed,SUM(unavailable) unavailable
                         FROM stock_batch GROUP BY sku_id,warehouse_id) b USING (sku_id,warehouse_id)
              WHERE s.available<>COALESCE(b.available,0) OR s.reserved<>COALESCE(b.reserved,0)
                 OR s.committed<>COALESCE(b.committed,0) OR s.unavailable<>COALESCE(b.unavailable,0)""",
    },
    "pay": {
        "payment_mock_events_stale": """COUNT(*) FROM mock_provider_event
                                       WHERE delivered_at IS NULL AND created_at<@cutoff""",
    },
}


def collect(args):
    checks = OUTBOX | CHECKS[args.service]
    query = (f"SET time_zone='+00:00'; SET @cutoff=TIMESTAMPADD(SECOND,-{args.stale_seconds},NOW(3)); SELECT " +
             ", ".join(f"(SELECT {sql})" for sql in checks.values()))
    command = ["mysql", "--no-defaults", "--protocol=TCP", "--connect-timeout=3", "--batch", "--skip-column-names",
               "--host=" + args.host, "--port=" + str(args.port), "--user=" + args.user,
               "--database=flour_" + args.service, "--execute=" + query]
    result = subprocess.run(command, check=True, capture_output=True, text=True, timeout=15)
    return dict(zip(checks, map(int, result.stdout.strip().split("	")), strict=True))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("service", choices=CHECKS)
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, required=True)
    parser.add_argument("--user", required=True)
    parser.add_argument("--stale-seconds", type=int, default=300)
    parser.add_argument("--prometheus", action="store_true")
    args = parser.parse_args()
    if args.stale_seconds < 1 or not 1 <= args.port <= 65535:
        parser.error("Use a positive age threshold and a valid MySQL port")
    if not os.environ.get("MYSQL_PWD"):
        parser.error("Supply the read-only MySQL password through MYSQL_PWD")
    try:
        metrics = collect(args)
    except (OSError, subprocess.SubprocessError, ValueError):
        print(f"Unable to query the {args.service} database", file=sys.stderr)
        return 2
    alerts = [name for name, value in metrics.items()
              if (name == "outbox_oldest_seconds" and value > args.stale_seconds)
              or (name not in OUTBOX and name != "orders_refund_pending" and value > 0)]
    if args.prometheus:
        for name, value in metrics.items():
            print(f'shop_trade_{name}{{service="{args.service}"}} {value}')
        print(f'shop_trade_alerts{{service="{args.service}"}} {len(alerts)}')
        print(f'shop_trade_collected_timestamp_seconds{{service="{args.service}"}} {int(time.time())}')
    else:
        print(json.dumps({"service": args.service, "metrics": metrics, "alerts": alerts}))
    return 1 if alerts else 0


if __name__ == "__main__":
    sys.exit(main())

#!/usr/bin/env python3
"""
Verification Script: Consumes compliance alerts from Kafka and verifies how accurate the detection metrics are

Usage:
    python scripts/verify_detection.py [--redpanda localhost:9092] \
                                       [--ground-truth scripts/ground_truth.json] \
                                       [--timeout 120]
"""

import argparse
import json
import sys
import time
from collections import defaultdict
from datetime import datetime, timezone
from pathlib import Path

try:
    from confluent_kafka import Consumer
except ImportError:
    print("ERROR: confluent-kafka not installed. Run: pip install confluent-kafka", file=sys.stderr)
    sys.exit(1)

#  Configuration

ALERT_TOPIC = "compliance-alerts"
CONSUMER_GROUP = "sentinel-verify-group"


#  Alert Matching 

def alerts_match_alert(alert, ground_truth_entry, tolerance_ms=5000):
    """
    Check if a compliance alert matches a ground truth entry.
    An alert matches if:
    - The alert type matches the ground truth type
    - The trader ID matches (or is in the participants list for wash trades)
    - The symbol matches
    - The alert timestamp is within tolerance of the ground truth timestamp
    """
    alert_type = alert.get("alertType", "")
    gt_type = ground_truth_entry.get("type", "")

    if alert_type != gt_type:
        return False

    if alert.get("symbol", "") != ground_truth_entry.get("symbol", ""):
        return False

    alert_trader = alert.get("traderId", "")
    gt_trader = ground_truth_entry.get("traderId", "")

    # For wash trades, the alert might flag any participant in the ring
    participants = ground_truth_entry.get("participants", [])
    if gt_type == "WASH_TRADE" and participants:
        if alert_trader not in participants:
            return False
    else:
        if alert_trader != gt_trader:
            return False

    # Check timestamp proximity
    alert_ts = alert.get("timestamp", 0)
    gt_ts = ground_truth_entry.get("timestamp", 0)
    if abs(alert_ts - gt_ts) > tolerance_ms:
        return False

    return True


def compute_metrics(ground_truth, alerts):
    """
    Compute recall, precision, and latency metrics.

    Recall = detected / total_injected
    Precision = true_positives / total_alerts
    """
    matched_gt = set()  # Indices of ground truth entries that were detected
    matched_alerts = set()  # Indices of alerts that are true positives

    for i, gt_entry in enumerate(ground_truth):
        for j, alert in enumerate(alerts):
            if j in matched_alerts:
                continue
            if alerts_match_alert(alert, gt_entry):
                matched_gt.add(i)
                matched_alerts.add(j)
                break

    total_injected = len(ground_truth)
    detected = len(matched_gt)
    total_alerts = len(alerts)
    true_positives = len(matched_alerts)
    false_positives = total_alerts - true_positives

    recall = detected / total_injected if total_injected > 0 else 0.0
    precision = true_positives / total_alerts if total_alerts > 0 else 0.0

    # Per-type breakdown
    type_stats = defaultdict(lambda: {"injected": 0, "detected": 0, "alerts": 0})
    for i, gt_entry in enumerate(ground_truth):
        gt_type = gt_entry.get("type", "UNKNOWN")
        type_stats[gt_type]["injected"] += 1
        if i in matched_gt:
            type_stats[gt_type]["detected"] += 1

    for j, alert in enumerate(alerts):
        alert_type = alert.get("alertType", "UNKNOWN")
        type_stats[alert_type]["alerts"] += 1

    # Latency computation
    latencies = []
    for i, gt_entry in enumerate(ground_truth):
        if i in matched_gt:
            gt_ts = gt_entry.get("timestamp", 0)
            for j, alert in enumerate(alerts):
                if j in matched_alerts and alerts_match_alert(alert, gt_entry):
                    alert_ts = alert.get("timestamp", 0)
                    latency_ms = alert_ts - gt_ts
                    latencies.append(latency_ms)
                    break

    latencies.sort()
    p50 = latencies[len(latencies) // 2] if latencies else 0
    p99_idx = int(len(latencies) * 0.99)
    p99 = latencies[p99_idx] if latencies else 0

    return {
        "recall": recall,
        "precision": precision,
        "total_injected": total_injected,
        "detected": detected,
        "total_alerts": total_alerts,
        "true_positives": true_positives,
        "false_positives": false_positives,
        "latency_p50_ms": p50,
        "latency_p99_ms": p99,
        "type_stats": dict(type_stats),
    }


#  Main Verifier 

def main():
    parser = argparse.ArgumentParser(description="Sentinel Detection Verifier")
    parser.add_argument("--redpanda", default="localhost:9092", help="Redpanda broker address")
    parser.add_argument("--ground-truth", default="scripts/ground_truth.json", help="Ground truth JSON file")
    parser.add_argument("--timeout", type=int, default=120, help="Max seconds to wait for alerts")
    parser.add_argument("--min-alerts", type=int, default=0, help="Minimum alerts before early stop")
    args = parser.parse_args()

    # Load ground truth
    gt_path = Path(args.ground_truth)
    if not gt_path.exists():
        print(f"ERROR: Ground truth file not found: {gt_path}", file=sys.stderr)
        print("Run chaos_generator.py first.", file=sys.stderr)
        sys.exit(1)

    with open(gt_path) as f:
        ground_truth = json.load(f)

    print(f"Verification Configuration:")
    print(f"  Ground truth entries: {len(ground_truth)}")
    print(f"  Redpanda: {args.redpanda}")
    print(f"  Timeout: {args.timeout}s")
    print()

    # Count ground truth by type
    gt_type_counts = defaultdict(int)
    for entry in ground_truth:
        gt_type_counts[entry.get("type", "UNKNOWN")] += 1
    print(f"Ground Truth Breakdown:")
    for alert_type, count in sorted(gt_type_counts.items()):
        print(f"  {alert_type}: {count}")
    print()

    # Initialize Kafka consumer
    conf = {
        "bootstrap.servers": args.redpanda,
        "group.id": CONSUMER_GROUP,
        "auto.offset.reset": "earliest",
        "enable.auto.commit": "true",
        "key.deserializer": lambda k: k.decode("utf-8") if k else None,
        "value.deserializer": lambda v: v.decode("utf-8") if v else None,
    }
    consumer = Consumer(conf)
    consumer.subscribe([ALERT_TOPIC])

    # Consume alerts
    alerts = []
    start_time = time.time()
    print("Consuming alerts...")

    try:
        while time.time() - start_time < args.timeout:
            msg = consumer.poll(1.0)
            if msg is None:
                if args.min_alerts > 0 and len(alerts) >= args.min_alerts:
                    print(f"  Reached minimum alert count ({args.min_alerts}), stopping early.")
                    break
                continue

            if msg.error():
                print(f"  Consumer error: {msg.error()}", file=sys.stderr)
                continue

            try:
                alert = json.loads(msg.value())
                alerts.append(alert)
                if len(alerts) % 100 == 0:
                    print(f"  Received {len(alerts)} alerts...", flush=True)
            except json.JSONDecodeError:
                print(f"  Skipping malformed alert at offset {msg.offset()}", file=sys.stderr)

    except KeyboardInterrupt:
        print("\n  Interrupted by user.")
    finally:
        consumer.close()

    elapsed = time.time() - start_time
    print(f"\nConsumption complete:")
    print(f"  Alerts received: {len(alerts)}")
    print(f"  Time elapsed: {elapsed:.1f}s")
    print()

    # Count alerts by type
    alert_type_counts = defaultdict(int)
    for alert in alerts:
        alert_type_counts[alert.get("alertType", "UNKNOWN")] += 1

    # Compute metrics
    metrics = compute_metrics(ground_truth, alerts)

    # Print results
    print("=" * 60)
    print("DETECTION METRICS")
    print("=" * 60)
    print()
    print(f"  Recall:    {metrics['recall']:.1%} ({metrics['detected']}/{metrics['total_injected']})")
    print(f"  Precision: {metrics['precision']:.1%} ({metrics['true_positives']}/{metrics['total_alerts']})")
    print()
    print(f"  True Positives:  {metrics['true_positives']}")
    print(f"  False Positives: {metrics['false_positives']}")
    print(f"  Missed:          {metrics['total_injected'] - metrics['detected']}")
    print()
    print(f"  Latency P50: {metrics['latency_p50_ms']:.1f}ms")
    print(f"  Latency P99: {metrics['latency_p99_ms']:.1f}ms")
    print()

    print("Per-Type Breakdown:")
    print(f"  {'Type':<20} {'Injected':>10} {'Detected':>10} {'Recall':>10} {'Alerts':>10}")
    print(f"  {'-' * 60}")
    for alert_type, stats in sorted(metrics["type_stats"].items()):
        type_recall = stats["detected"] / stats["injected"] if stats["injected"] > 0 else 0.0
        print(
            f"  {alert_type:<20} {stats['injected']:>10} {stats['detected']:>10} {type_recall:>9.1%} {stats['alerts']:>10}")

    print()
    print("Alert Type Distribution:")
    for alert_type, count in sorted(alert_type_counts.items()):
        print(f"  {alert_type}: {count}")

    # Exit criteria
    print()
    print("=" * 60)
    print("EXIT CRITERIA")
    print("=" * 60)

    recall_ok = metrics["recall"] >= 0.999
    precision_ok = metrics["precision"] >= 0.95
    latency_ok = metrics["latency_p99_ms"] < 100

    print(f"  Recall ≥ 99.9%:     {'✓ PASS' if recall_ok else '✗ FAIL'} ({metrics['recall']:.1%})")
    print(f"  Precision ≥ 95%:    {'✓ PASS' if precision_ok else '✗ FAIL'} ({metrics['precision']:.1%})")
    print(f"  P99 Latency < 100ms: {'✓ PASS' if latency_ok else '✗ FAIL'} ({metrics['latency_p99_ms']:.1f}ms)")

    all_pass = recall_ok and precision_ok and latency_ok
    print()
    if all_pass:
        print("  ✓ ALL CRITERIA PASSED")
    else:
        print("  ✗ SOME CRITERIA FAILED")
        sys.exit(1)

    # Save detailed results
    results_path = Path("scripts/verification_results.json")
    with open(results_path, "w") as f:
        json.dump({
            "metrics": {
                "recall": metrics["recall"],
                "precision": metrics["precision"],
                "total_injected": metrics["total_injected"],
                "detected": metrics["detected"],
                "total_alerts": metrics["total_alerts"],
                "true_positives": metrics["true_positives"],
                "false_positives": metrics["false_positives"],
                "latency_p50_ms": metrics["latency_p50_ms"],
                "latency_p99_ms": metrics["latency_p99_ms"],
            },
            "type_stats": metrics["type_stats"],
            "alert_type_counts": dict(alert_type_counts),
            "timestamp": datetime.now(timezone.utc).isoformat(),
        }, f, indent=2)
    print(f"\n  Detailed results saved to: {results_path}")


if __name__ == "__main__":
    main()

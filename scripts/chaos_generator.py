#!/usr/bin/env python3
"""
Chaos Generator: Async Kafka producer that streams realistic market noise
interleaved with deterministic, labeled manipulation patterns.

Produces to the `market-events` topic and writes ground truth to
`scripts/ground_truth.json` so the verification script can compute recall
and precision.

Usage:
    python scripts/chaos_generator.py [--events 10000] [--redpanda localhost:9092]
"""

import argparse
import json
import random
import sys
import time
import uuid
from datetime import datetime, timezone
from pathlib import Path

try:
    from confluent_kafka import Producer
except ImportError:
    print("ERROR: confluent-kafka not installed. Run: pip install confluent-kafka", file=sys.stderr)
    sys.exit(1)

# Configuration

SYMBOLS = [
    "AAPL", "GOOG", "MSFT", "AMZN", "TSLA", "META", "NVDA", "JPM",
    "BAC", "GS", "WMT", "DIS", "NFLX", "AMD", "INTC", "CRM", "ORCL",
    "CSCO", "PFE", "MRK", "XOM", "CVX", "KO", "PEP", "NKE", "MCD",
    "SBUX", "T", "VZ", "UNH", "HCA", "JNJ", "ABBV", "LLY", "BMY",
    "TMO", "DHR", "ABT", "MDT", "ISRG", "GILD", "AMGN", "BIIB",
    "REGN", "VRTX", "ILMN", "MRNA", "BNTX", "ZTS", "ELV", "CI",
]

SIDES = ["BUY", "SELL"]

# Detector thresholds (must match application.properties)
SPOOF_CANCEL_WINDOW_MS = 50
SPOOF_QUANTITY_THRESHOLD = 1000
WASH_WINDOW_MS = 5000
VELOCITY_DAILY_LIMIT = 10_000_000.0
VELOCITY_MINUTE_LIMIT = 1_000_000.0


# Event Factories

def _now_ms():
    return int(datetime.now(timezone.utc).timestamp() * 1000)


def _event_id():
    return f"evt-{uuid.uuid4().hex[:12]}"


def _order_id():
    return f"ord-{uuid.uuid4().hex[:12]}"


def make_order_placed(trader_id, symbol, side, quantity, price, timestamp):
    return {
        "type": "ORDER_PLACED",
        "eventId": _event_id(),
        "orderId": _order_id(),
        "traderId": trader_id,
        "symbol": symbol,
        "side": side,
        "quantity": quantity,
        "price": price,
        "timestamp": timestamp,
    }


def make_order_cancelled(original_order_id, trader_id, symbol, quantity, price, timestamp):
    return {
        "type": "ORDER_CANCELLED",
        "eventId": _event_id(),
        "originalOrderId": original_order_id,
        "traderId": trader_id,
        "symbol": symbol,
        "quantity": quantity,
        "price": price,
        "timestamp": timestamp,
    }


def make_trade_executed(buyer_id, seller_id, symbol, quantity, price, timestamp):
    return {
        "type": "TRADE_EXECUTED",
        "eventId": _event_id(),
        "buyerId": buyer_id,
        "sellerId": seller_id,
        "symbol": symbol,
        "quantity": quantity,
        "price": price,
        "timestamp": timestamp,
    }


# Pattern Generators

class SpoofingPattern:
    """Generates spoofing bursts: large orders cancelled within 50ms."""

    def __init__(self, rng, base_trader_id="SPOOF"):
        self.rng = rng
        self.base_trader_id = base_trader_id
        self.count = 0

    def generate(self, symbol, timestamp):
        """Returns (events, ground_truth_entry) for a single spoof burst."""
        self.count += 1
        trader_id = f"{self.base_trader_id}_{self.count}"
        quantity = self.rng.randint(5000, 50000)
        price = round(self.rng.uniform(100.0, 500.0), 2)
        side = self.rng.choice(SIDES)

        order_id = _order_id()
        order = make_order_placed(trader_id, symbol, side, quantity, price, timestamp)
        cancel = make_order_cancelled(
            order_id, trader_id, symbol, quantity, price,
            timestamp + self.rng.randint(10, 40)  # Well within 50ms window
        )

        ground_truth = {
            "type": "SPOOFING",
            "traderId": trader_id,
            "symbol": symbol,
            "eventId": order["eventId"],
            "timestamp": timestamp,
            "details": f"Fast cancel: {quantity}@${price} cancelled in <50ms",
        }

        return [order, cancel], ground_truth


class WashTradePattern:
    """Generates circular trading rings (A→B→C→A or A→B→C→D→A)."""

    def __init__(self, rng, base_trader_id="WASH"):
        self.rng = rng
        self.base_trader_id = base_trader_id
        self.count = 0

    def generate(self, symbol, timestamp, ring_size=3):
        """Returns (events, ground_truth_entry) for a wash trade ring."""
        self.count += 1
        traders = [f"{self.base_trader_id}_{self.count}_{chr(65 + i)}" for i in range(ring_size)]
        quantity = self.rng.randint(100, 5000)
        price = round(self.rng.uniform(100.0, 500.0), 2)

        events = []
        for i in range(ring_size):
            buyer = traders[i]
            seller = traders[(i + 1) % ring_size]
            trade = make_trade_executed(
                buyer, seller, symbol, quantity, price,
                timestamp + i * self.rng.randint(100, 500)  # Within 5s window
            )
            events.append(trade)

        ground_truth = {
            "type": "WASH_TRADE",
            "traderId": traders[0],
            "symbol": symbol,
            "eventId": events[0]["eventId"],
            "timestamp": timestamp,
            "participants": traders,
            "details": f"{ring_size}-node wash trade ring: {' → '.join(traders)} → {traders[0]}",
        }

        return events, ground_truth


class VelocityPattern:
    """Generates notional volume spikes exceeding daily/minute limits."""

    def __init__(self, rng, base_trader_id="VELO"):
        self.rng = rng
        self.base_trader_id = base_trader_id
        self.count = 0

    def generate(self, symbol, timestamp):
        """Returns (events, ground_truth_entry) for a velocity breach."""
        self.count += 1
        trader_id = f"{self.base_trader_id}_{self.count}"

        # Generate enough trades to exceed the minute limit ($1M)
        # Each trade: ~$200k notional, 6 trades = $1.2M > $1M limit
        target_notional = VELOCITY_MINUTE_LIMIT * 1.2
        per_trade_notional = target_notional / 6
        price = round(self.rng.uniform(100.0, 500.0), 2)
        quantity = max(1, int(per_trade_notional / price))

        events = []
        counterparty = f"NORMAL_{self.rng.randint(1, 200)}"
        for i in range(6):
            trade = make_trade_executed(
                trader_id, counterparty, symbol, quantity, price,
                timestamp + i * self.rng.randint(500, 8000)  # Spread within ~5s
            )
            events.append(trade)

        ground_truth = {
            "type": "VELOCITY_BREACH",
            "traderId": trader_id,
            "symbol": symbol,
            "eventId": events[0]["eventId"],
            "timestamp": timestamp,
            "details": f"Rolling minute notional: ${target_notional:,.0f} exceeds ${VELOCITY_MINUTE_LIMIT:,.0f} limit",
        }

        return events, ground_truth


# Normal Market Noise

def generate_normal_event(rng, timestamp):
    """Generates a single normal market event (no manipulation)."""
    trader_id = f"NORMAL_{rng.randint(1, 200)}"
    symbol = rng.choice(SYMBOLS)
    side = rng.choice(SIDES)
    quantity = rng.randint(10, 500)
    price = round(rng.uniform(50.0, 500.0), 2)

    event_type = rng.choices(
        ["ORDER_PLACED", "ORDER_CANCELLED", "TRADE_EXECUTED"],
        weights=[50, 30, 20],
        k=1,
    )[0]

    if event_type == "ORDER_PLACED":
        return make_order_placed(trader_id, symbol, side, quantity, price, timestamp)
    elif event_type == "ORDER_CANCELLED":
        return make_order_cancelled(
            _order_id(), trader_id, symbol, quantity, price, timestamp
        )
    else:
        seller_id = f"NORMAL_{rng.randint(1, 200)}"
        return make_trade_executed(trader_id, seller_id, symbol, quantity, price, timestamp)


# Main Producer

def main():
    parser = argparse.ArgumentParser(description="Sentinel Chaos Generator")
    parser.add_argument("--events", type=int, default=10000, help="Total events to produce")
    parser.add_argument("--redpanda", default="localhost:9092", help="Redpanda broker address")
    parser.add_argument("--ground-truth", default="scripts/ground_truth.json", help="Ground truth output file")
    parser.add_argument("--seed", type=int, default=42, help="Random seed for reproducibility")
    args = parser.parse_args()

    rng = random.Random(args.seed)

    # Initialize Kafka producer
    conf = {
        "bootstrap.servers": args.redpanda,
        "acks": "1",
        "linger.ms": 5,
        "batch.size": 16384,
    }
    producer = Producer(conf)
    delivered = 0
    ground_truth = []

    # Pattern generators
    spoof_gen = SpoofingPattern(rng)
    wash_gen = WashTradePattern(rng)
    vel_gen = VelocityPattern(rng)

    # Determine pattern injection rate
    # ~5% spoofing, ~3% wash trade, ~2% velocity = ~10% manipulation
    spoof_count = max(50, int(args.events * 0.05 / 2))  # Each spoof = 2 events
    wash_count = max(20, int(args.events * 0.03 / 3))  # Each wash = 3 events
    vel_count = max(10, int(args.events * 0.02 / 6))  # Each velocity = 6 events

    normal_count = args.events - (spoof_count * 2 + wash_count * 3 + vel_count * 6)
    if normal_count < 0:
        normal_count = max(100, args.events // 2)

    print(f"Chaos Generator Configuration:")
    print(f"  Total events target: {args.events}")
    print(f"  Normal events: {normal_count}")
    print(f"  Spoofing patterns: {spoof_count} (≈{spoof_count * 2} events)")
    print(f"  Wash trade rings: {wash_count} (≈{wash_count * 3} events)")
    print(f"  Velocity spikes: {vel_count} (≈{vel_count * 6} events)")
    print(f"  Redpanda: {args.redpanda}")
    print(f"  Ground truth: {args.ground_truth}")
    print()

    base_ts = _now_ms()
    event_idx = 0

    def delivery_report(err, msg):
        nonlocal delivered
        if err is None:
            delivered += 1
            if delivered % 1000 == 0:
                print(f"  Delivered {delivered} events...", flush=True)
        else:
            print(f"  Delivery failed: {err}", file=sys.stderr)

    # Build event schedule: interleave normal events with patterns
    schedule = []

    # Add normal events spread across the timeline
    for i in range(normal_count):
        ts = base_ts + i * rng.randint(10, 100)
        schedule.append(("normal", ts, i))

    # Inject patterns at deterministic intervals
    for i in range(spoof_count):
        inject_at = int(normal_count * (i + 1) / (spoof_count + 1))
        ts = base_ts + inject_at * 50
        schedule.append(("spoof", ts, inject_at))

    for i in range(wash_count):
        inject_at = int(normal_count * (i + 1) / (wash_count + 1))
        ts = base_ts + inject_at * 50
        schedule.append(("wash3", ts, inject_at))

    # Add some 4-node wash trade rings
    wash4_count = max(10, wash_count // 2)
    for i in range(wash4_count):
        inject_at = int(normal_count * (i + 1) / (wash4_count + 1))
        ts = base_ts + inject_at * 50
        schedule.append(("wash4", ts, inject_at))

    for i in range(vel_count):
        inject_at = int(normal_count * (i + 1) / (vel_count + 1))
        ts = base_ts + inject_at * 50
        schedule.append(("velocity", ts, inject_at))

    # Sort by injection position for realistic interleaving
    schedule.sort(key=lambda x: (x[2], rng.random()))

    print("Producing events...")
    start_time = time.time()

    for pattern_type, ts, _ in schedule:
        symbol = rng.choice(SYMBOLS)

        if pattern_type == "normal":
            event = generate_normal_event(rng, ts)
            events = [event]
        elif pattern_type == "spoof":
            events, gt = spoof_gen.generate(symbol, ts)
            ground_truth.append(gt)
        elif pattern_type == "wash3":
            events, gt = wash_gen.generate(symbol, ts, ring_size=3)
            ground_truth.append(gt)
        elif pattern_type == "wash4":
            events, gt = wash_gen.generate(symbol, ts, ring_size=4)
            ground_truth.append(gt)
        elif pattern_type == "velocity":
            events, gt = vel_gen.generate(symbol, ts)
            ground_truth.append(gt)

        for event in events:
            key = event.get("symbol", "UNKNOWN")
            value = json.dumps(event).encode("utf-8")
            producer.poll(0)
            try:
                producer.produce(
                    "market-events",
                    key=key,
                    value=value,
                    on_delivery=delivery_report
                )
                event_idx += 1
            except BufferError:
                print(f"  Buffer full, flushing...", flush=True)
                producer.flush(5000)

    # Flush remaining messages
    print(f"Flushing {producer.len_queued()} remaining messages...")
    producer.flush(30000)

    elapsed = time.time() - start_time
    print()
    print(f"Production complete:")
    print(f"  Events produced: {event_idx}")
    print(f"  Events delivered: {delivered}")
    print(f"  Ground truth entries: {len(ground_truth)}")
    print(f"  Time elapsed: {elapsed:.1f}s")
    print(f"  Throughput: {event_idx / elapsed:,.0f} events/sec")

    # Write ground truth
    gt_path = Path(args.ground_truth)
    with open(gt_path, "w") as f:
        json.dump(ground_truth, f, indent=2)
    print(f"  Ground truth saved to: {gt_path}")


if __name__ == "__main__":
    main()

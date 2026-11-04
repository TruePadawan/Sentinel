# Sentinel

Real-time financial surveillance engine that detects market manipulation patterns in streaming trade data. Built with *
*Java 21**, **Kafka/Redpanda**, and **Python**, Sentinel processes high-velocity market events through three compliance
detectors with sub-millisecond latency.

## Detection Algorithms

| Detector              | What It Finds                                                         | Algorithm                                     | Complexity      |
|-----------------------|-----------------------------------------------------------------------|-----------------------------------------------|-----------------|
| **SpoofingDetector**  | Large orders cancelled within 50ms or abnormal order-to-cancel ratios | Sliding window with incremental counters      | O(1) amortized  |
| **WashTradeDetector** | Circular trading rings (A→B→C→A) that inflate volume                  | Time-windowed directed graph with bounded DFS | O(d²) per trade |
| **VelocityDetector**  | Traders exceeding daily or rolling 1-minute notional limits           | Rolling window with midnight reset            | O(1) amortized  |

## Performance

Benchmarked on 100K synthetic events with a deterministic 10% manipulation pattern mix:

| Metric                       | Value            |
|------------------------------|------------------|
| SpoofingDetector throughput  | ~787K events/sec |
| VelocityDetector throughput  | ~613K events/sec |
| WashTradeDetector throughput | ~78K events/sec  |
| Combined P50 latency         | Sub-millisecond  |
| Detection recall             | ≥ 99.9%          |
| Detection precision          | ≥ 95%            |

## Quick Start

### Prerequisites

- **Java 21** (JDK 21 or later)
- **Maven 3.9+**
- **Docker & Docker Compose** (for Redpanda)
- **Python 3.10+** (for chaos generator and verification scripts)

### 1. Start Redpanda

```bash
docker compose up -d
```

Wait for the cluster to be healthy, then create the topics:

```bash
rpk topic create market-events --partitions 12 --replicas 1
rpk topic create compliance-alerts --partitions 6 --replicas 1
```

### 2. Run the Detection Engine

```bash
mvn exec:java
```

The application subscribes to `market-events`, runs each event through all three detectors, and publishes any triggered
alerts to `compliance-alerts`.

### 3. Generate Synthetic Market Data

```bash
python3 -m venv .venv
source .venv/bin/activate
pip install -r scripts/requirements.txt

python scripts/chaos_generator.py --events 10000
```

The chaos generator produces realistic market noise interleaved with deterministic manipulation patterns (spoofing
bursts, wash trade rings, and velocity spikes), writing a `ground_truth.json` file for verification.

### 4. Verify Detection Accuracy

```bash
python scripts/verify_detection.py
```

The verifier consumes all alerts from Kafka, matches them against the ground truth, and reports recall, precision, and
end-to-end latency.

## Configuration

All detector thresholds and Kafka settings are in `src/main/resources/application.properties`:

| Property                         | Default    | Description                              |
|----------------------------------|------------|------------------------------------------|
| `spoof.cancel_window_ms`         | 50         | Max ms between place and cancel to flag  |
| `spoof.oc_ratio_threshold`       | 10.0       | Cancel-to-order ratio over 60s to flag   |
| `spoof.oc_min_samples`           | 20         | Min events in window before ratio check  |
| `spoof.quantity_threshold`       | 1000       | Min order quantity for fast-cancel check |
| `wash.window_ms`                 | 5000       | Time window for wash trade graph         |
| `wash.max_cycle_length`          | 5          | Max DFS depth for cycle detection        |
| `velocity.daily_notional_limit`  | 10000000.0 | $10M daily limit per trader/symbol       |
| `velocity.minute_notional_limit` | 1000000.0  | $1M rolling-minute limit                 |

## Running Tests and Benchmarks

```bash
# All unit tests (72 tests)
mvn test

# Throughput benchmark (combined pipeline, 100K events)
mvn test -Dtest=ThroughputBenchmark

# Per-detector benchmark (isolated throughput)
mvn test -Dtest=PerDetectorBenchmark

# Save benchmark results as JSON
mvn test -Dtest=ThroughputBenchmark -Djson=true
```

## Building a Fat JAR

```bash
mvn package -DskipTests
java -jar target/Sentinel-1.0-SNAPSHOT.jar
```

## Event Schema

**OrderPlaced:**

```json
{
  "type": "ORDER_PLACED",
  "eventId": "evt-001",
  "orderId": "ord-abc",
  "traderId": "TRADER_A",
  "symbol": "AAPL",
  "side": "BUY",
  "quantity": 10000,
  "price": 175.50,
  "timestamp": 1730784000000
}
```

**OrderCancelled:**

```json
{
  "type": "ORDER_CANCELLED",
  "eventId": "evt-002",
  "originalOrderId": "ord-abc",
  "traderId": "TRADER_A",
  "symbol": "AAPL",
  "quantity": 10000,
  "price": 175.50,
  "timestamp": 1730784000045
}
```

**TradeExecuted:**

```json
{
  "type": "TRADE_EXECUTED",
  "eventId": "evt-003",
  "buyerId": "TRADER_A",
  "sellerId": "TRADER_B",
  "symbol": "AAPL",
  "quantity": 500,
  "price": 175.52,
  "timestamp": 1730784001000
}
```

**ComplianceAlert:**

```json
{
  "alertId": "alert-001",
  "alertType": "SPOOFING",
  "traderId": "TRADER_A",
  "symbol": "AAPL",
  "details": "Cancelled 95% of orders within 50ms window",
  "timestamp": 1730784000050,
  "severity": "HIGH"
}
```

## Kafka Topology

| Topic               | Partitions | Key Strategy        | Retention |
|---------------------|------------|---------------------|-----------|
| `market-events`     | 12         | `symbol` (String)   | 24h       |
| `compliance-alerts` | 6          | `traderId` (String) | 7d        |

Events are partitioned by `symbol` to co-locate all trades for a given asset, enabling cross-trader wash trading
detection while keeping per-trader spoofing state partition-local.

## Tech Stack

- **Java 21**: `record` types, `sealed interface`, pattern matching on `switch`, virtual threads
- **Maven**: Build, testing, and fat JAR packaging
- **Redpanda**: Kafka-compatible event streaming (single-node via Docker Compose)
- **kafka-clients 4.4.0**: Consumer and producer
- **Jackson 2.22**: Polymorphic JSON serialization
- **HdrHistogram 2.2.2**: Latency percentile measurement
- **JUnit 5**: Deterministic unit tests
- **Python 3**: Async chaos generator and verification script

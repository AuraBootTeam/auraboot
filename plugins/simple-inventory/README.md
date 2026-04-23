# Simple Inventory Template

Simple buy/sell/stock management for small business.

## What's Included

| Model | Description |
|-------|-------------|
| Product | Products/SKUs with pricing and units |
| Warehouse | Storage locations |
| Stock In | Inbound purchase receipts |
| Stock Out | Outbound sales shipments |

## Key Features

- **Product Status**: ACTIVE ↔ DISCONTINUED
- **Document Lifecycle**: DRAFT → CONFIRMED / CANCELLED
- **Stock Tracking**: Confirmed in/out movements only count toward inventory
- **Named Query**: Net stock movement summary per product

## Installation

```bash
aura plugin import plugins/simple-inventory
```

## Namespace

All resources use the `tinv` namespace prefix.

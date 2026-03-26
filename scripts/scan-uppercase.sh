#!/bin/bash

set -e

echo "🔍 Scanning uppercase strings..."

OUTPUT="uppercase_strings.csv"
DEDUP="uppercase_strings_dedup.csv"
STATS="uppercase_strings_stats.txt"

# 1️⃣ 扫描全仓（排除 node_modules / 二进制 / 编译产物）
rg -n --no-heading -o '"[A-Z0-9_]{3,}"' \
  --glob '!.git/**' \
  --glob '!node_modules/**' \
  --glob '!*\.class' \
  --glob '!*\.jar' \
  --glob '!*\.png' \
  --glob '!*\.jpg' \
  --glob '!*\.jpeg' \
  --glob '!*\.gif' \
  --glob '!*\.svg' \
  --glob '!*\.zip' \
  --glob '!*\.tar' \
  --glob '!*\.gz' \
  --glob '!*\.min\.js' \
| sed 's/"//g' \
| awk -F: 'BEGIN {print "file,line,value"} {print $1 "," $2 "," $3}' \
> "$OUTPUT"

echo "✅ Raw CSV generated: $OUTPUT"

# 2️⃣ 按 value 去重（核心步骤）
awk -F, 'NR==1 || !seen[$3]++' "$OUTPUT" > "$DEDUP"

echo "✅ Deduplicated CSV: $DEDUP"

# 3️⃣ 统计出现频率（最重要分析数据）
cut -d, -f3 "$OUTPUT" \
| tail -n +2 \
| sort \
| uniq -c \
| sort -nr \
> "$STATS"

echo "✅ Stats generated: $STATS"

# 4️⃣ 输出 Top 10
echo ""
echo "📊 Top 10 most frequent values:"
head -10 "$STATS"

echo ""
echo "🎯 Done."
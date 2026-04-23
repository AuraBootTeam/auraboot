-- Backfill historical inbox assignment rows created before InboxEventListener
-- generated readable command titles/subtitles.
--
-- Run:
--   psql -h localhost -U ghj -d aura_boot -v ON_ERROR_STOP=1 \
--     -f scripts/backfill-inbox-command-assignment-titles.sql

\echo 'Previewing command assignment rows that will be normalized...'

WITH normalized AS (
  SELECT
    i.id,
    i.title AS old_title,
    i.subtitle AS old_subtitle,
    trim(
      regexp_replace(
        initcap(
          replace(
            regexp_replace(coalesce(i.card_payload->>'commandCode', ''), '^.*:', ''),
            '_',
            ' '
          )
        ),
        '\s+',
        ' ',
        'g'
      )
    ) AS command_label,
    trim(
      regexp_replace(
        initcap(
          replace(
            regexp_replace(coalesce(i.card_payload->>'fromState', ''), '^.*:', ''),
            '_',
            ' '
          )
        ),
        '\s+',
        ' ',
        'g'
      )
    ) AS from_label,
    trim(
      regexp_replace(
        initcap(
          replace(
            regexp_replace(coalesce(i.card_payload->>'toState', ''), '^.*:', ''),
            '_',
            ' '
          )
        ),
        '\s+',
        ' ',
        'g'
      )
    ) AS to_label,
    trim(
      regexp_replace(
        initcap(
          replace(
            regexp_replace(
              coalesce(i.model_code, i.card_payload->>'modelCode', 'record'),
              '^[^_]{1,3}_',
              ''
            ),
            '_',
            ' '
          )
        ),
        '\s+',
        ' ',
        'g'
      )
    ) AS model_label,
    coalesce(nullif(i.record_id::text, ''), nullif(i.card_payload->>'recordId', '')) AS record_ref
  FROM ab_inbox_item i
  WHERE i.item_type = 'assignment'
    AND i.source_type = 'command'
    AND coalesce(i.card_payload->>'commandCode', '') <> ''
),
candidate_rows AS (
  SELECT
    id,
    old_title,
    old_subtitle,
    CASE
      WHEN from_label <> '' AND to_label <> '' THEN command_label || ': ' || from_label || ' → ' || to_label
      ELSE command_label
    END AS new_title,
    CASE
      WHEN record_ref IS NOT NULL AND record_ref <> '' THEN model_label || ' #' || record_ref
      ELSE old_subtitle
    END AS new_subtitle
  FROM normalized
)
SELECT id, old_title, new_title, old_subtitle, new_subtitle
FROM candidate_rows
WHERE old_title IS DISTINCT FROM new_title
   OR old_subtitle IS DISTINCT FROM new_subtitle
ORDER BY id DESC;

\echo 'Applying inbox assignment title/subtitle backfill...'

WITH normalized AS (
  SELECT
    i.id,
    i.title AS old_title,
    i.subtitle AS old_subtitle,
    trim(
      regexp_replace(
        initcap(
          replace(
            regexp_replace(coalesce(i.card_payload->>'commandCode', ''), '^.*:', ''),
            '_',
            ' '
          )
        ),
        '\s+',
        ' ',
        'g'
      )
    ) AS command_label,
    trim(
      regexp_replace(
        initcap(
          replace(
            regexp_replace(coalesce(i.card_payload->>'fromState', ''), '^.*:', ''),
            '_',
            ' '
          )
        ),
        '\s+',
        ' ',
        'g'
      )
    ) AS from_label,
    trim(
      regexp_replace(
        initcap(
          replace(
            regexp_replace(coalesce(i.card_payload->>'toState', ''), '^.*:', ''),
            '_',
            ' '
          )
        ),
        '\s+',
        ' ',
        'g'
      )
    ) AS to_label,
    trim(
      regexp_replace(
        initcap(
          replace(
            regexp_replace(
              coalesce(i.model_code, i.card_payload->>'modelCode', 'record'),
              '^[^_]{1,3}_',
              ''
            ),
            '_',
            ' '
          )
        ),
        '\s+',
        ' ',
        'g'
      )
    ) AS model_label,
    coalesce(nullif(i.record_id::text, ''), nullif(i.card_payload->>'recordId', '')) AS record_ref
  FROM ab_inbox_item i
  WHERE i.item_type = 'assignment'
    AND i.source_type = 'command'
    AND coalesce(i.card_payload->>'commandCode', '') <> ''
),
candidate_rows AS (
  SELECT
    id,
    old_title,
    old_subtitle,
    CASE
      WHEN from_label <> '' AND to_label <> '' THEN command_label || ': ' || from_label || ' → ' || to_label
      ELSE command_label
    END AS new_title,
    CASE
      WHEN record_ref IS NOT NULL AND record_ref <> '' THEN model_label || ' #' || record_ref
      ELSE old_subtitle
    END AS new_subtitle
  FROM normalized
),
updated AS (
  UPDATE ab_inbox_item i
  SET title = c.new_title,
      subtitle = c.new_subtitle
  FROM candidate_rows c
  WHERE i.id = c.id
    AND (
      i.title IS DISTINCT FROM c.new_title
      OR i.subtitle IS DISTINCT FROM c.new_subtitle
    )
  RETURNING i.id
)
SELECT COUNT(*) AS updated_rows FROM updated;

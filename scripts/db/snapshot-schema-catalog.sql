-- Structural inventory for explicit snapshot adoption; excludes row data and sequence positions.
WITH objects AS (
 SELECT 'relation' kind, c.relname::text AS key,
 jsonb_build_object('kind',c.relkind,'persistence',c.relpersistence,'partition',pg_get_partkeydef(c.oid),'bound',pg_get_expr(c.relpartbound,c.oid),'options',c.reloptions,'rowSecurity',c.relrowsecurity,'forceRowSecurity',c.relforcerowsecurity)::text AS definition
 FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
 WHERE n.nspname='public' AND c.relkind IN ('r','p','v','m') AND c.relname LIKE 'ab\_%'
 UNION ALL
 SELECT 'sequence', c.relname, jsonb_build_object('type',format_type(q.seqtypid,NULL),'start',q.seqstart,'increment',q.seqincrement,'max',q.seqmax,'min',q.seqmin,'cache',q.seqcache,'cycle',q.seqcycle)::text
 FROM pg_sequence q JOIN pg_class c ON c.oid=q.seqrelid JOIN pg_namespace n ON n.oid=c.relnamespace
 WHERE n.nspname='public' AND c.relname LIKE 'ab\_%'
 UNION ALL
 SELECT 'type', t.typname, jsonb_build_object('kind',t.typtype,'base',format_type(t.typbasetype,t.typtypmod),'notNull',t.typnotnull,'default',t.typdefault,'enum',(SELECT jsonb_agg(e.enumlabel ORDER BY e.enumsortorder) FROM pg_enum e WHERE e.enumtypid=t.oid))::text
 FROM pg_type t JOIN pg_namespace n ON n.oid=t.typnamespace
 WHERE n.nspname='public' AND t.typtype IN ('e','d')
 AND NOT EXISTS (SELECT 1 FROM pg_depend d WHERE d.objid=t.oid AND d.classid='pg_type'::regclass AND d.deptype='e')
 UNION ALL
 SELECT 'column' kind, c.relname || '.' || a.attname AS key,
 jsonb_build_object('type',format_type(a.atttypid,a.atttypmod),'notNull',a.attnotnull,'identity',a.attidentity,'generated',a.attgenerated,'default',pg_get_expr(d.adbin,d.adrelid))::text AS definition
 FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace JOIN pg_attribute a ON a.attrelid=c.oid
 LEFT JOIN pg_attrdef d ON d.adrelid=c.oid AND d.adnum=a.attnum
 WHERE n.nspname='public' AND c.relkind IN ('r','p') AND c.relname LIKE 'ab\_%' AND a.attnum>0 AND NOT a.attisdropped
 UNION ALL
 SELECT 'constraint', c.relname || '.' || k.conname, pg_get_constraintdef(k.oid,true)
 FROM pg_constraint k JOIN pg_class c ON c.oid=k.conrelid JOIN pg_namespace n ON n.oid=c.relnamespace
 WHERE n.nspname='public' AND c.relname LIKE 'ab\_%'
 UNION ALL
 SELECT 'index', c.relname || '.' || i.relname, pg_get_indexdef(i.oid,0,true)
 FROM pg_index x JOIN pg_class c ON c.oid=x.indrelid JOIN pg_class i ON i.oid=x.indexrelid JOIN pg_namespace n ON n.oid=c.relnamespace
 WHERE n.nspname='public' AND c.relname LIKE 'ab\_%'
 UNION ALL
 SELECT 'view', c.relname, pg_get_viewdef(c.oid,true) FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
 WHERE n.nspname='public' AND c.relkind IN ('v','m') AND c.relname LIKE 'ab\_%'
 UNION ALL
 SELECT 'function', p.oid::regprocedure::text, pg_get_functiondef(p.oid) FROM pg_proc p JOIN pg_namespace n ON n.oid=p.pronamespace
 WHERE n.nspname='public' AND p.prokind IN ('f','p') AND NOT EXISTS (SELECT 1 FROM pg_depend d WHERE d.objid=p.oid AND d.deptype='e')
 UNION ALL
 SELECT 'trigger', c.relname || '.' || t.tgname, pg_get_triggerdef(t.oid,true) FROM pg_trigger t JOIN pg_class c ON c.oid=t.tgrelid JOIN pg_namespace n ON n.oid=c.relnamespace
 WHERE n.nspname='public' AND c.relname LIKE 'ab\_%' AND NOT t.tgisinternal
) SELECT coalesce(jsonb_agg(jsonb_build_object('kind',kind,'key',key,'definition',definition) ORDER BY kind,key),'[]'::jsonb) FROM objects;

-- pg_restore evaluates populated CHECK constraints with an empty search_path.
-- Resolve the platform helper explicitly, preserving the existing validation semantics.
CREATE OR REPLACE FUNCTION public.valid_effect_class_array(arr jsonb) RETURNS boolean
    LANGUAGE plpgsql IMMUTABLE
    AS $$
DECLARE
    elem TEXT;
BEGIN
    IF arr IS NULL THEN RETURN TRUE; END IF;
    IF jsonb_typeof(arr) <> 'array' THEN RETURN FALSE; END IF;
    FOR elem IN SELECT jsonb_array_elements_text(arr) LOOP
        IF NOT public.valid_effect_class(elem) THEN RETURN FALSE; END IF;
    END LOOP;
    RETURN TRUE;
END;
$$;

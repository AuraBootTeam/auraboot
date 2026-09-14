-- Keep the response with its execution attempt; task output can be replaced by later runs.
ALTER TABLE ab_agent_run ADD COLUMN final_response TEXT;

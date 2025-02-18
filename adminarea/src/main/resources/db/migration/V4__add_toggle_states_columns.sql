-- Add toggle states columns
ALTER TABLE areas ADD COLUMN toggle_states TEXT DEFAULT '{}';
ALTER TABLE areas ADD COLUMN default_toggle_states TEXT DEFAULT '{}';
ALTER TABLE areas ADD COLUMN inherited_toggle_states TEXT DEFAULT '{}';
